-- FAKE DATA — not market data
-- Hand-chosen values for a public repository: every ticker starts with ZZ, every name starts with FAKE, no real price,
-- return or rate is used. Dates are plain calendar months and carry no meaning.
-- One exception: the season report reads its baseline from the two codes fixed in the product (KR 069500, US SPY), so the
-- fixture holds those two codes as FAKE proxies with invented values. They are the only non-ZZ tickers.
-- Shape: 12 assets (KR and US, all six positions), pool base date 2026-01-02, and 24 months in four 6-month blocks
-- (Jan 2008, Jul 2009, Jan 2010, Jul 2011): a 12-turn path needs a January block and a July block that do not touch.
-- Every block repeats the same six monthly returns per asset, so turns 1 and 2 have the same values under any seed.

INSERT INTO asset_group (code, name, kind) VALUES
    ('FAKE_ALPHA', 'FAKE Alpha Sector', 'SECTOR'),
    ('FAKE_BETA',  'FAKE Beta Sector',  'SECTOR'),
    ('FAKE_INDEX', 'FAKE Index Class',  'ASSET_CLASS');

INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name) VALUES
    ('KR', 'ZZ0001', 'FAKE Aegis KR Stock',    'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_ALPHA'), DATE '2000-01-03', 'PRICE', NULL),
    ('KR', 'ZZ0002', 'FAKE Bulwark KR ETF',    'ETF',   'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_INDEX'), DATE '2001-01-03', 'PRICE', NULL),
    ('KR', 'ZZ0003', 'FAKE Citadel KR Stock',  'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_BETA'),  DATE '2000-06-01', 'PRICE', NULL),
    ('KR', 'ZZ0004', 'FAKE Dune KR ETF',       'ETF',   'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_INDEX'), DATE '2002-01-03', 'PRICE', NULL),
    ('KR', 'ZZ0005', 'FAKE Ember KR Stock',    'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_ALPHA'), DATE '2000-09-01', 'PRICE', NULL),
    ('KR', 'ZZ0006', 'FAKE Flint KR Stock',  'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_BETA'),  DATE '2003-01-02', 'PRICE', NULL),
    ('US', 'ZZUS01', 'FAKE Glacier US Stock',  'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'FAKE_ALPHA'), DATE '1999-01-04', 'TOTAL', NULL),
    ('US', 'ZZUS02', 'FAKE Harbor US Stock',   'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'FAKE_BETA'),  DATE '1999-06-01', 'TOTAL', NULL),
    ('US', 'ZZUS03', 'FAKE Isle US ETF',       'ETF',   'USD', (SELECT id FROM asset_group WHERE code = 'FAKE_INDEX'), DATE '2004-01-02', 'TOTAL', 'FAKE Isle Index'),
    ('US', 'ZZUS04', 'FAKE Jetty US Stock',    'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'FAKE_ALPHA'), DATE '1999-09-01', 'TOTAL', NULL),
    ('KR', '069500', 'FAKE Proxy KR ETF',      'ETF',   'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_INDEX'), DATE '2002-01-03', 'PRICE', NULL),
    ('US', 'SPY',    'FAKE Proxy US ETF',      'ETF',   'USD', (SELECT id FROM asset_group WHERE code = 'FAKE_INDEX'), DATE '2003-01-02', 'TOTAL', 'FAKE Proxy Index');

-- Pool on base date 2026-01-02, every asset judged. KR base_fx_rate is 1; US base_fx_rate is 1300 (a round fake rate).
INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank,
                            window_start, window_end, months_used,
                            up_participation, down_participation, volatility, max_drawdown, system_position)
SELECT a.id, DATE '2026-01-02', v.base_price, v.base_fx_rate, NULL,
       DATE '2020-01-01', DATE '2025-12-01', 72,
       v.up, v.down, v.vol, v.mdd, v.pos
FROM (VALUES
    ('KR', 'ZZ0001',  10000.0000,    1.0000, 0.60000000, 0.50000000, 0.03000000, -0.10000000, 'GOALKEEPER'),
    ('KR', 'ZZ0002',   8000.0000,    1.0000, 0.55000000, 0.45000000, 0.02500000, -0.08000000, 'GOALKEEPER'),
    ('KR', 'ZZ0003',  20000.0000,    1.0000, 0.90000000, 0.80000000, 0.04000000, -0.15000000, 'DEFENDER'),
    ('KR', 'ZZ0004',  15000.0000,    1.0000, 0.95000000, 0.85000000, 0.03500000, -0.14000000, 'DEFENDER'),
    ('KR', 'ZZ0005',  50000.0000,    1.0000, 1.00000000, 1.00000000, 0.05000000, -0.20000000, 'MIDFIELDER'),
    ('KR', 'ZZ0006',  40000.0000,    1.0000, 1.30000000, 1.20000000, 0.08000000, -0.30000000, 'FORWARD_TARGET'),
    ('US', 'ZZUS01',    100.0000, 1300.0000, 1.02000000, 0.98000000, 0.05500000, -0.21000000, 'MIDFIELDER'),
    ('US', 'ZZUS02',    200.0000, 1300.0000, 1.40000000, 1.10000000, 0.09000000, -0.35000000, 'FORWARD_BREAKOUT'),
    ('US', 'ZZUS03',     50.0000, 1300.0000, 1.15000000, 0.70000000, 0.04500000, -0.18000000, 'FORWARD_DEFENSIVE'),
    ('US', 'ZZUS04',     80.0000, 1300.0000, 0.85000000, 0.75000000, 0.04200000, -0.16000000, 'DEFENDER'),
    ('KR', '069500',  30000.0000,    1.0000, 1.00000000, 1.00000000, 0.04800000, -0.19000000, 'MIDFIELDER'),
    ('US', 'SPY',       500.0000, 1300.0000, 1.00000000, 1.00000000, 0.04600000, -0.18000000, 'MIDFIELDER')
) AS v(market, ticker, base_price, base_fx_rate, up, down, vol, mdd, pos)
JOIN asset a ON a.market = v.market AND a.ticker = v.ticker;

INSERT INTO month_block (month_start, events_reviewed)
SELECT d::date, TRUE FROM generate_series(DATE '2008-01-01', DATE '2008-06-01', INTERVAL '1 month') AS d
UNION ALL
SELECT d::date, TRUE FROM generate_series(DATE '2009-07-01', DATE '2009-12-01', INTERVAL '1 month') AS d
UNION ALL
SELECT d::date, TRUE FROM generate_series(DATE '2010-01-01', DATE '2010-06-01', INTERVAL '1 month') AS d
UNION ALL
SELECT d::date, TRUE FROM generate_series(DATE '2011-07-01', DATE '2011-12-01', INTERVAL '1 month') AS d;

-- Local returns: six hand-chosen values per asset, indexed by the month's place in its block (January and July are place 1).
INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
SELECT a.id, mb.id, (v.r)[((extract(month FROM mb.month_start)::int - 1) % 6) + 1], 'ACTUAL'
FROM (VALUES
    ('KR', 'ZZ0001', ARRAY[ 0.05,  -0.03,  0.02,  0.01, -0.02,  0.04]::NUMERIC[]),
    ('KR', 'ZZ0002', ARRAY[ 0.01,   0.01,  0.00, -0.01,  0.02,  0.01]::NUMERIC[]),
    ('KR', 'ZZ0003', ARRAY[ 0.02,  -0.01,  0.03, -0.02,  0.01,  0.00]::NUMERIC[]),
    ('KR', 'ZZ0004', ARRAY[ 0.00,   0.02, -0.01,  0.01,  0.01, -0.01]::NUMERIC[]),
    ('KR', 'ZZ0005', ARRAY[-0.04,   0.06,  0.01, -0.03,  0.05,  0.02]::NUMERIC[]),
    ('KR', 'ZZ0006', ARRAY[ 0.08,  -0.07,  0.05,  0.03, -0.04,  0.06]::NUMERIC[]),
    ('US', 'ZZUS01', ARRAY[ 0.02,  -0.01,  0.04,  0.00, -0.03,  0.03]::NUMERIC[]),
    ('US', 'ZZUS02', ARRAY[ 0.07,  -0.05,  0.06, -0.02,  0.04, -0.03]::NUMERIC[]),
    ('US', 'ZZUS03', ARRAY[ 0.01,   0.00,  0.02,  0.01, -0.01,  0.02]::NUMERIC[]),
    ('US', 'ZZUS04', ARRAY[ 0.03,   0.01, -0.02,  0.02,  0.00,  0.01]::NUMERIC[]),
    ('KR', '069500', ARRAY[ 0.02,   0.01, -0.01,  0.02,  0.00,  0.01]::NUMERIC[]),
    ('US', 'SPY',    ARRAY[ 0.01,   0.02, -0.02,  0.01,  0.01,  0.00]::NUMERIC[])
) AS v(market, ticker, r)
JOIN asset a ON a.market = v.market AND a.ticker = v.ticker
CROSS JOIN month_block mb;

-- USD change against KRW, same six-place pattern.
INSERT INTO fx_month (month_block_id, currency, fx_change)
SELECT mb.id, 'USD', (ARRAY[0.01, -0.02, 0.00, 0.015, -0.005, 0.02]::NUMERIC[])[((extract(month FROM mb.month_start)::int - 1) % 6) + 1]
FROM month_block mb;

-- One fake event in the first month of every block, so a review always has events to show.
INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, direction, strength,
                         reaction_change, title, source, hindsight)
SELECT v.event_key, mb.id, 'TRADE', 'FAKE_EVENT', 'START', 'FAKE_OUTCOME', 'DOWN', 2,
       -0.01000000, v.title, 'FAKE source', 'FAKE hindsight'
FROM (VALUES
    ('FAKE_EVENT_01', DATE '2008-01-01', 'FAKE Event One'),
    ('FAKE_EVENT_02', DATE '2009-07-01', 'FAKE Event Two'),
    ('FAKE_EVENT_03', DATE '2010-01-01', 'FAKE Event Three'),
    ('FAKE_EVENT_04', DATE '2011-07-01', 'FAKE Event Four')
) AS v(event_key, month_start, title)
JOIN month_block mb ON mb.month_start = v.month_start;
