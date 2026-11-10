-- 가짜 데이터 — 실제 표본 아님. 매핑·풀 API 시험 전용 합성 고정본이다.
-- 번호(id)를 가정하지 않고 자연 키 서브쿼리로 연결한다. 시험 트랜잭션이 롤백한다.

INSERT INTO asset_group (code, name, kind) VALUES
    ('FAKE_SEMI',  '가짜 반도체', 'SECTOR'),
    ('FAKE_INDEX', '가짜 지수',   'ASSET_CLASS');

INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name) VALUES
    ('KR', '000660', '가짜 반도체', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'),  DATE '1999-01-04', 'PRICE', NULL),
    ('KR', '069500', '가짜 국내 ETF', 'ETF', 'KRW', (SELECT id FROM asset_group WHERE code = 'FAKE_INDEX'), DATE '2002-10-14', 'PRICE', NULL),
    ('US', 'SPY',    '가짜 미국 ETF', 'ETF', 'USD', (SELECT id FROM asset_group WHERE code = 'FAKE_INDEX'), DATE '2003-05-01', 'TOTAL', '가짜 추종 지수'),
    ('US', 'BRK.B',  '가짜 미국 주식', 'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'), DATE '1996-05-09', 'TOTAL', NULL);

-- 기준일 2026-01-02 풀 4건(판정 완료) + 다른 기준일(2025-01-02) 1건
INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank,
                            window_start, window_end, months_used,
                            up_participation, down_participation, volatility, max_drawdown, system_position)
SELECT a.id, DATE '2026-01-02', v.base_price, v.base_fx_rate, v.rank_no,
       DATE '2020-01-01', DATE '2025-12-01', 72,
       v.up, v.down, v.vol, v.mdd, v.pos
FROM (VALUES
    ('KR', '000660', 150000.0000, 1.0000, 3,    1.52000000, 1.31000000, 0.12000000, -0.48000000, 'FORWARD_BREAKOUT'),
    ('KR', '069500',  35000.0000, 1.0000, NULL, 1.00000000, 1.00000000, 0.05000000, -0.20000000, 'MIDFIELDER'),
    ('US', 'SPY',      480.0000,  1350.0000, NULL, 0.98000000, 0.95000000, 0.04500000, -0.19000000, 'DEFENDER'),
    ('US', 'BRK.B',    400.0000,  1350.0000, NULL, 0.70000000, 0.60000000, 0.06000000, -0.25000000, 'GOALKEEPER')
) AS v(market, ticker, base_price, base_fx_rate, rank_no, up, down, vol, mdd, pos)
JOIN asset a ON a.market = v.market AND a.ticker = v.ticker;

INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate,
                            window_start, window_end, months_used,
                            up_participation, down_participation, volatility, max_drawdown, system_position)
SELECT a.id, DATE '2025-01-02', 100000.0000, 1.0000,
       DATE '2019-01-01', DATE '2024-12-01', 72,
       1.10000000, 1.10000000, 0.11000000, -0.40000000, 'FORWARD_TARGET'
FROM asset a WHERE a.market = 'KR' AND a.ticker = '000660';

-- 월 블록 2개
INSERT INTO month_block (month_start, rate_dir, growth_dir, fx_dir, sentiment_dir,
                         rate_strength, growth_strength, fx_strength, sentiment_strength,
                         prob_p1, prob_p2, prob_p3, prob_p4, event_summary, events_reviewed) VALUES
    (DATE '2001-02-01', 'UP', 'DOWN', 'NEUTRAL', 'UP', 0.50000000, 1.25000000, 0.00000000, 0.75000000,
     0.2500, 0.2500, 0.2500, 0.2500, '가짜 사건 요약', TRUE),
    (DATE '2001-03-01', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, FALSE);

INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
SELECT a.id, b.id, -0.05000000, 'ACTUAL'
FROM asset a, month_block b WHERE a.market = 'US' AND a.ticker = 'SPY' AND b.month_start = DATE '2001-02-01';
INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
SELECT a.id, b.id, 0.03000000, 'PROXY_INDEX'
FROM asset a, month_block b WHERE a.market = 'US' AND a.ticker = 'SPY' AND b.month_start = DATE '2001-03-01';

INSERT INTO fx_month (month_block_id, currency, fx_change)
SELECT b.id, 'USD', 0.01500000 FROM month_block b WHERE b.month_start = DATE '2001-02-01';

INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, direction, strength,
                         reaction_change, title, source, hindsight)
SELECT 'FAKE_EVENT_01', b.id, 'TRADE', 'TARIFF', 'START', 'FAKE_OUTCOME', 'DOWN', 2,
       -0.02000000, '가짜 사건 제목', '가짜 출처', '가짜 사후 설명'
FROM month_block b WHERE b.month_start = DATE '2001-02-01';

INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak, attention_index)
SELECT a.id, b.id, 6, 1.25000000, 2, 0.65000000
FROM asset a, month_block b WHERE a.market = 'US' AND a.ticker = 'SPY' AND b.month_start = DATE '2001-02-01';
