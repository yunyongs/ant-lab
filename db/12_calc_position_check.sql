-- =====================================================================
-- my Fund Manager Phase 0 — 시스템 판정 포지션 계산 검증 SQL (P0-10)
-- 대상: db/12_calc_position.sql 이 채운 asset_position 의 판정 8컬럼과 정의 db/12_calc_position_def.sql (임시 뷰 pos_* 와 함수 pos_classify)
-- 근거: docs/erd.md 5.3(asset_position)·5.5(검증 규칙 H2·H9)·D35, docs/PLAN.md §4.10·§4.5·§5.2, db/13_calc_attention_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/12_calc_position_check.sql
--       적재(db/10_load_sample.sql → db/12_calc_position.sql) 뒤에 실행한다. 합성 행은 번호를 직접 지정해(OVERRIDING SYSTEM VALUE) 넣고
--       모두 되돌리므로 identity(번호표)를 소비하지 않아 실습 DB(antlab)에서도 돌릴 수 있다.
-- 판정: 마지막 줄 `p0_10_check` 가 `PASS` 이고 종료 코드가 0이면 통과다.
--       0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고, 그 밖에 하나라도 어긋나면 8절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 8절 뒤에 ROLLBACK 한다. 9절이 행 수·identity 마지막 값이 실행 전과 같은지 확인한다.
-- 절:   0 전제(FAIL 이면 여기서 멈춤) → 1 저장값 규칙 → 2 합성 손 계산 → 3 경계 표 → 4 순위 → 5 사후 정보 배제(H9) → 6 타입 → 8 판정 → ROLLBACK → 9 흔적
--       음성 사례(규칙을 일부러 어긴 뒤 잡히는지)는 사례가 속한 규칙의 절에 "음성 [규칙]" 행으로 쌓인다.
-- 규칙 코드 30개 (규칙마다 양성 쿼리 1개 + 음성 사례 1개 이상, 8절이 커버리지 N/N 을 확인한다):
--   저장값(1절):   R_FILLED R_H2 R_IND_N R_IND_UP R_IND_DOWN R_IND_VOL R_IND_MDD R_IND_MKT R_CONSIST R_RANGE R_NO_NAN
--   손 계산(2절):  HAND_METRIC HAND_REASON HAND_ROUNDED ERR_PROXY ERR_FX PAD40
--   경계 표(3절):  C_GK C_DEF C_BO C_FD C_TG C_ORDER
--   순위(4절):     RANK_K RANK_TIE RANK_ROUNDED
--   사후 정보(5절): H9_CUT H9_MOVED H9_TEETH
--   타입(6절):     TYPES
-- 독립 계산(1절): 사용자 정의 곱 집계(누적 곱), 자기 조인 최고점, 합·제곱합 분산식, 평균의 비로 같은 값을 다시 구하고 저장값과 8자리까지 같은지 본다.
--       정의의 방법(재귀 CTE·stddev_samp·합의 비)과 다른 방법이라 서로를 검증한다. 상수(36개월, 지연 1개월, 밴드 0.01, 069500·SPY 50/50)는 erd D35 에서 다시 옮겨 적었다.
-- 합성 손 계산(2절): 먼 미래(2097–2099) 합성 월 블록 36개와 합성 종목에서 손으로 계산한 기대값(리터럴, 시세 아님)과 pos_judge 를 대조한다. 서브트랜잭션에서 되돌린다.
-- 경계 표(3절): 판정 함수를 직접 불러 경계 바로 위·아래와 겹치는 규칙의 순서를 본다. 함수 본문을 일부러 고친 변이(pg_get_functiondef 를 글자 바꿈)가 표에 걸려야 한다.
-- 사후 정보 배제(5절, H9): 기준일 달과 그 뒤의 수익률을 더하거나 바꿔도, 창 앞 달을 바꿔도 판정 8컬럼이 같아야 한다. 창 안 달을 바꾸면 달라져야 하고(헛돌지 않음),
--       창 끝을 기준일 달로 민 정의에서는 시험이 위반을 잡아야 한다(이빨 확인).
-- 새 문법: 사용자 정의 집계 CREATE AGGREGATE (누적 곱), 윈도 함수 위의 집계, OVERRIDING SYSTEM VALUE (identity 열에 번호를 직접 넣기), pg_get_viewdef·pg_get_functiondef 로 정의를
--       글자 바꿈해 다시 만들기(변이 시험), PL/pgSQL 예외 블록(서브트랜잭션)으로 "바꿔 보고 되돌리기".
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 1–8절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN PERFORM setval('zz_verdict', 1); END $$;

-- 흔적 확인용 스냅샷: 다섯 표의 행 수와 identity 마지막 값. 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            v := v || t || '=없음;';
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
        s := COALESCE(pg_sequence_last_value(pg_get_serial_sequence('public.' || t, 'id')::regclass)::text, 'null');
        v := v || t || '=' || n || '/' || s || ';';
    END LOOP;
    RETURN v;
END $$;
CREATE TEMP TABLE zz_before AS SELECT pg_temp.zz_snap() AS snap;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '0 전제' '1 저장값' '2 손 계산' '3 경계 표' '4 순위' '5 H9' '6 타입' '8 판정'
    item     text    NOT NULL,
    expected text,
    actual   text,
    ok       boolean NOT NULL
);

-- 판정 프로시저 ------------------------------------------------------------
-- 스칼라 질의 한 개를 실행해 값을 text 로 돌려준다. 정확히 한 행이어야 한다. 0행은 NULL, 여러 행은 'ERROR too_many_rows'.
CREATE FUNCTION pg_temp.val(p_sql text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v_out text;
BEGIN
    EXECUTE p_sql INTO STRICT v_out;
    RETURN v_out;
EXCEPTION
    WHEN no_data_found THEN RETURN NULL;
    WHEN too_many_rows THEN RETURN 'ERROR too_many_rows';
END $$;

-- 음성 사례 실행기: 서브트랜잭션 안에서 p_mut(DDL/DML 문자열)를 실행한 뒤 검사 쿼리의 값을 text 로 얻고 모두 되돌린다.
--   변이가 p_allow(SQLSTATE) 오류로 끝나면 'ERR:<코드>' 를 돌려준다 (01 스키마가 더 강한 CHECK 로 막는 경우도 통과로 본다).
CREATE FUNCTION pg_temp.neg_run(p_q_sql text, p_mut text, p_allow text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text;
BEGIN
    BEGIN
        EXECUTE p_mut;
        v := pg_temp.val(p_q_sql);
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM = 'zz_undo' THEN
            NULL;
        ELSIF p_allow IS NOT NULL AND SQLSTATE = p_allow THEN
            v := 'ERR:' || SQLSTATE;
        ELSE
            v := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 80);
        END IF;
    END;
    RETURN v;
END $$;

-- 정의 파일을 읽는다 (쓰기 파일과 같은 파일. 임시 뷰 pos_* 와 함수 pos_classify 가 만들어진다)
\ir 12_calc_position_def.sql

-- 변이 도구 ------------------------------------------------------------------
-- 임시 뷰의 정의를 글자 바꿈해 같은 이름으로 다시 만든다 (의존하는 뷰는 이름이 아니라 번호로 이어져 있어 그대로 새 정의를 읽는다).
-- 바뀐 곳이 없으면 변이가 적용되지 않은 것이므로 오류로 멈춘다 (헛도는 변이 방지).
CREATE FUNCTION pg_temp.mut_view(p_view text, p_from text, p_to text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_old text := pg_get_viewdef(('pg_temp.' || p_view)::regclass);
    v_new text := replace(v_old, p_from, p_to);
BEGIN
    IF v_new = v_old THEN
        RAISE EXCEPTION 'zz 변이가 적용되지 않았다: 뷰 % 에서 [%] 를 찾지 못했다', p_view, p_from;
    END IF;
    EXECUTE 'CREATE OR REPLACE TEMP VIEW ' || p_view || ' AS ' || rtrim(v_new, E';\n ');
END $$;

-- 판정 함수의 본문을 글자 바꿈해 같은 이름으로 다시 만든다.
CREATE FUNCTION pg_temp.mut_classify(p_from text, p_to text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_old text := pg_get_functiondef('pg_temp.pos_classify'::regproc);
    v_new text := replace(v_old, p_from, p_to);
BEGIN
    IF v_new = v_old THEN
        RAISE EXCEPTION 'zz 변이가 적용되지 않았다: pos_classify 에서 [%] 를 찾지 못했다', p_from;
    END IF;
    EXECUTE v_new;
END $$;

-- 판정 함수 전체를 다른 CASE 로 바꾼다 (순서 변이용).
CREATE FUNCTION pg_temp.mut_classify_case(p_case text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE 'CREATE OR REPLACE FUNCTION pg_temp.pos_classify(p_up numeric, p_down numeric, p_mdd numeric, p_mkt_mdd numeric, p_vol_top boolean, p_vol_bottom boolean) '
         || 'RETURNS text LANGUAGE sql IMMUTABLE AS $f$ SELECT CASE ' || p_case || ' END $f$';
END $$;

-- 0 전제 -------------------------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('5개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '5',
        (SELECT count(*)::text FROM unnest(ARRAY['asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('정의 파일을 읽었다 (임시 뷰 pos_judge 와 함수 pos_classify 가 있다)', 'true',
        (to_regclass('pg_temp.pos_judge') IS NOT NULL AND to_regprocedure('pg_temp.pos_classify(numeric,numeric,numeric,numeric,boolean,boolean)') IS NOT NULL)::text),
    ('10 적재가 끝나 있다 (asset_position·asset_month_return 이 비어 있지 않다)', 'true',
        (SELECT (count(*) > 0)::text FROM asset_position) ),
    ('12 계산이 끝나 있다 (판정이 채워진 행이 있다 — 빈 표로 헛 PASS 하지 않는다)', 'true',
        (SELECT (count(*) > 0)::text FROM asset_position WHERE system_position IS NOT NULL)),
    ('시장 대용 종목 069500(KR)·SPY(US) 가 asset 에 있다', '2',
        (SELECT count(*)::text FROM asset WHERE (market, ticker) IN (('KR', '069500'), ('US', 'SPY')))),
    ('합성 시험이 쓰는 번호 구간(9000000000 이상)이 비어 있다', '0',
        ((SELECT count(*) FROM month_block WHERE id >= 9000000000) + (SELECT count(*) FROM asset WHERE id >= 9100000000)
       + (SELECT count(*) FROM asset_position WHERE id >= 9200000000) + (SELECT count(*) FROM asset_month_return WHERE id >= 9300000000)
       + (SELECT count(*) FROM fx_month WHERE id >= 9400000000))::text),
    ('합성 시험이 쓰는 달 구간(2097–2099)에 실제 월 블록이 없다', '0',
        (SELECT count(*)::text FROM month_block WHERE month_start >= DATE '2090-01-01')),
    ('H9 시험의 합성 달(기준일 달 이후)에 실제 월 블록이 없다 — 표본 구조 전제(기준일 하나, 그 뒤 달 없음)', '0',
        (SELECT count(*)::text FROM month_block WHERE month_start >= (SELECT date_trunc('month', min(base_date)::timestamp)::date FROM asset_position WHERE system_position IS NOT NULL)
                                                  AND month_start < DATE '2090-01-01')),
    ('asset_group 이 하나 이상 있다 (합성 종목의 그룹)', 'true', (SELECT (count(*) > 0)::text FROM asset_group))
) AS x(item, expected, actual);

INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('0 전제', '현재 DB 이름 (정보)', '(정보)', current_database(), true);

SELECT seq, item, expected, actual, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result
FROM zz_check_result
WHERE section = '0 전제'
ORDER BY seq;

DO $$
DECLARE
    v_fail integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok) INTO v_fail FROM zz_check_result WHERE section = '0 전제';
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-10 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 01 스키마와 10 적재, 12 계산을 먼저 실행했는지 확인한다)', v_fail;
    END IF;
END $$;

-- 독립 계산 도구 -------------------------------------------------------------
-- 누적 곱 집계: numeric_mul 을 거듭 곱한다 (정의의 재귀 CTE 와 다른 방법). 윈도 함수 안에서 쓰면 달 순서대로 누적한다.
CREATE AGGREGATE pg_temp.zz_prod(numeric) (SFUNC = numeric_mul, STYPE = numeric, INITCOND = '1');

-- 독립 시리즈: 기준일 행마다 창(36개월 앞, 기준일 달 제외 — 여기서는 interval 36 months 로 따로 씀)의 월 수익률(원화)과 시장 수익률.
--   국내·미국 대용 종목은 티커로 직접 찾아 조인한다 (정의의 pos_ret 뷰를 쓰지 않는다).
CREATE TEMP VIEW zz_ser AS
SELECT p.id AS pid, p.base_date, mb.id AS mbid, mb.month_start,
       CASE a.currency WHEN 'USD' THEN (1 + ar.local_return) * (1 + fx.fx_change) - 1 ELSE ar.local_return END AS r,
       0.5 * kr.r_k + 0.5 * us.r_u AS m
FROM asset_position p
JOIN asset a ON a.id = p.asset_id
JOIN month_block mb ON mb.month_start >= (date_trunc('month', p.base_date::timestamp) - INTERVAL '36 months')::date
                   AND mb.month_start <  date_trunc('month', p.base_date::timestamp)::date
JOIN asset_month_return ar ON ar.asset_id = p.asset_id AND ar.month_block_id = mb.id
LEFT JOIN fx_month fx ON fx.month_block_id = mb.id AND fx.currency = a.currency
JOIN (SELECT ar2.month_block_id, ar2.local_return AS r_k FROM asset_month_return ar2 JOIN asset s ON s.id = ar2.asset_id
      WHERE s.market = 'KR' AND s.ticker = '069500') kr ON kr.month_block_id = mb.id
JOIN (SELECT ar3.month_block_id, (1 + ar3.local_return) * (1 + fx3.fx_change) - 1 AS r_u FROM asset_month_return ar3
      JOIN asset s ON s.id = ar3.asset_id JOIN fx_month fx3 ON fx3.month_block_id = ar3.month_block_id AND fx3.currency = 'USD'
      WHERE s.market = 'US' AND s.ticker = 'SPY') us ON us.month_block_id = mb.id;

-- 독립 낙폭: 누적 곱(zz_prod) → 자기 조인 최고점(W0 = 1 포함) → 비 − 1 의 최솟값. 시장 낙폭은 기준일의 창 안 월 블록 전체(시장 시리즈) 기준.
CREATE TEMP VIEW zz_ind_dd AS
WITH w AS (
    SELECT pid, month_start, pg_temp.zz_prod(1 + r) OVER (PARTITION BY pid ORDER BY month_start) AS wl FROM zz_ser
)
SELECT a.pid, ROUND(LEAST(0, min((a.wl + 0.0000000000000000000000000000000000000000)
                  / GREATEST(1, (SELECT max(b.wl) FROM w b WHERE b.pid = a.pid AND b.month_start <= a.month_start)) - 1)), 8) AS mdd
FROM w a GROUP BY a.pid;
CREATE TEMP VIEW zz_ind_mkt AS
WITH mm AS (
    SELECT DISTINCT p.base_date, mb.month_start,
           (0.5 * (SELECT ar.local_return FROM asset_month_return ar JOIN asset s ON s.id = ar.asset_id
                   WHERE s.market = 'KR' AND s.ticker = '069500' AND ar.month_block_id = mb.id)
          + 0.5 * (SELECT (1 + ar.local_return) * (1 + fx.fx_change) - 1 FROM asset_month_return ar JOIN asset s ON s.id = ar.asset_id
                   JOIN fx_month fx ON fx.month_block_id = mb.id AND fx.currency = 'USD'
                   WHERE s.market = 'US' AND s.ticker = 'SPY' AND ar.month_block_id = mb.id)) AS m
    FROM asset_position p
    JOIN month_block mb ON mb.month_start >= (date_trunc('month', p.base_date::timestamp) - INTERVAL '36 months')::date
                       AND mb.month_start <  date_trunc('month', p.base_date::timestamp)::date
),
w AS (
    SELECT base_date, month_start, pg_temp.zz_prod(1 + m) OVER (PARTITION BY base_date ORDER BY month_start) AS wl FROM mm
)
SELECT a.base_date, ROUND(LEAST(0, min((a.wl + 0.0000000000000000000000000000000000000000)
                  / GREATEST(1, (SELECT max(b.wl) FROM w b WHERE b.base_date = a.base_date AND b.month_start <= a.month_start)) - 1)), 8) AS mdd
FROM w a GROUP BY a.base_date;

-- 독립 지표: 평균의 비(오른 달·빠진 달 평균 ÷ 시장 평균), 합·제곱합 분산식 + sqrt, 달 수. 8자리 반올림 한 번.
CREATE TEMP VIEW zz_ind AS
SELECT s.pid, count(*) AS n,
       ROUND((avg(s.r) FILTER (WHERE s.m >= 0.01) + 0.0000000000000000000000000000000000000000) / avg(s.m) FILTER (WHERE s.m >= 0.01), 8) AS up,
       ROUND((avg(s.r) FILTER (WHERE s.m <= -0.01) + 0.0000000000000000000000000000000000000000) / avg(s.m) FILTER (WHERE s.m <= -0.01), 8) AS down,
       ROUND(sqrt((count(*) * sum(s.r * s.r) - sum(s.r) * sum(s.r)) / (count(*) * (count(*) - 1))), 8) AS vol,
       d.mdd, dm.mdd AS mkt_mdd
FROM zz_ser s
LEFT JOIN zz_ind_dd d ON d.pid = s.pid
LEFT JOIN zz_ind_mkt dm ON dm.base_date = s.base_date
GROUP BY s.pid, d.mdd, dm.mdd;

-- 합성 시험 틀 ---------------------------------------------------------------
-- 합성 달 36개: t = 1…36 → 2097-01 … 2099-12. 시장 수익률 m(밴드 경계 포함), 환율 변화 f, 미국 대용(SPY)의 현지 수익률 u.
--   t=36 만 환율 +10% 이고 SPY 현지 수익률은 0 → 원화 수익률 (1+0)(1+0.10)−1 = 0.10 = 국내 대용과 같다 → 시장 수익률 = m 그대로(0.5·m + 0.5·m).
--   m: t=1–5 +0.02, t=6 +0.01(오른 달 경계 포함), t=7–11 −0.02, t=12 −0.01(빠진 달 경계 포함), t=13 +0.00999999(중립), t=14 −0.00999999(중립), t=15–35 0, t=36 +0.10
--   오른 달 7개(t=1–6, 36)의 시장 합 0.21, 빠진 달 6개(t=7–12)의 시장 합 −0.11.
CREATE TEMP TABLE zz_fx_m (t integer PRIMARY KEY, m numeric NOT NULL, f numeric NOT NULL, u numeric NOT NULL);
INSERT INTO zz_fx_m
SELECT t,
       CASE WHEN t <= 5 THEN 0.02 WHEN t = 6 THEN 0.01 WHEN t <= 11 THEN -0.02 WHEN t = 12 THEN -0.01
            WHEN t = 13 THEN 0.00999999 WHEN t = 14 THEN -0.00999999 WHEN t <= 35 THEN 0 ELSE 0.10 END,
       CASE WHEN t = 36 THEN 0.10 ELSE 0 END,
       CASE WHEN t = 36 THEN 0 WHEN t <= 5 THEN 0.02 WHEN t = 6 THEN 0.01 WHEN t <= 11 THEN -0.02 WHEN t = 12 THEN -0.01
            WHEN t = 13 THEN 0.00999999 WHEN t = 14 THEN -0.00999999 ELSE 0 END
FROM generate_series(1, 36) AS t;

-- 합성 종목과 월 수익률 규칙. 종목 코드 → 시장, 달별 현지 수익률 r(t) (NULL 이면 그 달 행 없음).
--   Z1  국내, r = m                      → 참여율 1, 시장과 같은 변동성·낙폭
--   Z3  미국, 현지 r = SPY 와 같은 u        → 원화로 바꾸면 Z1 과 같다 (환율 결합을 시험)
--   Z2  국내, t=1 에 −0.50, 나머지 0       → 낙폭 −0.5 (W0 = 1 이 최고점), 표준편차 0.5/6, 오른 달 합 −0.5 → 상승 참여율 −0.5/0.21
--   Z7  국내, 홀수 달 +0.01 짝수 달 −0.01   → 평균 0, 표본 표준편차 0.01·6/√35 (n−1), 오른 달 합 −0.01 → −0.01/0.21, 빠진 달 합 0
--   Z4  국내, t=15–27 행 없음(23개월)      → 최소 달 수 24 미달 MONTHS      Z4b t=15–26 행 없음(24개월) → 판정 가능(경계)
--   Z5  t=1–3 행 없음 → 오른 달 4개 N_UP    Z8 t=1–2 행 없음 → 오른 달 5개 판정 가능(경계)
--   Z6  t=7–8 행 없음 → 빠진 달 4개 N_DOWN  Z9 t=7 행 없음  → 빠진 달 5개 판정 가능(경계)
--   QT  r = 1.15 m (t=13,14 는 0)          → 상승·하락 참여율 1.15 → 타겟형 칸
--   QR  미국, 현지 r = 1.1 m (t=13,14 는 0), t=36 만 현지 0.00909091 → 원화 (1.00909091)(1.10) − 1 = 0.110000001
--       상승 참여율 원값 = 1.1 + 1e-9 / 0.21 (1.1 초과), 저장값 1.10000000 (1.1 초과 아님), 하락 1.1. 별도 풀 2100-01-09 (QR·Y1·Y21, N=3 → k=1, QR 은 상위·하위 아님)
--       → 저장값으로 비교하면 미드필더, 반올림 전 값을 넘기면 타겟형 (판정 함수에 반올림 값을 넘기는지 시험)
--   QF  빠진 달만 0.7 m, 나머지 m          → 상승 1.0, 하락 0.7 → 수비적 칸
--   QB  r = 2 m (t=13,14 는 0)             → 상승·하락 2.0, 변동성 상위, 낙폭이 시장의 1.5배보다 깊음 → 돌파형 칸
--   Yc  국내, r = c·m (c = 1…21), Y4X·Y17X 는 Y4·Y17 과 같되 t=15 에 +0.00000001 → 반올림한 변동성이 같은 동률 쌍
CREATE TEMP TABLE zz_fx_asset (code text PRIMARY KEY, market text NOT NULL);
INSERT INTO zz_fx_asset VALUES ('Z1','KR'),('Z2','KR'),('Z3','US'),('Z4','KR'),('Z4B','KR'),('Z5','KR'),('Z6','KR'),('Z7','KR'),('Z8','KR'),('Z9','KR'),
                               ('QT','KR'),('QR','US'),('QF','KR'),('QB','KR'),('Y4X','KR'),('Y17X','KR');
INSERT INTO zz_fx_asset SELECT 'Y' || c, 'KR' FROM generate_series(1, 21) c;

CREATE TEMP TABLE zz_fx_ret (code text NOT NULL, t integer NOT NULL, r numeric NOT NULL, PRIMARY KEY (code, t));
INSERT INTO zz_fx_ret
SELECT a.code, m.t,
       CASE a.code
            WHEN 'Z3' THEN m.u
            WHEN 'Z2' THEN CASE WHEN m.t = 1 THEN -0.50 ELSE 0 END
            WHEN 'Z7' THEN CASE WHEN m.t % 2 = 1 THEN 0.01 ELSE -0.01 END
            WHEN 'QT' THEN CASE WHEN m.t IN (13, 14) THEN 0 ELSE 1.15 * m.m END
            WHEN 'QR' THEN CASE WHEN m.t = 36 THEN 0.00909091 WHEN m.t IN (13, 14) THEN 0 ELSE 1.1 * m.m END
            WHEN 'QF' THEN CASE WHEN m.m <= -0.01 THEN 0.7 * m.m ELSE m.m END
            WHEN 'QB' THEN CASE WHEN m.t IN (13, 14) THEN 0 ELSE 2 * m.m END
            WHEN 'Y4X'  THEN 4 * m.m + CASE WHEN m.t = 15 THEN 0.00000001 ELSE 0 END
            WHEN 'Y17X' THEN 17 * m.m + CASE WHEN m.t = 15 THEN 0.00000001 ELSE 0 END
            WHEN 'Y1' THEN m.m WHEN 'Y2' THEN 2 * m.m WHEN 'Y3' THEN 3 * m.m WHEN 'Y4' THEN 4 * m.m WHEN 'Y5' THEN 5 * m.m
            WHEN 'Y6' THEN 6 * m.m WHEN 'Y7' THEN 7 * m.m WHEN 'Y8' THEN 8 * m.m WHEN 'Y9' THEN 9 * m.m WHEN 'Y10' THEN 10 * m.m
            WHEN 'Y11' THEN 11 * m.m WHEN 'Y12' THEN 12 * m.m WHEN 'Y13' THEN 13 * m.m WHEN 'Y14' THEN 14 * m.m WHEN 'Y15' THEN 15 * m.m
            WHEN 'Y16' THEN 16 * m.m WHEN 'Y17' THEN 17 * m.m WHEN 'Y18' THEN 18 * m.m WHEN 'Y19' THEN 19 * m.m WHEN 'Y20' THEN 20 * m.m
            WHEN 'Y21' THEN 21 * m.m
            ELSE m.m END
FROM zz_fx_asset a CROSS JOIN zz_fx_m m
WHERE NOT (a.code = 'Z4'  AND m.t BETWEEN 15 AND 27)
  AND NOT (a.code = 'Z4B' AND m.t BETWEEN 15 AND 26)
  AND NOT (a.code = 'Z5'  AND m.t BETWEEN 1 AND 3)
  AND NOT (a.code = 'Z6'  AND m.t BETWEEN 7 AND 8)
  AND NOT (a.code = 'Z8'  AND m.t BETWEEN 1 AND 2)
  AND NOT (a.code = 'Z9'  AND m.t = 7);

-- 합성 기준일 풀: 2100-01-04 손 계산 풀(13종목), 01-05 N=20, 01-06 N=21, 01-07 동률 N=20, 01-08 N=1. 모두 창 = 2097-01 … 2099-12.
CREATE TEMP TABLE zz_fx_pos (base_date date NOT NULL, code text NOT NULL, PRIMARY KEY (base_date, code));
INSERT INTO zz_fx_pos
SELECT DATE '2100-01-04', code FROM zz_fx_asset WHERE code IN ('Z1','Z2','Z3','Z4','Z4B','Z5','Z6','Z7','Z8','Z9','QT','QF','QB');
INSERT INTO zz_fx_pos SELECT DATE '2100-01-05', 'Y' || c FROM generate_series(1, 20) c;
INSERT INTO zz_fx_pos SELECT DATE '2100-01-06', 'Y' || c FROM generate_series(1, 21) c;
INSERT INTO zz_fx_pos SELECT DATE '2100-01-07', 'Y' || c FROM generate_series(1, 4) c;
INSERT INTO zz_fx_pos SELECT DATE '2100-01-07', 'Y4X';
INSERT INTO zz_fx_pos SELECT DATE '2100-01-07', 'Y' || c FROM generate_series(7, 16) c;
INSERT INTO zz_fx_pos VALUES (DATE '2100-01-07', 'Y17'), (DATE '2100-01-07', 'Y17X');
INSERT INTO zz_fx_pos SELECT DATE '2100-01-07', 'Y' || c FROM generate_series(18, 20) c;
INSERT INTO zz_fx_pos VALUES (DATE '2100-01-08', 'Y1');
INSERT INTO zz_fx_pos VALUES (DATE '2100-01-09', 'QR'), (DATE '2100-01-09', 'Y1'), (DATE '2100-01-09', 'Y21');

-- 합성 행을 넣는다 (서브트랜잭션 안에서만 부른다). 번호를 직접 지정해 identity 를 소비하지 않는다.
CREATE FUNCTION pg_temp.fx_build() RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_group bigint;
    v_kr    bigint;
    v_us    bigint;
BEGIN
    SELECT min(id) INTO v_group FROM asset_group;
    SELECT id INTO STRICT v_kr FROM asset WHERE market = 'KR' AND ticker = '069500';
    SELECT id INTO STRICT v_us FROM asset WHERE market = 'US' AND ticker = 'SPY';
    INSERT INTO month_block (id, month_start) OVERRIDING SYSTEM VALUE
    SELECT 9000000000 + t, (DATE '2097-01-01' + make_interval(months => t - 1))::date FROM generate_series(1, 36) t;
    INSERT INTO fx_month (id, month_block_id, currency, fx_change) OVERRIDING SYSTEM VALUE
    SELECT 9400000000 + t, 9000000000 + t, 'USD', f FROM zz_fx_m;
    INSERT INTO asset_month_return (id, asset_id, month_block_id, local_return) OVERRIDING SYSTEM VALUE
    SELECT 9300000000 + t, v_kr, 9000000000 + t, m FROM zz_fx_m
    UNION ALL
    SELECT 9300000100 + t, v_us, 9000000000 + t, u FROM zz_fx_m;
    INSERT INTO asset (id, market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis) OVERRIDING SYSTEM VALUE
    SELECT 9100000000 + row_number() OVER (ORDER BY code), market, 'ZZ_' || code, '합성 ' || code, 'STOCK',
           CASE market WHEN 'US' THEN 'USD' ELSE 'KRW' END, v_group, DATE '2000-01-01', 'PRICE'
    FROM zz_fx_asset;
    INSERT INTO asset_position (id, asset_id, base_date, base_price, base_fx_rate) OVERRIDING SYSTEM VALUE
    SELECT 9200000000 + row_number() OVER (ORDER BY fp.base_date, fp.code), a.id, fp.base_date, 1, 1
    FROM zz_fx_pos fp JOIN asset a ON a.ticker = 'ZZ_' || fp.code;
    INSERT INTO asset_month_return (id, asset_id, month_block_id, local_return) OVERRIDING SYSTEM VALUE
    SELECT 9300001000 + row_number() OVER (ORDER BY r.code, r.t), a.id, 9000000000 + r.t, r.r
    FROM zz_fx_ret r JOIN asset a ON a.ticker = 'ZZ_' || r.code;
END $$;

-- 손 계산 기대값 (합성 입력에서 손으로 계산한 값. 시세 아님). 2100-01-04 풀 13종목.
--   N = 판정 가능 10종목 → k = CEIL(0.2 × 10) = 2. 시장 최대 낙폭 = −0.10520790 (오른 뒤 t=7–14 의 하락: 0.98^5 × 0.99 × (1 − 0.00999999²) − 1).
--   Z1 = Z3 (환율 결합), Z2: 상승 −0.5/0.21 = −2.38095238, 하락 0/−0.11 = 0, 표준편차 0.5/6 = 0.08333333, 낙폭 −0.5
--   Z7: 상승 −0.01/0.21 = −0.04761905, 하락 0, 표준편차 0.01·6/√35 = 0.01014185
--   변동성 오름차순(하위): Z7 0.0101, QF 0.0192, Z9 0.0200 …  내림차순(상위): Z2 0.0833, QB 0.0399 …  → 상위 20% = {Z2, QB}, 하위 20% = {Z7, QF}
--   포지션: Z7 하위·하락 0 ≤ 0.2 골키퍼 / Z2 하락 0 < 0.6 수비수 / QB 상위·상승 2.0 > 1.3·낙폭 −0.2009 ≤ 1.5×(−0.1052) 돌파형 /
--           QF 상승 1.0 ≥ 1.0·하락 0.7 < 0.8 수비적 / QT 상승 1.15 > 1.1·하락 1.15 ≤ 1.2·상위 아님 타겟형 / 나머지 미드필더
CREATE TEMP TABLE zz_hand_exp (code text PRIMARY KEY, reason text, months_used integer, up numeric, down numeric, vol numeric, mdd numeric, pos text);
INSERT INTO zz_hand_exp VALUES
    ('Z1',  NULL,       36,  1.00000000,  1.00000000, 0.02008711, -0.10520790, 'MIDFIELDER'),
    ('Z3',  NULL,       36,  1.00000000,  1.00000000, 0.02008711, -0.10520790, 'MIDFIELDER'),
    ('Z2',  NULL,       36, -2.38095238,  0.00000000, 0.08333333, -0.50000000, 'DEFENDER'),
    ('Z7',  NULL,       36, -0.04761905,  0.00000000, 0.01014185, -0.01168165, 'GOALKEEPER'),
    ('Z4',  'MONTHS',   23,  NULL, NULL, NULL, NULL, NULL),
    ('Z4B', NULL,       24,  1.00000000,  1.00000000, 0.02465707, -0.10520790, 'MIDFIELDER'),
    ('Z5',  'N_UP',     33,  NULL, NULL, NULL, NULL, NULL),
    ('Z6',  'N_DOWN',   34,  NULL, NULL, NULL, NULL, NULL),
    ('Z8',  NULL,       34,  1.00000000,  1.00000000, 0.02022159, -0.10520790, 'MIDFIELDER'),
    ('Z9',  NULL,       35,  1.00000000,  1.00000000, 0.01999159, -0.08694684, 'MIDFIELDER'),
    ('QT',  NULL,       36,  1.15000000,  1.15000000, 0.02293602, -0.12006723, 'FORWARD_TARGET'),
    ('QF',  NULL,       36,  1.00000000,  0.70000000, 0.01915175, -0.07468332, 'FORWARD_DEFENSIVE'),
    ('QB',  NULL,       36,  2.00000000,  2.00000000, 0.03988873, -0.20093476, 'FORWARD_BREAKOUT');

-- 손 계산 비교: 합성 행을 넣고 pos_judge 를 기대값과 비교해 다른 칸의 수를 센다 (서브트랜잭션, 되돌림).
--   'metric': 판정 가능 종목의 8컬럼 중 달 수·참여율·변동성·낙폭·포지션과 시장 낙폭, 판정 불능 종목의 8컬럼 NULL
--   'reason': 사유 코드(판정 불능 3종과 판정 가능 경계 4종)
CREATE FUNCTION pg_temp.hand_count(p_mode text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v bigint;
BEGIN
    BEGIN
        PERFORM pg_temp.fx_build();
        IF p_mode = 'metric' THEN
            SELECT count(*) INTO v
            FROM zz_hand_exp e
            LEFT JOIN asset a ON a.ticker = 'ZZ_' || e.code
            LEFT JOIN asset_position p ON p.asset_id = a.id AND p.base_date = DATE '2100-01-04'
            LEFT JOIN pos_judge j ON j.asset_position_id = p.id
            WHERE CASE WHEN e.reason IS NOT NULL   -- 판정 불능: 8컬럼이 모두 NULL (달 수만 남기는 것도 위반)
                       THEN num_nulls(j.window_start, j.window_end, j.months_used, j.up_participation, j.down_participation,
                                      j.volatility, j.max_drawdown, j.system_position) <> 8
                       ELSE (j.window_start, j.window_end, j.months_used, j.up_participation, j.down_participation, j.volatility, j.max_drawdown, j.system_position)
                            IS DISTINCT FROM (DATE '2097-01-01', DATE '2099-12-01', e.months_used::smallint, e.up, e.down, e.vol, e.mdd, e.pos)
                  END;
            v := v + (SELECT count(*) FROM pos_metric m JOIN asset_position p ON p.id = m.asset_position_id JOIN asset a ON a.id = p.asset_id
                      WHERE a.ticker = 'ZZ_Z1' AND p.base_date = DATE '2100-01-04' AND m.mkt_max_drawdown IS DISTINCT FROM -0.10520790);
            v := v + (SELECT abs(count(*) - 13) FROM pos_judge j JOIN asset_position p ON p.id = j.asset_position_id WHERE p.base_date = DATE '2100-01-04');
        ELSE
            SELECT count(*) INTO v
            FROM zz_hand_exp e
            LEFT JOIN asset a ON a.ticker = 'ZZ_' || e.code
            LEFT JOIN asset_position p ON p.asset_id = a.id AND p.base_date = DATE '2100-01-04'
            LEFT JOIN pos_judge j ON j.asset_position_id = p.id
            WHERE (j.reason, j.judgeable) IS DISTINCT FROM (e.reason, e.reason IS NULL);
        END IF;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 입력이 빠졌을 때 멈추는가: 합성 행에서 입력을 지운 뒤 정의를 읽어 기대한 오류 메시지가 나오면 0, 오류가 없거나 다른 오류이면 1.
CREATE FUNCTION pg_temp.err_count(p_kind text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v      bigint := 1;
    v_num  numeric;
    v_pat  text := CASE p_kind WHEN 'PROXY' THEN 'P0-10 정의: 시장 대용 종목의 수익률이 없다%' ELSE 'P0-10 정의: USD 종목에 환율 행이 없다%' END;
BEGIN
    BEGIN
        PERFORM pg_temp.fx_build();
        BEGIN
            IF p_kind = 'PROXY' THEN
                DELETE FROM asset_month_return WHERE id = 9300000000 + 10;     -- 국내 대용(069500) t=10 행을 지운다
                SELECT count(r_mkt) INTO v_num FROM pos_mkt;
            ELSE
                DELETE FROM fx_month WHERE id = 9400000000 + 20;               -- t=20 환율 행을 지운다
                SELECT count(r_krw) INTO v_num FROM pos_ret;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            IF SQLSTATE = 'P0001' AND SQLERRM LIKE v_pat THEN
                v := 0;
            END IF;
        END;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 풀 순위 비교: 합성 풀의 k·크기·상위/하위 집합이 기대와 다른 수를 센다.
--   'k'   : 풀 크기 N 과 k = CEIL(0.2 N) (N=20 → 4, N=21 → 5, N=1 → 1)
--   'set' : 상위·하위 20% 에 든 종목 (경계 동률 포함, N=1 이면 상위·하위가 함께 참)
CREATE FUNCTION pg_temp.pool_count(p_mode text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v bigint;
BEGIN
    BEGIN
        PERFORM pg_temp.fx_build();
        IF p_mode = 'k' THEN
            SELECT count(*) INTO v
            FROM (VALUES (DATE '2100-01-05', 20, 4), (DATE '2100-01-06', 21, 5), (DATE '2100-01-07', 20, 4), (DATE '2100-01-08', 1, 1)) AS e(d, n, k)
            LEFT JOIN (SELECT p.base_date, max(r.n_pool) AS n, max(r.k) AS k
                       FROM pos_rank r JOIN asset_position p ON p.id = r.asset_position_id GROUP BY p.base_date) g ON g.base_date = e.d
            WHERE (g.n, g.k) IS DISTINCT FROM (e.n, e.k);
        ELSIF p_mode = 'cut' THEN
            -- 반올림 전 값은 1.1 을 넘고 저장값은 1.10000000 인 사례가 실제로 갈리며, 판정은 저장값 기준(미드필더)이어야 한다
            SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END INTO v
            FROM pos_metric m JOIN pos_judge j ON j.asset_position_id = m.asset_position_id
            JOIN asset_position p ON p.id = m.asset_position_id JOIN asset a ON a.id = p.asset_id
            WHERE a.ticker = 'ZZ_QR' AND p.base_date = DATE '2100-01-09'
              AND m.up_raw > 1.1 AND j.up_participation = 1.10000000 AND j.system_position = 'MIDFIELDER';
        ELSIF p_mode = 'rounded' THEN
            -- 반올림한 변동성이 같은 쌍(Y4·Y4X, Y17·Y17X)의 원값은 다르다: 순위가 원값이 아니라 저장되는 반올림 값을 따르는가
            SELECT count(*) INTO v
            FROM (SELECT p.base_date, a.ticker, m.volatility, m.vol_raw FROM pos_metric m
                  JOIN asset_position p ON p.id = m.asset_position_id JOIN asset a ON a.id = p.asset_id
                  WHERE p.base_date = DATE '2100-01-07' AND a.ticker IN ('ZZ_Y4', 'ZZ_Y4X')) t
            HAVING count(DISTINCT volatility) <> 1 OR count(DISTINCT vol_raw) <> 2;
            v := COALESCE(v, 0) + (SELECT count(*) FROM (SELECT 1 FROM pos_rank r JOIN asset_position p ON p.id = r.asset_position_id
                                                         JOIN asset a ON a.id = p.asset_id
                                                         WHERE p.base_date = DATE '2100-01-07' AND a.ticker IN ('ZZ_Y4', 'ZZ_Y4X') AND NOT r.vol_bottom) x);
        ELSE
            SELECT count(*) INTO v
            FROM (VALUES (DATE '2100-01-05', 'ZZ_Y17,ZZ_Y18,ZZ_Y19,ZZ_Y20', 'ZZ_Y1,ZZ_Y2,ZZ_Y3,ZZ_Y4'),
                         (DATE '2100-01-06', 'ZZ_Y17,ZZ_Y18,ZZ_Y19,ZZ_Y20,ZZ_Y21', 'ZZ_Y1,ZZ_Y2,ZZ_Y3,ZZ_Y4,ZZ_Y5'),
                         (DATE '2100-01-07', 'ZZ_Y17,ZZ_Y17X,ZZ_Y18,ZZ_Y19,ZZ_Y20', 'ZZ_Y1,ZZ_Y2,ZZ_Y3,ZZ_Y4,ZZ_Y4X'),
                         (DATE '2100-01-08', 'ZZ_Y1', 'ZZ_Y1')) AS e(d, top, bottom)
            LEFT JOIN (SELECT p.base_date,
                              string_agg(a.ticker, ',' ORDER BY a.ticker COLLATE "C") FILTER (WHERE r.vol_top) AS top,
                              string_agg(a.ticker, ',' ORDER BY a.ticker COLLATE "C") FILTER (WHERE r.vol_bottom) AS bottom
                       FROM pos_rank r JOIN asset_position p ON p.id = r.asset_position_id JOIN asset a ON a.id = p.asset_id GROUP BY p.base_date) g
                   ON g.base_date = e.d
            WHERE (g.top, g.bottom) IS DISTINCT FROM (e.top, e.bottom);
        END IF;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN COALESCE(v, 0);
END $$;

-- 경계 표 (3절): 판정 함수를 직접 부른다. (up, down, mdd, 시장 mdd, 변동성 상위, 변동성 하위) → 기대 포지션.
--   규칙 코드는 그 사례가 지키는 규칙: C_GK 골키퍼, C_DEF 수비수, C_BO 돌파형, C_FD 수비적, C_TG 타겟형, C_ORDER 순서.
CREATE TEMP TABLE zz_t3 (id text PRIMARY KEY, code text NOT NULL, up numeric, down numeric, mdd numeric, mkt numeric, top boolean, bottom boolean, expected text NOT NULL);
INSERT INTO zz_t3 VALUES
    -- 골키퍼: 변동성 하위 20% 그리고 하락 참여율 ≤ 0.2 (아래 한계 없음)
    ('g1', 'C_GK',  1.0, 0.20000000, -0.1, -0.2, false, true,  'GOALKEEPER'),
    ('g2', 'C_GK',  1.0, 0.20000001, -0.1, -0.2, false, true,  'DEFENDER'),
    ('g3', 'C_GK',  1.0, 0.00000000, -0.1, -0.2, false, false, 'DEFENDER'),
    ('g4', 'C_GK',  1.0, -0.50000000, -0.1, -0.2, false, true, 'GOALKEEPER'),
    ('g5', 'C_GK',  1.5, 0.10000000, -0.4, -0.2, true,  true,  'GOALKEEPER'),
    -- 수비수: 하락 참여율 < 0.6
    ('d1', 'C_DEF', 0.5, 0.59999999, -0.1, -0.2, false, false, 'DEFENDER'),
    ('d2', 'C_DEF', 0.5, 0.60000000, -0.1, -0.2, false, false, 'MIDFIELDER'),
    -- 돌파형: 변동성 상위 20% 그리고 상승 > 1.3 그리고 낙폭이 시장의 1.5배 이상 깊음
    ('b1', 'C_BO',  1.30000001, 0.9, -0.3, -0.2, true,  false, 'FORWARD_BREAKOUT'),
    ('b2', 'C_BO',  1.30000000, 0.9, -0.3, -0.2, true,  false, 'MIDFIELDER'),
    ('b3', 'C_BO',  1.5, 0.9, -0.29999999, -0.2, true,  false, 'MIDFIELDER'),
    ('b4', 'C_BO',  1.5, 0.9, -0.3, -0.2, false, false, 'FORWARD_TARGET'),
    ('b5', 'C_BO',  1.5, 0.9, 0.0, 0.0, true,  false, 'FORWARD_BREAKOUT'),
    ('b6', 'C_BO',  1.5, 0.9, -0.01, 0.0, true,  false, 'FORWARD_BREAKOUT'),
    -- 수비적: 상승 ≥ 1.0 그리고 하락 < 0.8
    ('f1', 'C_FD',  0.99999999, 0.7, -0.1, -0.2, false, false, 'MIDFIELDER'),
    ('f2', 'C_FD',  1.00000000, 0.7, -0.1, -0.2, false, false, 'FORWARD_DEFENSIVE'),
    ('f3', 'C_FD',  1.0, 0.79999999, -0.1, -0.2, false, false, 'FORWARD_DEFENSIVE'),
    ('f4', 'C_FD',  1.0, 0.80000000, -0.1, -0.2, false, false, 'MIDFIELDER'),
    -- 타겟형: 상승 > 1.1 그리고 하락 ≤ 1.2 그리고 변동성 상위 20% 아님
    ('t1', 'C_TG',  1.10000000, 0.9, -0.1, -0.2, false, false, 'MIDFIELDER'),
    ('t2', 'C_TG',  1.10000001, 0.9, -0.1, -0.2, false, false, 'FORWARD_TARGET'),
    ('t3', 'C_TG',  1.5, 1.20000000, -0.1, -0.2, false, false, 'FORWARD_TARGET'),
    ('t4', 'C_TG',  1.5, 1.20000001, -0.1, -0.2, false, false, 'MIDFIELDER'),
    ('t5', 'C_TG',  1.2, 0.9, -0.1, -0.2, true,  false, 'MIDFIELDER'),
    ('t6', 'C_TG',  1.5, 0.9, -0.1, -0.2, true,  false, 'MIDFIELDER'),
    -- 순서가 결과를 가르는 겹침
    ('o1', 'C_ORDER', 1.5, 0.1, -0.4, -0.2, true,  true,  'GOALKEEPER'),          -- 골키퍼 ∩ 수비수 ∩ 돌파형
    ('o2', 'C_ORDER', 1.5, 0.5, -0.1, -0.2, false, false, 'DEFENDER'),            -- 수비수 ∩ 수비적
    ('o3', 'C_ORDER', 1.5, 0.7, -0.4, -0.2, true,  false, 'FORWARD_BREAKOUT'),    -- 돌파형 ∩ 수비적
    ('o4', 'C_ORDER', 1.2, 0.7, -0.1, -0.2, false, false, 'FORWARD_DEFENSIVE'),   -- 수비적 ∩ 타겟형
    ('o5', 'C_ORDER', 1.5, 0.7, -0.1, -0.2, true,  false, 'FORWARD_DEFENSIVE'),   -- 상위인데 낙폭이 얕아 돌파형이 아니면 수비적
    ('o6', 'C_ORDER', 1.5, 0.5, -0.4, -0.2, true,  false, 'DEFENDER');            -- 수비수 ∩ 돌파형

CREATE FUNCTION pg_temp.t3_count() RETURNS bigint
LANGUAGE sql AS $$
    SELECT count(*) FROM zz_t3 t
    WHERE pg_temp.pos_classify(t.up, t.down, t.mdd, t.mkt, t.top, t.bottom) IS DISTINCT FROM t.expected
$$;

-- 사후 정보 배제 (5절, H9) -----------------------------------------------------
CREATE TEMP TABLE zz_h9_base (asset_position_id bigint PRIMARY KEY, window_start date, window_end date, months_used smallint,
                              up numeric, down numeric, vol numeric, mdd numeric, pos text);
CREATE TEMP TABLE zz_h9_now (LIKE zz_h9_base);
CREATE FUNCTION pg_temp.h9_snap(p_tbl text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('DELETE FROM %s', p_tbl);
    EXECUTE format('INSERT INTO %s SELECT asset_position_id, window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position FROM pos_judge', p_tbl);
END $$;

-- 전제: 기준일 달 이후에 실제 월 블록이 없다(0절이 확인한다). 있으면 합성 월 블록과 겹쳐 5절이 틀리게 돌므로 0절이 먼저 멈춘다.
--       기준일별로 일반화하는 일은 기준일이 여럿인 표본이 생길 때(MVP 적재 전) 후속으로 한다.
-- 변이 전 판정 8컬럼(p_def_mut 로 정의를 먼저 바꿀 수 있다)과 변이 후를 비교해 달라진 행 수를 센다 (서브트랜잭션, 되돌림).
--   FUTURE : 기준일 달과 그 뒤 합성 월 블록 3개를 더하고 모든 종목·환율에 큰 값을 넣은 뒤, 기준일 달 이후 모든 수익률을 다른 값으로 덮어쓴다
--   PRE    : 창 앞(기준일 달 − 36개월 앞) 모든 수익률·환율을 상수로 바꾼다 (표본의 2007–2012 구간)
--   BOTH   : FUTURE + PRE
--   IN     : 창 안 한 달(창 시작 + 17개월)의 모든 수익률을 바꾼다 — 달라져야 하는 경우 (시험이 헛돌지 않는다는 증거)
CREATE FUNCTION pg_temp.h9_count(p_def_mut text, p_kind text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v       bigint;
    v_base  date := (SELECT date_trunc('month', min(base_date)::timestamp)::date FROM asset_position WHERE system_position IS NOT NULL);
BEGIN
    BEGIN
        IF p_def_mut IS NOT NULL THEN EXECUTE p_def_mut; END IF;
        PERFORM pg_temp.h9_snap('zz_h9_base');
        IF p_kind IN ('FUTURE', 'BOTH') THEN
            INSERT INTO month_block (id, month_start) OVERRIDING SYSTEM VALUE
            SELECT 9000001000 + i, (v_base + make_interval(months => i))::date FROM generate_series(0, 2) i;
            INSERT INTO fx_month (id, month_block_id, currency, fx_change) OVERRIDING SYSTEM VALUE
            SELECT 9400001000 + i, 9000001000 + i, 'USD', 0.20 FROM generate_series(0, 2) i;
            INSERT INTO asset_month_return (id, asset_id, month_block_id, local_return) OVERRIDING SYSTEM VALUE
            SELECT 9300010000 + row_number() OVER (ORDER BY a.id, i), a.id, 9000001000 + i, 0.30 FROM asset a CROSS JOIN generate_series(0, 2) i;
            UPDATE asset_month_return ar SET local_return = CASE WHEN ar.local_return > 0 THEN -0.20 ELSE 0.20 END
            FROM month_block mb WHERE mb.id = ar.month_block_id AND mb.month_start >= v_base;
        END IF;
        IF p_kind IN ('PRE', 'BOTH') THEN
            UPDATE asset_month_return ar SET local_return = 0.01
            FROM month_block mb WHERE mb.id = ar.month_block_id AND mb.month_start < (v_base - make_interval(months => 36))::date;
            UPDATE fx_month fx SET fx_change = 0.05
            FROM month_block mb WHERE mb.id = fx.month_block_id AND mb.month_start < (v_base - make_interval(months => 36))::date;
        END IF;
        IF p_kind = 'IN' THEN
            UPDATE asset_month_return ar SET local_return = CASE WHEN ar.local_return > 0.07 THEN -0.07 ELSE 0.07 END
            FROM month_block mb WHERE mb.id = ar.month_block_id AND mb.month_start = (v_base - make_interval(months => 36 - 17))::date;
        END IF;
        PERFORM pg_temp.h9_snap('zz_h9_now');
        SELECT count(*) INTO v
        FROM zz_h9_base b FULL JOIN zz_h9_now n ON n.asset_position_id = b.asset_position_id
        WHERE b.asset_position_id IS NULL OR n.asset_position_id IS NULL
           OR (b.window_start, b.window_end, b.months_used, b.up, b.down, b.vol, b.mdd, b.pos)
              IS DISTINCT FROM (n.window_start, n.window_end, n.months_used, n.up, n.down, n.vol, n.mdd, n.pos);
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 타입 검사 (6절): 정의 본문(뷰·함수의 정의 글자)에서 부동소수점을 만드는 이름을 센다. 이름 목록은 이 함수 한 곳에 있다.
CREATE FUNCTION pg_temp.float_hits(p_text text) RETURNS bigint
LANGUAGE sql IMMUTABLE AS $$
    SELECT count(*) FROM regexp_matches(lower(p_text),
        '(\m(date_part|float4|float8|float|real|percentile_cont|percentile_disc)\M|\m(ln|exp|power)\(|double precision)', 'g')
$$;

-- 1 저장값 규칙 + 2~6 정의 규칙: 위반 개수를 돌려주는 쿼리 목록 ---------------------------------------------
-- 같은 쿼리를 양성(실제 DB, 0건)과 음성(위반을 만든 뒤 기대한 개수)에 쓴다.
CREATE TEMP TABLE zz_q (code text PRIMARY KEY, section text NOT NULL, item text NOT NULL, sql text NOT NULL);
INSERT INTO zz_q (code, section, item, sql) VALUES
    ('R_FILLED', '1 저장값', '판정 8컬럼이 비어 있는 asset_position 행 수 (모든 기준일의 판정이 채워졌는가 — H2·S2 의 전제)',
     $q$SELECT count(*) FROM asset_position WHERE system_position IS NULL OR window_start IS NULL$q$),
    ('R_H2', '1 저장값', '창이 기준일 달의 바로 전 달에서 끝나지 않거나(H2) 시작이 그보다 35개월 앞이 아닌 판정 행 (사후 정보 배제의 저장값 쪽)',
     $q$SELECT count(*) FROM asset_position p WHERE p.system_position IS NOT NULL
        AND (p.window_end <> (date_trunc('month', p.base_date::timestamp) - INTERVAL '1 month')::date
          OR p.window_start <> (date_trunc('month', p.base_date::timestamp) - INTERVAL '36 months')::date)$q$),
    ('R_IND_N', '1 저장값', 'months_used 가 독립 계산(창 안에서 종목·시장 수익률이 둘 다 있는 달 수)과 다른 행',
     $q$SELECT count(*) FROM asset_position p LEFT JOIN zz_ind i ON i.pid = p.id
        WHERE p.system_position IS NOT NULL AND (i.pid IS NULL OR p.months_used <> i.n)$q$),
    ('R_IND_UP', '1 저장값', 'up_participation 이 평균의 비 독립 계산(8자리 반올림)과 다른 행',
     $q$SELECT count(*) FROM asset_position p LEFT JOIN zz_ind i ON i.pid = p.id
        WHERE p.system_position IS NOT NULL AND (i.pid IS NULL OR p.up_participation IS DISTINCT FROM i.up)$q$),
    ('R_IND_DOWN', '1 저장값', 'down_participation 이 평균의 비 독립 계산과 다른 행',
     $q$SELECT count(*) FROM asset_position p LEFT JOIN zz_ind i ON i.pid = p.id
        WHERE p.system_position IS NOT NULL AND (i.pid IS NULL OR p.down_participation IS DISTINCT FROM i.down)$q$),
    ('R_IND_VOL', '1 저장값', 'volatility 가 합·제곱합 분산식 독립 계산(표본 표준편차, 8자리)과 다른 행',
     $q$SELECT count(*) FROM asset_position p LEFT JOIN zz_ind i ON i.pid = p.id
        WHERE p.system_position IS NOT NULL AND (i.pid IS NULL OR p.volatility IS DISTINCT FROM i.vol)$q$),
    ('R_IND_MDD', '1 저장값', 'max_drawdown 이 사용자 정의 곱 집계 + 자기 조인 최고점 독립 계산과 다른 행',
     $q$SELECT count(*) FROM asset_position p LEFT JOIN zz_ind i ON i.pid = p.id
        WHERE p.system_position IS NOT NULL AND (i.pid IS NULL OR p.max_drawdown IS DISTINCT FROM i.mdd)$q$),
    ('R_IND_MKT', '1 저장값', '정의의 시장 최대 낙폭(pos_metric.mkt_max_drawdown)이 독립 계산과 다른 기준일 행 (돌파형의 비교 기준)',
     $q$SELECT count(*) FROM pos_metric m JOIN asset_position p ON p.id = m.asset_position_id
        LEFT JOIN zz_ind i ON i.pid = p.id
        WHERE p.system_position IS NOT NULL AND (i.pid IS NULL OR m.mkt_max_drawdown IS DISTINCT FROM i.mkt_mdd)$q$),
    ('R_CONSIST', '1 저장값', 'system_position 이 저장된 참여율·낙폭과 같은 기준일 풀의 저장된 변동성 순위로 판정 함수를 다시 불러 낸 값과 다른 행 (일관성, 완료 기준 5)',
     $q$SELECT count(*) FROM (
          SELECT p.id, p.system_position, p.up_participation u, p.down_participation d, p.max_drawdown dd, p.base_date,
                 rank() OVER (PARTITION BY p.base_date ORDER BY p.volatility DESC) AS rd,
                 rank() OVER (PARTITION BY p.base_date ORDER BY p.volatility ASC)  AS ra,
                 count(*) OVER (PARTITION BY p.base_date) AS n
          FROM asset_position p WHERE p.system_position IS NOT NULL) s
        JOIN zz_ind i ON i.pid = s.id
        WHERE s.system_position IS DISTINCT FROM pg_temp.pos_classify(s.u, s.d, s.dd, i.mkt_mdd, s.rd <= ceil(0.2 * s.n), s.ra <= ceil(0.2 * s.n))$q$),
    ('R_RANGE', '1 저장값', '범위 밖 행: months_used 가 24–36 밖, 변동성 < 0, 낙폭이 [−1, 0] 밖, 창이 36개월이 아님 (DDL 은 months_used > 0 만 막는다)',
     $q$SELECT count(*) FROM asset_position WHERE system_position IS NOT NULL
        AND (months_used NOT BETWEEN 24 AND 36 OR volatility < 0 OR max_drawdown NOT BETWEEN -1 AND 0
             OR window_end <> (window_start + INTERVAL '35 months')::date)$q$),
    ('R_NO_NAN', '1 저장값', 'NaN 이 저장된 행 + 정의의 반올림 전 값(up_raw·down_raw·vol_raw)에 NaN 이 든 행',
     $q$SELECT (SELECT count(*) FROM asset_position WHERE up_participation = 'NaN'::numeric OR down_participation = 'NaN'::numeric
                                                     OR volatility = 'NaN'::numeric OR max_drawdown = 'NaN'::numeric)
             + (SELECT count(*) FROM pos_metric WHERE up_raw = 'NaN'::numeric OR down_raw = 'NaN'::numeric OR vol_raw = 'NaN'::numeric)$q$),
    ('HAND_METRIC', '2 손 계산', '합성 13종목(2100-01-04 풀)에서 pos_judge 가 손 계산 기대값(달 수·참여율·변동성·낙폭·포지션, 시장 낙폭, 판정 불능의 8컬럼 NULL)과 다른 칸 수',
     $q$SELECT pg_temp.hand_count('metric')$q$),
    ('HAND_REASON', '2 손 계산', '판정 불능 사유(MONTHS·N_UP·N_DOWN)와 최소 달 수 경계(24/23, 오른 달 5/4, 빠진 달 5/4)의 판정 가능 여부가 기대와 다른 종목 수',
     $q$SELECT pg_temp.hand_count('reason')$q$),
    ('ERR_PROXY', '2 손 계산', '시장 대용 종목의 한 달 수익률 행이 없을 때 정의가 오류로 멈추지 않는 경우 (0 이어야 한다 = 멈춘다)',
     $q$SELECT pg_temp.err_count('PROXY')$q$),
    ('ERR_FX', '2 손 계산', 'USD 종목의 한 달 환율 행이 없을 때 정의가 오류로 멈추지 않는 경우 (0 으로 덮지 않는다)',
     $q$SELECT pg_temp.err_count('FX')$q$),
    ('PAD40', '2 손 계산', '참여율의 반올림 전 값(up_raw·down_raw)의 소수 자릿수가 40 미만인 판정 가능 행 (분자를 40자리로 올려 한 번만 나눈다)',
     $q$SELECT count(*) FROM pos_metric WHERE reason IS NULL AND (scale(up_raw) < 40 OR scale(down_raw) < 40)$q$),
    ('C_GK', '3 경계 표', '골키퍼 경계 사례(하락 0.20000000/0.20000001, 변동성 하위 아님, 음수 하락, 상위와 하위가 함께 참)에서 판정 함수가 기대와 다른 수',
     $q$SELECT count(*) FROM zz_t3 t WHERE t.code = 'C_GK' AND pg_temp.pos_classify(t.up, t.down, t.mdd, t.mkt, t.top, t.bottom) IS DISTINCT FROM t.expected$q$),
    ('C_DEF', '3 경계 표', '수비수 경계 사례(하락 0.59999999/0.60000000)에서 기대와 다른 수',
     $q$SELECT count(*) FROM zz_t3 t WHERE t.code = 'C_DEF' AND pg_temp.pos_classify(t.up, t.down, t.mdd, t.mkt, t.top, t.bottom) IS DISTINCT FROM t.expected$q$),
    ('C_BO', '3 경계 표', '돌파형 경계 사례(상승 1.30000000/1.30000001, 낙폭 정확히 1.5배/1e-8 얕음, 변동성 상위 아님, 시장 낙폭 0)에서 기대와 다른 수',
     $q$SELECT count(*) FROM zz_t3 t WHERE t.code = 'C_BO' AND pg_temp.pos_classify(t.up, t.down, t.mdd, t.mkt, t.top, t.bottom) IS DISTINCT FROM t.expected$q$),
    ('C_FD', '3 경계 표', '수비적 경계 사례(상승 0.99999999/1.0, 하락 0.79999999/0.80000000)에서 기대와 다른 수',
     $q$SELECT count(*) FROM zz_t3 t WHERE t.code = 'C_FD' AND pg_temp.pos_classify(t.up, t.down, t.mdd, t.mkt, t.top, t.bottom) IS DISTINCT FROM t.expected$q$),
    ('C_TG', '3 경계 표', '타겟형 경계 사례(상승 1.10000000/1.10000001, 하락 1.20000000/1.20000001, 변동성 상위 20%·상승 1.2 → 미드필더)에서 기대와 다른 수',
     $q$SELECT count(*) FROM zz_t3 t WHERE t.code = 'C_TG' AND pg_temp.pos_classify(t.up, t.down, t.mdd, t.mkt, t.top, t.bottom) IS DISTINCT FROM t.expected$q$),
    ('C_ORDER', '3 경계 표', '여러 규칙에 걸리는 사례(골키퍼∩수비수∩돌파형, 수비수∩수비적, 돌파형∩수비적, 수비적∩타겟형, 수비수∩돌파형)에서 순서가 기대와 다른 수',
     $q$SELECT count(*) FROM zz_t3 t WHERE t.code = 'C_ORDER' AND pg_temp.pos_classify(t.up, t.down, t.mdd, t.mkt, t.top, t.bottom) IS DISTINCT FROM t.expected$q$),
    ('HAND_ROUNDED', '2 손 계산', '반올림 전 상승 참여율이 1.1 을 넘고 저장값은 1.10000000 인 합성 종목(QR)이 저장값 기준의 미드필더가 아닌 경우 (문턱 비교는 저장되는 8자리 값, M14)',
     $q$SELECT pg_temp.pool_count('cut')$q$),
    ('RANK_K', '4 순위', '합성 풀의 크기 N 과 k = CEIL(0.2 N) 가 기대(N=20 → 4, N=21 → 5, N=1 → 1)와 다른 기준일 수',
     $q$SELECT pg_temp.pool_count('k')$q$),
    ('RANK_TIE', '4 순위', '변동성 상위·하위 20% 에 든 종목이 기대와 다른 기준일 수 (경계 동률 포함: 4위 동률 둘 → 5개, N=1 → 상위·하위 모두 참)',
     $q$SELECT pg_temp.pool_count('set')$q$),
    ('RANK_ROUNDED', '4 순위', '반올림 값은 같고 원값은 다른 쌍(Y4·Y4X)이 순위에서 같은 순위가 아닌 경우 (순위는 저장되는 반올림 변동성 기준)',
     $q$SELECT pg_temp.pool_count('rounded')$q$),
    ('H9_CUT', '5 H9', '기준일 달과 그 뒤 월 블록을 더하고 값을 바꾸며 창 앞 달도 바꿨을 때 판정 8컬럼이 달라진 행 수 (사후 정보 배제, 0 이어야 한다)',
     $q$SELECT pg_temp.h9_count(NULL, 'BOTH')$q$),
    ('H9_MOVED', '5 H9', '창 안 한 달을 바꿨는데 어느 행의 판정도 달라지지 않은 경우 (시험이 헛돌지 않는다는 증거 — 달라져야 한다)',
     $q$SELECT (pg_temp.h9_count(NULL, 'IN') = 0)::integer$q$),
    ('H9_TEETH', '5 H9', '창 끝을 기준일 달로 민 샌 정의(end_lag = 0)를 같은 시험이 잡지 못한 경우 (잡으면 0)',
     $q$SELECT (pg_temp.h9_count($m$SELECT pg_temp.mut_view('pos_k', '1 AS end_lag', '0 AS end_lag')$m$, 'FUTURE') = 0)::integer$q$),
    ('TYPES', '6 타입', 'pos_ 뷰·함수의 정의 글자에서 부동소수점 이름(date_part, float*, real, double precision, ln, exp, power, percentile_*)이 나온 수 + float 열을 가진 pos_ 뷰 열 수',
     $q$SELECT (SELECT COALESCE(sum(pg_temp.float_hits(pg_get_viewdef(c.oid))), 0)
                FROM pg_class c WHERE c.relnamespace = pg_my_temp_schema() AND c.relkind = 'v' AND c.relname LIKE 'pos\_%')
             + pg_temp.float_hits(pg_get_functiondef('pg_temp.pos_classify'::regproc))
             + pg_temp.float_hits(pg_get_functiondef('pg_temp.pos_fail'::regproc))
             + (SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                WHERE c.relnamespace = pg_my_temp_schema() AND c.relkind = 'v' AND c.relname LIKE 'pos\_%'
                  AND a.attnum > 0 AND NOT a.attisdropped AND a.atttypid IN ('float4'::regtype, 'float8'::regtype))$q$);

-- 양성: 위 쿼리 모두 위반 0건 ---------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT q.section, '[' || q.code || '] ' || q.item || ' — 위반 0건', '0', v.n, v.n IS NOT DISTINCT FROM '0'
FROM zz_q q CROSS JOIN LATERAL (SELECT pg_temp.val(q.sql) AS n OFFSET 0) v
ORDER BY q.section, q.code;

-- 정보 행 (FAIL 아님): 규모와 판정 불능 건수, 미드필더 중 전형 범위(0.7–1.1) 밖의 수. 값(참여율·변동성·종목별 포지션)은 쓰지 않는다
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('1 저장값', '판정이 채워진 행 수 / asset_position 행 수 (정보)', '(정보)',
        pg_temp.val('SELECT count(*) FROM asset_position WHERE system_position IS NOT NULL') || ' / ' || pg_temp.val('SELECT count(*) FROM asset_position'), true),
       ('1 저장값', '기준일 수 / 판정 풀 크기의 최솟값 (정보)', '(정보)',
        pg_temp.val('SELECT count(DISTINCT base_date) FROM asset_position') || ' / '
        || pg_temp.val('SELECT min(c) FROM (SELECT count(*) AS c FROM asset_position WHERE system_position IS NOT NULL GROUP BY base_date) g'), true),
       ('1 저장값', '미드필더 중 하락 참여율이 0.7–1.1 밖인 행 수 (정보 — 표의 0.7–1.1 은 전형 설명이지 판정 조건이 아니다, W1)', '(정보)',
        pg_temp.val($s$SELECT count(*) FROM asset_position WHERE system_position = 'MIDFIELDER' AND (down_participation < 0.7 OR down_participation > 1.1)$s$), true);

-- 음성 사례: 일부러 위반을 만들면 그 규칙의 검사 쿼리가 기대한 개수로 잡는지 (서브트랜잭션 안의 변이, 끝에 되돌린다) ----------
-- expect: 기대 위반 수의 하한(ge: 그 수 이상). alt: 변이가 01 스키마의 CHECK 에 막혀 이 오류 코드로 끝나도 통과
CREATE TEMP TABLE zz_neg (n serial, q text NOT NULL, label text NOT NULL, mutate text NOT NULL, expect text NOT NULL DEFAULT '1', alt text);
INSERT INTO zz_neg (q, label, mutate, expect, alt) VALUES
    ('R_FILLED', '기준일 행 하나의 판정 8컬럼을 모두 NULL 로 만들면 (전부-또는-없음 CHECK 는 통과)',
     $m$UPDATE asset_position SET window_start = NULL, window_end = NULL, months_used = NULL, up_participation = NULL, down_participation = NULL,
               volatility = NULL, max_drawdown = NULL, system_position = NULL WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_H2', '창 끝을 한 달 앞으로 당기면 (DDL 은 통과, 창이 기준일 달의 바로 전 달이 아니다)',
     $m$UPDATE asset_position SET window_end = (window_end - INTERVAL '1 month')::date WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_H2', '창 시작을 한 달 앞으로 당기면',
     $m$UPDATE asset_position SET window_start = (window_start - INTERVAL '1 month')::date WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_IND_N', 'months_used 를 1 바꾸면',
     $m$UPDATE asset_position SET months_used = months_used - 1 WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_IND_UP', 'up_participation 을 1e-8 바꾸면 (8자리 일치를 요구한다)',
     $m$UPDATE asset_position SET up_participation = up_participation + 0.00000001 WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_IND_DOWN', 'down_participation 을 1e-8 바꾸면',
     $m$UPDATE asset_position SET down_participation = down_participation + 0.00000001 WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_IND_VOL', 'volatility 를 1e-8 바꾸면',
     $m$UPDATE asset_position SET volatility = volatility + 0.00000001 WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_IND_MDD', 'max_drawdown 을 1e-8 바꾸면',
     $m$UPDATE asset_position SET max_drawdown = CASE WHEN max_drawdown <= -0.00000001 THEN max_drawdown + 0.00000001 ELSE max_drawdown - 0.00000001 END
        WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_IND_MKT', '정의의 낙폭 나눗셈 분모(최고점)에 0.0001 을 더하면 (시장 낙폭이 달라진다)',
     $m$SELECT pg_temp.mut_view('pos_dd', '/ p.pk)', '/ (p.pk + 0.0001))')$m$, '1', NULL),
    ('R_CONSIST', '저장된 포지션을 다른 값으로 바꾸면 (DDL 은 6값 목록만 확인한다)',
     $m$UPDATE asset_position SET system_position = CASE WHEN system_position = 'MIDFIELDER' THEN 'DEFENDER' ELSE 'MIDFIELDER' END WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_RANGE', 'months_used 를 23 으로 만들면 (DDL 은 > 0 만 막는다)',
     $m$UPDATE asset_position SET months_used = 23 WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', NULL),
    ('R_NO_NAN', '정의의 변동성을 NaN 으로 만드는 값을 넣으면 — 저장값 NaN 은 01 스키마의 CHECK(23514)가 먼저 막는다',
     $m$UPDATE asset_position SET volatility = 'NaN'::numeric WHERE id = (SELECT min(id) FROM asset_position)$m$, '1', '23514'),
    ('HAND_METRIC', '밴드 경계를 오른 달에서 제외하면 (r시장 ≥ 0.01 → > 0.01)',
     $m$SELECT pg_temp.mut_view('pos_metric', '(s.r_mkt >= k.band)', '(s.r_mkt > k.band)')$m$, '1', NULL),
    ('HAND_METRIC', '밴드 경계를 빠진 달에서 제외하면 (r시장 ≤ −0.01 → < −0.01)',
     $m$SELECT pg_temp.mut_view('pos_metric', '(s.r_mkt <= (- k.band))', '(s.r_mkt < (- k.band))')$m$, '1', NULL),
    ('HAND_METRIC', '표본 표준편차(n − 1)를 모표준편차(n)로 바꾸면',
     $m$SELECT pg_temp.mut_view('pos_metric', 'stddev_samp(s.r_krw)', 'stddev_pop(s.r_krw)')$m$, '1', NULL),
    ('HAND_METRIC', '최고점에서 W0 = 1 을 빼면 (첫 달에 바로 빠진 종목의 낙폭이 0 이 된다)',
     $m$SELECT pg_temp.mut_view('pos_dd', 'max(wealth.w) OVER (', 'max(wealth.w) FILTER (WHERE wealth.t > 0) OVER (')$m$, '1', NULL),
    ('HAND_METRIC', '미국 종목에 환율을 곱하지 않으면 (원화 결합 없음)',
     $m$SELECT pg_temp.mut_view('pos_ret', '((((1)::numeric + ar.local_return) * ((1)::numeric + fx.fx_change)) - (1)::numeric)', 'ar.local_return')$m$, '1', NULL),
    ('HAND_METRIC', '시장 비중을 한 쪽 0.6 으로 바꾸면 (50/50 이 아니다)',
     $m$SELECT pg_temp.mut_view('pos_k', '0.5 AS w_kr', '0.6 AS w_kr')$m$, '1', NULL),
    ('HAND_METRIC', '판정 불능 행에 사용한 달 수만 남기면 (8컬럼 전부-또는-없음 위반)',
     $m$SELECT pg_temp.mut_view('pos_judge', 'ELSE NULL::smallint', 'ELSE (m.months_used)::smallint')$m$, '1', NULL),
    ('HAND_METRIC', '창을 35개월로 줄이면',
     $m$SELECT pg_temp.mut_view('pos_k', '36 AS win_months', '35 AS win_months')$m$, '1', NULL),
    ('HAND_METRIC', '창 끝을 기준일 달로 밀면 (end_lag = 0)',
     $m$SELECT pg_temp.mut_view('pos_k', '1 AS end_lag', '0 AS end_lag')$m$, '1', NULL),
    ('HAND_REASON', '최소 달 수를 25 로 올리면 (24개월 종목이 판정 불능이 된다)',
     $m$SELECT pg_temp.mut_view('pos_k', '24 AS min_months', '25 AS min_months')$m$, '1', NULL),
    ('HAND_REASON', '최소 달 수를 23 으로 내리면 (23개월 종목이 판정 가능이 된다)',
     $m$SELECT pg_temp.mut_view('pos_k', '24 AS min_months', '23 AS min_months')$m$, '1', NULL),
    ('HAND_REASON', '오른 달 최소를 6 으로 올리면',
     $m$SELECT pg_temp.mut_view('pos_k', '5 AS min_up', '6 AS min_up')$m$, '1', NULL),
    ('HAND_REASON', '오른 달 최소를 4 로 내리면',
     $m$SELECT pg_temp.mut_view('pos_k', '5 AS min_up', '4 AS min_up')$m$, '1', NULL),
    ('HAND_REASON', '빠진 달 최소를 6 으로 올리면',
     $m$SELECT pg_temp.mut_view('pos_k', '5 AS min_down', '6 AS min_down')$m$, '1', NULL),
    ('HAND_REASON', '빠진 달 최소를 4 로 내리면',
     $m$SELECT pg_temp.mut_view('pos_k', '5 AS min_down', '4 AS min_down')$m$, '1', NULL),
    ('HAND_ROUNDED', '판정 함수에 반올림 전 값(up_raw·down_raw)을 넘기면 (QR 이 타겟형이 된다)',
     $m$SELECT pg_temp.mut_view('pos_judge', 'pos_classify(m.up_participation, m.down_participation', 'pos_classify(m.up_raw, m.down_raw')$m$, '1', NULL),
    ('ERR_PROXY', '오류 함수를 0 을 돌려주는 함수로 바꾸면 (빠진 대용 종목을 0 으로 덮는다)',
     $m$CREATE OR REPLACE FUNCTION pg_temp.pos_fail(p_msg text) RETURNS numeric LANGUAGE sql AS 'SELECT 0::numeric'$m$, '1', NULL),
    ('ERR_FX', '오류 함수를 0 을 돌려주는 함수로 바꾸면 (빠진 환율을 0 으로 덮는다)',
     $m$CREATE OR REPLACE FUNCTION pg_temp.pos_fail(p_msg text) RETURNS numeric LANGUAGE sql AS 'SELECT 0::numeric'$m$, '1', NULL),
    ('PAD40', '분자를 40자리로 올리는 0 을 빼면 (나눗셈이 16자리 근처에서 먼저 반올림한다)',
     $m$SELECT pg_temp.mut_view('pos_k', '0.0000000000000000000000000000000000000000 AS pad40', '0.0 AS pad40')$m$, '1', NULL),
    ('C_GK', '골키퍼 하락 한계를 < 로 바꾸면 (0.2 정확히가 빠진다)',
     $m$SELECT pg_temp.mut_classify('p_vol_bottom AND p_down <= 0.2', 'p_vol_bottom AND p_down < 0.2')$m$, '1', NULL),
    ('C_GK', '골키퍼 하락 한계를 0.3 으로 바꾸면',
     $m$SELECT pg_temp.mut_classify('p_vol_bottom AND p_down <= 0.2', 'p_vol_bottom AND p_down <= 0.3')$m$, '1', NULL),
    ('C_GK', '골키퍼에서 변동성 하위 조건을 빼면',
     $m$SELECT pg_temp.mut_classify('p_vol_bottom AND p_down <= 0.2', 'p_down <= 0.2')$m$, '1', NULL),
    ('C_GK', '골키퍼에 하락 참여율 아래 한계(≥ 0)를 걸면 (음수는 선방 이상이다)',
     $m$SELECT pg_temp.mut_classify('p_vol_bottom AND p_down <= 0.2', 'p_vol_bottom AND p_down BETWEEN 0 AND 0.2')$m$, '1', NULL),
    ('C_DEF', '수비수 경계를 ≤ 0.6 으로 바꾸면',
     $m$SELECT pg_temp.mut_classify('WHEN p_down < 0.6', 'WHEN p_down <= 0.6')$m$, '1', NULL),
    ('C_BO', '돌파형 상승 경계를 ≥ 1.3 으로 바꾸면',
     $m$SELECT pg_temp.mut_classify('p_up > 1.3', 'p_up >= 1.3')$m$, '1', NULL),
    ('C_BO', '돌파형 낙폭 경계를 < 로 바꾸면 (정확히 1.5배가 빠진다)',
     $m$SELECT pg_temp.mut_classify('p_mdd <= 1.5 * p_mkt_mdd', 'p_mdd < 1.5 * p_mkt_mdd')$m$, '1', NULL),
    ('C_BO', '돌파형 배수를 1.4 로 바꾸면',
     $m$SELECT pg_temp.mut_classify('p_mdd <= 1.5 * p_mkt_mdd', 'p_mdd <= 1.4 * p_mkt_mdd')$m$, '1', NULL),
    ('C_BO', '돌파형에서 변동성 상위 조건을 빼면',
     $m$SELECT pg_temp.mut_classify('p_vol_top AND p_up > 1.3', 'p_up > 1.3')$m$, '1', NULL),
    ('C_FD', '수비적 상승 경계를 > 1.0 으로 바꾸면',
     $m$SELECT pg_temp.mut_classify('p_up >= 1.0 AND', 'p_up > 1.0 AND')$m$, '1', NULL),
    ('C_FD', '수비적 하락 경계를 ≤ 0.8 로 바꾸면',
     $m$SELECT pg_temp.mut_classify('p_down < 0.8', 'p_down <= 0.8')$m$, '1', NULL),
    ('C_TG', '타겟형 상승 경계를 ≥ 1.1 로 바꾸면',
     $m$SELECT pg_temp.mut_classify('p_up > 1.1', 'p_up >= 1.1')$m$, '1', NULL),
    ('C_TG', '타겟형 하락 경계를 < 1.2 로 바꾸면',
     $m$SELECT pg_temp.mut_classify('p_down <= 1.2', 'p_down < 1.2')$m$, '1', NULL),
    ('C_TG', '타겟형에서 변동성 상위 제외 조건을 빼면',
     $m$SELECT pg_temp.mut_classify(' AND NOT p_vol_top', '')$m$, '1', NULL),
    ('C_ORDER', '타겟형을 수비적보다 먼저 보면 (O4 의 순서)',
     $m$SELECT pg_temp.mut_classify_case($c$
        WHEN p_vol_bottom AND p_down <= 0.2 THEN 'GOALKEEPER' WHEN p_down < 0.6 THEN 'DEFENDER'
        WHEN p_vol_top AND p_up > 1.3 AND p_mdd <= 1.5 * p_mkt_mdd THEN 'FORWARD_BREAKOUT'
        WHEN p_up > 1.1 AND p_down <= 1.2 AND NOT p_vol_top THEN 'FORWARD_TARGET'
        WHEN p_up >= 1.0 AND p_down < 0.8 THEN 'FORWARD_DEFENSIVE' ELSE 'MIDFIELDER'$c$)$m$, '1', NULL),
    ('C_ORDER', '수비적을 돌파형보다 먼저 보면',
     $m$SELECT pg_temp.mut_classify_case($c$
        WHEN p_vol_bottom AND p_down <= 0.2 THEN 'GOALKEEPER' WHEN p_down < 0.6 THEN 'DEFENDER'
        WHEN p_up >= 1.0 AND p_down < 0.8 THEN 'FORWARD_DEFENSIVE'
        WHEN p_vol_top AND p_up > 1.3 AND p_mdd <= 1.5 * p_mkt_mdd THEN 'FORWARD_BREAKOUT'
        WHEN p_up > 1.1 AND p_down <= 1.2 AND NOT p_vol_top THEN 'FORWARD_TARGET' ELSE 'MIDFIELDER'$c$)$m$, '1', NULL),
    ('C_ORDER', '수비수를 골키퍼보다 먼저 보면',
     $m$SELECT pg_temp.mut_classify_case($c$
        WHEN p_down < 0.6 THEN 'DEFENDER' WHEN p_vol_bottom AND p_down <= 0.2 THEN 'GOALKEEPER'
        WHEN p_vol_top AND p_up > 1.3 AND p_mdd <= 1.5 * p_mkt_mdd THEN 'FORWARD_BREAKOUT'
        WHEN p_up >= 1.0 AND p_down < 0.8 THEN 'FORWARD_DEFENSIVE'
        WHEN p_up > 1.1 AND p_down <= 1.2 AND NOT p_vol_top THEN 'FORWARD_TARGET' ELSE 'MIDFIELDER'$c$)$m$, '1', NULL),
    ('C_ORDER', '돌파형을 수비수보다 먼저 보면',
     $m$SELECT pg_temp.mut_classify_case($c$
        WHEN p_vol_bottom AND p_down <= 0.2 THEN 'GOALKEEPER'
        WHEN p_vol_top AND p_up > 1.3 AND p_mdd <= 1.5 * p_mkt_mdd THEN 'FORWARD_BREAKOUT'
        WHEN p_down < 0.6 THEN 'DEFENDER' WHEN p_up >= 1.0 AND p_down < 0.8 THEN 'FORWARD_DEFENSIVE'
        WHEN p_up > 1.1 AND p_down <= 1.2 AND NOT p_vol_top THEN 'FORWARD_TARGET' ELSE 'MIDFIELDER'$c$)$m$, '1', NULL),
    ('C_ORDER', '수비적을 수비수보다 먼저 보면',
     $m$SELECT pg_temp.mut_classify_case($c$
        WHEN p_vol_bottom AND p_down <= 0.2 THEN 'GOALKEEPER'
        WHEN p_up >= 1.0 AND p_down < 0.8 THEN 'FORWARD_DEFENSIVE' WHEN p_down < 0.6 THEN 'DEFENDER'
        WHEN p_vol_top AND p_up > 1.3 AND p_mdd <= 1.5 * p_mkt_mdd THEN 'FORWARD_BREAKOUT'
        WHEN p_up > 1.1 AND p_down <= 1.2 AND NOT p_vol_top THEN 'FORWARD_TARGET' ELSE 'MIDFIELDER'$c$)$m$, '1', NULL),
    ('RANK_K', '상위·하위 개수를 올림(CEIL) 대신 내림으로 하면 (N=21 → 4)',
     $m$SELECT pg_temp.mut_view('pos_rank', 'ceil(', 'floor(')$m$, '1', NULL),
    ('RANK_K', '개수를 반올림으로 하면 (N=21 → 4)',
     $m$SELECT pg_temp.mut_view('pos_rank', 'ceil(', 'round(')$m$, '1', NULL),
    ('RANK_TIE', '순위를 RANK 대신 ROW_NUMBER 로 매기면 (경계 동률이 잘린다)',
     $m$SELECT pg_temp.mut_view('pos_rank', 'rank() OVER', 'row_number() OVER')$m$, '1', NULL),
    ('RANK_TIE', '상위 조건을 ≤ k 대신 < k 로 바꾸면',
     $m$SELECT pg_temp.mut_view('pos_rank', '((vol_rank_desc)::numeric <= k)', '((vol_rank_desc)::numeric < k)')$m$, '1', NULL),
    ('RANK_TIE', '하위 조건을 ≤ k 대신 < k 로 바꾸면',
     $m$SELECT pg_temp.mut_view('pos_rank', '((vol_rank_asc)::numeric <= k)', '((vol_rank_asc)::numeric < k)')$m$, '1', NULL),
    ('RANK_ROUNDED', '순위를 반올림 값이 아닌 원값(vol_raw)으로 매기면',
     $m$SELECT pg_temp.mut_view('pos_rank', 'ORDER BY m.volatility', 'ORDER BY m.vol_raw')$m$, '1', NULL),
    ('H9_CUT', '창 끝을 기준일 달로 밀면 (end_lag = 0 — 기준일 달 이후 값이 판정에 들어간다)',
     $m$SELECT pg_temp.mut_view('pos_k', '1 AS end_lag', '0 AS end_lag')$m$, '1', NULL),
    ('H9_CUT', '창을 240개월로 늘리면 (창 앞 달 — 표본의 2007–2012 — 이 들어간다)',
     $m$SELECT pg_temp.mut_view('pos_k', '36 AS win_months', '240 AS win_months')$m$, '1', NULL),
    ('H9_MOVED', '바꿀 창 안 달의 수익률을 미리 모두 0.07 로 만들면 (시험의 변이가 같은 값으로 덮어써 아무것도 바꾸지 못해 헛돈다)',
     $m$UPDATE asset_month_return ar SET local_return = 0.07 FROM month_block mb
        WHERE mb.id = ar.month_block_id
          AND mb.month_start = ((SELECT date_trunc('month', min(base_date)::timestamp)::date FROM asset_position WHERE system_position IS NOT NULL) - INTERVAL '19 months')::date$m$, '1', NULL),
    ('H9_TEETH', '창 끝 지연 상수를 정의가 읽지 않게 하면 (샌 변이가 효과가 없어 시험이 잡을 것이 없다)',
     $m$SELECT pg_temp.mut_view('pos_window', 'k.end_lag', '1')$m$, '1', NULL),
    ('TYPES', '정의에 date_part 호출이 있으면',
     $m$CREATE OR REPLACE FUNCTION pg_temp.pos_fail(p_msg text) RETURNS numeric LANGUAGE sql AS 'SELECT date_part(''month'', now())::numeric'$m$, '1', NULL),
    ('TYPES', '정의 뷰에 double precision 열이 생기면',
     $m$CREATE TEMP VIEW pos_zz_float AS SELECT 1::double precision AS x$m$, '1', NULL);

DO $$
DECLARE
    r    record;
    v_n  text;
    v_ok boolean;
BEGIN
    FOR r IN SELECT n.q, n.label, n.mutate, n.expect, n.alt, q.section, q.sql FROM zz_neg n JOIN zz_q q ON q.code = n.q ORDER BY n.n LOOP
        v_n := pg_temp.neg_run(r.sql, r.mutate, r.alt);
        v_ok := v_n LIKE 'ERR:%' OR (v_n ~ '^[0-9]+$' AND v_n::bigint >= r.expect::bigint);
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES (r.section, '음성 [' || r.q || '] ' || r.label || ' — 위반 ' || r.expect || '건 이상', '≥ ' || r.expect, v_n, v_ok);
    END LOOP;
END $$;

-- 8 판정 ---------------------------------------------------------------------
-- 8.1 규칙 커버리지: 음성 사례가 모두 통과한 규칙 코드 수 / 규칙 코드 수 (N/N 이어야 한다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '8 판정', '규칙 커버리지 (음성 사례가 있고 모두 통과한 규칙 코드 / 전체 규칙 코드)',
       (SELECT count(*) FROM zz_q) || '/' || (SELECT count(*) FROM zz_q),
       cov.n || '/' || (SELECT count(*) FROM zz_q),
       cov.n = (SELECT count(*) FROM zz_q)
FROM (SELECT count(*) AS n
      FROM zz_q q
      WHERE EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%')
        AND NOT EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%' AND NOT r.ok)) cov;

-- 8.2 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 8.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 8.4 판정 행
SELECT CASE WHEN count(*) > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result;

-- 8.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3).
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-10 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 9 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 다섯 표의 행 수와 identity 마지막 값이 실행 전과 같고, public 에 zz_ 객체가 없다.
DO $$
DECLARE
    v_before text;
    v_after  text;
    v_zz     integer;
BEGIN
    SELECT snap INTO v_before FROM zz_before;
    v_after := pg_temp.zz_snap();
    SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
         + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%') INTO v_zz;
    IF v_before IS DISTINCT FROM v_after OR v_zz <> 0 THEN
        PERFORM setval('zz_verdict', 1);
        RAISE EXCEPTION E'P0-10 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    IF (SELECT last_value FROM zz_verdict) = 2 THEN
        PERFORM setval('zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_10_check;
