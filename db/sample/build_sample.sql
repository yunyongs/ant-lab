-- =====================================================================
-- my Fund Manager Phase 0 — 표본 변환 스크립트 (P0-08): 원본 일별 파일 → 파생 CSV
-- 근거: docs/PLAN.md §4.2·§4.6·§7, docs/erd.md 5.3(asset_position, asset_month_return, fx_month), D15·D16·D20
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <임시 DB> -f db/sample/build_sample.sql
--       -v parts=fx 를 붙이면 월 블록과 환율 변화 CSV 2개만 만든다 (가격 원본이 없을 때).
-- 하는 일: 원본(data/raw/p0-08/, 추적 안 함)을 TEMP 테이블에 읽어 월말 값을 고르고 수익률을 계산해
--          db/sample/ 의 CSV 4개를 쓴다. 영구 테이블에는 아무것도 쓰지 않는다 (파일 끝 ROLLBACK).
--          모든 검사를 통과한 뒤에만 CSV를 쓴다 (중간 실패 시 기존 CSV는 그대로).
--          평소 실행 순서(db/README.md)에 없다. 원본이 있는 기계에서 표본을 다시 만들 때만 돈다.
-- 입력 계약 (tools/fetch 의 `norm` 이 만든 정규화 파일 3개 + FRED. 모양은 db/sample/README.md와 tools/fetch/README.md):
--   db/sample/asset.csv                          손 편집 종목 목록 (종목·상장일·유형)
--   data/raw/p0-08/fred_dexkous.csv              머리글 observation_date,DEXKOUS · 결측은 빈 칸 또는 .
--   data/raw/p0-08/norm/daily.csv                머리글 market,ticker,trade_date,close,close_price,close_raw,source
--                                                close = 수익률 계산 기준 종가(국내 수정 종가 / 미국 KIS 구간은 MODP=1 종가 / 미국 yfinance 구간은 Adj Close),
--                                                close_price = 배당 미조정(분할만 조정) 종가 — source=YF 행에서만 채움(대체 수익률의 기초 SPY·GLD 용),
--                                                close_raw = 그날 실제 종가(기준일 행만 필수, 나머지 빈 칸 허용),
--                                                source ∈ KIS/YF. (합성 시험 입력은 SYN — -v allow_synthetic=1 일 때만 받는다)
--                                                한 (종목 × 구간 ①/②)은 출처가 하나여야 한다 (섞이면 오류).
--   (data/raw/p0-08/norm/dividends.csv 는 도구가 대조용으로만 쓴다. 이 스크립트는 읽지 않는다 — KIS 가격에 배당을 더하는 경로가 없다.)
--   data/raw/p0-08/norm/shares.csv               머리글 market,ticker,as_of,shares,source · 상장주식수 (없으면 머리글만 → 순위 빈 칸)
--   공통: UTF-8, 쉼표, 날짜 YYYY-MM-DD, 소수점 ., 천 단위 구분 없음. 한 파일 안 (market, ticker, 날짜) 중복 금지.
-- 출력: db/sample/month_block.csv · fx_month.csv · asset_position.csv · asset_month_return.csv (열 계약: db/sample/README.md 의 CSV 열 계약)
-- 규칙 (docs/PLAN §7, db/sample/README.md 의 변환 규칙):
--   월말 가격 = 그 달 마지막 거래일 종가(분할 조정). 국내 수익률 = 월말/직전 달 월말 − 1.
--   수익률(ACTUAL) = 월말 close / 직전 달 월말 close − 1. 미국(TOTAL)은 close 자체가 배당을 담은 시리즈라 그대로 쓴다
--   (KIS MODP=1 종가는 배당락일에 배당만큼 조정돼 있다 — P1 실측 — 이므로 배당을 더하면 두 번 센다. 구간 ①은 yfinance Adj Close. README 변환 규칙의 미국 수익률 행).
--   KR(PRICE)도 같은 식. 대체 수익률(PROXY_INDEX) = (1+기초 가격 수익률)(1+환율 변화) − 1 − 총보수/12, 기초 가격 수익률은 close_price 의 비.
--   상장월과 그 이전 = PROXY_INDEX (ETF만), 상장 다음 달부터 ACTUAL. 반올림은 CSV에 쓰기 직전 한 번(ROUND 8자리).
--   값을 만들지 않는다: 가격·거래일이 없는 달은 오류로 멈춘다 (0 대입·앞값 채움 금지).
-- 새 문법: \copy … FROM '파일' WITH (HEADER MATCH), DISTINCT ON, \if/\gset, \copy (SELECT …) TO, 윈도 함수 rank().
-- 명령줄(psql)이 기준이다. 셸을 부르지 않는다 (FROM PROGRAM 없음).
-- =====================================================================

\if :{?parts}
\else
  \set parts all
\endif
\if :{?allow_synthetic}
\else
  \set allow_synthetic 0
\endif
SELECT (:'parts' = 'fx') AS only_fx \gset

BEGIN;

-- 설정값 ------------------------------------------------------------------
CREATE TEMP TABLE p_opt (allow_synth int);
INSERT INTO p_opt VALUES (:allow_synthetic);

CREATE TEMP TABLE p_segment (seg_start date, seg_end date);
INSERT INTO p_segment VALUES ('2007-01-01', '2012-04-01'), ('2023-01-01', '2025-12-01');

CREATE TEMP TABLE p_month AS
SELECT d::date AS month_start
FROM p_segment s, generate_series(s.seg_start::timestamp, s.seg_end::timestamp, interval '1 month') AS d;

-- 기준일 (PLAN §4.2: 1월 첫 거래일. 휴장일이 아닌지는 가격 파일에 그날 행이 있는지로 확인된다)
CREATE TEMP TABLE p_base (base_date date);
INSERT INTO p_base VALUES ('2026-01-02');

-- 대체 수익률 설정: 대체 지수 ETF → 기초 ETF(일별 원본의 US 티커), 연 총보수 (비율)
-- ⚠ 총보수는 운용사 공시로 확인한 값으로 바꾼다 (db/sample/README.md "확인 필요")
CREATE TEMP TABLE p_proxy (market text, ticker text, base_ticker text, annual_fee numeric);
INSERT INTO p_proxy VALUES ('KR', '360750', 'SPY', 0.0007), ('KR', '411060', 'GLD', 0.0019);

-- 환율 ---------------------------------------------------------------------
CREATE TEMP TABLE stg_fx (observation_date text, "DEXKOUS" text);
\copy stg_fx FROM 'data/raw/p0-08/fred_dexkous.csv' WITH (FORMAT csv, HEADER MATCH)

DO $$
DECLARE
    v_bad bigint;
BEGIN
    SELECT count(*) INTO v_bad FROM stg_fx
    WHERE observation_date IS NULL OR observation_date !~ '^\d{4}-\d{2}-\d{2}$'
       OR ("DEXKOUS" IS NOT NULL AND "DEXKOUS" <> '.' AND "DEXKOUS" !~ '^\d+(\.\d+)?$');
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 환율 원본에 형식이 맞지 않는 행 % 건 (날짜 빈 칸 포함)', v_bad;
    END IF;
    -- 같은 날짜가 두 번이면 월말 값을 고를 수 없다 (값이 같아도 거부한다)
    SELECT count(*) INTO v_bad FROM (SELECT observation_date FROM stg_fx GROUP BY 1 HAVING count(*) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 환율 원본에 같은 날짜가 두 번 나오는 행 % 건', v_bad;
    END IF;
    SELECT count(*) INTO v_bad FROM stg_fx WHERE "DEXKOUS" IS NOT NULL AND "DEXKOUS" <> '.' AND "DEXKOUS"::numeric <= 0;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 환율 관측값이 0 이하인 행 % 건', v_bad;
    END IF;
END $$;

-- 관측값만 (결측 건너뜀) → 월말 관측값
CREATE TEMP TABLE fx_obs AS
SELECT observation_date::date AS d, "DEXKOUS"::numeric AS x
FROM stg_fx
WHERE "DEXKOUS" IS NOT NULL AND "DEXKOUS" <> '.';

CREATE TEMP TABLE fx_me AS
SELECT DISTINCT ON (date_trunc('month', d)) date_trunc('month', d)::date AS m, d, x
FROM fx_obs
ORDER BY date_trunc('month', d), d DESC;

-- 환율 변화 (반올림 전). 직전 달 월말이 없으면 행이 빠져 아래 확인이 멈춘다
CREATE TEMP TABLE fx_calc AS
SELECT p.month_start, c.x / pv.x - 1 AS fx_change
FROM p_month p
JOIN fx_me c  ON c.m  = p.month_start
JOIN fx_me pv ON pv.m = (p.month_start - interval '1 month')::date;

DO $$
DECLARE
    v_expect bigint;
    v_got    bigint;
BEGIN
    SELECT count(*) INTO v_expect FROM p_month;
    SELECT count(*) INTO v_got FROM fx_calc;
    IF v_got <> v_expect THEN
        RAISE EXCEPTION 'P0-08 변환: 환율 변화가 % 개월뿐이다 (기대 %). 월말 관측값이 없는 달이 있다', v_got, v_expect;
    END IF;
END $$;

\if :only_fx
  -- 여기까지의 검사를 모두 통과했을 때만 쓴다
  \copy (SELECT to_char(month_start, 'YYYY-MM-DD') AS month_start FROM p_month ORDER BY month_start) TO 'db/sample/month_block.csv' WITH (FORMAT csv, HEADER)
  \copy (SELECT to_char(month_start, 'YYYY-MM-DD') AS month_start, 'USD' AS currency, ROUND(fx_change, 8)::text AS fx_change FROM fx_calc ORDER BY month_start) TO 'db/sample/fx_month.csv' WITH (FORMAT csv, HEADER)
  \echo 'P0-08 변환: parts=fx — 월 블록과 환율 변화만 썼다'
  ROLLBACK;
  \quit
\endif

-- 종목 목록 -----------------------------------------------------------------
CREATE TEMP TABLE stg_asset (market text, ticker text, name text, asset_type text, currency text,
                             group_code text, listed_on text, return_basis text, proxy_index_name text);
\copy stg_asset FROM 'db/sample/asset.csv' WITH (FORMAT csv, HEADER MATCH)

CREATE TEMP TABLE a_asset AS
SELECT market, ticker, asset_type, listed_on::date AS listed_on FROM stg_asset;

-- 일별 원본 ------------------------------------------------------------------
-- tools/fetch 가 합친 정규화 파일. 머리글은 HEADER MATCH 가 열 이름·순서로 확인한다.
CREATE TEMP TABLE stg_daily (market text, ticker text, trade_date text, close text, close_price text, close_raw text, source text);
\copy stg_daily FROM 'data/raw/p0-08/norm/daily.csv' WITH (FORMAT csv, HEADER MATCH)

DO $$
DECLARE
    v_bad bigint;
BEGIN
    SELECT count(*) INTO v_bad FROM stg_daily
    WHERE num_nulls(market, ticker, trade_date, close, source) > 0
       OR market NOT IN ('KR', 'US')
       OR ticker !~ '^[A-Za-z0-9]+$'
       OR trade_date !~ '^\d{4}-\d{2}-\d{2}$'
       OR close IS NULL OR close !~ '^\d+(\.\d+)?$' OR close::numeric <= 0
       OR (close_price IS NOT NULL AND (close_price !~ '^\d+(\.\d+)?$' OR close_price::numeric <= 0))
       OR (close_raw IS NOT NULL AND (close_raw !~ '^\d+(\.\d+)?$' OR close_raw::numeric <= 0));
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 일별 원본에 형식이 맞지 않거나 빈 칸이거나 0 이하인 행 % 건', v_bad;
    END IF;
    SELECT count(*) INTO v_bad FROM stg_daily WHERE source IS NULL OR source NOT IN ('KIS', 'YF', 'SYN') OR (source = 'SYN' AND (SELECT allow_synth FROM p_opt) <> 1);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 출처(source)가 빈 칸이거나 KIS/YF 가 아닌 행 % 건 (합성 시험 입력은 -v allow_synthetic=1)', v_bad;
    END IF;
    SELECT count(*) INTO v_bad FROM stg_daily WHERE source = 'SYN';
    IF v_bad > 0 THEN
        RAISE NOTICE 'P0-08 변환: ⚠ 합성(가짜) 입력 % 행 — 만든 CSV는 시험용이며 실제 DB에 적재하지 않는다', v_bad;
    END IF;
    SELECT count(*) INTO v_bad FROM (
        SELECT market, ticker, trade_date FROM stg_daily GROUP BY 1, 2, 3 HAVING count(*) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 일별 원본에 같은 날짜가 두 번 나오는 (시장, 종목, 날짜) % 건', v_bad;
    END IF;
    -- 단일 출처 규칙: 한 (종목 × 구간 ①/②)은 출처가 하나 (구간 ① = 2015 이전, ② = 2015 이후 — 표본 구간 사이 간격이 크다)
    SELECT count(*) INTO v_bad FROM (
        SELECT market, ticker, (trade_date::date >= '2015-01-01') AS seg2 FROM stg_daily
        GROUP BY 1, 2, 3 HAVING count(DISTINCT source) > 1) d;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 한 구간 안에서 출처가 섞인 (종목, 구간) % 건 (단일 출처 규칙)', v_bad;
    END IF;
    -- 배당 미조정 종가(close_price)는 yfinance 구간에만 있다: YF 행은 필수, KIS 행은 비어 있어야 한다
    SELECT count(*) INTO v_bad FROM stg_daily
    WHERE (source = 'YF' AND close_price IS NULL) OR (source = 'KIS' AND close_price IS NOT NULL);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: close_price 규칙 위반 % 건 (YF 행은 필수, KIS 행은 빈 칸)', v_bad;
    END IF;
END $$;

CREATE TEMP TABLE raw_daily AS
SELECT market, upper(ticker) AS ticker, trade_date::date AS trade_date,
       close::numeric AS close, close_price::numeric AS close_price, close_raw::numeric AS close_raw
FROM stg_daily;

-- 상장주식수 (순위 계산용. 기준일 이전 가장 가까운 값)
CREATE TEMP TABLE stg_shares (market text, ticker text, as_of text, shares text, source text);
\copy stg_shares FROM 'data/raw/p0-08/norm/shares.csv' WITH (FORMAT csv, HEADER MATCH)

DO $$
DECLARE
    v_bad bigint;
BEGIN
    SELECT count(*) INTO v_bad FROM stg_shares
    WHERE num_nulls(market, ticker, as_of, shares, source) > 0
       OR market NOT IN ('KR', 'US') OR as_of !~ '^\d{4}-\d{2}-\d{2}$' OR shares !~ '^\d+(\.\d+)?$' OR shares::numeric <= 0
       OR source NOT IN ('KIS', 'YF', 'SYN') OR (source = 'SYN' AND (SELECT allow_synth FROM p_opt) <> 1);
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 상장주식수 원본에 형식이 맞지 않거나 빈 칸이거나 0 이하인 행 % 건', v_bad;
    END IF;
END $$;

-- 쓰이지 않는 종목(오타 방지): 종목 목록에도 대체 지수 기초에도 순위 후보에도 없는 티커
DO $$
DECLARE
    v_list text;
BEGIN
    SELECT string_agg(DISTINCT r.market || ':' || r.ticker, ', ') INTO v_list
    FROM raw_daily r
    WHERE NOT EXISTS (SELECT 1 FROM a_asset a WHERE a.market = r.market AND a.ticker = r.ticker)
      AND NOT EXISTS (SELECT 1 FROM p_proxy x WHERE r.market = 'US' AND x.base_ticker = r.ticker)
      AND NOT EXISTS (SELECT 1 FROM stg_shares sh WHERE sh.market = r.market AND upper(sh.ticker) = r.ticker);
    IF v_list IS NOT NULL THEN
        RAISE EXCEPTION 'P0-08 변환: 종목 목록에 없는 종목이 일별 원본에 있다: %', v_list;
    END IF;
END $$;

-- 월말 가격 (그 시장의 그 달 마지막 거래일)
CREATE TEMP TABLE px_me AS
SELECT DISTINCT ON (market, ticker, date_trunc('month', trade_date))
       market, ticker, date_trunc('month', trade_date)::date AS m, trade_date, close, close_price
FROM raw_daily
ORDER BY market, ticker, date_trunc('month', trade_date), trade_date DESC;

-- 종목 × 월: 종류와 수익률 (반올림 전)
CREATE TEMP TABLE ret_calc AS
SELECT a.market, a.ticker, p.month_start,
       k.return_kind,
       CASE k.return_kind
           WHEN 'ACTUAL' THEN c.close / pv.close - 1
           ELSE (b.close_price / bp.close_price) * (1 + f.fx_change) - 1 - x.annual_fee / 12
       END AS r
FROM a_asset a
CROSS JOIN p_month p
CROSS JOIN LATERAL (
    SELECT CASE WHEN a.asset_type = 'ETF' AND p.month_start <= date_trunc('month', a.listed_on)::date
                THEN 'PROXY_INDEX' ELSE 'ACTUAL' END AS return_kind) k
LEFT JOIN px_me c  ON c.market  = a.market AND c.ticker  = a.ticker AND c.m  = p.month_start
LEFT JOIN px_me pv ON pv.market = a.market AND pv.ticker = a.ticker AND pv.m = (p.month_start - interval '1 month')::date
LEFT JOIN p_proxy x ON x.market = a.market AND x.ticker = a.ticker
LEFT JOIN px_me b  ON b.market  = 'US' AND b.ticker  = x.base_ticker AND b.m  = p.month_start
LEFT JOIN px_me bp ON bp.market = 'US' AND bp.ticker = x.base_ticker AND bp.m = (p.month_start - interval '1 month')::date
LEFT JOIN fx_calc f ON f.month_start = p.month_start;

DO $$
DECLARE
    v_list text;
    v_bad  bigint;
BEGIN
    SELECT string_agg(market || ':' || ticker, ', ' ORDER BY market, ticker), count(*)
      INTO v_list, v_bad
    FROM (SELECT DISTINCT market, ticker FROM ret_calc WHERE r IS NULL) t;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 월말 가격이 없어 수익률을 만들 수 없는 종목 % 개: % (값을 만들지 않는다. 원본을 확인한다)', v_bad, v_list;
    END IF;
    SELECT count(*) INTO v_bad FROM ret_calc WHERE r < -1;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'P0-08 변환: 수익률이 −100%% 아래인 행 % 건', v_bad;
    END IF;
END $$;

-- 시가총액 순위 = 상장주식수(기준일 이전 가장 가까운 값) × 기준일 실제 종가, 시장 안 순위 (shares.csv 가 비면 순위 빈 칸)
CREATE TEMP TABLE mc_rank AS
SELECT market, ticker, rank() OVER (PARTITION BY market ORDER BY market_cap DESC) AS market_cap_rank
FROM (
    SELECT sh.market, upper(sh.ticker) AS ticker, sh.shares::numeric * d.close_raw AS market_cap
    FROM (SELECT DISTINCT ON (market, upper(ticker)) market, ticker, shares
          FROM stg_shares WHERE as_of::date <= (SELECT base_date FROM p_base)
          ORDER BY market, upper(ticker), as_of DESC) sh
    JOIN raw_daily d ON d.market = sh.market AND d.ticker = upper(sh.ticker)
                    AND d.trade_date = (SELECT base_date FROM p_base)
    WHERE d.close_raw IS NOT NULL
) t;

-- 기준일 가격·환율·순위 ---------------------------------------------------------
CREATE TEMP TABLE base_fx AS
SELECT DISTINCT ON (1) b.base_date, o.d AS obs_date, o.x
FROM p_base b JOIN fx_obs o ON o.d <= b.base_date
ORDER BY 1, o.d DESC;

CREATE TEMP TABLE pos_calc AS
SELECT a.market, a.ticker, b.base_date, d.close_raw AS base_price,
       CASE a.market WHEN 'KR' THEN 1 ELSE f.x END AS base_fx_rate,
       CASE WHEN a.asset_type = 'STOCK' THEN r.market_cap_rank END AS market_cap_rank
FROM a_asset a
CROSS JOIN p_base b
LEFT JOIN raw_daily d ON d.market = a.market AND d.ticker = a.ticker AND d.trade_date = b.base_date
LEFT JOIN base_fx f ON f.base_date = b.base_date
LEFT JOIN mc_rank r ON r.market = a.market AND r.ticker = a.ticker;

DO $$
DECLARE
    v_list text;
    v_obs  date;
    v_base date;
BEGIN
    SELECT string_agg(market || ':' || ticker, ', ' ORDER BY market, ticker) INTO v_list
    FROM pos_calc WHERE base_price IS NULL OR base_fx_rate IS NULL;
    IF v_list IS NOT NULL THEN
        RAISE EXCEPTION 'P0-08 변환: 기준일 종가 또는 환율이 없는 종목: % (기준일에 거래일이 아닌 시장이 있는지 확인한다)', v_list;
    END IF;
    SELECT obs_date, base_date INTO v_obs, v_base FROM base_fx;
    IF v_obs <> v_base THEN
        RAISE NOTICE 'P0-08 변환: 기준일 % 의 환율 관측값이 없어 % 값을 썼다 (README에 적는다)', v_base, v_obs;
    END IF;
    SELECT string_agg(ticker, ', ' ORDER BY ticker) INTO v_list
    FROM a_asset a WHERE a.asset_type = 'STOCK'
      AND NOT EXISTS (SELECT 1 FROM mc_rank r WHERE r.market = a.market AND r.ticker = a.ticker);
    IF v_list IS NOT NULL THEN
        RAISE NOTICE 'P0-08 변환: 시가총액 순위를 정하지 못해 빈 칸인 주식: % (erd §9 P0-08: 상장주식수 미수집)', v_list;
    END IF;
END $$;

-- 모든 검사를 통과했다: 이제 CSV 4개를 쓴다 (앞에서 멈추면 기존 CSV 는 그대로다)
\copy (SELECT to_char(month_start, 'YYYY-MM-DD') AS month_start FROM p_month ORDER BY month_start) TO 'db/sample/month_block.csv' WITH (FORMAT csv, HEADER)
\copy (SELECT to_char(month_start, 'YYYY-MM-DD') AS month_start, 'USD' AS currency, ROUND(fx_change, 8)::text AS fx_change FROM fx_calc ORDER BY month_start) TO 'db/sample/fx_month.csv' WITH (FORMAT csv, HEADER)
\copy (SELECT market, ticker, to_char(month_start, 'YYYY-MM-DD') AS month_start, ROUND(r, 8)::text AS local_return, return_kind FROM ret_calc ORDER BY market COLLATE "C", ticker COLLATE "C", month_start) TO 'db/sample/asset_month_return.csv' WITH (FORMAT csv, HEADER)
\copy (SELECT market, ticker, to_char(base_date, 'YYYY-MM-DD') AS base_date, ROUND(base_price, 4)::text AS base_price, ROUND(base_fx_rate, 4)::text AS base_fx_rate, market_cap_rank::text AS market_cap_rank FROM pos_calc ORDER BY market COLLATE "C", ticker COLLATE "C") TO 'db/sample/asset_position.csv' WITH (FORMAT csv, HEADER)

ROLLBACK;
