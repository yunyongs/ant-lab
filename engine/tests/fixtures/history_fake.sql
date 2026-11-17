-- FAKE DATA — not market data
-- Synthetic history for the engine reader tests. Dates are calendar facts, values are invented.
-- Assets are inserted out of (market, ticker) order on purpose: ids must not follow the sort.
-- Months: 2006-12, 2007-01..2012-04, 2023-01..2026-01 (the M2-02 sample shape).

INSERT INTO asset_group (code, name, kind) VALUES ('FAKE_GROUP', 'FAKE group', 'SECTOR');

INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name)
SELECT v.market, v.ticker, v.name, v.asset_type, v.currency, g.id, DATE '2000-01-03', 'PRICE', v.proxy
FROM (VALUES
    ('US', 'ZZUS02', 'FAKE US fund',  'ETF',   'USD', 'FAKE index'),
    ('US', 'ZZUS01', 'FAKE US stock', 'STOCK', 'USD', NULL),
    ('KR', 'ZZ0001', 'FAKE KR stock', 'STOCK', 'KRW', NULL)
) AS v (market, ticker, name, asset_type, currency, proxy)
CROSS JOIN asset_group g WHERE g.code = 'FAKE_GROUP';

INSERT INTO month_block (month_start)
SELECT m::date
FROM generate_series(DATE '2006-12-01', DATE '2006-12-01', INTERVAL '1 month') AS m
UNION ALL
SELECT m::date FROM generate_series(DATE '2007-01-01', DATE '2012-04-01', INTERVAL '1 month') AS m
UNION ALL
SELECT m::date FROM generate_series(DATE '2023-01-01', DATE '2026-01-01', INTERVAL '1 month') AS m;

INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)
SELECT a.id, d.base_date, 1000.0000, CASE WHEN a.currency = 'USD' THEN 1300.0000 ELSE 1.0000 END
FROM asset a
JOIN (VALUES
    ('ZZUS02', DATE '2026-01-02'), ('ZZUS01', DATE '2026-01-02'), ('ZZ0001', DATE '2026-01-02'),
    ('ZZ0001', DATE '2025-01-02'), ('ZZUS01', DATE '2025-01-02'),
    ('ZZ0001', DATE '2025-07-01'), ('ZZUS01', DATE '2025-07-01')
) AS d (ticker, base_date) ON d.ticker = a.ticker;

-- Deterministic returns in [-0.08, 0.08], every asset in every month.
INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
SELECT a.id, m.id,
       round(((EXTRACT(MONTH FROM m.month_start)::int * 7 + EXTRACT(YEAR FROM m.month_start)::int * 3
               + a.id::int * 5) % 17 - 8) / 100.0, 8),
       'ACTUAL'
FROM asset a CROSS JOIN month_block m;

-- Hand-chosen rows: an exact zero, a long negative fraction, a proxy-index month.
UPDATE asset_month_return r SET local_return = 0
FROM asset a, month_block m
WHERE r.asset_id = a.id AND r.month_block_id = m.id AND a.ticker = 'ZZ0001' AND m.month_start = DATE '2023-01-01';

UPDATE asset_month_return r SET local_return = -0.12345678
FROM asset a, month_block m
WHERE r.asset_id = a.id AND r.month_block_id = m.id AND a.ticker = 'ZZUS01' AND m.month_start = DATE '2023-02-01';

UPDATE asset_month_return r SET return_kind = 'PROXY_INDEX'
FROM asset a, month_block m
WHERE r.asset_id = a.id AND r.month_block_id = m.id AND a.ticker = 'ZZUS02'
  AND m.month_start BETWEEN DATE '2007-01-01' AND DATE '2007-06-01';

INSERT INTO fx_month (month_block_id, currency, fx_change)
SELECT m.id, 'USD', round(((EXTRACT(MONTH FROM m.month_start)::int * 5 + EXTRACT(YEAR FROM m.month_start)::int) % 11 - 5) / 100.0, 8)
FROM month_block m;
