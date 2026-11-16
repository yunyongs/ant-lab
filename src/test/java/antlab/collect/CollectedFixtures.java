package antlab.collect;

import org.springframework.jdbc.core.JdbcTemplate;

/** Inserts synthetic runs and closes for the collected-data tests. Fake values only; assets come from history_pool.sql. */
final class CollectedFixtures {

    static final String DAY_1 = "2030-01-02";
    static final String RUN_1 = "2030-01-03 14:00:00+09";
    static final String RUN_2 = "2030-01-04 09:00:00+09";
    static final String RUN_3 = "2030-01-05 09:00:00+09";

    private final JdbcTemplate jdbc;

    CollectedFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A succeeded run that started at {@code startedAt}; returns its id. */
    long run(String startedAt) {
        return jdbc.queryForObject("""
                INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at,
                                     request_count, inserted_count, revised_count, unchanged_count, failed_target_count)
                VALUES ('KIS_DAILY_PRICE', 'MANUAL', 'SUCCEEDED', ?::timestamptz, ?::timestamptz + interval '1 minute',
                        1, 1, 0, 0, 0) RETURNING id
                """, Long.class, startedAt, startedAt);
    }

    /** One vintage of one close; the asset is found by its natural key. */
    long price(String market, String ticker, String tradeDate, String basis, String exchange, String close,
               int vintage, String observedAt, long runId) {
        return jdbc.queryForObject("""
                INSERT INTO asset_daily_price (asset_id, trade_date, price_basis, source, exchange_code, close_price,
                                               vintage_no, observed_at, job_run_id)
                SELECT a.id, ?::date, ?, 'KIS', ?, ?::numeric, ?, ?::timestamptz, ?
                FROM asset a WHERE a.market = ? AND a.ticker = ?
                RETURNING id
                """, Long.class, tradeDate, basis, exchange, close, vintage, observedAt, runId, market, ticker);
    }
}
