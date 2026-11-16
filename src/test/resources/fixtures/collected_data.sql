-- Fake data, not real quotes: synthetic rows for the collected-data mapping tests. Needs history_pool.sql (assets).
-- Looked up by natural key, never by assumed id. The test transaction rolls everything back.

INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at,
                     request_count, inserted_count, revised_count, unchanged_count, failed_target_count)
VALUES ('KIS_DAILY_PRICE', 'MANUAL', 'SUCCEEDED', TIMESTAMPTZ '2030-01-03 14:00:00+09', TIMESTAMPTZ '2030-01-03 14:01:00+09',
        5, 3, 0, 0, 0);

INSERT INTO asset_daily_price (asset_id, trade_date, price_basis, source, exchange_code, close_price, vintage_no, observed_at, job_run_id)
SELECT a.id, v.trade_date, v.price_basis, 'KIS', v.exchange_code, v.close_price, 1,
       TIMESTAMPTZ '2030-01-03 14:00:00+09', (SELECT min(id) FROM job_run)
FROM (VALUES
    ('KR', '000660', DATE '2030-01-02', 'ADJUSTED', NULL,  111111.0000),
    ('KR', '069500', DATE '2030-01-02', 'RAW',      NULL,   22222.0000),
    ('US', 'SPY',    DATE '2030-01-02', 'ADJUSTED', 'AMS',    333.3300)
) AS v(market, ticker, trade_date, price_basis, exchange_code, close_price)
JOIN asset a ON a.market = v.market AND a.ticker = v.ticker;
