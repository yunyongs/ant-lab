package antlab.collect.series;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.JpaMappingTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One bad row per V12 constraint, checked by SQLSTATE and constraint name. PostgreSQL reports only the first violated CHECK
 * by name (erd 2), so each case breaks exactly one rule. Each case runs in its own rolled-back transaction.
 */
@JpaMappingTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MacroConstraintTest {

    private static final String START = "TIMESTAMPTZ '2030-01-03 09:00:00+09'";
    private static final String FINISH = "TIMESTAMPTZ '2030-01-03 09:01:00+09'";
    private static final Pattern CONSTRAINT = Pattern.compile("(?:constraint|제약 조건) \"([a-z_]+)\"");

    @Autowired
    DataSource dataSource;

    private final class Tx implements AutoCloseable {
        final Connection c;

        Tx() throws SQLException {
            c = dataSource.getConnection();
            c.setAutoCommit(false);
        }

        long series(Map<String, String> over) throws SQLException {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("code", "'TEST_SERIES'");
            v.put("source", "'FRED'");
            v.put("source_series_id", "'TESTSERIES'");
            v.put("unit", "'INDEX'");
            v.put("frequency", "'MONTHLY'");
            v.put("release_lag_days", "10");
            v.put("revisable", "true");
            v.putAll(over);
            return queryLong("INSERT INTO series (" + String.join(", ", v.keySet()) + ") VALUES (" + String.join(", ", v.values())
                    + ") RETURNING id");
        }

        long run() throws SQLException {
            return queryLong("INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at, request_count, inserted_count, "
                    + "revised_count, unchanged_count, failed_target_count) VALUES ('FRED_SERIES', 'MANUAL', 'SUCCEEDED', " + START + ", "
                    + FINISH + ", 1, 1, 0, 0, 0) RETURNING id");
        }

        long observation(long series, long run, Map<String, String> over) throws SQLException {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("series_id", Long.toString(series));
            v.put("ref_date", "DATE '2025-10-01'");
            v.put("value", "100.5");
            v.put("realtime_start", "DATE '2025-11-13'");
            v.put("observed_at", START);
            v.put("vintage_no", "1");
            v.put("job_run_id", Long.toString(run));
            v.putAll(over);
            return queryLong("INSERT INTO series_observation (" + String.join(", ", v.keySet()) + ") VALUES ("
                    + String.join(", ", v.values()) + ") RETURNING id");
        }

        long queryLong(String sql) throws SQLException {
            try (Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
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

    private interface Attempt {
        void run(Tx tx) throws SQLException;
    }

    private record Case(String name, String sqlState, String constraint, Attempt attempt) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Case bad(String constraint, String sqlState, Attempt attempt) {
        return new Case(constraint, sqlState, constraint, attempt);
    }

    static Stream<Case> cases() {
        return Stream.of(
                bad("ck_series_code_format", "23514", tx -> tx.series(Map.of("code", "'lower_case'"))),
                bad("ck_series_source", "23514", tx -> tx.series(Map.of("source", "'WB'"))),
                bad("ck_series_source_series_id_format", "23514", tx -> tx.series(Map.of("source_series_id", "'bad id'"))),
                bad("ck_series_unit_format", "23514", tx -> tx.series(Map.of("unit", "'percent'"))),
                bad("ck_series_frequency", "23514", tx -> tx.series(Map.of("frequency", "'HOURLY'"))),
                bad("ck_series_release_lag_days", "23514", tx -> tx.series(Map.of("release_lag_days", "61"))),
                bad("ck_series_release_lag_days", "23514", tx -> tx.series(Map.of("release_lag_days", "-1"))),
                bad("uq_series_code", "23505", tx -> {
                    tx.series(Map.of());
                    tx.series(Map.of("source_series_id", "'OTHER'"));
                }),
                bad("uq_series_source_series_id", "23505", tx -> {
                    tx.series(Map.of());
                    tx.series(Map.of("code", "'TEST_OTHER'"));
                }),
                bad("ck_job_run_job_name", "23514", tx -> tx.queryLong("INSERT INTO job_run (job_name, run_trigger, status, started_at) "
                        + "VALUES ('WB_SERIES', 'MANUAL', 'RUNNING', " + START + ") RETURNING id")),
                bad("ck_series_observation_value", "23514", tx -> tx.observation(tx.series(Map.of()), tx.run(), Map.of("value", "'NaN'::numeric"))),
                bad("ck_series_observation_vintage_no", "23514", tx -> tx.observation(tx.series(Map.of()), tx.run(), Map.of("vintage_no", "0"))),
                bad("ck_series_observation_one_date", "23514", tx -> tx.observation(tx.series(Map.of()), tx.run(),
                        Map.of("available_on", "DATE '2025-11-14'"))),
                bad("ck_series_observation_realtime_start", "23514", tx -> tx.observation(tx.series(Map.of()), tx.run(),
                        Map.of("realtime_start", "DATE '2025-09-30'"))),
                bad("ck_series_observation_available_on", "23514", tx -> tx.observation(tx.series(Map.of()), tx.run(),
                        Map.of("realtime_start", "NULL", "available_on", "DATE '2025-09-30'"))),
                bad("fk_series_observation_series", "23503", tx -> tx.observation(-1, tx.run(), Map.of())),
                bad("fk_series_observation_job_run", "23503", tx -> tx.observation(tx.series(Map.of()), -1, Map.of())),
                bad("uq_series_observation_vintage", "23505", tx -> {
                    long series = tx.series(Map.of());
                    long run = tx.run();
                    tx.observation(series, run, Map.of());
                    tx.observation(series, run, Map.of("realtime_start", "DATE '2025-11-20'"));
                }),
                bad("uq_series_observation_realtime", "23505", tx -> {
                    long series = tx.series(Map.of());
                    long run = tx.run();
                    tx.observation(series, run, Map.of());
                    tx.observation(series, run, Map.of("vintage_no", "2"));
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void 나쁜_행은_정해진_제약_이름으로_거절된다(Case c) throws SQLException {
        try (Tx tx = new Tx()) {
            try {
                c.attempt().run(tx);
                assertThat(c.constraint()).as("the bad row was accepted").isNull();
            } catch (SQLException e) {
                assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(c.sqlState());
                Matcher m = CONSTRAINT.matcher(e.getMessage());
                assertThat(m.find()).as(e.getMessage()).isTrue();
                assertThat(m.group(1)).isEqualTo(c.constraint());
            }
        }
    }

    @Test
    void 날짜_없는_행은_같은_날짜에_여러_빈티지를_가질_수_있고_날짜_있는_빈티지는_겹치지_않는다() throws SQLException {
        try (Tx tx = new Tx()) {
            long series = tx.series(Map.of());
            long run = tx.run();
            tx.observation(series, run, Map.of("realtime_start", "NULL"));
            tx.observation(series, run, Map.of("realtime_start", "NULL", "vintage_no", "2", "value", "100.6"));
            tx.observation(series, run, Map.of("realtime_start", "DATE '2025-11-13'", "vintage_no", "3", "value", "100.7"));

            assertThat(tx.queryLong("SELECT count(*) FROM series_observation")).isEqualTo(3);
        }
    }

    @Test
    void 값_열은_큰_수준과_음수와_여덟_자리_소수를_받는다() throws SQLException {
        try (Tx tx = new Tx()) {
            long series = tx.series(Map.of());
            long run = tx.run();
            tx.observation(series, run, Map.of("value", "1234567890123456.12345678"));
            tx.observation(series, run, Map.of("value", "-0.15000000", "ref_date", "DATE '2025-11-01'", "realtime_start", "NULL"));

            assertThat(tx.queryLong("SELECT count(*) FROM series_observation")).isEqualTo(2);
        }
    }
}
