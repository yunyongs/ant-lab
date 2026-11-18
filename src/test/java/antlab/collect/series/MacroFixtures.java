package antlab.collect.series;

import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts synthetic series, runs and observations for the macro tests. Fake values only. */
final class MacroFixtures {

    static final String RUN_1 = "2030-01-03 14:00:00+09";
    static final String RUN_2 = "2030-01-04 09:00:00+09";

    private final JdbcTemplate jdbc;

    MacroFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long series(String code, String source, String sourceId, boolean revisable) {
        return jdbc.queryForObject("""
                INSERT INTO series (code, source, source_series_id, unit, frequency, release_lag_days, revisable)
                VALUES (?, ?, ?, 'INDEX', 'MONTHLY', 10, ?) RETURNING id
                """, Long.class, code, source, sourceId, revisable);
    }

    long run(String jobName, String startedAt) {
        return jdbc.queryForObject("""
                INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at,
                                     request_count, inserted_count, revised_count, unchanged_count, failed_target_count)
                VALUES (?, 'MANUAL', 'SUCCEEDED', ?::timestamptz, ?::timestamptz + interval '1 minute', 1, 1, 0, 0, 0) RETURNING id
                """, Long.class, jobName, startedAt, startedAt);
    }

    long observation(long seriesId, String refDate, String value, String realtimeStart, String availableOn, int vintage,
            String observedAt, long runId) {
        return jdbc.queryForObject("""
                INSERT INTO series_observation (series_id, ref_date, value, realtime_start, available_on, observed_at, vintage_no, job_run_id)
                VALUES (?, ?::date, ?::numeric, ?::date, ?::date, ?::timestamptz, ?, ?) RETURNING id
                """, Long.class, seriesId, refDate, value, realtimeStart, availableOn, observedAt, vintage, runId);
    }
}
