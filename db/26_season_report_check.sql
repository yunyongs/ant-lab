-- =====================================================================
-- my Fund Manager Phase 0 — 시즌 성적표 검증 SQL (P0-20)
-- 대상: db/26_season_report_def.sql 의 함수 (sr_report·sr_turns·sr_season_positions·sr_players·sr_change_costs)와 db/12_calc_position_def.sql 의 배열 입력 함수 3개.
--       연습 시즌의 현재 상태(턴 전환 직후이든 매매 직후 턴 중간이든 끝난 시즌이든)를 같은 규칙으로 본다.
-- 근거: docs/erd.md D38·9절 P0-20, docs/PLAN.md §4.6·§4.10·§4.13·§5.2·§6.1·§6.2, db/25_trade_formation_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/26_season_report_check.sql
--       INSERT 를 하지 않아 identity(번호표)를 소비하지 않으므로 실습 DB 에서도 돌릴 수 있다. 정의 파일(12·21·25·26)과 22 정의를 \ir 로 읽는다.
--       범위: 현재 턴 ≥ 1 이어야 한다 (0턴이면 0절 FAIL, 종료 코드 3). 1턴 직후 · 5턴 직후 · 5턴 매매 직후(턴 중간) · 10턴 FINISHED 모두 PASS 해야 한다.
-- 판정: 마지막 줄 `p0_20_check` 가 `PASS` 이고 종료 코드가 0이면 통과다. 0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고, 그 밖에 하나라도 어긋나면 16절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
-- 흔적: 파일 전체가 한 트랜잭션이고 16절 뒤에 ROLLBACK 한다. 17절이 행 수·identity 마지막 값이 실행 전과 같고 public 에 zz_ 객체가 없는지 확인한다.
-- 절:   00 전제 → 01 계약 → 02 결과 → 03 기준선 → 04 변동성 → 05 위험·낙폭 → 06 최대 단일 종목 → 07 회전율·독려 → 08 포메이션 준수 → 09 역할 수행 → 10 비용 → 11 대사 집계 →
--       12 사후 정보 배제 → 13 거부 사례 → 14 합성 단위 → 15 정보 → 16 판정 → 17 흔적
-- 독립 계산 경로: 함수는 체결(execution)·정수 산술·창 함수 없이 계산한다. 이 파일은 원장(cash_ledger)에서 보유와 대금을 다시 만들고, 유리수 비교·두 번 도는 공식·DISTINCT ON·소수 40자리 나눗셈·
--       pos_ret 직접 집계·재귀 CTE 처럼 다른 코드 경로로 기대값을 구한다. 12 배열 함수는 모든 기준일 행에서 12 의 뷰와 같은 값을 내는지 대조한다.
-- 판정의 틀: 규칙마다 위반(불일치) 수를 세어 0 이어야 하고, 음성 사례는 서브트랜잭션에서 값을 일부러 바꿔 보고 출력이 기대대로 변하는지 본다.
--       대상이 이 DB 에 없으면 사례는 "적용 불가" 정보 행이 되고 통과 사례 수에 세지 않는다. 오류를 변화로 세지 않는다 (변이가 오류로 끝나면 FAIL).
-- 출력: 건수와 판정만. 가격·금액·비율 값은 내지 않는다 (FAIL 행도 일치 여부와 건수만 보여 준다).
-- 새 문법: 서브트랜잭션 변이 함수(EXCEPTION 블록으로 되돌리며 값을 돌려주기), 재귀 CTE 로 낙폭 다시 만들기, DISTINCT ON, 유리수 비교로 최대 정수 찾기, generate_series, 동적 SQL 로 열 검사.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\ir 26_season_report_def.sql
\ir 22_reconcile_season_def.sql

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 00–16절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN PERFORM setval('zz_verdict', 1); END $$;

-- 흔적 확인용 스냅샷: 읽거나 일부러 바꿔 보는 표들의 행 수와 identity 마지막 값. 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE OR REPLACE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    c text;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['member', 'season', 'season_path', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'turn_price', 'turn',
                             'formation_change', 'turn_feed', 'reconciliation_log', 'asset_position', 'asset_month_return', 'fx_month', 'month_block'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            v := v || t || '=없음;';
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
        SELECT attname INTO c FROM pg_attribute WHERE attrelid = ('public.' || t)::regclass AND attidentity <> '' LIMIT 1;
        s := CASE WHEN c IS NULL THEN '-' ELSE COALESCE(pg_sequence_last_value(pg_get_serial_sequence('public.' || t, c)::regclass)::text, 'null') END;
        v := v || t || '=' || n || '/' || s || ';';
    END LOOP;
    RETURN v;
END $$;
SET client_min_messages TO warning;
DROP TABLE IF EXISTS pg_temp.zz_before;   -- 정리용 DROP 은 항상 pg_temp. 로 한정한다 (임시 표가 없을 때 public 의 같은 이름 표를 지우지 않게)
RESET client_min_messages;
CREATE TEMP TABLE zz_before AS SELECT pg_temp.zz_snap() AS snap;

BEGIN;

CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,
    item     text    NOT NULL,
    expected text,
    actual   text,
    ok       boolean NOT NULL
);

-- 연습 시즌 찾기: 20·21·22·23·25 파일과 같은 이메일
CREATE TEMP VIEW zz_cur AS
SELECT s.* FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');

-- 판정 프로시저 ------------------------------------------------------------

CREATE FUNCTION pg_temp.cnt(p_sql text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v bigint;
BEGIN
    EXECUTE p_sql INTO v;
    RETURN v;
END $$;

-- 값 비교: 기대와 실제 문자열이 같으면 통과 (둘 다 NULL 이어도 통과)
CREATE FUNCTION pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- 위반 수 비교: 위반 쿼리가 돌려주는 개수가 0 이어야 한다 (값은 내지 않고 개수만)
CREATE FUNCTION pg_temp.check_zero(p_section text, p_item text, p_viol_sql text) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN
    BEGIN
        v := pg_temp.cnt(p_viol_sql)::text;
    EXCEPTION WHEN OTHERS THEN
        v := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 80);
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES (p_section, p_item, '0', v, v = '0');
END $$;

-- 서브트랜잭션 변이: 변이 문장을 실행한 뒤 같은 트랜잭션 안에서 probe(문자열 하나를 돌려주는 쿼리)를 평가하고 되돌린다. 변이가 오류면 'ERROR …' 를 돌려준다.
CREATE FUNCTION pg_temp.zz_mut(p_mutate text, p_probe text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text;
BEGIN
    BEGIN
        EXECUTE p_mutate;
        EXECUTE p_probe INTO v;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            v := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 80);
        END IF;
    END;
    RETURN v;
END $$;

-- 오류 HINT 만 돌려준다 (오류가 없으면 '오류 없음')
CREATE FUNCTION pg_temp.zz_hint(p_sql text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    h text;
BEGIN
    BEGIN
        EXECUTE p_sql;
        RETURN '오류 없음';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS h = PG_EXCEPTION_HINT;
        RETURN COALESCE(h, '-');
    END;
END $$;

-- 변이 사례: probe 가 'true' 를 돌려줘야 통과. p_when 이 0건이면 "적용 불가" 정보 행.
CREATE PROCEDURE pg_temp.mut_chk(p_section text, p_label text, p_mutate text, p_probe text, p_when text DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v text;
BEGIN
    IF p_when IS NOT NULL AND pg_temp.cnt(p_when) = 0 THEN
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES (p_section, '변이 ' || p_label || ' — 적용 불가 (이 DB 에 대상이 없다) (정보)', '(정보)', '적용 불가', true);
        RETURN;
    END IF;
    v := pg_temp.zz_mut(p_mutate, p_probe);
    INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES (p_section, '변이 ' || p_label, 'true', v, v = 'true');
END $$;

-- 거부 사례: 준비 문장(변이) 뒤 호출이 P0001 + 기대한 HINT 로 실패해야 하고, 행 수·identity 가 그대로여야 한다. p_hint 가 '*' 로 시작하면 메시지 앞부분을 본다.
CREATE PROCEDURE pg_temp.rej(p_label text, p_prep text, p_call text, p_hint text, p_when text DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_before text := pg_temp.zz_snap();
    v_after  text;
    v_hint   text;
    v_msg    text;
    v_act    text;
    v_ok     boolean;
BEGIN
    IF p_when IS NOT NULL AND pg_temp.cnt(p_when) = 0 THEN
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('13 거부', '거부 [' || p_hint || '] ' || p_label || ' — 적용 불가 (이 DB 에 대상이 없다) (정보)', '(정보)', '적용 불가', true);
        RETURN;
    END IF;
    BEGIN
        IF p_prep IS NOT NULL THEN EXECUTE p_prep; END IF;
        EXECUTE p_call;
        v_act := '오류 없음';
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
            v_msg := SQLERRM;
            v_act := SQLSTATE || ' ' || COALESCE(v_hint, '-');
            IF left(p_hint, 1) = '*' THEN v_act := SQLSTATE || ' ' || CASE WHEN v_msg LIKE substr(p_hint, 2) || '%' THEN p_hint ELSE '메시지 불일치' END; END IF;
        END IF;
    END;
    v_after := pg_temp.zz_snap();
    v_ok := v_act = 'P0001 ' || p_hint AND v_before = v_after;
    IF v_act = 'P0001 ' || p_hint AND v_before <> v_after THEN
        v_act := v_act || ' (행 수·identity 가 바뀜)';
    END IF;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('13 거부', '거부 [' || p_hint || '] ' || p_label, 'P0001 ' || p_hint || ', 행 수·identity 그대로', v_act, v_ok);
END $$;

-- 00 전제 -------------------------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('격리 수준이 READ COMMITTED 다', 'read committed', current_setting('transaction_isolation')),
    ('22개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '22',
        (SELECT count(*)::text FROM unnest(ARRAY['member', 'season', 'season_path', 'turn', 'turn_price', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup',
                                                 'formation_change', 'turn_feed', 'reconciliation_log', 'asset', 'asset_position', 'asset_month_return', 'fx_month', 'month_block',
                                                 'asset_group', 'month_event', 'asset_month_attention']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('정의 파일을 읽었다 (sr_report·sr_turns·sr_season_positions·sr_players·sr_change_costs·pos_metrics·pos_max_drawdown·pos_block_mkt·formation_costs·recon_code)', '10',
        ((to_regprocedure('pg_temp.sr_report(bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.sr_turns(bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.sr_season_positions(bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.sr_players(bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.sr_change_costs(bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.pos_metrics(numeric[], numeric[], integer, integer, integer)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.pos_max_drawdown(numeric[])') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.pos_block_mkt(bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.formation_costs(bigint)') IS NOT NULL)::int
       + (to_regclass('pg_temp.recon_code') IS NOT NULL)::int)::text),
    ('연습 시즌이 정확히 1개 있다', '1', (SELECT count(*)::text FROM zz_cur)),
    ('현재 턴 ≥ 1 이다 (0턴에는 스냅샷이 없어 성적표가 없다)', 'true', ((SELECT current_turn_no FROM zz_cur) >= 1)::text)
) AS x(item, expected, actual);

INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('00 전제', '현재 DB 이름 (정보)', '(정보)', current_database(), true);

SELECT seq, item, expected, actual, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result
FROM zz_check_result
WHERE section = '00 전제'
ORDER BY seq;

DO $$
DECLARE
    v_fail integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok) INTO v_fail FROM zz_check_result WHERE section = '00 전제';
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-20 전제 실패: 00절 FAIL % 건 (위의 00절 결과를 본다. 01–03 스키마와 10 적재, 12 판정, 20 시즌 시작, 21 팀 구성, 23 턴 진행 1회 이상을 먼저 실행했는지 확인한다)', v_fail;
    END IF;
END $$;

\o /dev/null
-- 기준 표: 현재 상태의 성적표와 보조 목록을 한 번 굳혀 둔다 (변이 사례는 함수를 다시 부른다)
CREATE TEMP TABLE zz_rep AS SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur));
CREATE TEMP TABLE zz_tt  AS SELECT * FROM pg_temp.sr_turns((SELECT id FROM zz_cur));
CREATE TEMP TABLE zz_sp  AS SELECT * FROM pg_temp.sr_season_positions((SELECT id FROM zz_cur));
CREATE TEMP TABLE zz_pl  AS SELECT * FROM pg_temp.sr_players((SELECT id FROM zz_cur));
CREATE TEMP TABLE zz_cc  AS SELECT * FROM pg_temp.sr_change_costs((SELECT id FROM zz_cur));
\o

-- 독립 경로 보조 (함수와 다른 코드) ----------------------------------------------
-- 원장 기반 보유: 주문마다 BUY/SELL 원장 금액 ÷ 체결가로 수량을 다시 만들고(체결 표를 쓰지 않는다) 턴 t 의 보유 = turn_no < t 인 주문의 합. 값 = 수량 × turn_price.
CREATE FUNCTION pg_temp.zz_hold_led(p_season bigint)
RETURNS TABLE (asset_id bigint, turn_no integer, qty numeric, price numeric, val numeric)
LANGUAGE sql STABLE AS $$
    WITH ol AS (
        SELECT o.asset_id, o.turn_no AS ot,
               CASE WHEN l.type = 'BUY' THEN 1 ELSE -1 END * round(abs(l.amount) / (SELECT e.fill_price FROM execution e WHERE e.order_id = o.id LIMIT 1)) AS dq
        FROM cash_ledger l JOIN orders o ON o.id = l.ref_order_id
        WHERE l.season_id = p_season AND l.type IN ('BUY', 'SELL')
    ), t AS (SELECT tn.turn_no FROM turn tn WHERE tn.season_id = p_season)
    SELECT x.asset_id, t.turn_no, x.q, tp.price, x.q * tp.price
    FROM t
    CROSS JOIN LATERAL (SELECT ol.asset_id, sum(ol.dq) AS q FROM ol WHERE ol.ot < t.turn_no GROUP BY ol.asset_id HAVING sum(ol.dq) > 0) x
    JOIN turn_price tp ON tp.season_id = p_season AND tp.asset_id = x.asset_id AND tp.turn_no = t.turn_no
$$;

-- 원장 기반 회전율·독려: 1턴 이후(turn 1..T−1) 주문의 대금(|BUY| + |SELL|). 출발점은 orders.source, 같은 턴 조건은 turn_feed.turn_no.
CREATE FUNCTION pg_temp.zz_flow(p_season bigint)
RETURNS TABLE (n_plus numeric, n_nudge numeric, n_same numeric)
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(sum(abs(l.amount)), 0),
           COALESCE(sum(abs(l.amount)) FILTER (WHERE o.source IN ('RANKING', 'FEED', 'ALERT')), 0),
           COALESCE(sum(abs(l.amount)) FILTER (WHERE o.source = 'RANKING' OR (o.source IN ('FEED', 'ALERT') AND f.turn_no = o.turn_no)), 0)
    FROM cash_ledger l
    JOIN orders o ON o.id = l.ref_order_id
    LEFT JOIN turn_feed f ON f.id = o.turn_feed_id
    WHERE l.season_id = p_season AND l.type IN ('BUY', 'SELL') AND o.turn_no >= 1 AND o.turn_no < (SELECT s.current_turn_no FROM season s WHERE s.id = p_season)
$$;

-- 시즌 시리즈 직접 집계: pos_ret 를 시즌 경로 1..T 의 블록으로 걸러 종목마다 월 수익률·시장 수익률(069500·SPY 50/50, 직접 곱셈)을 만들고 지표를 다시 낸다.
--   낙폭은 재귀 CTE(부 지수) — 12 의 배열 함수(plpgsql 반복)와 다른 코드다. 순위·분류는 이 파일 안에서 다시 한다 (분류 규칙은 12 의 pos_classify 한 곳).
CREATE FUNCTION pg_temp.zz_pos_indep(p_season bigint)
RETURNS TABLE (market text, ticker text, months_used integer, n_up integer, n_down integer, up_p numeric, down_p numeric, vol numeric, mdd numeric, reason text, pos text)
LANGUAGE sql STABLE AS $$
    WITH RECURSIVE s AS (SELECT se.base_date, se.current_turn_no AS t FROM season se WHERE se.id = p_season),
    pth AS (SELECT sp.turn_no, sp.month_block_id FROM season_path sp, s WHERE sp.season_id = p_season AND sp.turn_no BETWEEN 1 AND s.t),
    mk AS (
        SELECT pa.turn_no, 0.5 * kr.r_krw + 0.5 * us.r_krw AS m
        FROM pth pa
        JOIN pos_ret kr ON kr.market = 'KR' AND kr.ticker = '069500' AND kr.month_block_id = pa.month_block_id
        JOIN pos_ret us ON us.market = 'US' AND us.ticker = 'SPY' AND us.month_block_id = pa.month_block_id
    ),
    ser AS (
        SELECT a.id AS asset_id, a.market::text AS market, a.ticker::text AS ticker, pa.turn_no, r.r_krw AS r, mk.m
        FROM s JOIN asset_position ap ON ap.base_date = s.base_date JOIN asset a ON a.id = ap.asset_id
        CROSS JOIN pth pa
        JOIN pos_ret r ON r.asset_id = a.id AND r.month_block_id = pa.month_block_id
        JOIN mk ON mk.turn_no = pa.turn_no
    ),
    wealth (asset_id, t, w, pk) AS (
        SELECT DISTINCT asset_id, 0::smallint, 1::numeric, 1::numeric FROM ser
        UNION ALL
        SELECT w0.asset_id, se.turn_no, w0.w * (1 + se.r), GREATEST(w0.pk, w0.w * (1 + se.r))
        FROM wealth w0 JOIN ser se ON se.asset_id = w0.asset_id AND se.turn_no = w0.t + 1
    ),
    dd AS (SELECT asset_id, ROUND(min((w + (SELECT pad40 FROM pos_k)) / pk - 1), 8) AS mdd FROM wealth GROUP BY asset_id),
    mw (t, w, pk) AS (
        SELECT 0::smallint, 1::numeric, 1::numeric
        UNION ALL
        SELECT mk.turn_no, mw0.w * (1 + mk.m), GREATEST(mw0.pk, mw0.w * (1 + mk.m)) FROM mw mw0 JOIN mk ON mk.turn_no = mw0.t + 1
    ),
    mdd AS (SELECT ROUND(min((w + (SELECT pad40 FROM pos_k)) / pk - 1), 8) AS v FROM mw),
    agg AS (
        SELECT p.asset_id, p.market, p.ticker,
               count(se.r)::integer AS months_used,
               (count(*) FILTER (WHERE se.m >= 0.01))::integer AS n_up,
               (count(*) FILTER (WHERE se.m <= -0.01))::integer AS n_down,
               sum(se.r) FILTER (WHERE se.m >= 0.01) AS sru, sum(se.m) FILTER (WHERE se.m >= 0.01) AS smu,
               sum(se.r) FILTER (WHERE se.m <= -0.01) AS srd, sum(se.m) FILTER (WHERE se.m <= -0.01) AS smd,
               stddev_samp(se.r) AS vr
        FROM (SELECT a.id AS asset_id, a.market::text AS market, a.ticker::text AS ticker
              FROM s JOIN asset_position ap ON ap.base_date = s.base_date JOIN asset a ON a.id = ap.asset_id) p
        LEFT JOIN ser se ON se.asset_id = p.asset_id
        GROUP BY p.asset_id, p.market, p.ticker
    ),
    j AS (
        SELECT g.*, CASE WHEN g.months_used < 6 THEN 'MONTHS' WHEN g.n_up < 3 THEN 'N_UP' WHEN g.n_down < 3 THEN 'N_DOWN'
                         WHEN COALESCE(g.smu, 0) = 0 OR COALESCE(g.smd, 0) = 0 THEN 'ZERO_DEN' END AS reason
        FROM agg g
    ),
    v AS (
        SELECT j.*, ROUND((j.sru + (SELECT pad40 FROM pos_k)) / j.smu, 8) AS up_p, ROUND((j.srd + (SELECT pad40 FROM pos_k)) / j.smd, 8) AS down_p, ROUND(j.vr, 8) AS vol
        FROM j WHERE j.reason IS NULL
    ),
    rk AS (
        SELECT v.asset_id, rank() OVER (ORDER BY v.vol DESC) <= ceil(0.2 * count(*) OVER ()) AS top,
                           rank() OVER (ORDER BY v.vol ASC)  <= ceil(0.2 * count(*) OVER ()) AS bot
        FROM v
    )
    SELECT j.market, j.ticker, j.months_used, j.n_up, j.n_down, v.up_p, v.down_p, v.vol, d.mdd, j.reason,
           CASE WHEN j.reason IS NULL THEN pg_temp.pos_classify(v.up_p, v.down_p, d.mdd, (SELECT mdd.v FROM mdd), rk.top, rk.bot) END
    FROM j LEFT JOIN v ON v.asset_id = j.asset_id LEFT JOIN dd d ON d.asset_id = j.asset_id AND j.reason IS NULL LEFT JOIN rk ON rk.asset_id = j.asset_id
$$;

-- 시즌 실측 대조: 함수의 sr_season_positions 와 위 직접 집계가 다른 행 수 (열마다 null 안전 비교)
CREATE FUNCTION pg_temp.zz_pos_diff(p_season bigint) RETURNS bigint
LANGUAGE sql STABLE AS $$
    SELECT count(*) FROM pg_temp.sr_season_positions(p_season) f
    FULL JOIN pg_temp.zz_pos_indep(p_season) i ON i.market = f.market AND i.ticker = f.ticker
    WHERE f.market IS NULL OR i.market IS NULL
       OR (f.months_used, f.n_up, f.n_down, f.up_participation, f.down_participation, f.volatility, f.max_drawdown, f.reason, f.measured_position)
          IS DISTINCT FROM (i.months_used, i.n_up, i.n_down, i.up_p, i.down_p, i.vol, i.mdd, i.reason, i.pos)
$$;

-- 시즌 실측 판정된 종목 수
CREATE FUNCTION pg_temp.zz_judged(p_season bigint) RETURNS bigint
LANGUAGE sql STABLE AS $$ SELECT count(*) FROM pg_temp.sr_season_positions(p_season) WHERE reason IS NULL $$;

-- 독립 역할 수행: 원장 기반 노출(zz_hold_led)과 독립 실측(zz_pos_indep)으로 일치 가치 ÷ 판정된 가치. 선언은 lineup 구간을 별도 조인으로 찾는다.
CREATE FUNCTION pg_temp.zz_role_indep(p_season bigint) RETURNS numeric
LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN sum(x.val) FILTER (WHERE x.pos IS NOT NULL) > 0
                THEN ROUND(COALESCE(sum(x.val) FILTER (WHERE x.pos = x.declared), 0) / sum(x.val) FILTER (WHERE x.pos IS NOT NULL), 8) END
    FROM (SELECT h.val, lu.declared_position AS declared, i.pos
          FROM pg_temp.zz_hold_led(p_season) h
          JOIN asset a ON a.id = h.asset_id
          JOIN lineup lu ON lu.season_id = p_season AND lu.asset_id = h.asset_id AND lu.start_turn < h.turn_no AND coalesce(lu.end_turn, 32767) >= h.turn_no
          LEFT JOIN pg_temp.zz_pos_indep(p_season) i ON i.market = a.market AND i.ticker = a.ticker) x
$$;

-- 01 계약 -------------------------------------------------------------------
\o /dev/null
DO $$
DECLARE
    v_sid  bigint := (SELECT id FROM zz_cur);
    v_t    smallint := (SELECT current_turn_no FROM zz_cur);
    v_cols text;
    c      text;
    v_n    bigint;
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES ('01 계약', 'sr_report 가 정확히 1행이다', '1', (SELECT count(*)::text FROM zz_rep), (SELECT count(*) FROM zz_rep) = 1);
    SELECT string_agg(attname::text, ',' ORDER BY attnum) INTO v_cols FROM pg_attribute WHERE attrelid = 'zz_rep'::regclass AND attnum > 0 AND NOT attisdropped;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('01 계약', 'sr_report 의 열 이름·순서가 erd D38 표와 같다 (37열)',
            'season_status,is_final,report_turns,path_length,pending_orders,pending_changes,initial_cash,final_value,total_return,monthly_volatility,max_drawdown,baseline_final_value,baseline_return,excess_return,risk_limit,risk_breach_turns,risk_breach_depth,max_single_weight,trade_notional,turnover_annual,formation_deviation,role_fit,role_players_judged,role_players_unjudged,nudge_share,nudge_same_turn_share,total_fee,team_fee,formation_change_fee,platform_nudge_fee,formation_changes,formation_change_orders,changes_without_orders,recon_mismatch_rows,recon_turn_bundles,recon_manual_bundles,recon_out_of_range_rows',
            v_cols,
            v_cols = 'season_status,is_final,report_turns,path_length,pending_orders,pending_changes,initial_cash,final_value,total_return,monthly_volatility,max_drawdown,baseline_final_value,baseline_return,excess_return,risk_limit,risk_breach_turns,risk_breach_depth,max_single_weight,trade_notional,turnover_annual,formation_deviation,role_fit,role_players_judged,role_players_unjudged,nudge_share,nudge_same_turn_share,total_fee,team_fee,formation_change_fee,platform_nudge_fee,formation_changes,formation_change_orders,changes_without_orders,recon_mismatch_rows,recon_turn_bundles,recon_manual_bundles,recon_out_of_range_rows');
    -- 비율 열은 scale 8, 금액 열은 정수
    FOREACH c IN ARRAY ARRAY['total_return', 'monthly_volatility', 'max_drawdown', 'baseline_return', 'excess_return', 'risk_limit', 'risk_breach_depth', 'max_single_weight',
                             'turnover_annual', 'formation_deviation', 'role_fit', 'nudge_share', 'nudge_same_turn_share'] LOOP
        EXECUTE format('SELECT count(*) FROM zz_rep WHERE %I IS NOT NULL AND scale(%I) <> 8', c, c) INTO v_n;
        INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES ('01 계약', '비율 열 ' || c || ' 은 소수 8자리다 (값이 있을 때)', '0', v_n::text, v_n = 0);
    END LOOP;
    FOREACH c IN ARRAY ARRAY['initial_cash', 'final_value', 'baseline_final_value', 'trade_notional', 'total_fee', 'team_fee', 'formation_change_fee', 'platform_nudge_fee'] LOOP
        EXECUTE format('SELECT count(*) FROM zz_rep WHERE %I IS NULL OR %I <> trunc(%I)', c, c, c) INTO v_n;
        INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES ('01 계약', '금액 열 ' || c || ' 은 NULL 이 아닌 원 단위 정수값이다', '0', v_n::text, v_n = 0);
    END LOOP;
    -- 개수 열은 NULL 이 아니다
    EXECUTE 'SELECT count(*) FROM zz_rep WHERE pending_orders IS NULL OR pending_changes IS NULL OR risk_breach_turns IS NULL OR role_players_judged IS NULL OR role_players_unjudged IS NULL
             OR formation_changes IS NULL OR formation_change_orders IS NULL OR changes_without_orders IS NULL OR recon_mismatch_rows IS NULL OR recon_turn_bundles IS NULL
             OR recon_manual_bundles IS NULL OR recon_out_of_range_rows IS NULL' INTO v_n;
    INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES ('01 계약', '개수 열 12개가 모두 NULL 이 아니다', '0', v_n::text, v_n = 0);
    -- sr_turns 는 T 행, turn_no = 1..T
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('01 계약', 'sr_turns 가 T 행이고 turn_no 가 1..T 다', v_t::text || ' 행 · 1..T',
            (SELECT count(*)::text || ' 행 · ' || CASE WHEN count(DISTINCT turn_no) = count(*) AND min(turn_no) = 1 AND max(turn_no) = v_t THEN '1..T' ELSE '어긋남' END FROM zz_tt),
            (SELECT count(*) = v_t AND count(DISTINCT turn_no) = count(*) AND min(turn_no) = 1 AND max(turn_no) = v_t FROM zz_tt));
    SELECT string_agg(attname::text, ',' ORDER BY attnum) INTO v_cols FROM pg_attribute WHERE attrelid = 'zz_tt'::regclass AND attnum > 0 AND NOT attisdropped;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('01 계약', 'sr_turns 의 열 이름·순서', 'turn_no,total_value,period_return,drawdown,baseline_value,target_goalkeeper,target_defender,target_midfielder,target_forward,deviation,max_weight,breach',
            v_cols, v_cols = 'turn_no,total_value,period_return,drawdown,baseline_value,target_goalkeeper,target_defender,target_midfielder,target_forward,deviation,max_weight,breach');
    SELECT string_agg(attname::text, ',' ORDER BY attnum) INTO v_cols FROM pg_attribute WHERE attrelid = 'zz_sp'::regclass AND attnum > 0 AND NOT attisdropped;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('01 계약', 'sr_season_positions 의 열 이름·순서', 'market,ticker,months_used,n_up,n_down,up_participation,down_participation,volatility,max_drawdown,measured_position,reason',
            v_cols, v_cols = 'market,ticker,months_used,n_up,n_down,up_participation,down_participation,volatility,max_drawdown,measured_position,reason');
    SELECT string_agg(attname::text, ',' ORDER BY attnum) INTO v_cols FROM pg_attribute WHERE attrelid = 'zz_pl'::regclass AND attnum > 0 AND NOT attisdropped;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('01 계약', 'sr_players 의 열 이름·순서', 'market,ticker,declared_position,exposure_turns,exposure_value,measured_position,matched',
            v_cols, v_cols = 'market,ticker,declared_position,exposure_turns,exposure_value,measured_position,matched');
    SELECT string_agg(attname::text, ',' ORDER BY attnum) INTO v_cols FROM pg_attribute WHERE attrelid = 'zz_cc'::regclass AND attnum > 0 AND NOT attisdropped;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('01 계약', 'sr_change_costs 의 열 이름·순서', 'change_seq,turn_no,order_count,buy_notional,sell_notional,fee_sum,tax_sum',
            v_cols, v_cols = 'change_seq,turn_no,order_count,buy_notional,sell_notional,fee_sum,tax_sum');
    -- 어떤 출력에도 날짜·시각·월·블록·경로 값이 없고 float 열도 없다
    SELECT count(*) INTO v_n
    FROM pg_attribute a JOIN pg_type ty ON ty.oid = a.atttypid
    WHERE a.attrelid IN ('zz_rep'::regclass, 'zz_tt'::regclass, 'zz_sp'::regclass, 'zz_pl'::regclass, 'zz_cc'::regclass) AND a.attnum > 0 AND NOT a.attisdropped
      AND (ty.typname IN ('date', 'timestamp', 'timestamptz', 'interval', 'time', 'float4', 'float8')
           OR a.attname::text ~ '(date|(^|_)month(_|$)|block|season_path|window|created|updated)');
    INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES ('01 계약', '다섯 출력의 모든 열 중 날짜·시각·월·블록·경로 이름/타입 또는 float 열 수', '0', v_n::text, v_n = 0);
    -- 함수 성질: 모두 STABLE(또는 순수 IMMUTABLE), 쓰기 없음
    SELECT count(*) INTO v_n FROM pg_proc p WHERE p.pronamespace = pg_my_temp_schema() AND p.proname LIKE 'sr\_%' AND p.provolatile = 'v';
    INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES ('01 계약', 'sr_ 함수 중 VOLATILE 인 것 (조회 전용이라 STABLE·IMMUTABLE)', '0', v_n::text, v_n = 0);
    SELECT count(*) INTO v_n FROM pg_proc p WHERE p.pronamespace = pg_my_temp_schema() AND p.proname IN ('pos_block_mkt', 'pos_max_drawdown', 'pos_metrics') AND p.provolatile = 'v';
    INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES ('01 계약', '12 배열 함수 3개 중 VOLATILE 인 것', '0', v_n::text, v_n = 0);
END $$;
\o

-- 02 결과 -------------------------------------------------------------------
\o /dev/null
-- 모든 턴에서 평가액을 원장 잔액(DEPOSIT + turn_no < t 주문의 원장 행) + 체결 재구성 보유 × 가격으로 다시 만든다
SELECT pg_temp.check_zero('02 결과', '모든 턴: 원장 잔액 + 체결 재구성 보유 평가액 ≠ turn.total_value 인 턴 수',
$q$SELECT count(*) FROM turn t JOIN zz_cur c ON c.id = t.season_id
   WHERE t.total_value IS DISTINCT FROM
         (SELECT COALESCE(sum(l.amount), 0) FROM cash_ledger l LEFT JOIN orders o ON o.id = l.ref_order_id
          WHERE l.season_id = t.season_id AND (l.type = 'DEPOSIT' OR o.turn_no < t.turn_no))
       + (SELECT COALESCE(sum(x.q * tp.price), 0)
          FROM (SELECT o.asset_id, sum(CASE o.side WHEN 'BUY' THEN e.quantity ELSE -e.quantity END) AS q
                FROM orders o JOIN execution e ON e.order_id = o.id
                WHERE o.season_id = t.season_id AND o.turn_no < t.turn_no GROUP BY o.asset_id) x
          JOIN turn_price tp ON tp.season_id = t.season_id AND tp.asset_id = x.asset_id AND tp.turn_no = t.turn_no)$q$);
SELECT pg_temp.check_zero('02 결과', 'ADJUSTMENT 원장 행 수 (Phase 0 에는 없다 — 있으면 위 재구성이 어긋난다)',
$q$SELECT count(*) FROM cash_ledger l JOIN zz_cur c ON c.id = l.season_id WHERE l.type = 'ADJUSTMENT'$q$);
SELECT pg_temp.check_eq('02 결과', 'final_value = turn(T).total_value (일치 여부)', 'true',
    (SELECT (r.final_value = t.total_value)::text FROM zz_rep r JOIN zz_cur c ON true JOIN turn t ON t.season_id = c.id AND t.turn_no = c.current_turn_no));
SELECT pg_temp.check_eq('02 결과', 'total_return = ROUND((V_T − C) ÷ C, 8) (numeric ROUND, 일치 여부)', 'true',
    (SELECT (r.total_return = ROUND((r.final_value - c.initial_cash) / c.initial_cash, 8))::text FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('02 결과', 'initial_cash = season.initial_cash · risk_limit = season.risk_limit · report_turns = 현재 턴 · path_length', 'true',
    (SELECT (r.initial_cash = c.initial_cash AND r.risk_limit = c.risk_limit AND r.report_turns = c.current_turn_no AND r.path_length = c.path_length
             AND r.season_status = c.status AND r.is_final = (c.status = 'FINISHED'))::text FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('02 결과', 'sr_turns.total_value = turn.total_value (모든 턴, 어긋난 수 0)', '0',
    (SELECT count(*)::text FROM zz_tt x JOIN zz_cur c ON true JOIN turn t ON t.season_id = c.id AND t.turn_no = x.turn_no WHERE x.total_value <> t.total_value));
-- 기간 수익률(표시용)은 numeric ROUND 와 같다
SELECT pg_temp.check_eq('02 결과', 'sr_turns.period_return = ROUND((V_t − V_{t−1}) ÷ V_{t−1}, 8) 어긋난 수', '0',
    (SELECT count(*)::text FROM zz_tt x JOIN zz_cur c ON true
     JOIN LATERAL (SELECT COALESCE((SELECT t0.total_value FROM turn t0 WHERE t0.season_id = c.id AND t0.turn_no = x.turn_no - 1), c.initial_cash) AS pv) p ON true
     JOIN turn t ON t.season_id = c.id AND t.turn_no = x.turn_no
     WHERE x.period_return <> ROUND((t.total_value - p.pv) / p.pv, 8)));
\o

-- 03 기준선 -------------------------------------------------------------------
\o /dev/null
-- 수량을 "수량 × 가격 × (1 + 요율) ≤ 예산 인 가장 큰 수량"(유리수 비교)으로 따로 구하고, 수수료는 ROUND(가격 × 수량 × 요율, 0) 로 다시 계산한다. 요율은 시장 CASE 로 따로 쓴다.
CREATE TEMP VIEW zz_bq AS
SELECT a.id AS asset_id, CASE WHEN a.market = 'KR' THEN c.fee_rate_kr ELSE c.fee_rate_us + c.fx_spread_rate END AS rate, tp.price AS p0,
       (SELECT max(g) FROM generate_series(0, ceil(c.initial_cash * 0.5 / tp.price)::integer + 1) AS g
        WHERE g * tp.price * (1 + CASE WHEN a.market = 'KR' THEN c.fee_rate_kr ELSE c.fee_rate_us + c.fx_spread_rate END) <= c.initial_cash * 0.5) AS qty
FROM zz_cur c
JOIN asset a ON (a.market = 'KR' AND a.ticker = '069500') OR (a.market = 'US' AND a.ticker = 'SPY')
JOIN turn_price tp ON tp.season_id = c.id AND tp.asset_id = a.id AND tp.turn_no = 0;
SELECT pg_temp.check_eq('03 기준선', '대용 2종 (069500·SPY) 이 시즌 풀에 있다', '2',
    (SELECT count(*)::text FROM zz_bq b JOIN zz_cur c ON true JOIN asset_position ap ON ap.asset_id = b.asset_id AND ap.base_date = c.base_date));
SELECT pg_temp.check_eq('03 기준선', '유리수 비교 수량 = 21 team_qty (어긋난 수)', '0',
    (SELECT count(*)::text FROM zz_bq b JOIN zz_cur c ON true WHERE b.qty <> pg_temp.team_qty(c.initial_cash * 0.5, b.p0, b.rate)));
SELECT pg_temp.check_eq('03 기준선', '독립 B_t (t = 1..T) = sr_turns.baseline_value (어긋난 수)', '0',
    (SELECT count(*)::text FROM zz_tt x JOIN zz_cur c ON true
     WHERE x.baseline_value IS DISTINCT FROM
           (SELECT sum(b.qty * tp.price) FROM zz_bq b JOIN turn_price tp ON tp.season_id = c.id AND tp.asset_id = b.asset_id AND tp.turn_no = x.turn_no)
           + c.initial_cash - (SELECT sum(b.qty * b.p0 + ROUND(b.p0 * b.qty * b.rate, 0)) FROM zz_bq b)));
SELECT pg_temp.check_eq('03 기준선', 'baseline_final_value = sr_turns(T).baseline_value · baseline_return · excess_return = 한 번에 ROUND (일치 여부)', 'true',
    (SELECT (r.baseline_final_value = x.baseline_value
             AND r.baseline_return = ROUND((x.baseline_value - c.initial_cash) / c.initial_cash, 8)
             AND r.excess_return = ROUND((r.final_value - x.baseline_value) / c.initial_cash, 8))::text
     FROM zz_rep r JOIN zz_cur c ON true JOIN zz_tt x ON x.turn_no = c.current_turn_no));
SELECT pg_temp.check_eq('03 기준선', '남은 현금 ≥ 0 (예산 안에서 샀다) — 0 미만인 수', '0',
    (SELECT count(*)::text FROM zz_cur c WHERE c.initial_cash - (SELECT sum(b.qty * b.p0 + ROUND(b.p0 * b.qty * b.rate, 0)) FROM zz_bq b) < 0));
\o

-- 04 변동성 -------------------------------------------------------------------
\o /dev/null
-- 두 번 도는 공식: 평균을 먼저 구하고 √(Σ(r − r̄)² ÷ (n − 1)) 를 sqrt(numeric) 로 8자리 ROUND. r_t = (V_t − V_{t−1}) ÷ V_{t−1}.
SELECT pg_temp.check_eq('04 변동성', 'T ≥ 2 면 √(Σ(r − r̄)² ÷ (n − 1)) 의 8자리와 같다, T = 1 이면 NULL', 'true',
    (SELECT CASE WHEN c.current_turn_no = 1 THEN (r.monthly_volatility IS NULL)::text
                 ELSE (r.monthly_volatility = (SELECT ROUND(sqrt(sum((q.r - q.mu) * (q.r - q.mu)) / (count(*) - 1)), 8)
                                                FROM (SELECT x.r, avg(x.r) OVER () AS mu
                                                      FROM (SELECT (t.total_value - COALESCE(lag(t.total_value) OVER (ORDER BY t.turn_no), c.initial_cash)) / COALESCE(lag(t.total_value) OVER (ORDER BY t.turn_no), c.initial_cash) AS r
                                                            FROM turn t WHERE t.season_id = c.id) x) q))::text END
     FROM zz_rep r JOIN zz_cur c ON true));
-- 합성 손 계산: 수익률 4개 [+10%, −10%, +10%, −10%] 의 표본 표준편차 = √(0.04 × 4 ÷ 3)… 대신 정수 평가액으로 만든 [1000→1100→990→1089→980.1] 의 상대 변화가 정확히 +0.1·−0.1 임을 확인한다.
SELECT pg_temp.check_eq('04 변동성', '합성: 수익률 [0.1, −0.1, 0.1, −0.1] 의 표본 표준편차 8자리 (손 계산 √(0.04/3·… ) = 0.11547005)', '0.11547005',
    (SELECT ROUND(stddev_samp(x), 8)::text FROM (VALUES (0.1::numeric), (-0.1), (0.1), (-0.1)) v(x)));
SELECT pg_temp.check_eq('04 변동성', '합성: 수익률이 하나뿐이면 표본 표준편차가 NULL', 'true',
    (SELECT (stddev_samp(x) IS NULL)::text FROM (VALUES (0.1::numeric)) v(x)));
\o

-- 05 위험·낙폭 -------------------------------------------------------------------
\o /dev/null
SELECT pg_temp.check_eq('05 위험', 'max_drawdown = min(turn.drawdown) · 창 함수 최고점으로 다시 계산한 낙폭이 저장값과 다른 턴 수 0', '0',
    (SELECT count(*)::text FROM (SELECT t.drawdown,
              ROUND(t.total_value / GREATEST(c.initial_cash, max(t.total_value) OVER (ORDER BY t.turn_no)) - 1, 8) AS re
          FROM turn t JOIN zz_cur c ON c.id = t.season_id) x WHERE x.drawdown <> LEAST(0, x.re)));
SELECT pg_temp.check_eq('05 위험', 'report.max_drawdown = min(turn.drawdown)', 'true',
    (SELECT (r.max_drawdown = (SELECT min(t.drawdown) FROM turn t JOIN zz_cur c ON c.id = t.season_id))::text FROM zz_rep r));
SELECT pg_temp.check_eq('05 위험', 'risk_breach_turns · depth = 직접 센 값 (일치 여부)', 'true',
    (SELECT (r.risk_breach_turns = (SELECT count(*) FROM turn t JOIN zz_cur c ON c.id = t.season_id WHERE t.drawdown < c.risk_limit)
             AND r.risk_breach_depth = LEAST(0, (SELECT min(t.drawdown - c.risk_limit) FROM turn t JOIN zz_cur c ON c.id = t.season_id)))::text FROM zz_rep r));
SELECT pg_temp.check_eq('05 위험', 'sr_turns.drawdown = turn.drawdown · breach = (drawdown < risk_limit) 어긋난 수', '0',
    (SELECT count(*)::text FROM zz_tt x JOIN zz_cur c ON true JOIN turn t ON t.season_id = c.id AND t.turn_no = x.turn_no WHERE x.drawdown <> t.drawdown OR x.breach <> (t.drawdown < c.risk_limit)));
-- 변이: 낙폭 = 위험 선언이면 위반이 아니다, 1e-8 더 깊으면 위반이다 (마지막 턴을 바꾼다)
CALL pg_temp.mut_chk('05 위험', '마지막 턴 낙폭 = risk_limit → 이 턴은 위반이 아니다 (같으면 지킴)',
    $m$UPDATE turn t SET drawdown = c.risk_limit FROM zz_cur c WHERE t.season_id = c.id AND t.turn_no = c.current_turn_no$m$,
    $p$SELECT (r.risk_breach_turns = (SELECT count(*) FROM turn t JOIN zz_cur c ON c.id = t.season_id WHERE t.drawdown < c.risk_limit) AND r.risk_breach_turns
                 = (SELECT count(*) FROM turn t JOIN zz_cur c ON c.id = t.season_id WHERE t.turn_no < c.current_turn_no AND t.drawdown < c.risk_limit))::text
       FROM pg_temp.sr_report((SELECT id FROM zz_cur)) r$p$);
CALL pg_temp.mut_chk('05 위험', '마지막 턴 낙폭 = risk_limit − 0.00000001 → 이 턴은 위반이고 깊이가 최소 −0.00000001',
    $m$UPDATE turn t SET drawdown = c.risk_limit - 0.00000001 FROM zz_cur c WHERE t.season_id = c.id AND t.turn_no = c.current_turn_no$m$,
    $p$SELECT (r.risk_breach_turns = 1 + (SELECT count(*) FROM turn t JOIN zz_cur c ON c.id = t.season_id WHERE t.turn_no < c.current_turn_no AND t.drawdown < c.risk_limit)
               AND r.risk_breach_depth <= -0.00000001 AND r.max_drawdown <= r.risk_limit - 0.00000001)::text
       FROM pg_temp.sr_report((SELECT id FROM zz_cur)) r$p$);
\o

-- 06 최대 단일 종목 -------------------------------------------------------------------
\o /dev/null
SELECT pg_temp.check_eq('06 단일 종목', '원장 기반 보유(Σ 종목 수량 × 가격) = turn.holdings_value 인 턴 (모든 턴, 어긋난 수 0)', '0',
    (SELECT count(*)::text FROM turn t JOIN zz_cur c ON c.id = t.season_id
     WHERE t.holdings_value <> COALESCE((SELECT sum(h.val) FROM pg_temp.zz_hold_led(c.id) h WHERE h.turn_no = t.turn_no), 0)));
SELECT pg_temp.check_eq('06 단일 종목', 'max_single_weight = max(턴, 종목) ROUND(수량 × 가격 ÷ 총액, 8) (원장 경로, 보유가 없으면 0)', 'true',
    (SELECT (r.max_single_weight = COALESCE((SELECT max(ROUND(h.val / t.total_value, 8)) FROM pg_temp.zz_hold_led(c.id) h JOIN turn t ON t.season_id = c.id AND t.turn_no = h.turn_no), 0))::text
     FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('06 단일 종목', 'sr_turns.max_weight = 턴별 최대 ROUND(수량 × 가격 ÷ 총액, 8) (어긋난 수)', '0',
    (SELECT count(*)::text FROM zz_tt x JOIN zz_cur c ON true JOIN turn t ON t.season_id = c.id AND t.turn_no = x.turn_no
     WHERE x.max_weight <> COALESCE((SELECT max(ROUND(h.val / t.total_value, 8)) FROM pg_temp.zz_hold_led(c.id) h WHERE h.turn_no = t.turn_no), 0)));
SELECT pg_temp.check_eq('06 단일 종목', '0 ≤ max_single_weight ≤ 1', 'true', (SELECT (max_single_weight BETWEEN 0 AND 1)::text FROM zz_rep));
\o

-- 07 회전율·독려 -------------------------------------------------------------------
\o /dev/null
SELECT pg_temp.check_eq('07 회전·독려', 'trade_notional = 원장 |BUY| + |SELL| (1턴 이후, 보고 범위, 일치 여부)', 'true',
    (SELECT (r.trade_notional = f.n_plus)::text FROM zz_rep r JOIN zz_cur c ON true CROSS JOIN LATERAL pg_temp.zz_flow(c.id) f));
SELECT pg_temp.check_eq('07 회전·독려', 'turnover_annual = ROUND(12 × N ÷ Σ V_t, 8) (원장 분자, 일치 여부)', 'true',
    (SELECT (r.turnover_annual = ROUND(12 * f.n_plus / (SELECT sum(t.total_value) FROM turn t WHERE t.season_id = c.id), 8))::text
     FROM zz_rep r JOIN zz_cur c ON true CROSS JOIN LATERAL pg_temp.zz_flow(c.id) f));
SELECT pg_temp.check_eq('07 회전·독려', 'nudge_share · nudge_same_turn_share = 원장 분자 ÷ 분모 (분모 0 이면 둘 다 NULL)', 'true',
    (SELECT CASE WHEN f.n_plus = 0 THEN (r.nudge_share IS NULL AND r.nudge_same_turn_share IS NULL)::text
                 ELSE (r.nudge_share = ROUND(f.n_nudge / f.n_plus, 8) AND r.nudge_same_turn_share = ROUND(f.n_same / f.n_plus, 8))::text END
     FROM zz_rep r JOIN zz_cur c ON true CROSS JOIN LATERAL pg_temp.zz_flow(c.id) f));
SELECT pg_temp.check_eq('07 회전·독려', '같은 턴 비중 ≤ 경로 비중 (분자가 부분집합)', 'true',
    (SELECT (r.nudge_same_turn_share IS NULL OR r.nudge_same_turn_share <= r.nudge_share)::text FROM zz_rep r));
-- 변이 1: 1턴 이후 주문 하나를 RANKING 으로 → 경로 비중이 그 대금만큼 오르고 같은 턴 비중도 오른다 (RANKING 은 그 턴 화면)
CALL pg_temp.mut_chk('07 회전·독려', '1턴 이후 DIRECT 주문 하나를 RANKING 으로 바꾸면 두 비중이 독립 계산대로 오른다',
    $m$UPDATE orders SET source = 'RANKING' WHERE id = (SELECT o.id FROM orders o JOIN zz_cur c ON c.id = o.season_id
                                                       WHERE o.turn_no BETWEEN 1 AND c.current_turn_no - 1 AND o.source = 'DIRECT' AND o.turn_feed_id IS NULL ORDER BY o.id LIMIT 1)$m$,
    $p$SELECT (r.nudge_share = ROUND(f.n_nudge / f.n_plus, 8) AND r.nudge_same_turn_share = ROUND(f.n_same / f.n_plus, 8)
               AND r.nudge_share > (SELECT z.nudge_share FROM zz_rep z) AND r.nudge_same_turn_share > (SELECT z.nudge_same_turn_share FROM zz_rep z))::text
       FROM pg_temp.sr_report((SELECT id FROM zz_cur)) r CROSS JOIN pg_temp.zz_flow((SELECT id FROM zz_cur)) f$p$,
    $w$SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no BETWEEN 1 AND c.current_turn_no - 1 AND o.source = 'DIRECT' AND o.turn_feed_id IS NULL$w$);
-- 변이 2: FEED·ALERT 주문의 피드 턴을 주문 턴 − 1 로 → 경로 비중은 그대로이고 같은 턴 비중만 줄어든다
CALL pg_temp.mut_chk('07 회전·독려', 'FEED·ALERT 주문의 피드 턴을 주문 턴 − 1 로 옮기면 같은 턴 비중만 줄어든다 (경로 비중 그대로)',
    $m$UPDATE turn_feed SET turn_no = turn_no - 1, seq = (SELECT COALESCE(max(f2.seq), 0) + 1 FROM turn_feed f2 WHERE f2.season_id = turn_feed.season_id AND f2.turn_no = turn_feed.turn_no - 1)
       WHERE id = (SELECT o.turn_feed_id FROM orders o JOIN zz_cur c ON c.id = o.season_id
                   WHERE o.turn_no BETWEEN 2 AND c.current_turn_no - 1 AND o.source IN ('FEED', 'ALERT') ORDER BY o.id LIMIT 1)$m$,
    $p$SELECT (r.nudge_share = (SELECT z.nudge_share FROM zz_rep z) AND r.nudge_same_turn_share < (SELECT z.nudge_same_turn_share FROM zz_rep z)
               AND r.nudge_same_turn_share = ROUND(f.n_same / f.n_plus, 8))::text
       FROM pg_temp.sr_report((SELECT id FROM zz_cur)) r CROSS JOIN pg_temp.zz_flow((SELECT id FROM zz_cur)) f$p$,
    $w$SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no BETWEEN 2 AND c.current_turn_no - 1 AND o.source IN ('FEED', 'ALERT')$w$);
\o

-- 08 포메이션 준수 -------------------------------------------------------------------
\o /dev/null
-- 턴 시작 시점 포메이션을 DISTINCT ON 으로 따로 찾고, 턴마다 (분자 + pad40) ÷ 분모 를 소수 40자리로 나눠 더한 뒤 ÷ T 를 8자리로 반올림한다 (D35 방식).
CREATE FUNCTION pg_temp.zz_dev_indep(p_season bigint) RETURNS numeric
LANGUAGE sql STABLE AS $$
    SELECT ROUND(sum((x.a + (SELECT pad40 FROM pos_k)) / x.b) / count(*), 8)
    FROM (SELECT abs(100 * t.goalkeeper_value - f.g * t.total_value) + abs(100 * t.defender_value - f.d * t.total_value)
               + abs(100 * t.midfielder_value - f.m * t.total_value) + abs(100 * t.forward_value - f.f * t.total_value) AS a,
                 100 * t.total_value AS b
          FROM turn t
          JOIN (SELECT DISTINCT ON (t2.turn_no) t2.turn_no,
                       COALESCE(fc.after_goalkeeper, s.start_goalkeeper) AS g, COALESCE(fc.after_defender, s.start_defender) AS d,
                       COALESCE(fc.after_midfielder, s.start_midfielder) AS m, COALESCE(fc.after_forward, s.start_forward) AS f
                FROM turn t2 JOIN season s ON s.id = t2.season_id
                LEFT JOIN formation_change fc ON fc.season_id = t2.season_id AND fc.turn_no < t2.turn_no
                WHERE t2.season_id = p_season
                ORDER BY t2.turn_no, fc.id DESC NULLS LAST) f ON f.turn_no = t.turn_no
          WHERE t.season_id = p_season) x
$$;
SELECT pg_temp.check_eq('08 준수', 'formation_deviation = 소수 40자리 나눗셈 합의 8자리 (DISTINCT ON 으로 찾은 기준 포메이션, 일치 여부)', 'true',
    (SELECT (r.formation_deviation = pg_temp.zz_dev_indep(c.id))::text FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('08 준수', '0 ≤ formation_deviation ≤ 2', 'true', (SELECT (formation_deviation BETWEEN 0 AND 2)::text FROM zz_rep));
SELECT pg_temp.check_eq('08 준수', 'sr_turns 목표 포메이션 4개의 합 = 100 · 변경 전 턴은 시작 포메이션 (어긋난 수)', '0',
    (SELECT count(*)::text FROM zz_tt x JOIN zz_cur c ON true
     WHERE x.target_goalkeeper + x.target_defender + x.target_midfielder + x.target_forward <> 100
        OR (NOT EXISTS (SELECT 1 FROM formation_change fc WHERE fc.season_id = c.id AND fc.turn_no < x.turn_no)
            AND (x.target_goalkeeper, x.target_defender, x.target_midfielder, x.target_forward) <> (c.start_goalkeeper, c.start_defender, c.start_midfielder, c.start_forward))));
SELECT pg_temp.check_eq('08 준수', 'sr_turns.deviation = ROUND(분자 ÷ 분모, 8) 턴별 (소수 40자리 경로, 어긋난 수)', '0',
    (SELECT count(*)::text FROM zz_tt x JOIN zz_cur c ON true JOIN turn t ON t.season_id = c.id AND t.turn_no = x.turn_no
     WHERE x.deviation <> ROUND((abs(100 * t.goalkeeper_value - x.target_goalkeeper * t.total_value) + abs(100 * t.defender_value - x.target_defender * t.total_value)
                               + abs(100 * t.midfielder_value - x.target_midfielder * t.total_value) + abs(100 * t.forward_value - x.target_forward * t.total_value)
                               + (SELECT pad40 FROM pos_k)) / (100 * t.total_value), 8)));
-- 변이: 시즌 시작 포메이션을 수비 +5 · 공격 −5 로 옮기면 (그 포메이션이 기준인 턴이 있으므로) 독립 계산대로 값이 달라진다
CALL pg_temp.mut_chk('08 준수', '시작 포메이션을 수비 +5 · 공격 −5 로 옮기면 준수 값이 독립 계산대로 달라진다',
    $m$UPDATE season SET start_defender = start_defender + 5, start_forward = start_forward - 5 WHERE id = (SELECT id FROM zz_cur)$m$,
    $p$SELECT (r.formation_deviation = pg_temp.zz_dev_indep(r0.id) AND r.formation_deviation <> (SELECT z.formation_deviation FROM zz_rep z))::text
       FROM pg_temp.sr_report((SELECT id FROM zz_cur)) r CROSS JOIN zz_cur r0$p$,
    $w$SELECT count(*) FROM zz_cur WHERE start_forward >= 5$w$);
-- 변이: 보고 범위의 마지막 전술 변경(같은 턴에 변경이 여럿이면 앞선 변경은 가려진다)의 after 를 옮기면 변경 뒤 턴의 기준 포메이션이 이동한다 (변경이 보고 범위에 있을 때). 수비 +5·공격 −5 가 우연히 값을 안 바꿀 수 있어(실제 비중이
--       두 목표 사이에 있으면 상쇄) 이어서 골키퍼 +5·공격 −5 를 더한 상태도 본다: 둘 다 독립 계산과 같아야 하고 둘 중 하나는 값이 달라야 한다.
CREATE FUNCTION pg_temp.zz_dev_ok(p_ref numeric) RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT (r.formation_deviation = pg_temp.zz_dev_indep(c.id))::text || '|' || (r.formation_deviation <> p_ref)::text
    FROM zz_cur c CROSS JOIN pg_temp.sr_report(c.id) r
$$;
CALL pg_temp.mut_chk('08 준수', '보고 범위의 마지막 전술 변경의 after 를 옮기면(수비 +5 · 공격 −5, 이어서 골키퍼 +5 · 공격 −5) 변경 뒤 턴의 기준이 이동해 독립 계산과 같게 달라진다',
    $m$UPDATE formation_change SET after_defender = after_defender + 5, after_forward = after_forward - 5
       WHERE id = (SELECT fc.id FROM formation_change fc JOIN zz_cur c ON c.id = fc.season_id WHERE fc.turn_no < c.current_turn_no ORDER BY fc.id DESC LIMIT 1)$m$,
    $p$SELECT (split_part(a.v, '|', 1) = 'true' AND split_part(b.v, '|', 1) = 'true' AND (split_part(a.v, '|', 2) = 'true' OR split_part(b.v, '|', 2) = 'true'))::text
       FROM (SELECT pg_temp.zz_dev_ok((SELECT z.formation_deviation FROM zz_rep z)) AS v) a
       CROSS JOIN (SELECT pg_temp.zz_mut(
           $mm$UPDATE formation_change SET after_goalkeeper = after_goalkeeper + 5, after_forward = after_forward - 5
               WHERE id = (SELECT fc.id FROM formation_change fc JOIN zz_cur c ON c.id = fc.season_id WHERE fc.turn_no < c.current_turn_no ORDER BY fc.id DESC LIMIT 1)$mm$,
           $pp$SELECT pg_temp.zz_dev_ok((SELECT z.formation_deviation FROM zz_rep z))$pp$) AS v) b$p$,
    $w$SELECT count(*) FROM formation_change fc JOIN zz_cur c ON c.id = fc.season_id WHERE fc.turn_no < c.current_turn_no AND fc.after_forward >= 10$w$);
\o

-- 09 역할 수행 -------------------------------------------------------------------
\o /dev/null
-- ① 12 배열 함수 ≡ 12 뷰: 모든 기준일 행에서 pos_metrics(창 배열) = pos_metric 의 값 11개, pos_max_drawdown(시장 배열) = pos_dd('M:'), pos_block_mkt = pos_mkt.r_mkt
CREATE TEMP TABLE zz_win AS
SELECT m.asset_position_id, m.base_date, m.months_used, m.n_up, m.n_down, m.up_raw, m.down_raw, m.vol_raw, m.up_participation, m.down_participation, m.volatility, m.max_drawdown, m.reason,
       (SELECT array_agg(s.r_krw ORDER BY s.month_start) FROM pos_series s WHERE s.sid = 'A:' || m.asset_position_id) AS ra,
       (SELECT array_agg(s.r_mkt ORDER BY s.month_start) FROM pos_series s WHERE s.sid = 'A:' || m.asset_position_id) AS ma
FROM pos_metric m;
SELECT pg_temp.check_eq('09 역할', '① 비교한 기준일 행 수 (정보: 1 이상)', 'true', (SELECT (count(*) >= 1)::text FROM zz_win));
SELECT pg_temp.check_eq('09 역할', '① pos_metrics(창 배열, 24·5·5) ≠ pos_metric 인 행 수 (months_used·n_up·n_down·up_raw·down_raw·vol_raw·up·down·vol·mdd·reason)', '0',
    (SELECT count(*)::text FROM zz_win w CROSS JOIN pos_k k
     CROSS JOIN LATERAL pg_temp.pos_metrics(w.ra, w.ma, k.min_months, k.min_up, k.min_down) x
     WHERE (x.months_used, x.n_up, x.n_down, x.up_raw, x.down_raw, x.vol_raw, x.up, x.down, x.vol, x.mdd, x.reason)
           IS DISTINCT FROM (w.months_used, w.n_up, w.n_down, w.up_raw, w.down_raw, w.vol_raw, w.up_participation, w.down_participation, w.volatility, w.max_drawdown, w.reason)));
SELECT pg_temp.check_eq('09 역할', '① pos_max_drawdown(시장 창 배열) ≠ pos_dd(M:기준일) 인 기준일 수', '0',
    (SELECT count(*)::text FROM (SELECT d.sid, d.max_drawdown AS dd,
              pg_temp.pos_max_drawdown((SELECT array_agg(s.r_mkt ORDER BY s.month_start) FROM pos_series s WHERE s.sid = d.sid)) AS fn
          FROM pos_dd d WHERE d.sid LIKE 'M:%') z WHERE z.dd IS DISTINCT FROM z.fn));
SELECT pg_temp.check_eq('09 역할', '① pos_max_drawdown(종목 창 배열) ≠ pos_dd(A:행) 인 행 수', '0',
    (SELECT count(*)::text FROM (SELECT d.max_drawdown AS dd,
              pg_temp.pos_max_drawdown((SELECT array_agg(s.r_krw ORDER BY s.month_start) FROM pos_series s WHERE s.sid = d.sid)) AS fn
          FROM pos_dd d WHERE d.sid LIKE 'A:%') z WHERE z.dd IS DISTINCT FROM z.fn));
SELECT pg_temp.check_eq('09 역할', '① pos_block_mkt(월 블록) ≠ pos_mkt.r_mkt 인 (기준일, 블록) 수', '0',
    (SELECT count(*)::text FROM pos_mkt m WHERE pg_temp.pos_block_mkt(m.month_block_id) IS DISTINCT FROM m.r_mkt));
-- ② 합성 시리즈 손 계산 (시즌 최소 6·3·3). m = [.01, .02, .03, −.01, −.02, −.03], r = [.02, .04, .06, −.005, −.01, −.015]
--     오른 달 3·빠진 달 3 (경계 .01 포함), up = .12 ÷ .06 = 2, down = −.03 ÷ −.06 = .5, 변동성 = √0.00092 = 0.03033150, 낙폭 = .995 × .99 × .985 − 1 = −0.02972575
SELECT pg_temp.check_eq('09 역할', '② 합성 6달(오른 3·빠진 3): 달 수·오른·빠진·참여율 둘·변동성·낙폭·사유', '6|3|3|2.00000000|0.50000000|0.03033150|-0.02972575|-',
    (SELECT x.months_used || '|' || x.n_up || '|' || x.n_down || '|' || x.up || '|' || x.down || '|' || x.vol || '|' || x.mdd || '|' || COALESCE(x.reason, '-')
     FROM pg_temp.pos_metrics(ARRAY[0.02, 0.04, 0.06, -0.005, -0.01, -0.015]::numeric[], ARRAY[0.01, 0.02, 0.03, -0.01, -0.02, -0.03]::numeric[], 6, 3, 3) x));
-- ② 보강 (NULL 원소·길이 불일치): 두 값이 모두 있는 달만 센다. 변동성·참여율·낙폭도 거른 달만으로 계산된다 (mdd 는 거른 배열의 pos_max_drawdown 과 같다)
SELECT pg_temp.check_eq('09 역할', '② 합성: r 에 NULL 원소(3개 중 1개) → 오른 달 1·쓴 달 2, mdd 가 거른 배열과 같다', '1|2|true',
    (SELECT x.n_up || '|' || x.months_used || '|' || (x.mdd IS NOT DISTINCT FROM pg_temp.pos_max_drawdown(ARRAY[0.1, -0.2]::numeric[]))
     FROM pg_temp.pos_metrics(ARRAY[0.1, NULL, -0.2]::numeric[], ARRAY[0.02, 0.03, -0.02]::numeric[], 1, 0, 0) x));
SELECT pg_temp.check_eq('09 역할', '② 합성: r 이 m 보다 짧다(길이 2·3) → 쓴 달 2, mdd 가 거른 배열과 같다', '2|true',
    (SELECT x.months_used || '|' || (x.mdd IS NOT DISTINCT FROM pg_temp.pos_max_drawdown(ARRAY[0.1, -0.2]::numeric[]))
     FROM pg_temp.pos_metrics(ARRAY[0.1, -0.2]::numeric[], ARRAY[0.02, -0.03, -0.02]::numeric[], 1, 0, 0) x));
SELECT pg_temp.check_eq('09 역할', '② 합성: 오른 달 2개(경계 바로 아래 .0099 포함) → N_UP, 8컬럼 NULL', 'N_UP|true',
    (SELECT x.reason || '|' || (x.up IS NULL AND x.down IS NULL AND x.vol IS NULL AND x.mdd IS NULL)
     FROM pg_temp.pos_metrics(ARRAY[0.02, 0.04, 0.06, -0.005, -0.01, -0.015]::numeric[], ARRAY[0.01, 0.02, 0.0099, -0.01, -0.02, -0.03]::numeric[], 6, 3, 3) x));
SELECT pg_temp.check_eq('09 역할', '② 합성: 빠진 달 2개(−.0099 는 중립) → N_DOWN', 'N_DOWN',
    (SELECT x.reason FROM pg_temp.pos_metrics(ARRAY[0.02, 0.04, 0.06, -0.005, -0.01, -0.015]::numeric[], ARRAY[0.01, 0.02, 0.03, -0.0099, -0.02, -0.03]::numeric[], 6, 3, 3) x));
SELECT pg_temp.check_eq('09 역할', '② 합성: 달이 2개면 MONTHS (표본 부족을 0 으로 덮지 않는다)', 'MONTHS|2',
    (SELECT x.reason || '|' || x.months_used FROM pg_temp.pos_metrics(ARRAY[0.02, -0.01]::numeric[], ARRAY[0.02, -0.02]::numeric[], 6, 3, 3) x));
SELECT pg_temp.check_eq('09 역할', '② 합성: 오른·빠진 달이 없으면(최소 0) ZERO_DEN', 'ZERO_DEN',
    (SELECT x.reason FROM pg_temp.pos_metrics(ARRAY[0.02, 0.01, 0.03]::numeric[], ARRAY[0.001, 0.002, 0.003]::numeric[], 1, 0, 0) x));
SELECT pg_temp.check_eq('09 역할', '② 합성: 빈 배열·NULL 은 MONTHS 0달이고 낙폭은 NULL', 'MONTHS|0|true',
    (SELECT x.reason || '|' || x.months_used || '|' || (pg_temp.pos_max_drawdown(NULL) IS NULL AND pg_temp.pos_max_drawdown(ARRAY[]::numeric[]) IS NULL)
     FROM pg_temp.pos_metrics(NULL, NULL, 6, 3, 3) x));
SELECT pg_temp.check_eq('09 역할', '② 합성: 낙폭 [+10%, −20%, +5%] = 0.8 ÷ 1 − 1 = −0.20000000 (최고점은 W0 포함)', '-0.20000000',
    (SELECT pg_temp.pos_max_drawdown(ARRAY[0.1, -0.2, 0.05]::numeric[])::text));
SELECT pg_temp.check_eq('09 역할', '② 합성: 계속 오르면 낙폭 0 (W0 포함)', '0.00000000',
    (SELECT pg_temp.pos_max_drawdown(ARRAY[0.1, 0.2]::numeric[])::text));
-- ③ 시즌 실측을 pos_ret 직접 집계·재귀 CTE 로 다시 계산해 대조 (현재 상태)
SELECT pg_temp.check_eq('09 역할', '③ 시즌 실측 sr_season_positions ≠ 직접 집계인 풀 종목 수', '0', (SELECT pg_temp.zz_pos_diff(id)::text FROM zz_cur));
SELECT pg_temp.check_eq('09 역할', '③ 풀 종목 수 = 기준일 풀의 행 수 · months_used ≤ T', 'true',
    (SELECT ((SELECT count(*) FROM zz_sp) = (SELECT count(*) FROM asset_position ap WHERE ap.base_date = c.base_date)
             AND (SELECT max(months_used) FROM zz_sp) <= c.current_turn_no)::text FROM zz_cur c));
SELECT pg_temp.check_eq('09 역할', '③ role_fit · 판정된·판정 불능 선수 수 = 원장 경로 독립 계산 (일치 여부)', 'true',
    (SELECT (r.role_fit IS NOT DISTINCT FROM pg_temp.zz_role_indep(c.id)
             AND r.role_players_judged = (SELECT count(DISTINCT a.id) FROM pg_temp.zz_hold_led(c.id) h JOIN asset a ON a.id = h.asset_id
                                           JOIN pg_temp.zz_pos_indep(c.id) i ON i.market = a.market AND i.ticker = a.ticker WHERE i.pos IS NOT NULL)
             AND r.role_players_unjudged = (SELECT count(DISTINCT a.id) FROM pg_temp.zz_hold_led(c.id) h JOIN asset a ON a.id = h.asset_id
                                             JOIN pg_temp.zz_pos_indep(c.id) i ON i.market = a.market AND i.ticker = a.ticker WHERE i.pos IS NULL))::text
     FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('09 역할', '③ sr_players: 노출 가치 합 = Σ 원장 보유 평가액 · 노출 턴 합 = 보유 (종목, 턴) 수', 'true',
    (SELECT ((SELECT COALESCE(sum(exposure_value), 0) FROM zz_pl) = (SELECT COALESCE(sum(val), 0) FROM pg_temp.zz_hold_led(c.id))
             AND (SELECT COALESCE(sum(exposure_turns), 0) FROM zz_pl) = (SELECT count(*) FROM pg_temp.zz_hold_led(c.id)))::text FROM zz_cur c));
-- ④ 변이: 시장 대용(069500·SPY)의 시즌 경로 앞 3개월 수익률을 −5% 로 바꾸면 빠진 달이 늘어 실측이 판정되는 종목이 생긴다 (T ≥ 6). 판정된 쪽도 직접 집계와 같다.
CALL pg_temp.mut_chk('09 역할', '대용 2종의 경로 앞 3개월을 −5% 로 바꾸면 판정되는 종목이 생기고 직접 집계와 같다 (T ≥ 6)',
    $m$UPDATE asset_month_return SET local_return = -0.05
       WHERE asset_id IN (SELECT a.id FROM asset a WHERE (a.market = 'KR' AND a.ticker = '069500') OR (a.market = 'US' AND a.ticker = 'SPY'))
         AND month_block_id IN (SELECT sp.month_block_id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no BETWEEN 1 AND 3)$m$,
    $p$SELECT (pg_temp.zz_judged(c.id) >= 1 AND pg_temp.zz_pos_diff(c.id) = 0
               AND (SELECT r.role_fit IS NOT DISTINCT FROM pg_temp.zz_role_indep(c.id) FROM pg_temp.sr_report(c.id) r))::text FROM zz_cur c$p$,
    $w$SELECT count(*) FROM zz_cur WHERE current_turn_no >= 6$w$);
-- ④ 변이: 위 변이에 이어 노출 종목의 선언을 실측과 같게 바꾸면 role_fit 이 1 이 되고(오름), 직접 계산과 같다
CALL pg_temp.mut_chk('09 역할', '판정된 노출 종목의 선언 포지션을 실측과 같게 바꾸면 role_fit 이 1.00000000 이 되고 직접 계산과 같다 (T ≥ 6)',
    $m$UPDATE asset_month_return SET local_return = -0.05
       WHERE asset_id IN (SELECT a.id FROM asset a WHERE (a.market = 'KR' AND a.ticker = '069500') OR (a.market = 'US' AND a.ticker = 'SPY'))
         AND month_block_id IN (SELECT sp.month_block_id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no BETWEEN 1 AND 3);
       UPDATE lineup lu SET declared_position = i.pos
       FROM zz_cur c, asset a, pg_temp.zz_pos_indep(c.id) i
       WHERE lu.season_id = c.id AND a.id = lu.asset_id AND i.market = a.market AND i.ticker = a.ticker AND i.pos IS NOT NULL$m$,
    $p$SELECT (r.role_fit = 1 AND r.role_fit IS NOT DISTINCT FROM pg_temp.zz_role_indep(c.id) AND r.role_players_judged >= 1)::text
       FROM zz_cur c CROSS JOIN pg_temp.sr_report(c.id) r$p$,
    $w$SELECT count(*) FROM zz_cur WHERE current_turn_no >= 6$w$);
\o

-- 10 비용 -------------------------------------------------------------------
\o /dev/null
SELECT pg_temp.check_eq('10 비용', 'total_fee = −Σ 원장 FEE (보고 범위, 0원 수수료는 원장 행이 없다) · team_fee = 0턴 · platform_nudge_fee = RANKING·ALERT 의 1턴 이후 수수료', 'true',
    (SELECT (r.total_fee = COALESCE((SELECT -sum(l.amount) FROM cash_ledger l JOIN orders o ON o.id = l.ref_order_id WHERE l.season_id = c.id AND l.type = 'FEE' AND o.turn_no < c.current_turn_no), 0)
             AND r.team_fee = COALESCE((SELECT -sum(l.amount) FROM cash_ledger l JOIN orders o ON o.id = l.ref_order_id WHERE l.season_id = c.id AND l.type = 'FEE' AND o.turn_no = 0), 0)
             AND r.platform_nudge_fee = COALESCE((SELECT -sum(l.amount) FROM cash_ledger l JOIN orders o ON o.id = l.ref_order_id
                                                  WHERE l.season_id = c.id AND l.type = 'FEE' AND o.turn_no BETWEEN 1 AND c.current_turn_no - 1 AND o.source IN ('RANKING', 'ALERT')), 0))::text
     FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('10 비용', 'total_fee = Σ execution.fee (두 번째 경로, 보고 범위)', 'true',
    (SELECT (r.total_fee = COALESCE((SELECT sum(e.fee) FROM execution e JOIN orders o ON o.id = e.order_id WHERE o.season_id = c.id AND o.turn_no < c.current_turn_no), 0))::text FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('10 비용', 'formation_change_fee = 변경에 묶인 주문의 execution.fee 직접 조인 합 (보고 범위) = 25 formation_costs 합', 'true',
    (SELECT (r.formation_change_fee = COALESCE((SELECT sum(e.fee) FROM orders o JOIN execution e ON e.order_id = o.id
                                                 WHERE o.season_id = c.id AND o.formation_change_id IS NOT NULL AND o.turn_no < c.current_turn_no), 0)
             AND r.formation_change_fee = COALESCE((SELECT sum(fc.fee_sum) FROM pg_temp.formation_costs(c.id) fc WHERE fc.turn_no < c.current_turn_no), 0))::text
     FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('10 비용', 'formation_changes · formation_change_orders · changes_without_orders = 직접 센 값', 'true',
    (SELECT (r.formation_changes = (SELECT count(*) FROM formation_change fc WHERE fc.season_id = c.id AND fc.turn_no < c.current_turn_no)
             AND r.formation_change_orders = (SELECT count(*) FROM orders o WHERE o.season_id = c.id AND o.formation_change_id IS NOT NULL AND o.turn_no < c.current_turn_no)
             AND r.changes_without_orders = (SELECT count(*) FROM formation_change fc WHERE fc.season_id = c.id AND fc.turn_no < c.current_turn_no
                                              AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.formation_change_id = fc.id)))::text
     FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('10 비용', 'pending_orders · pending_changes = turn_no = T 인 주문·변경 수', 'true',
    (SELECT (r.pending_orders = (SELECT count(*) FROM orders o WHERE o.season_id = c.id AND o.turn_no = c.current_turn_no)
             AND r.pending_changes = (SELECT count(*) FROM formation_change fc WHERE fc.season_id = c.id AND fc.turn_no = c.current_turn_no))::text FROM zz_rep r JOIN zz_cur c ON true));
SELECT pg_temp.check_eq('10 비용', 'execution.tax 가 0 이 아닌 체결 수 (세금은 EXT — 성적표가 세금을 빼지 않는다)', '0',
    (SELECT count(*)::text FROM execution e JOIN orders o ON o.id = e.order_id JOIN zz_cur c ON c.id = o.season_id WHERE e.tax <> 0));
SELECT pg_temp.check_eq('10 비용', 'sr_change_costs: 모든 변경의 turn_no < T · change_seq = 1..n · 합 = formation_change_fee', 'true',
    (SELECT ((SELECT count(*) FROM zz_cc WHERE turn_no >= c.current_turn_no) = 0
             AND (SELECT COALESCE(string_agg(change_seq::text, ',' ORDER BY change_seq), '') FROM zz_cc)
               = (SELECT COALESCE(string_agg(g::text, ',' ORDER BY g), '') FROM generate_series(1, (SELECT count(*) FROM zz_cc)::integer) g)
             AND (SELECT COALESCE(sum(fee_sum), 0) FROM zz_cc) = r.formation_change_fee)::text FROM zz_rep r JOIN zz_cur c ON true));
\o

-- 11 대사 집계 -------------------------------------------------------------------
\o /dev/null
-- 묶음 구분의 독립 경로: (turn_no, attempt_no) 마다 check_code 집합이 22 의 recon_code 17개 전체면 턴 전환, 수동 15개(manual)면 수동 검사
CREATE FUNCTION pg_temp.zz_recon_indep(p_season bigint) RETURNS TABLE (mismatch bigint, turn_b bigint, manual_b bigint, out_range bigint)
LANGUAGE sql STABLE AS $$
    WITH b AS (
        SELECT rl.turn_no, rl.attempt_no, array_agg(rl.check_code::text ORDER BY rl.check_code) AS codes
        FROM reconciliation_log rl WHERE rl.season_id = p_season GROUP BY rl.turn_no, rl.attempt_no
    )
    SELECT (SELECT count(*) FROM reconciliation_log rl WHERE rl.season_id = p_season AND rl.status = 'MISMATCH'),
           (SELECT count(*) FROM b WHERE b.codes = (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c)),
           (SELECT count(*) FROM b WHERE b.codes = (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.manual)),
           (SELECT count(*) FROM reconciliation_log rl JOIN season s ON s.id = rl.season_id WHERE rl.season_id = p_season AND rl.turn_no > s.path_length)
$$;
SELECT pg_temp.check_eq('11 대사', '대사 4개 열 = 코드 집합 17/15 로 센 독립 값 (일치 여부)', 'true',
    (SELECT (r.recon_mismatch_rows = z.mismatch AND r.recon_turn_bundles = z.turn_b AND r.recon_manual_bundles = z.manual_b AND r.recon_out_of_range_rows = z.out_range)::text
     FROM zz_rep r JOIN zz_cur c ON true CROSS JOIN LATERAL pg_temp.zz_recon_indep(c.id) z));
SELECT pg_temp.check_eq('11 대사', '행 수 기준 묶음(17행·15행) 개수 + 그 밖의 크기 묶음 = 전체 묶음 (함수가 세지 못한 묶음 없음 — 정보 아님)', 'true',
    (SELECT ((SELECT count(*) FROM (SELECT 1 FROM reconciliation_log rl JOIN zz_cur c ON c.id = rl.season_id GROUP BY rl.turn_no, rl.attempt_no) q)
            = r.recon_turn_bundles + r.recon_manual_bundles + (SELECT count(*) FROM (SELECT count(*) AS n FROM reconciliation_log rl JOIN zz_cur c ON c.id = rl.season_id
                                                                                       GROUP BY rl.turn_no, rl.attempt_no) q WHERE q.n NOT IN (15, 17)))::text FROM zz_rep r));
-- 변이 (UPDATE 만 쓴다 — INSERT 는 identity 를 소비해 흔적 확인을 깨뜨린다): 로그 한 행의 상태를 MISMATCH 로 바꾸면 불일치 행이 +1 이고 묶음 수는 그대로다 (서브트랜잭션, 되돌림).
CALL pg_temp.mut_chk('11 대사', '로그의 MATCH 한 행을 MISMATCH 로 바꾸면 recon_mismatch_rows 가 +1 이고 17행·15행 묶음 수는 그대로다',
    $m$UPDATE reconciliation_log SET status = 'MISMATCH', mismatch_count = 1, classification = 'INVESTIGATE'
       WHERE id = (SELECT min(rl.id) FROM reconciliation_log rl JOIN zz_cur c ON c.id = rl.season_id WHERE rl.status = 'MATCH')$m$,
    $p$SELECT (r.recon_mismatch_rows = (SELECT z.recon_mismatch_rows + 1 FROM zz_rep z) AND r.recon_turn_bundles = (SELECT z.recon_turn_bundles FROM zz_rep z)
               AND r.recon_manual_bundles = (SELECT z.recon_manual_bundles FROM zz_rep z)
               AND r.recon_mismatch_rows = z2.mismatch)::text
       FROM pg_temp.sr_report((SELECT id FROM zz_cur)) r CROSS JOIN pg_temp.zz_recon_indep((SELECT id FROM zz_cur)) z2$p$,
    $w$SELECT count(*) FROM reconciliation_log rl JOIN zz_cur c ON c.id = rl.season_id WHERE rl.status = 'MATCH'$w$);
CALL pg_temp.mut_chk('11 대사', '로그 한 행의 턴을 경로 길이 + 1 로 옮기면 범위 밖 행이 +1 이고 네 열이 독립 계산과 같다 (그 행이 속한 묶음은 17행·15행이 아니게 된다)',
    $m$UPDATE reconciliation_log SET turn_no = (SELECT c.path_length + 1 FROM zz_cur c)
       WHERE id = (SELECT min(rl.id) FROM reconciliation_log rl JOIN zz_cur c ON c.id = rl.season_id)$m$,
    $p$SELECT (r.recon_out_of_range_rows = (SELECT z.recon_out_of_range_rows + 1 FROM zz_rep z)
               AND (r.recon_mismatch_rows, r.recon_turn_bundles, r.recon_manual_bundles, r.recon_out_of_range_rows) = (z2.mismatch, z2.turn_b, z2.manual_b, z2.out_range)
               AND r.recon_turn_bundles + r.recon_manual_bundles < (SELECT z.recon_turn_bundles + z.recon_manual_bundles FROM zz_rep z))::text
       FROM pg_temp.sr_report((SELECT id FROM zz_cur)) r CROSS JOIN pg_temp.zz_recon_indep((SELECT id FROM zz_cur)) z2$p$,
    $w$SELECT count(*) FROM reconciliation_log rl JOIN zz_cur c ON c.id = rl.season_id$w$);
\o

-- 12 사후 정보 배제 -------------------------------------------------------------------
\o /dev/null
-- 네 출력(보고서·턴별·실측·선수별)을 jsonb 문자열 → md5 한 값으로 굳혀 비교한다 (값은 내지 않는다)
CREATE FUNCTION pg_temp.zz_sig(p_season bigint) RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT md5((SELECT to_jsonb(r)::text FROM pg_temp.sr_report(p_season) r)
            || (SELECT COALESCE(string_agg(to_jsonb(t)::text, '|' ORDER BY t.turn_no), '') FROM pg_temp.sr_turns(p_season) t)
            || (SELECT COALESCE(string_agg(to_jsonb(p)::text, '|' ORDER BY p.market, p.ticker), '') FROM pg_temp.sr_season_positions(p_season) p)
            || (SELECT COALESCE(string_agg(to_jsonb(l)::text, '|' ORDER BY l.market, l.ticker, l.declared_position), '') FROM pg_temp.sr_players(p_season) l))
$$;
CREATE TEMP TABLE zz_sig0 AS SELECT pg_temp.zz_sig(id) AS sig FROM zz_cur;
-- T 뒤의 블록(경로 turn_no > T)의 수익률·환율을 바꾸고 경로 한 행을 다른 블록으로 옮겨도 출력이 같다
CALL pg_temp.mut_chk('12 사후 정보', 'T 뒤 경로 블록의 모든 종목 수익률·환율을 바꿔도 네 출력이 글자 단위로 같다',
    $m$UPDATE asset_month_return SET local_return = 0.9
       WHERE month_block_id IN (SELECT sp.month_block_id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no > c.current_turn_no);
       UPDATE fx_month SET fx_change = 0.5
       WHERE month_block_id IN (SELECT sp.month_block_id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no > c.current_turn_no)$m$,
    $p$SELECT (pg_temp.zz_sig(id) = (SELECT sig FROM zz_sig0))::text FROM zz_cur$p$,
    $w$SELECT count(*) FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no > c.current_turn_no$w$);
CALL pg_temp.mut_chk('12 사후 정보', 'T + 1 경로 행을 사용하지 않는 다른 블록으로 옮겨도 네 출력이 같다',
    $m$UPDATE season_path SET month_block_id = (SELECT mb.id FROM month_block mb WHERE mb.id NOT IN (SELECT sp2.month_block_id FROM season_path sp2) ORDER BY mb.id LIMIT 1)
       WHERE id = (SELECT sp.id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no = c.current_turn_no + 1)$m$,
    $p$SELECT (pg_temp.zz_sig(id) = (SELECT sig FROM zz_sig0))::text FROM zz_cur$p$,
    $w$SELECT count(*) FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no = c.current_turn_no + 1$w$);
CALL pg_temp.mut_chk('12 사후 정보', 'T 뒤 turn_price 행이 있으면(턴 T + 1 …) 바꿔도 네 출력이 같다',
    $m$UPDATE turn_price SET price = price + 1000 WHERE season_id = (SELECT id FROM zz_cur) AND turn_no > (SELECT current_turn_no FROM zz_cur)$m$,
    $p$SELECT (pg_temp.zz_sig(id) = (SELECT sig FROM zz_sig0))::text FROM zz_cur$p$,
    $w$SELECT count(*) FROM turn_price tp JOIN zz_cur c ON c.id = tp.season_id WHERE tp.turn_no > c.current_turn_no$w$);
-- 대조군: T 이하 경로 블록을 바꾸면 출력이 달라진다 (같으면 위 검사가 변화를 못 보는 것이다). 양수·음수 두 가지 중 하나는 반드시 다르다 (대용 수익률 한 달이 오른 달이거나 빠진 달이므로).
CALL pg_temp.mut_chk('12 사후 정보', '대조군: 경로 1턴 블록의 대용 2종 수익률을 +50% 또는 −50% 로 바꾸면 출력이 달라진다 (둘 중 하나는 반드시)',
    $m$UPDATE asset_month_return SET local_return = 0.5
       WHERE asset_id IN (SELECT a.id FROM asset a WHERE (a.market = 'KR' AND a.ticker = '069500') OR (a.market = 'US' AND a.ticker = 'SPY'))
         AND month_block_id = (SELECT sp.month_block_id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no = 1)$m$,
    $p$SELECT (pg_temp.zz_sig(id) <> (SELECT sig FROM zz_sig0) OR pg_temp.zz_mut(
          $mm$UPDATE asset_month_return SET local_return = -0.5
             WHERE asset_id IN (SELECT a.id FROM asset a WHERE (a.market = 'KR' AND a.ticker = '069500') OR (a.market = 'US' AND a.ticker = 'SPY'))
               AND month_block_id = (SELECT sp.month_block_id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no = 1)$mm$,
          $pp$SELECT (pg_temp.zz_sig(id) <> (SELECT sig FROM zz_sig0))::text FROM zz_cur$pp$) = 'true')::text FROM zz_cur$p$);
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '12 사후 정보', '적용 불가 확인: 끝난 시즌(T = path_length)이면 T 뒤 블록이 없다 (정보)', '(정보)', CASE WHEN current_turn_no = path_length THEN '적용 불가' ELSE '해당 없음 — 위 변이가 돌았다' END,
       true FROM zz_cur WHERE current_turn_no = path_length;
\o

-- 13 거부 사례 -------------------------------------------------------------------
\o /dev/null
CALL pg_temp.rej('시즌 번호가 NULL', NULL, 'SELECT * FROM pg_temp.sr_report(NULL)', 'INVALID_ARGUMENT');
CALL pg_temp.rej('시즌 번호가 NULL (sr_turns)', NULL, 'SELECT * FROM pg_temp.sr_turns(NULL)', 'INVALID_ARGUMENT');
CALL pg_temp.rej('없는 시즌', NULL, 'SELECT * FROM pg_temp.sr_report(-1)', 'SEASON_NOT_FOUND');
CALL pg_temp.rej('current_turn_no = 0 (스냅샷 없음)', 'UPDATE season SET status = ''IN_PROGRESS'', current_turn_no = 0 WHERE id = (SELECT id FROM zz_cur)',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_NO_TURN');
CALL pg_temp.rej('turn 행 하나 삭제 (1..T 가 아니다)', 'DELETE FROM turn WHERE season_id = (SELECT id FROM zz_cur) AND turn_no = 1',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_INCONSISTENT');
CALL pg_temp.rej('turn_no > T 인 주문', 'UPDATE orders SET turn_no = (SELECT current_turn_no + 1 FROM zz_cur) WHERE id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0)',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_INCONSISTENT');
CALL pg_temp.rej('FINISHED 인데 turn_no = T 인 주문',
                 'UPDATE season SET path_length = current_turn_no, status = ''FINISHED'' WHERE id = (SELECT id FROM zz_cur);
                  UPDATE orders SET turn_no = (SELECT current_turn_no FROM zz_cur) WHERE id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0)',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_INCONSISTENT');
CALL pg_temp.rej('season_path 한 행 삭제', 'DELETE FROM season_path WHERE season_id = (SELECT id FROM zz_cur) AND turn_no = 1',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_INCONSISTENT');
CALL pg_temp.rej('풀 종목(대용이 아닌)의 0턴 가격 삭제',
                 'DELETE FROM turn_price WHERE season_id = (SELECT id FROM zz_cur) AND turn_no = 0
                    AND asset_id = (SELECT a.id FROM asset a JOIN asset_position ap ON ap.asset_id = a.id AND ap.base_date = (SELECT base_date FROM zz_cur)
                                    WHERE NOT ((a.market = ''KR'' AND a.ticker = ''069500'') OR (a.market = ''US'' AND a.ticker = ''SPY'')) ORDER BY a.id LIMIT 1)',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_INCONSISTENT');
CALL pg_temp.rej('보유한 턴에 선언 구간이 없다 (lineup 행 삭제)',
                 'DELETE FROM lineup WHERE id = (SELECT lu.id FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id ORDER BY lu.id LIMIT 1)',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_INCONSISTENT');
-- 겹친 선언 구간: 닫힌 구간 c 안의 한 턴을 보유한 종목에서, c 뒤의 구간 o 의 start_turn 을 c.start_turn 으로 당긴다 (UPDATE 만 쓴다 — INSERT 는 identity 를 소비한다). 대상이 없으면 적용 불가.
CREATE TEMP VIEW zz_ovl AS
SELECT c.id AS cid, o.id AS oid, c.start_turn AS cs
FROM lineup c JOIN zz_cur z ON z.id = c.season_id
JOIN lineup o ON o.season_id = c.season_id AND o.asset_id = c.asset_id AND o.id <> c.id AND o.start_turn >= c.end_turn
WHERE c.end_turn IS NOT NULL
  AND EXISTS (SELECT 1 FROM pg_temp.sr_hold(z.id) h WHERE h.asset_id = c.asset_id AND h.turn_no > c.start_turn AND h.turn_no <= c.end_turn);
CALL pg_temp.rej('보유한 턴에 선언 구간이 둘 (겹치는 구간, UPDATE)',
                 'UPDATE lineup SET start_turn = (SELECT cs FROM (SELECT * FROM zz_ovl ORDER BY cid, oid LIMIT 1) q) WHERE id = (SELECT oid FROM (SELECT * FROM zz_ovl ORDER BY cid, oid LIMIT 1) q)',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_INCONSISTENT',
                 'SELECT count(*) FROM zz_ovl');
-- 끝난 시즌(FINISHED, T = path_length)에 turn_no = T 인 전술 변경: 보고 범위 안의 전술 변경 하나의 묶인 주문 연결을 끊고(주문은 그대로 둔다) turn_no 를 T 로 옮긴다. 대상이 없으면 적용 불가.
CALL pg_temp.rej('FINISHED 인데 turn_no = T 인 전술 변경 (UPDATE)',
                 'UPDATE season SET path_length = current_turn_no, status = ''FINISHED'' WHERE id = (SELECT id FROM zz_cur);
                  UPDATE orders SET formation_change_id = NULL WHERE formation_change_id = (SELECT min(fc.id) FROM formation_change fc JOIN zz_cur c ON c.id = fc.season_id WHERE fc.turn_no < c.current_turn_no);
                  UPDATE formation_change SET turn_no = (SELECT current_turn_no FROM zz_cur)
                   WHERE id = (SELECT min(fc.id) FROM formation_change fc JOIN zz_cur c ON c.id = fc.season_id WHERE fc.turn_no < c.current_turn_no)',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_INCONSISTENT',
                 'SELECT count(*) FROM formation_change fc JOIN zz_cur c ON c.id = fc.season_id WHERE fc.turn_no < c.current_turn_no');
CALL pg_temp.rej('대용 SPY 의 턴 T 가격 삭제',
                 'DELETE FROM turn_price WHERE season_id = (SELECT id FROM zz_cur) AND turn_no = (SELECT current_turn_no FROM zz_cur) AND asset_id = (SELECT id FROM asset WHERE market = ''US'' AND ticker = ''SPY'')',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_NO_BENCHMARK');
CALL pg_temp.rej('대용 069500 이 시즌 기준일 풀에 없다',
                 'DELETE FROM asset_position WHERE base_date = (SELECT base_date FROM zz_cur) AND asset_id = (SELECT id FROM asset WHERE market = ''KR'' AND ticker = ''069500'')',
                 'SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))', 'REPORT_NO_BENCHMARK');
CALL pg_temp.rej('경로 블록의 대용 수익률 행이 없다 → 12 pos_fail 이 멈춘다',
                 'DELETE FROM asset_month_return WHERE asset_id = (SELECT id FROM asset WHERE market = ''US'' AND ticker = ''SPY'')
                    AND month_block_id = (SELECT sp.month_block_id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no = 1)',
                 'SELECT * FROM pg_temp.sr_season_positions((SELECT id FROM zz_cur))', '*P0-10 정의');
CALL pg_temp.rej('다섯 공개 함수 모두 같은 범위 검사를 먼저 한다 (sr_players, 없는 시즌)', NULL, 'SELECT * FROM pg_temp.sr_players(-1)', 'SEASON_NOT_FOUND');
CALL pg_temp.rej('sr_change_costs, 없는 시즌', NULL, 'SELECT * FROM pg_temp.sr_change_costs(-1)', 'SEASON_NOT_FOUND');
CALL pg_temp.rej('sr_season_positions, 없는 시즌', NULL, 'SELECT * FROM pg_temp.sr_season_positions(-1)', 'SEASON_NOT_FOUND');
-- 오류 메시지에 금액·가격·달이 없다: 거부 사례마다 메시지에 숫자 열(4자리 이상 연속 숫자)이 있는지 본다
CREATE TEMP TABLE zz_msgs (msg text);
DO $$
DECLARE
    q text;
    m text;
BEGIN
    FOREACH q IN ARRAY ARRAY['SELECT * FROM pg_temp.sr_report(NULL)', 'SELECT * FROM pg_temp.sr_report(-1)',
                             'UPDATE season SET status = ''IN_PROGRESS'', current_turn_no = 0 WHERE id = (SELECT id FROM zz_cur); SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))',
                             'DELETE FROM turn WHERE season_id = (SELECT id FROM zz_cur) AND turn_no = 1; SELECT * FROM pg_temp.sr_report((SELECT id FROM zz_cur))',
                             'SELECT pg_temp.sr_div8(1.5, 3)', 'SELECT pg_temp.sr_div8(1, 0)'] LOOP
        BEGIN
            EXECUTE q;
            m := '오류 없음';
        EXCEPTION WHEN OTHERS THEN
            m := SQLERRM;
        END;
        INSERT INTO zz_msgs VALUES (m);
    END LOOP;
END $$;
-- 위 DO 의 UPDATE·DELETE 는 EXCEPTION 블록 안이라 오류와 함께 되돌아간다 (성공한 UPDATE 후 SELECT 가 오류로 끝나므로)
\o
SELECT pg_temp.check_eq('13 거부', '거부 메시지 6개 중 숫자 4자리 이상이 들어 있거나 "오류 없음"인 것 (금액·가격·달 노출 금지)', '0',
    (SELECT count(*)::text FROM zz_msgs WHERE msg ~ '[0-9]{4,}' OR msg = '오류 없음'));
SELECT pg_temp.check_eq('13 거부', '거부 메시지 확인 뒤 데이터가 그대로다 (행 수·identity)', 'true', (SELECT (pg_temp.zz_snap() = (SELECT snap FROM zz_before))::text));

-- 14 합성 단위 -------------------------------------------------------------------
\o /dev/null
SELECT pg_temp.check_eq('14 합성', 'sr_div8(−1, 3) = −0.33333333', '-0.33333333', (SELECT pg_temp.sr_div8(-1, 3)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_div8(1, −200000000) = −0.00000001 (정확히 0.5 는 0 에서 먼 쪽)', '-0.00000001', (SELECT pg_temp.sr_div8(1, -200000000)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_div8(−1, 200000000) = −0.00000001', '-0.00000001', (SELECT pg_temp.sr_div8(-1, 200000000)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_div8(3, 200000000) = 0.00000002 (1.5e-8 → 올림)', '0.00000002', (SELECT pg_temp.sr_div8(3, 200000000)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_div8(1, −3) = −0.33333333 · sr_div8(−1, −3) = 0.33333333 (부호 4조합)', '-0.33333333|0.33333333|0.33333333',
    (SELECT pg_temp.sr_div8(1, -3)::text || '|' || pg_temp.sr_div8(-1, -3)::text || '|' || pg_temp.sr_div8(1, 3)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_div8(4, 1000000000) = 0.00000000 (0.4e-8 → 내림) · sr_div8(5, 1000000000) = 0.00000001', '0.00000000|0.00000001',
    (SELECT pg_temp.sr_div8(4, 1000000000)::text || '|' || pg_temp.sr_div8(5, 1000000000)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_div8(7, 1) = 7.00000000 (분모 1, 소수 8자리)', '7.00000000', (SELECT pg_temp.sr_div8(7, 1)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_div8(0, 5) = 0.00000000', '0.00000000', (SELECT pg_temp.sr_div8(0, 5)::text));
-- 정수 아님·0 으로 나누기·NULL 은 거부
SELECT pg_temp.check_eq('14 합성', 'sr_div8 거부: 소수 분자 · 0 분모 · NULL — 모두 INVALID_ARGUMENT', 'INVALID_ARGUMENT|INVALID_ARGUMENT|INVALID_ARGUMENT',
    (SELECT (SELECT h FROM (SELECT pg_temp.zz_hint('SELECT pg_temp.sr_div8(1.5, 3)') AS h) a) || '|' || pg_temp.zz_hint('SELECT pg_temp.sr_div8(1, 0)') || '|' || pg_temp.zz_hint('SELECT pg_temp.sr_div8(NULL, 3)')));
-- 정확한 공통 분모 대 턴별 반올림: [1/(2e8), 0/1] 의 평균 = 2.5e-9 → 0.00000000 (턴별로 먼저 반올림하면 (1e-8 + 0) ÷ 2 = 5e-9 → 0.00000001)
SELECT pg_temp.check_eq('14 합성', 'sr_frac_mean 은 공통 분모로 한 번 반올림한다 (턴별 반올림 평균과 다른 사례)', '0.00000000|0.00000001',
    (SELECT pg_temp.sr_frac_mean(ARRAY[1, 0]::numeric[], ARRAY[200000000, 1]::numeric[])::text || '|'
         || ROUND((pg_temp.sr_div8(1, 200000000) + pg_temp.sr_div8(0, 1)) / 2, 8)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_frac_mean([1,2,3],[2,4,6]) = 0.5 (세 분수 모두 1/2)', '0.50000000', (SELECT pg_temp.sr_frac_mean(ARRAY[1, 2, 3]::numeric[], ARRAY[2, 4, 6]::numeric[])::text));
-- 회전율 대수 변형: 12 × N ÷ Σ V = (N ÷ 평균 V) × 12 ÷ T. N = 1200, V = [900, 1000, 1100], T = 3 → 4.8
SELECT pg_temp.check_eq('14 합성', '회전율 대수 변형: sr_div8(12 × 1200, 3000) = (1200 ÷ 1000) × 12 ÷ 3 = 4.8', '4.80000000|4.80000000',
    (SELECT pg_temp.sr_div8(12 * 1200, 3000)::text || '|' || ROUND(1200 / (3000.0 / 3) * 12 / 3, 8)::text));
SELECT pg_temp.check_eq('14 합성', 'sr_k 상수: 최소 6·3·3 · 연율 12 · 독려 3종 · 플랫폼 몫 RANKING·ALERT', '6|3|3|12|{RANKING,FEED,ALERT}|{RANKING,ALERT}',
    (SELECT k.min_months || '|' || k.min_up || '|' || k.min_down || '|' || k.annual || '|' || k.nudge_sources::text || '|' || k.platform_sources::text FROM pg_temp.sr_k() k));
\o

-- 15 정보 -------------------------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '15 정보', 'T · 경로 길이 · 상태 (정보)', '(정보)', r.report_turns || ' · ' || r.path_length || ' · ' || r.season_status, true FROM zz_rep r;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '15 정보', 'pending_orders · pending_changes (스냅샷 밖, 계산에서 빠진 수) (정보)', '(정보)', r.pending_orders || ' · ' || r.pending_changes, true FROM zz_rep r;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '15 정보', '풀 종목 수 · 판정됨 · 사유별 MONTHS/N_UP/N_DOWN/ZERO_DEN (정보 — 값 없음)', '(정보)',
       count(*) || ' · ' || count(*) FILTER (WHERE reason IS NULL) || ' · ' || count(*) FILTER (WHERE reason = 'MONTHS') || '/' || count(*) FILTER (WHERE reason = 'N_UP') || '/'
       || count(*) FILTER (WHERE reason = 'N_DOWN') || '/' || count(*) FILTER (WHERE reason = 'ZERO_DEN'), true FROM zz_sp;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '15 정보', '노출 선수 수(종목) · 판정됨 · 판정 불능 · role_fit 이 NULL 인가 (정보)', '(정보)',
       (SELECT count(DISTINCT market || ':' || ticker) FROM zz_pl) || ' · ' || r.role_players_judged || ' · ' || r.role_players_unjudged || ' · ' || (r.role_fit IS NULL), true FROM zz_rep r;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '15 정보', '1턴 이후 주문 수(보고 범위) · 출발점별 RANKING/FEED/ALERT/BENCH/DIRECT (정보)', '(정보)',
       count(*) || ' · ' || count(*) FILTER (WHERE o.source = 'RANKING') || '/' || count(*) FILTER (WHERE o.source = 'FEED') || '/' || count(*) FILTER (WHERE o.source = 'ALERT') || '/'
       || count(*) FILTER (WHERE o.source = 'BENCH') || '/' || count(*) FILTER (WHERE o.source = 'DIRECT'), true
FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no BETWEEN 1 AND c.current_turn_no - 1;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '15 정보', '위험 위반 턴 수 · 전술 변경 수 · 변경 없이 낸 1턴 이후 주문 수 (정보)', '(정보)',
       r.risk_breach_turns || ' · ' || r.formation_changes || ' · '
       || (SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no BETWEEN 1 AND c.current_turn_no - 1 AND o.formation_change_id IS NULL), true FROM zz_rep r;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '15 정보', '대사: MISMATCH 행 · 턴 전환 묶음 · 수동 묶음 · 범위 밖 행 (정보)', '(정보)',
       r.recon_mismatch_rows || ' · ' || r.recon_turn_bundles || ' · ' || r.recon_manual_bundles || ' · ' || r.recon_out_of_range_rows, true FROM zz_rep r;

-- 16 판정 ---------------------------------------------------------------------
-- 16.1 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok AND actual IS DISTINCT FROM '적용 불가') AS pass, count(*) FILTER (WHERE NOT ok) AS fail,
       count(*) FILTER (WHERE actual = '적용 불가') AS na
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 16.2 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 16.3 정보 행 (PASS 출력에서도 건수와 "적용 불가"가 보이게)
SELECT section, item, actual FROM zz_check_result WHERE section = '15 정보' OR actual = '적용 불가' ORDER BY seq;

-- 16.4 판정 행 (적용 불가 정보 행은 통과 사례 수에 세지 않는다)
SELECT CASE WHEN count(*) FILTER (WHERE actual IS DISTINCT FROM '적용 불가') > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) FILTER (WHERE actual IS DISTINCT FROM '적용 불가') AS total, count(*) FILTER (WHERE ok AND actual IS DISTINCT FROM '적용 불가') AS pass,
       count(*) FILTER (WHERE NOT ok) AS fail, count(*) FILTER (WHERE actual = '적용 불가') AS not_applicable
FROM zz_check_result;

-- 16.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3). 판정 표식은 트랜잭션이 되돌아가도 남는다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) FILTER (WHERE actual IS DISTINCT FROM '적용 불가') INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-20 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 17 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
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
        RAISE EXCEPTION E'P0-20 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    IF (SELECT last_value FROM zz_verdict) = 2 THEN
        PERFORM setval('zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_20_check;
