-- Fake data, not real quotes: the synthetic history of the simulator parity harness (SimulatorParityFixtureTest).
-- Every name starts with the fake marker so the shared cleanup scripts can find it. Nothing here is a real price or return.
--
-- Shape: 14 pool assets (the two fixed benchmarks 069500 and SPY among them) plus one asset outside the pool, 228 months
-- (2007-01 to 2025-12, all reviewed, no events) so 12-, 24- and 60-turn paths exist. A US asset has a lower id than the KR
-- ones, so the id order differs from the key order. Crafted rows: half-won base prices (rounding ties), a -99 % month,
-- a block of zero returns, a USD month with no change, and an asset that never falls (cannot be measured by the season).

INSERT INTO asset_group (code, name, kind) VALUES
    ('FAKE_SEMI',  '가짜 반도체', 'SECTOR'),
    ('FAKE_INDEX', '가짜 지수',   'ASSET_CLASS');

INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name)
SELECT v.market, v.ticker, v.name, v.asset_type, v.currency, g.id, DATE '1999-01-04', v.basis, v.proxy
FROM (VALUES
    (1, 'US', 'SPY',    '가짜 미국 지수 ETF', 'ETF',   'USD', 'FAKE_INDEX', 'TOTAL', '가짜 추종 지수'),
    (2, 'KR', '069500', '가짜 국내 지수 ETF', 'ETF',   'KRW', 'FAKE_INDEX', 'PRICE', NULL),
    (3, 'US', 'BRK.B',  '가짜 미국 주식 A',   'STOCK', 'USD', 'FAKE_SEMI',  'TOTAL', NULL),
    (4, 'KR', '000660', '가짜 국내 주식 A',   'STOCK', 'KRW', 'FAKE_SEMI',  'PRICE', NULL),
    (5, 'KR', '005930', '가짜 국내 주식 B',   'STOCK', 'KRW', 'FAKE_SEMI',  'PRICE', NULL),
    (6, 'US', 'AAPL',   '가짜 미국 주식 B',   'STOCK', 'USD', 'FAKE_SEMI',  'TOTAL', NULL),
    (7, 'KR', '035420', '가짜 국내 주식 C',   'STOCK', 'KRW', 'FAKE_SEMI',  'PRICE', NULL),
    (8, 'US', 'MSFT',   '가짜 미국 주식 C',   'STOCK', 'USD', 'FAKE_SEMI',  'TOTAL', NULL),
    (9, 'KR', '051910', '가짜 국내 주식 D',   'STOCK', 'KRW', 'FAKE_SEMI',  'PRICE', NULL),
    (10, 'KR', '099999', '가짜 국내 채권 ETF', 'ETF',   'KRW', 'FAKE_INDEX', 'PRICE', NULL),
    (11, 'US', 'GLD',    '가짜 미국 금 ETF',   'ETF',   'USD', 'FAKE_INDEX', 'TOTAL', NULL),
    (12, 'KR', '090430', '가짜 국내 주식 E',   'STOCK', 'KRW', 'FAKE_SEMI',  'PRICE', NULL),
    (13, 'US', 'QQQ',    '가짜 미국 성장 ETF', 'ETF',   'USD', 'FAKE_INDEX', 'TOTAL', NULL),
    (14, 'KR', '122630', '가짜 국내 레버리지 ETF', 'ETF', 'KRW', 'FAKE_INDEX', 'PRICE', NULL),
    (15, 'KR', '999999', '가짜 풀 밖 종목',    'STOCK', 'KRW', 'FAKE_SEMI',  'PRICE', NULL)
) AS v(ord, market, ticker, name, asset_type, currency, grp, basis, proxy)
JOIN asset_group g ON g.code = v.grp
ORDER BY v.ord;

-- Base-date pool: 14 judged assets (the 15th has no row, so it is not in the pool).
INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank,
                            window_start, window_end, months_used,
                            up_participation, down_participation, volatility, max_drawdown, system_position)
SELECT a.id, DATE '2026-01-02', v.base_price, v.base_fx_rate, NULL,
       DATE '2020-01-01', DATE '2025-12-01', 72,
       v.up, v.down, v.vol, v.mdd, v.pos
FROM (VALUES
    ('US', 'SPY',     480.0000, 1350.0000, 0.98000000, 0.95000000, 0.04500000, -0.19000000, 'DEFENDER'),
    ('KR', '069500', 35000.5000,    1.0000, 1.00000000, 1.00000000, 0.05000000, -0.20000000, 'MIDFIELDER'),
    ('US', 'BRK.B',   400.0000, 1350.0000, 0.70000000, 0.60000000, 0.06000000, -0.25000000, 'GOALKEEPER'),
    ('KR', '000660', 150000.0000,   1.0000, 1.52000000, 1.31000000, 0.12000000, -0.48000000, 'FORWARD_BREAKOUT'),
    ('KR', '005930',  72000.0000,   1.0000, 1.05000000, 1.02000000, 0.07000000, -0.30000000, 'FORWARD_TARGET'),
    ('US', 'AAPL',    190.2500, 1350.0000, 1.15000000, 1.10000000, 0.08000000, -0.33000000, 'FORWARD_TARGET'),
    ('KR', '035420', 210500.0000,   1.0000, 1.00000000, 0.90000000, 0.07500000, -0.35000000, 'MIDFIELDER'),
    ('US', 'MSFT',     33.3350, 1350.0000, 0.95000000, 0.70000000, 0.06500000, -0.28000000, 'FORWARD_DEFENSIVE'),
    ('KR', '051910', 250000.0000,   1.0000, 0.99000000, 1.15000000, 0.09000000, -0.40000000, 'DEFENDER'),
    ('KR', '099999', 100000.0000,   1.0000, 1.00000000, 0.01000000, 0.00500000, -0.01000000, 'GOALKEEPER'),
    ('US', 'GLD',     180.0000, 1350.0000, 0.85000000, 0.80000000, 0.05500000, -0.22000000, 'DEFENDER'),
    ('KR', '090430',  15000.0000,   1.0000, 1.60000000, 1.40000000, 0.14000000, -0.55000000, 'FORWARD_BREAKOUT'),
    ('US', 'QQQ',     400.0000, 1362.5000, 1.10000000, 1.05000000, 0.07000000, -0.30000000, 'MIDFIELDER'),
    ('KR', '122630',  12345.5000,   1.0000, 1.20000000, 1.25000000, 0.11000000, -0.45000000, 'FORWARD_TARGET')
) AS v(market, ticker, base_price, base_fx_rate, up, down, vol, mdd, pos)
JOIN asset a ON a.market = v.market AND a.ticker = v.ticker;

INSERT INTO month_block (month_start, events_reviewed)
SELECT d::date, TRUE
FROM generate_series(DATE '2007-01-01', DATE '2025-12-01', INTERVAL '1 month') AS d;

-- Local returns: a deterministic integer-seeded wave per asset (a.n = asset order, m.i = month number from 2007-01),
-- scaled by a per-asset volatility step. 099999 only rises (never measurable); 051910 loses 99 % in 2010-03;
-- 005930 is flat in 2015-01 to 2015-06.
INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
SELECT a.id, m.id,
       CASE
           WHEN a.n = 10 THEN ROUND((1 + ((m.i * 7 + a.n) % 9)) / 1000.0, 8)
           WHEN a.n = 9 AND m.month_start = DATE '2010-03-01' THEN -0.99000000
           WHEN a.n = 5 AND m.month_start BETWEEN DATE '2015-01-01' AND DATE '2015-06-01' THEN 0.00000000
           ELSE ROUND(((((m.i * 37 + a.n * 101 + ((m.i * m.i) % 13) * 7 + ((a.n * m.i) % 11) * 3) % 41) - 20) / 200.0
                       + 0.00123457 * (((a.n + m.i) % 3) - 1)) * (1 + (a.n % 4) * 0.5), 8)
       END,
       'ACTUAL'
FROM (SELECT id, row_number() OVER (ORDER BY id) AS n FROM asset WHERE name LIKE '가짜%') a
CROSS JOIN (SELECT id, month_start,
                   (EXTRACT(YEAR FROM month_start)::int - 2007) * 12 + EXTRACT(MONTH FROM month_start)::int - 1 AS i
            FROM month_block) m
WHERE a.n <= 14;

-- USD change: zero in every 17th month, a small wave otherwise.
INSERT INTO fx_month (month_block_id, currency, fx_change)
SELECT id, 'USD',
       CASE WHEN i % 17 = 0 THEN 0.00000000
            ELSE ROUND((((i * 5 + 3) % 13) - 6) / 200.0 + 0.00031579, 8) END
FROM (SELECT id, (EXTRACT(YEAR FROM month_start)::int - 2007) * 12 + EXTRACT(MONTH FROM month_start)::int - 1 AS i
      FROM month_block) m;

-- Attention follows the size of the move, with a streak bonus (a stand-in; the generator only needs the stored index).
INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak, attention_index)
SELECT r.asset_id, r.month_block_id, 12, z.value, 1 + (r.month_block_id % 4),
       ROUND(ABS(z.value) * 10 + (r.month_block_id % 4) * 0.25, 8)
FROM asset_month_return r
CROSS JOIN LATERAL (SELECT ROUND(LEAST(3, GREATEST(-3, r.local_return * 12)), 8) AS value) z;
