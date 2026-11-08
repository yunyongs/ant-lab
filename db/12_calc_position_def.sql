-- =====================================================================
-- my Fund Manager Phase 0 — 시스템 판정 포지션 정의 (P0-10): 참여율·변동성·최대 낙폭·포지션의 임시 뷰와 판정 함수
-- 근거: docs/erd.md 5.3(asset_position·month_block·asset_month_return)·D14·D15·D35, docs/PLAN.md §4.10(포지션 표·판정 순서)·§4.5(사후 정보 배제)·§5.2(반올림 한 번)·§6.1(시장 지수)
-- 단독 실행 대상이 아니다. db/12_calc_position.sql(쓰기)과 db/12_calc_position_check.sql(검증)이 \ir 로 읽는다.
--       쓰기와 검증이 같은 정의를 쓰므로 검증이 복사본이 아니라 실제 정의를 시험한다. 시즌 실측 포지션(P0-20)도 이 파일을 \ir 로 재사용한다.
-- 만드는 것: 세션 임시 뷰 9개와 세션 함수 2개. 트랜잭션 문 없음, 테이블 쓰기 없음. 읽는 표는 asset·asset_position·month_block·asset_month_return·fx_month 5개뿐이다
--       (시즌·계좌 표를 읽지 않는다). 시가총액 순위(market_cap_rank)는 읽지 않는다 (erd D15).
-- 뷰 (의존 순서):
--   pos_k       상수 한 줄 (창 36개월, 창 끝 지연 1개월, 최소 달 수 24·5·5, 밴드 0.01, 상위 비율 0.20, 시장 대용 069500·SPY 50/50)
--   pos_ret     (asset_id, market, ticker, month_block_id, month_start, r_krw)  원화 월 수익률. USD 종목에 환율 행이 없으면 오류로 멈춘다
--   pos_window  (base_date, asset_position_id, asset_id, window_start, window_end)  기준일 행마다 판정 창
--   pos_mkt     (base_date, month_block_id, month_start, r_mkt)  기준일 창 안 월 블록마다 시장 수익률. 대용 종목 행이나 환율이 없으면 오류로 멈춘다
--   pos_series  (sid, kind, base_date, asset_position_id, asset_id, month_start, r_krw, r_mkt)  종목 시리즈('A:<id>')와 시장 시리즈('M:<기준일>')를 한 모양으로 쌓은 것
--   pos_dd      (sid, max_drawdown)  시리즈마다 최대 낙폭 (재귀 CTE 부 지수, 정확한 numeric, 마지막에 한 번 반올림)
--   pos_metric  (… months_used, n_up, n_down, up_raw·down_raw·vol_raw(반올림 전), up_participation·down_participation·volatility·max_drawdown(반올림), mkt_max_drawdown, reason)
--   pos_rank    (asset_position_id, n_pool, k, vol_rank_desc, vol_rank_asc, vol_top, vol_bottom)  같은 기준일 판정 가능 행끼리의 변동성 순위
--   pos_judge   (… judgeable, reason, 8컬럼)  최종. system_position 은 pos_classify 가 만든다
-- 함수:
--   pg_temp.pos_fail(text)       오류를 일으킨다 (입력이 빠졌을 때 0·NULL 로 덮지 않고 멈추려고). VOLATILE 이라 계획 단계에서 미리 평가되지 않는다
--   pg_temp.pos_classify(…)      판정 규칙 한 곳. 문턱 숫자는 이 함수 본문에만 있다
-- 정의 (docs/erd.md D35):
--   M2 판정 창: window_end = 기준일이 속한 달 − 1개월, window_start = window_end − 35개월 (둘 다 1일, 달력 경계). 데이터가 없는 달(끊김)은 세지 않는다.
--   M3 months_used = 창 안에서 종목 수익률과 시장 수익률이 둘 다 있는 달 수.
--   M4 원화 월 수익률: KR = local_return, US = (1 + local_return)(1 + fx_change) − 1. 정확한 numeric, 반올림 없음. PROXY_INDEX 행도 그대로 쓴다.
--   M5 시장 수익률: r시장 = 0.5 × r(KR 069500) + 0.5 × r원화(US SPY), 매달 50/50 (PLAN §6.1 의 Phase 0 대용).
--   M6 오른 달 r시장 ≥ 0.01, 빠진 달 r시장 ≤ −0.01 (경계 포함), 그 사이는 중립 (참여율에서만 빠지고 변동성·낙폭에는 들어간다).
--   M7 참여율 = Σ오른(빠진) 달 r종목 ÷ Σ오른(빠진) 달 r시장. 같은 달 집합이라 "평균의 비" = "합의 비". 분자를 소수 40자리로 올려 한 번 나누고 ROUND(…, 8) 한 번.
--   M9 변동성 = stddev_samp(numeric) (n − 1, 월 단위, 연율화 안 함), ROUND(…, 8) 한 번.
--   M10 최대 낙폭: W0 = 1, Wt = Wt-1 × (1 + rt), 최고점 = max(W0 … Wt), 낙폭 = min(Wt ÷ 최고점 − 1), ROUND(…, 8) 한 번. 시장의 낙폭도 같은 방법(창 안 월 블록 전부).
--   M11 최소 달 수: months_used ≥ 24 그리고 오른 달 ≥ 5 그리고 빠진 달 ≥ 5. 못 미치면 판정 불능 (reason MONTHS·N_UP·N_DOWN·ZERO_DEN, 8컬럼 NULL).
--   M13 풀 상대 순위: 같은 기준일의 판정 가능 행 N개에서 k = CEIL(0.2 × N). 상위/하위 = 변동성 순위(RANK, 동률 포함) ≤ k. 저장하지 않고 조회한다.
--   M14 문턱 비교는 저장되는 8자리 반올림 값으로 한다 (저장된 행만으로 판정을 다시 낼 수 있다).
--   판정 순서 (PLAN §4.10, 소유자 O1–O4): 골키퍼 → 수비수 → 돌파형 → 수비적 → 타겟형 → 미드필더(나머지).
-- 타입: 모두 numeric·integer·bigint·text·date·boolean. float4/float8 을 만드는 함수·캐스트(date_part, percentile_cont, ln, exp, power)를 쓰지 않는다.
--       stddev_samp(numeric) 은 numeric 을 돌려준다 (내부 scale 32). 날짜 산술은 date_trunc(timestamp)·make_interval 만 쓴다.
-- 새 문법: 재귀 CTE(부 지수), 윈도 함수 RANK()·MAX() OVER (… ROWS UNBOUNDED PRECEDING), 집계 FILTER, 세션 함수 CREATE FUNCTION pg_temp.…, CASE 로 조건부 오류.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 의존의 반대 순서로 지운다 (같은 세션에서 다시 읽어도 되도록. 임시 스키마가 아직 없으면 건너뛴다)
DO $$
DECLARE
    v text;
BEGIN
    FOREACH v IN ARRAY ARRAY['pos_judge', 'pos_rank', 'pos_metric', 'pos_dd', 'pos_series', 'pos_mkt', 'pos_window', 'pos_ret', 'pos_k'] LOOP
        IF to_regclass('pg_temp.' || v) IS NOT NULL THEN EXECUTE 'DROP VIEW pg_temp.' || v; END IF;
    END LOOP;
    IF to_regprocedure('pg_temp.pos_classify(numeric, numeric, numeric, numeric, boolean, boolean)') IS NOT NULL THEN
        DROP FUNCTION pg_temp.pos_classify(numeric, numeric, numeric, numeric, boolean, boolean);
    END IF;
    IF to_regprocedure('pg_temp.pos_fail(text)') IS NOT NULL THEN DROP FUNCTION pg_temp.pos_fail(text); END IF;
END $$;

-- 입력이 빠졌을 때 멈추는 함수. 호출하는 CASE 가지는 빠진 입력이 있는 행에서만 평가된다.
CREATE FUNCTION pg_temp.pos_fail(p_msg text) RETURNS numeric
LANGUAGE plpgsql VOLATILE AS $$
BEGIN
    RAISE EXCEPTION 'P0-10 정의: %', p_msg;
END $$;

-- 판정 함수: 규칙과 순서의 유일한 정의 (문턱은 이 본문에만 있다. 바꾸면 erd D35·PLAN §4.10 도 바꾼다).
--   p_up·p_down·p_mdd·p_mkt_mdd 는 저장되는 8자리 값(M14), p_vol_top·p_vol_bottom 은 같은 기준일 풀의 변동성 상위·하위 20%.
--   돌파형의 "낙폭이 깊다" = 최대 낙폭 ≤ 1.5 × 시장 최대 낙폭 (둘 다 0 이하이므로 더 깊거나 같다).
CREATE FUNCTION pg_temp.pos_classify(p_up numeric, p_down numeric, p_mdd numeric, p_mkt_mdd numeric,
                                     p_vol_top boolean, p_vol_bottom boolean) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN p_vol_bottom AND p_down <= 0.2                                  THEN 'GOALKEEPER'
        WHEN p_down < 0.6                                                    THEN 'DEFENDER'
        WHEN p_vol_top AND p_up > 1.3 AND p_mdd <= 1.5 * p_mkt_mdd           THEN 'FORWARD_BREAKOUT'
        WHEN p_up >= 1.0 AND p_down < 0.8                                    THEN 'FORWARD_DEFENSIVE'
        WHEN p_up > 1.1 AND p_down <= 1.2 AND NOT p_vol_top                  THEN 'FORWARD_TARGET'
        ELSE 'MIDFIELDER'
    END
$$;

-- 상수 (바꾸면 erd D35 도 바꾸고 전 구간을 다시 계산한다). 검증 파일은 독립 확인용으로 따로 적었다
CREATE TEMP VIEW pos_k AS
SELECT 36                              AS win_months,     -- 판정 창 달 수 (M2)
       1                               AS end_lag,        -- 창 끝 = 기준일 달 − 1개월 (M2, 사후 정보 배제)
       24                              AS min_months,     -- 최소 사용 달 수 (M11)
       5                               AS min_up,         -- 최소 오른 달 (M11)
       5                               AS min_down,       -- 최소 빠진 달 (M11)
       0.01::numeric                   AS band,           -- 오른·빠진 달의 시장 수익률 경계 (M6, 경계 포함)
       0.20::numeric                   AS top_ratio,      -- 상위·하위 비율 (M13)
       'KR'::text                      AS kr_market,      -- 시장 대용 (M5): 국내
       '069500'::text                  AS kr_ticker,
       'US'::text                      AS us_market,      -- 시장 대용 (M5): 미국
       'SPY'::text                     AS us_ticker,
       0.5::numeric                    AS w_kr,           -- 매달 50/50
       0.5::numeric                    AS w_us,
       0.0000000000000000000000000000000000000000::numeric AS pad40;  -- 소수 40자리 올림용 0 (M7·M10: 나눗셈이 먼저 반올림하지 않게)

-- M4 원화 월 수익률. USD 인데 그 달 환율 행이 없으면 0 으로 덮지 않고 멈춘다.
CREATE TEMP VIEW pos_ret AS
SELECT a.id AS asset_id, a.market::text AS market, a.ticker::text AS ticker, mb.id AS month_block_id, mb.month_start,
       CASE WHEN a.currency = 'KRW' THEN ar.local_return
            WHEN fx.fx_change IS NULL
                 THEN pg_temp.pos_fail('USD 종목에 환율 행이 없다: asset_id=' || a.id || ' month_start=' || mb.month_start)
            ELSE (1 + ar.local_return) * (1 + fx.fx_change) - 1
       END AS r_krw
FROM asset_month_return ar
JOIN asset a       ON a.id = ar.asset_id
JOIN month_block mb ON mb.id = ar.month_block_id
LEFT JOIN fx_month fx ON fx.month_block_id = ar.month_block_id AND fx.currency = a.currency;

-- M2 판정 창: 기준일 행마다 달력 경계 (둘 다 1일). date_trunc 는 timestamp 로 받아 시간대와 무관하다.
CREATE TEMP VIEW pos_window AS
SELECT p.base_date, p.id AS asset_position_id, p.asset_id,
       (date_trunc('month', p.base_date::timestamp)::date
            - make_interval(months => k.end_lag + k.win_months - 1))::date AS window_start,
       (date_trunc('month', p.base_date::timestamp)::date
            - make_interval(months => k.end_lag))::date                     AS window_end
FROM asset_position p CROSS JOIN pos_k k;

-- M5 시장 수익률: 기준일 창 안의 월 블록마다. 대용 종목 행이나 환율이 빠지면 pos_fail 로 멈춘다.
CREATE TEMP VIEW pos_mkt AS
SELECT w.base_date, mb.id AS month_block_id, mb.month_start,
       CASE WHEN kr.r_krw IS NULL OR us.r_krw IS NULL
                 THEN pg_temp.pos_fail('시장 대용 종목의 수익률이 없다: base_date=' || w.base_date || ' month_start=' || mb.month_start)
            ELSE k.w_kr * kr.r_krw + k.w_us * us.r_krw
       END AS r_mkt
FROM (SELECT DISTINCT base_date, window_start, window_end FROM pos_window) w
CROSS JOIN pos_k k
JOIN month_block mb ON mb.month_start BETWEEN w.window_start AND w.window_end
LEFT JOIN pos_ret kr ON kr.market = k.kr_market AND kr.ticker = k.kr_ticker AND kr.month_block_id = mb.id
LEFT JOIN pos_ret us ON us.market = k.us_market AND us.ticker = k.us_ticker AND us.month_block_id = mb.id;

-- 시리즈: 종목('A') = 창 안에서 종목 수익률과 시장 수익률이 둘 다 있는 달, 시장('M') = 창 안 월 블록 전부.
CREATE TEMP VIEW pos_series AS
SELECT 'A:' || w.asset_position_id AS sid, 'A'::text AS kind, w.base_date, w.asset_position_id, w.asset_id,
       r.month_start, r.r_krw, m.r_mkt
FROM pos_window w
JOIN pos_ret r ON r.asset_id = w.asset_id AND r.month_start BETWEEN w.window_start AND w.window_end
JOIN pos_mkt m ON m.base_date = w.base_date AND m.month_block_id = r.month_block_id
UNION ALL
SELECT 'M:' || m.base_date, 'M', m.base_date, NULL::bigint, NULL::bigint, m.month_start, m.r_mkt, m.r_mkt
FROM pos_mkt m;

-- M10 최대 낙폭: 부 지수 W0 = 1 에서 시작하는 재귀 CTE (정확한 numeric 곱), 최고점은 W0 를 포함한 누적 최댓값.
--     나눗셈의 분자를 소수 40자리로 올려 한 번 나누고 마지막에 한 번 반올림한다.
CREATE TEMP VIEW pos_dd AS
WITH RECURSIVE
ord AS (
    SELECT sid, r_krw AS r, row_number() OVER (PARTITION BY sid ORDER BY month_start) AS t
    FROM pos_series
),
wealth (sid, t, w) AS (
    SELECT DISTINCT sid, 0::bigint, 1::numeric FROM ord
    UNION ALL
    SELECT o.sid, o.t, wealth.w * (1 + o.r)
    FROM wealth JOIN ord o ON o.sid = wealth.sid AND o.t = wealth.t + 1
),
peak AS (
    SELECT sid, t, w, max(w) OVER (PARTITION BY sid ORDER BY t ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS pk
    FROM wealth
)
SELECT p.sid, ROUND(min((p.w + k.pad40) / p.pk - 1), 8) AS max_drawdown
FROM peak p CROSS JOIN pos_k k
GROUP BY p.sid;

-- 지표: 기준일 행마다 한 줄 (판정 불능 행도 남는다). *_raw 는 반올림 전 정확값 (검증용), 나머지는 저장하는 8자리 값.
CREATE TEMP VIEW pos_metric AS
WITH agg AS (
    SELECT w.base_date, w.asset_position_id, w.asset_id, w.window_start, w.window_end,
           count(s.month_start)::integer                                   AS months_used,
           (count(*) FILTER (WHERE s.r_mkt >= k.band))::integer            AS n_up,
           (count(*) FILTER (WHERE s.r_mkt <= -k.band))::integer           AS n_down,
           sum(s.r_krw) FILTER (WHERE s.r_mkt >= k.band)                   AS sum_r_up,
           sum(s.r_mkt) FILTER (WHERE s.r_mkt >= k.band)                   AS sum_m_up,
           sum(s.r_krw) FILTER (WHERE s.r_mkt <= -k.band)                  AS sum_r_down,
           sum(s.r_mkt) FILTER (WHERE s.r_mkt <= -k.band)                  AS sum_m_down,
           stddev_samp(s.r_krw)                                            AS vol_raw
    FROM pos_window w
    CROSS JOIN pos_k k
    LEFT JOIN pos_series s ON s.sid = 'A:' || w.asset_position_id
    GROUP BY w.base_date, w.asset_position_id, w.asset_id, w.window_start, w.window_end
),
calc AS (
    SELECT a.*,
           CASE WHEN a.sum_m_up   <> 0 THEN (a.sum_r_up   + k.pad40) / a.sum_m_up   END AS up_raw,
           CASE WHEN a.sum_m_down <> 0 THEN (a.sum_r_down + k.pad40) / a.sum_m_down END AS down_raw,
           CASE WHEN a.months_used < k.min_months THEN 'MONTHS'
                WHEN a.n_up   < k.min_up          THEN 'N_UP'
                WHEN a.n_down < k.min_down        THEN 'N_DOWN'
                WHEN COALESCE(a.sum_m_up, 0) = 0 OR COALESCE(a.sum_m_down, 0) = 0 THEN 'ZERO_DEN'
           END AS reason
    FROM agg a CROSS JOIN pos_k k
)
SELECT c.base_date, c.asset_position_id, c.asset_id, c.window_start, c.window_end, c.months_used, c.n_up, c.n_down,
       c.up_raw, c.down_raw, c.vol_raw,
       CASE WHEN c.reason IS NULL THEN ROUND(c.up_raw, 8)   END AS up_participation,
       CASE WHEN c.reason IS NULL THEN ROUND(c.down_raw, 8) END AS down_participation,
       CASE WHEN c.reason IS NULL THEN ROUND(c.vol_raw, 8)  END AS volatility,
       CASE WHEN c.reason IS NULL THEN d.max_drawdown       END AS max_drawdown,
       CASE WHEN c.reason IS NULL THEN dm.max_drawdown      END AS mkt_max_drawdown,
       c.reason
FROM calc c
LEFT JOIN pos_dd d  ON d.sid  = 'A:' || c.asset_position_id
LEFT JOIN pos_dd dm ON dm.sid = 'M:' || c.base_date;

-- M13 풀 상대 순위: 같은 기준일의 판정 가능 행끼리. RANK 라 동률은 같은 순위이고 경계 동률은 모두 들어간다. 저장된(반올림) 변동성 기준.
CREATE TEMP VIEW pos_rank AS
SELECT t.asset_position_id, t.n_pool, t.k,
       t.vol_rank_desc, t.vol_rank_asc,
       t.vol_rank_desc <= t.k AS vol_top,
       t.vol_rank_asc  <= t.k AS vol_bottom
FROM (SELECT m.asset_position_id,
             count(*)  OVER (PARTITION BY m.base_date)                         AS n_pool,
             ceil(pk.top_ratio * count(*) OVER (PARTITION BY m.base_date))     AS k,
             rank()    OVER (PARTITION BY m.base_date ORDER BY m.volatility DESC) AS vol_rank_desc,
             rank()    OVER (PARTITION BY m.base_date ORDER BY m.volatility ASC)  AS vol_rank_asc
      FROM pos_metric m CROSS JOIN pos_k pk
      WHERE m.reason IS NULL) t;

-- 최종: 판정 8컬럼 + 판정 가능 여부·사유. 판정 불능 행은 8컬럼이 모두 NULL (전부-또는-없음).
CREATE TEMP VIEW pos_judge AS
SELECT m.base_date, m.asset_position_id, m.asset_id,
       m.reason IS NULL AS judgeable, m.reason,
       CASE WHEN m.reason IS NULL THEN m.window_start END                AS window_start,
       CASE WHEN m.reason IS NULL THEN m.window_end END                  AS window_end,
       CASE WHEN m.reason IS NULL THEN m.months_used::smallint END       AS months_used,
       m.up_participation, m.down_participation, m.volatility, m.max_drawdown,
       CASE WHEN m.reason IS NULL
            THEN pg_temp.pos_classify(m.up_participation, m.down_participation, m.max_drawdown, m.mkt_max_drawdown,
                                      r.vol_top, r.vol_bottom)
       END AS system_position
FROM pos_metric m
LEFT JOIN pos_rank r ON r.asset_position_id = m.asset_position_id;
