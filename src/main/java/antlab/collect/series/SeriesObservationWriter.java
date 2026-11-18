package antlab.collect.series;

import antlab.collect.job.RunLostException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends observations as vintages (erd D41, 10.6). There is no UPDATE or DELETE on series_observation here, by design.
 * One series is one transaction, so a failing series leaves the others' rows in place.
 * <ul>
 * <li>{@link Mode#LATEST}: the source gives today's value of each date. A value equal to the latest stored vintage adds
 *     nothing, a different one adds the next vintage, a new date adds vintage 1.</li>
 * <li>{@link Mode#HISTORY}: the source gives the whole vintage history of each date (ALFRED). The stored vintages must be
 *     a prefix of it; the rest is appended. A stored vintage that is missing or different is an error, not a revision.</li>
 * </ul>
 */
public final class SeriesObservationWriter {

    public enum Mode { LATEST, HISTORY }

    /** One value to store; {@code realtimeStart} and {@code availableOn} are null when the source gives no date. */
    public record Incoming(LocalDate refDate, BigDecimal value, LocalDate realtimeStart, LocalDate availableOn) {
    }

    public record Result(int inserted, int revised, int unchanged) {
    }

    /** FOR SHARE waits for a recovery UPDATE of the run row and blocks it until this transaction ends. */
    private static final String RUN_STATUS = "SELECT status FROM job_run WHERE id = ? FOR SHARE";

    private static final String STORED = """
            SELECT ref_date, vintage_no, value, realtime_start, available_on, observed_at
            FROM series_observation
            WHERE series_id = ? AND ref_date >= ?
            """;

    private static final String INSERT = """
            INSERT INTO series_observation (series_id, ref_date, value, realtime_start, available_on, observed_at,
                                            vintage_no, job_run_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private record Stored(int vintageNo, BigDecimal value, LocalDate realtimeStart, LocalDate availableOn, Instant observedAt) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SeriesObservationWriter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LocalDate latestRefDate(long seriesId) {
        return jdbc.queryForObject("SELECT max(ref_date) FROM series_observation WHERE series_id = ?", LocalDate.class, seriesId);
    }

    /** Reads the stored vintages from the earliest incoming date; use the overload with {@code from} for a history read. */
    public Result write(long jobRunId, Instant observedAt, long seriesId, Mode mode, List<Incoming> incoming) {
        LocalDate from = incoming.stream().map(Incoming::refDate).min(Comparator.naturalOrder()).orElse(null);
        return write(jobRunId, observedAt, seriesId, mode, from, incoming);
    }

    /**
     * @param from the first reference date the source was asked for; in history mode every stored date from it on must
     *             be in the answer, so a vanished leading date or an empty answer fails instead of committing the rest
     */
    public Result write(long jobRunId, Instant observedAt, long seriesId, Mode mode, LocalDate from, List<Incoming> incoming) {
        if (incoming.isEmpty() && (mode == Mode.LATEST || from == null)) {
            return new Result(0, 0, 0);
        }
        LocalDate earliest = from;
        return tx.execute(status -> {
            List<String> state = jdbc.queryForList(RUN_STATUS, String.class, jobRunId);
            if (state.size() != 1 || !"RUNNING".equals(state.getFirst())) {
                throw new RunLostException(jobRunId);
            }
            Map<LocalDate, List<Stored>> stored = new HashMap<>();
            jdbc.query(STORED, rs -> {
                stored.computeIfAbsent(rs.getObject("ref_date", LocalDate.class), d -> new ArrayList<>())
                        .add(new Stored(rs.getInt("vintage_no"), rs.getBigDecimal("value"),
                                rs.getObject("realtime_start", LocalDate.class), rs.getObject("available_on", LocalDate.class),
                                rs.getObject("observed_at", OffsetDateTime.class).toInstant()));
            }, seriesId, earliest);
            stored.values().forEach(list -> list.sort(Comparator.comparingInt(Stored::vintageNo)));

            Map<LocalDate, List<Incoming>> byDate = new LinkedHashMap<>();
            for (Incoming row : incoming) {
                byDate.computeIfAbsent(row.refDate(), d -> new ArrayList<>()).add(row);
            }
            if (mode == Mode.HISTORY) {
                for (LocalDate storedDate : stored.keySet()) {
                    if (!byDate.containsKey(storedDate)) {
                        throw new VintageMismatchException("a stored date is missing from the source's history");
                    }
                }
            }
            List<Object[]> rows = new ArrayList<>();
            int revised = 0;
            int unchanged = 0;
            for (Map.Entry<LocalDate, List<Incoming>> entry : byDate.entrySet()) {
                List<Stored> known = stored.getOrDefault(entry.getKey(), List.of());
                List<Incoming> wanted = entry.getValue();
                if (mode == Mode.LATEST) {
                    if (wanted.size() != 1) {
                        throw new VintageMismatchException("the source gave more than one value for one date");
                    }
                    Incoming row = wanted.getFirst();
                    Stored latest = known.isEmpty() ? null : known.getLast();
                    if (latest != null && latest.value().compareTo(row.value()) == 0) {
                        unchanged++;
                        continue;
                    }
                    if (latest != null && !observedAt.isAfter(latest.observedAt())) {
                        throw new ClockSkewException();
                    }
                    int vintage = latest == null ? 1 : latest.vintageNo() + 1;
                    if (latest != null) {
                        revised++;
                    }
                    rows.add(insertRow(jobRunId, observedAt, seriesId, row, vintage));
                } else {
                    wanted = new ArrayList<>(wanted);
                    wanted.sort(Comparator.comparing(Incoming::realtimeStart, Comparator.nullsFirst(Comparator.naturalOrder())));
                    if (known.size() > wanted.size()) {
                        throw new VintageMismatchException("a stored vintage is missing from the source's history");
                    }
                    if (wanted.size() > known.size() && !known.isEmpty() && !observedAt.isAfter(known.getLast().observedAt())) {
                        throw new ClockSkewException();
                    }
                    for (int i = 0; i < wanted.size(); i++) {
                        Incoming row = wanted.get(i);
                        if (i > 0 && Objects.equals(row.realtimeStart(), wanted.get(i - 1).realtimeStart())) {
                            throw new VintageMismatchException("two values claim one vintage");
                        }
                        if (i < known.size()) {
                            Stored have = known.get(i);
                            if (!Objects.equals(have.realtimeStart(), row.realtimeStart())
                                    || !Objects.equals(have.availableOn(), row.availableOn())
                                    || have.value().compareTo(row.value()) != 0) {
                                throw new VintageMismatchException("a stored vintage differs from the source's history");
                            }
                            unchanged++;
                        } else {
                            if (i > 0) {
                                revised++;
                            }
                            rows.add(insertRow(jobRunId, observedAt, seriesId, row, i + 1));
                        }
                    }
                }
            }
            if (!rows.isEmpty()) {
                jdbc.batchUpdate(INSERT, rows);
            }
            return new Result(rows.size(), revised, unchanged);
        });
    }

    private static Object[] insertRow(long jobRunId, Instant observedAt, long seriesId, Incoming row, int vintage) {
        return new Object[] {seriesId, row.refDate(), row.value(), row.realtimeStart(), row.availableOn(),
                observedAt.atOffset(ZoneOffset.UTC), vintage, jobRunId};
    }
}
