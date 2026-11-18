package antlab.regime;

import antlab.history.AxisDirection;
import antlab.history.RegimePool;
import antlab.regime.MonthRegime.AxisValue;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the months of {@code month_block} and fills their regime columns from NULL (SPRING_JPA §7: {@code JdbcClient}, since
 * the entity is read-only). Fill-only: a stored value is never changed, and a value that differs from the answer stops the
 * run before the first row is written. All rows are written in one transaction, locked first, so a second run waits and then
 * finds the values it would have written. The API role may update only these columns (db/callback/afterMigrate.sql).
 */
@Component
class MonthRegimeWriter {

    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private static final String SELECT_MONTHS = """
            SELECT id, to_char(month_start, 'YYYY-MM') AS month, rate_dir, growth_dir, fx_dir, sentiment_dir,
                   rate_strength, growth_strength, fx_strength, sentiment_strength, prob_p1, prob_p2, prob_p3, prob_p4,
                   regime_label, indicator_z::text AS indicator_z
            FROM month_block ORDER BY month_start""";

    /** Every column falls back to what is stored, so a value already there can never be replaced by this statement. */
    static final String FILL = """
            UPDATE month_block SET
                   rate_dir = COALESCE(rate_dir, CAST(:rate_dir AS varchar)),
                   growth_dir = COALESCE(growth_dir, CAST(:growth_dir AS varchar)),
                   fx_dir = COALESCE(fx_dir, CAST(:fx_dir AS varchar)),
                   sentiment_dir = COALESCE(sentiment_dir, CAST(:sentiment_dir AS varchar)),
                   rate_strength = COALESCE(rate_strength, CAST(:rate_strength AS numeric)),
                   growth_strength = COALESCE(growth_strength, CAST(:growth_strength AS numeric)),
                   fx_strength = COALESCE(fx_strength, CAST(:fx_strength AS numeric)),
                   sentiment_strength = COALESCE(sentiment_strength, CAST(:sentiment_strength AS numeric)),
                   prob_p1 = COALESCE(prob_p1, CAST(:prob_p1 AS numeric)),
                   prob_p2 = COALESCE(prob_p2, CAST(:prob_p2 AS numeric)),
                   prob_p3 = COALESCE(prob_p3, CAST(:prob_p3 AS numeric)),
                   prob_p4 = COALESCE(prob_p4, CAST(:prob_p4 AS numeric)),
                   regime_label = COALESCE(regime_label, CAST(:regime_label AS varchar)),
                   indicator_z = COALESCE(indicator_z, CAST(:indicator_z AS jsonb))
            WHERE id = :id""";

    /** Outcome of one fill: rows that got at least one value, and rows that already held exactly the answer. */
    record Result(int filled, int unchanged) {
    }

    /** A stored value differs from the answer. The message names the row id and the column, never a value or a month. */
    static final class ConflictException extends RuntimeException {
        ConflictException(long monthBlockId, String column) {
            super("month_block " + monthBlockId + " holds a different value in " + column);
        }
    }

    /** The months of the table changed between the engine call and the write. */
    static final class MonthsChangedException extends RuntimeException {
        MonthsChangedException() {
            super("the months of month_block changed during the run");
        }
    }

    private record Stored(long id, MonthRegime values) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate write;

    MonthRegimeWriter(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.write = new TransactionTemplate(transactions);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** The months in calendar order as {@code YYYY-MM}. No lock is kept: the engine call that follows may take a minute. */
    List<String> months() {
        return readOnly.execute(status -> select("").stream().map(s -> s.values().month()).toList());
    }

    /**
     * Fills the answer into the table in one transaction. {@code answer} holds one entry per month of the table, in order.
     * Throws {@link ConflictException} or {@link MonthsChangedException} before any row is written; any other failure rolls
     * back what was written.
     */
    Result fill(List<MonthRegime> answer) {
        return write.execute(status -> {
            List<Stored> stored = select(" FOR UPDATE");
            if (stored.size() != answer.size()) {
                throw new MonthsChangedException();
            }
            List<Integer> toFill = new ArrayList<>();
            for (int i = 0; i < stored.size(); i++) {
                if (!stored.get(i).values().month().equals(answer.get(i).month())) {
                    throw new MonthsChangedException();
                }
                if (needsFill(stored.get(i), answer.get(i))) {
                    toFill.add(i);
                }
            }
            for (int i : toFill) {
                update(stored.get(i).id(), answer.get(i));
            }
            return new Result(toFill.size(), stored.size() - toFill.size());
        });
    }

    /** True when at least one column is NULL where the answer has a value; throws when a stored value differs. */
    private static boolean needsFill(Stored stored, MonthRegime answer) {
        Map<String, Object> have = stored.values().columns();
        Map<String, Object> want = answer.columns();
        boolean fill = false;
        for (String column : MonthRegime.COLUMNS) {
            Object now = have.get(column);
            if (now == null) {
                fill |= want.get(column) != null;
            } else if (!MonthRegime.sameValue(now, want.get(column))) {
                throw new ConflictException(stored.id(), column);
            }
        }
        return fill;
    }

    private void update(long id, MonthRegime answer) {
        var statement = jdbc.sql(FILL).param("id", id);
        for (Map.Entry<String, Object> column : answer.columns().entrySet()) {
            statement = statement.param(column.getKey(), sqlValue(column.getValue()));
        }
        statement.update();
    }

    @SuppressWarnings("unchecked")
    private static Object sqlValue(Object value) {
        if (value == null || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> indicators) {
            return indicatorJson((Map<String, BigDecimal>) indicators);
        }
        return ((Enum<?>) value).name();
    }

    /** A JSON object of plain decimal numbers (the wire digits are kept) and nulls; keys and numbers are validated upstream. */
    static String indicatorJson(Map<String, BigDecimal> indicators) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, BigDecimal> entry : indicators.entrySet()) {
            json.append(first ? "" : ",").append('"').append(entry.getKey()).append("\":")
                    .append(entry.getValue() == null ? "null" : entry.getValue().toPlainString());
            first = false;
        }
        return json.append('}').toString();
    }

    private List<Stored> select(String lock) {
        return jdbc.sql(SELECT_MONTHS + lock).query(MonthRegimeWriter::row).list();
    }

    private static Stored row(ResultSet rs, int rowNum) throws SQLException {
        MonthRegime values = new MonthRegime(rs.getString("month"),
                axis(rs, "rate"), axis(rs, "growth"), axis(rs, "fx"), axis(rs, "sentiment"), probabilities(rs),
                rs.getString("regime_label") == null ? null : RegimePool.valueOf(rs.getString("regime_label")),
                indicators(rs.getString("indicator_z")));
        return new Stored(rs.getLong("id"), values);
    }

    private static AxisValue axis(ResultSet rs, String name) throws SQLException {
        String direction = rs.getString(name + "_dir");
        BigDecimal strength = rs.getBigDecimal(name + "_strength");
        return direction == null && strength == null ? null : new AxisValue(direction == null ? null : AxisDirection.valueOf(direction), strength);
    }

    private static List<BigDecimal> probabilities(ResultSet rs) throws SQLException {
        BigDecimal first = rs.getBigDecimal("prob_p1");
        if (first == null) {
            return null;
        }
        return List.of(first, rs.getBigDecimal("prob_p2"), rs.getBigDecimal("prob_p3"), rs.getBigDecimal("prob_p4"));
    }

    private static Map<String, BigDecimal> indicators(String json) {
        if (json == null) {
            return null;
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : JSON.readTree(json).properties()) {
            out.put(field.getKey(), field.getValue().isNull() ? null : field.getValue().decimalValue());
        }
        return out;
    }
}
