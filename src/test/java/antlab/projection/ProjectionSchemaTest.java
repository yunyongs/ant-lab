package antlab.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.support.FlywayScratchDatabase;
import antlab.support.JpaMappingTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V9 as a schema: the exact set of constraints and indexes it adds, an upgrade that leaves seasons alone, the exposure tags,
 * and one bad row per rule (checked by SQLSTATE and constraint name, never by message text).
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProjectionSchemaTest {

    private static final Set<String> EXPECTED = Set.of("projection.pk_projection", "projection.uq_projection_season_phase",
            "projection.fk_projection_season", "projection.ck_projection_phase", "projection.ck_projection_turn_no",
            "projection.ck_projection_total_value", "projection.ck_projection_horizon_turns",
            "projection.ck_projection_path_count", "projection.ck_projection_request", "projection.ck_projection_result",
            "projection.ck_projection_versions", "projection.ck_projection_history_digest");

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String UNIQUE = "23505";
    private static final String SEASON_SQL = """
            INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date,
                initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                start_goalkeeper, start_defender, start_midfielder, start_forward,
                current_goalkeeper, current_defender, current_midfielder, current_forward)
            VALUES (%d, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004, -0.20,
                10, 30, 40, 20, 10, 30, 40, 20) RETURNING id""";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    PostgreSQLContainer postgres;

    private static Set<String> objects(JdbcTemplate j) {
        Set<String> out = new TreeSet<>();
        j.query("""
                SELECT conrelid::regclass::text || '.' || conname FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace AND conrelid::regclass::text <> 'flyway_schema_history'
                UNION
                SELECT tablename || '.' || indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """, rs -> {
            out.add(rs.getString(1));
        });
        return out;
    }

    @Test
    void v9AddsExactlyTheNamedConstraintsAndIndexes() {
        Set<String> v8;
        Set<String> v9;
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v9_catalog")) {
            scratch.migrateTo("8");
            v8 = objects(new JdbcTemplate(scratch.dataSource()));
            scratch.migrateTo("9");
            v9 = objects(new JdbcTemplate(scratch.dataSource()));
        }
        Set<String> added = new TreeSet<>(v9);
        added.removeAll(v8);

        assertThat(v8).isSubsetOf(v9);
        assertThat(added).containsExactlyInAnyOrderElementsOf(EXPECTED);
        assertThat(EXPECTED).allSatisfy(n -> assertThat(n.substring(n.indexOf('.') + 1).length()).isLessThanOrEqualTo(63));
    }

    @Test
    void upgradeFromV8LeavesSeasonsAloneAndCreatesAnEmptyTable() {
        try (FlywayScratchDatabase scratch = new FlywayScratchDatabase(postgres, "v9_upgrade")) {
            scratch.migrateTo("8");
            JdbcTemplate scratchJdbc = new JdbcTemplate(scratch.dataSource());
            scratchJdbc.update("INSERT INTO member (email, name, password) VALUES ('player@example.invalid', 'p1', '!')");
            scratchJdbc.update("""
                    INSERT INTO season (member_id, status, path_length, current_turn_no, seed, engine_version,
                        data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                        start_goalkeeper, start_defender, start_midfielder, start_forward,
                        current_goalkeeper, current_defender, current_midfielder, current_forward)
                    SELECT id, 'FINISHED', 12, 12, 1, 'test', '2020-01-31', '2020-01-31', 10000000, 0.001, 0.001, 0.004,
                        -0.20, 10, 30, 40, 20, 10, 30, 40, 20 FROM member ORDER BY id LIMIT 1
                    """);
            List<Map<String, Object>> before = scratchJdbc.queryForList("SELECT * FROM season ORDER BY id");

            assertThat(scratch.migrateTo("9").migrationsExecuted).isEqualTo(1);

            assertThat(scratchJdbc.queryForList("SELECT * FROM season ORDER BY id")).isEqualTo(before);
            assertThat(scratchJdbc.queryForObject("SELECT count(*) FROM projection", Integer.class)).isZero();
            assertThat(scratch.migrateTo("9").migrationsExecuted).isZero();
        }
    }

    @Test
    void everyColumnHasAnExposureTagAndTheDigestAndSeedLikeColumnsAreInternal() {
        assertThat(jdbc.queryForList("""
                SELECT a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relname = 'projection' AND a.attnum > 0 AND NOT a.attisdropped
                  AND coalesce(col_description(c.oid, a.attnum), '') !~ '^\\[(노출|내부)\\] '
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relname = 'projection' AND a.attnum > 0 AND NOT a.attisdropped
                  AND col_description(c.oid, a.attnum) LIKE '[내부]%' ORDER BY a.attname
                """, String.class)).containsExactly("created_at", "engine_version", "history_digest", "id", "projection_version",
                "request", "season_id");
    }

    final class Tx implements AutoCloseable {
        final Connection c;
        final long season;

        Tx() throws SQLException {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
            long member = queryLong("INSERT INTO member (email, name, password) VALUES ('pj-" + SEQ.incrementAndGet()
                    + "@example.invalid', 'm', 'x') RETURNING id");
            season = queryLong(SEASON_SQL.formatted(member));
        }

        String rowSql(Map<String, String> overrides) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("season_id", String.valueOf(season));
            v.put("phase", "'START'");
            v.put("turn_no", "0");
            v.put("total_value", "10000000");
            v.put("horizon_turns", "60");
            v.put("path_count", "2000");
            v.put("request", "'{}'::jsonb");
            v.put("result", "'[]'::jsonb");
            v.put("projection_version", "'m2-pj-v1'");
            v.put("engine_version", "'m2-sb6-v1'");
            v.put("history_digest", "repeat('a', 64)");
            v.putAll(overrides);
            return "INSERT INTO projection (" + String.join(", ", v.keySet()) + ") VALUES (" + String.join(", ", v.values()) + ")";
        }

        void exec(String sql) throws SQLException {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }

        long queryLong(String sql) throws SQLException {
            try (PreparedStatement s = c.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }

        @Override
        public void close() throws SQLException {
            c.rollback();
            c.close();
        }
    }

    @Test
    void aValidRowIsAccepted() throws Exception {
        try (Tx tx = new Tx()) {
            tx.exec(tx.rowSql(Map.of()));
            tx.exec(tx.rowSql(Map.of("phase", "'END'", "turn_no", "12")));
        }
    }

    @ParameterizedTest(name = "{0} = {1} breaks {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "phase|'MID'|23514|ck_projection_phase",
            "turn_no|3|23514|ck_projection_start_turn",
            "total_value|0|23514|ck_projection_total_value",
            "total_value|10000000.5|23514|ck_projection_total_value",
            "total_value|'NaN'::numeric|23514|ck_projection_total_value",
            "horizon_turns|61|23514|ck_projection_horizon_turns",
            "path_count|99|23514|ck_projection_path_count",
            "path_count|5001|23514|ck_projection_path_count",
            "request|'[]'::jsonb|23514|ck_projection_request",
            "result|'{}'::jsonb|23514|ck_projection_result",
            "projection_version|''|23514|ck_projection_versions",
            "engine_version|''|23514|ck_projection_versions",
            "history_digest|repeat('A', 64)|23514|ck_projection_history_digest",
            "history_digest|repeat('a', 63)|23514|ck_projection_history_digest",
            "season_id|987654321|23503|fk_projection_season"})
    void aRuleBreakingRowIsRejectedByTheNamedConstraint(String column, String literal, String state, String constraint)
            throws Exception {
        try (Tx tx = new Tx()) {
            String sql = tx.rowSql(Map.of(column, literal));
            assertThatThrownBy(() -> tx.exec(sql)).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo(state);
                assertThat(e.getMessage()).contains(constraint);
            });
        }
    }

    @Test
    void aNegativeTurnOfAnEndRowIsRejected() throws Exception {
        try (Tx tx = new Tx()) {
            String sql = tx.rowSql(Map.of("phase", "'END'", "turn_no", "-1"));
            assertThatThrownBy(() -> tx.exec(sql)).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo("23514");
                assertThat(e.getMessage()).contains("ck_projection_turn_no");
            });
        }
    }

    @Test
    void aSecondRowForTheSameSeasonAndPhaseIsRejected() throws Exception {
        try (Tx tx = new Tx()) {
            tx.exec(tx.rowSql(Map.of()));
            assertThatThrownBy(() -> tx.exec(tx.rowSql(Map.of()))).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo(UNIQUE);
                assertThat(e.getMessage()).contains("uq_projection_season_phase");
            });
        }
    }
}
