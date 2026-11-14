-- =====================================================================
-- my Fund Manager Phase 0 — 팀 구성 검증 SQL (P0-15)
-- 대상: db/21_team_buy.sql 이 만든 0턴 매수 (orders · execution · holding · cash_ledger · cash_account · lineup)와 db/21_team_buy_def.sql 의 함수
-- 근거: docs/erd.md 7.3·7.5(대사 규칙 2·3·4·6·7·8·9·13·14·17·18)·6.3(season·lineup)·D4·D9·D23·9절 P0-15, docs/SPRING_JPA.md §5.1·§5.2,
--       docs/PLAN.md §4.1-2·§4.6·§4.10·§5.2, db/20_season_start_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/21_team_buy_check.sql
--       21 을 실행한 DB 에서 한다. INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아 실습 DB(antlab)에서도 돌릴 수 있다.
--       정의 파일을 \ir 로 읽으므로 같은 폴더의 21_team_buy_def.sql 이 있어야 한다.
--       P0-19 이후: 음성 사례의 대상(보유 수량 > 0 인 보유·열린 라인업)이 없는 포트폴리오(초기 팀 전부 매도·교체)는 "적용 불가" 정보 행이고, 함수 사례는 열린 구간이 있는 첫 종목(없으면 풀의 첫 종목)으로 돈다.
--       Not applicable is the zz_check_result.applicable column (false), written only by pg_temp.na_row and tallied only by the zz_tally view.
--       Right after the team is built (canonical state) 12.1b asserts that the not-applicable count is 0.
-- 판정: 마지막 줄 `p0_15_check` 가 `PASS` 이고 종료 코드가 0이면 통과다. 0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고,
--       그 밖에 하나라도 어긋나면 12절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 12절 뒤에 ROLLBACK 한다. 13절이 행 수·identity 마지막 값이 실행 전과 같은지 확인한다.
-- 절:   00 전제(FAIL 이면 여기서 멈춤) → 01 팀 → 02 수량 → 03 체결 → 04 원장 → 05 보유 → 06 라인업 → 07 포메이션 → 08 잔액 → 09 함수 → 10 단위 → 11 정보 → 12 판정 → ROLLBACK → 13 흔적
--       음성 사례(규칙을 일부러 어긴 뒤 잡히는지)는 사례가 속한 규칙의 절에 "음성 [규칙]" 행으로 쌓인다.
-- 규칙 코드 22개 (규칙마다 양성 쿼리 1개 + 음성 사례 1개 이상, 12절이 커버리지 N/N 을 확인한다):
--       TEAM_SIZE TEAM_LIST ORDER_SHAPE / QTY_RULE / FILL_PRICE FEE_RULE EXEC_VALUES / CASH_SUM LEDGER_CHAIN ORDER_LEDGER LEDGER_ORDER /
--       HOLDING_QTY AVG_PRICE / LINEUP_HOLDING LINEUP_OVERLAP DECLARED_DIFF / FORMATION_ACTUAL / RESERVED_ZERO / FN_INSUFFICIENT FN_TURN FN_POOL_QTY_POS / UNIT_CALC
--       모두 "팀을 구성한 뒤 언제나 참"인 규칙이다 — 턴 진행(P0-17)·매매(P0-19)가 진행된 DB 에서도 PASS 해야 한다.
--       판정 재료는 바뀌지 않는 기록(0턴 주문·체결·그 주문의 원장 행, start_turn = 0 라인업, turn_price(0))이고, 바뀌는 상태(보유·잔액·열린 라인업)는
--       언제나 참인 항등식으로만 본다. 팀 직후에만 참인 것(보유 수량 = 팀 수량, 열린 라인업 수)은 11절 정보 행으로만 낸다.
-- 독립 계산: 수량은 생성 구간 안에서 조건을 만족하는 최대 정수를 찾는 방법(floor 나눗셈이 아님), 수수료는 정수 산술 div(2·p·q·R + 10^8, 2·10^8),
--       평균 단가는 체결 이력을 재귀로 재생하는 정수 산술이다. ROUND 에 기대지 않는다.
--       기대값(팀 명단 · 현금 목표)은 21_team_buy.sql 이 아니라 문서(docs/erd.md §9 P0-15 메모)에서 다시 옮겨 적었다.
-- 출력: 건수와 판정만. 가격·수량·금액·비중은 내지 않는다 (10절 단위 사례는 합성 값이라 시세가 아니다).
-- 진행된 상태(일부·전량 매도)에서도 헛 FAIL 이 없도록 음성 사례의 대상은 "아직 보유 중인 것"(수량 > 0, 열린 라인업)으로 고른다.
--       보유 중인 팀 종목이 하나도 없으면(전부 매도) 대상 없는 사례는 FAIL 로 남는다 — 팀 전량 매도는 Phase 0 의 범위 밖이다.
-- 함수 사례는 시즌 상태를 시험하는 두 사례를 빼고 진행 중으로 되돌린 서브트랜잭션에서 부른다 (끝난 시즌에서도 PASS).
-- 함수 음성 사례(9절)는 서브트랜잭션 안에서 INSERT 전에 실패하는 호출만 쓴다 — 번호표를 쓰지 않는다. 정확히 같은 금액의 통과 경계는
--       INSERT 가 필요해 이 파일에 없고 db/README.md (g)와 QA 의 합성 시즌에서 확인한다 (buy_affordable 의 경계는 10절이 본다).
-- P0-08·P0-09·P0-11·P0-12·P0-14 판정 계약에서 복사한 것: ① pg_temp.zz_verdict ② 결과 표 zz_check_result ④ check_eq
--       ⑤ 0절 뒤 멈춤과 판정(표식 2 또는 1) ⑥ 13절 흔적 확인(표식 0)과 마지막 CASE.
-- 새 문법: \ir 로 정의 읽기, 서브트랜잭션으로 "바꿔 보고 되돌리기", GET STACKED DIAGNOSTICS 로 HINT 읽기, 여러 문장 EXECUTE, 재귀 CTE,
--       generate_series 로 최대 정수 찾기, LATERAL, FILTER 집계.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\ir 21_team_buy_def.sql

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 00–12절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

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
    FOREACH t IN ARRAY ARRAY['season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'turn_price', 'asset_position'] LOOP
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
DROP TABLE IF EXISTS pg_temp.zz_before;
RESET client_min_messages;
CREATE TEMP TABLE zz_before AS SELECT pg_temp.zz_snap() AS snap;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '00 전제' … '12 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL,
    applicable boolean NOT NULL DEFAULT true   -- false = the case has no target in this portfolio (informational row, never counted as a pass)
);

-- Shared tally: one place decides what counts. Only rows with applicable = true count toward totals and passes.
CREATE TEMP VIEW zz_tally AS
SELECT section,
       count(*) AS n_rows,
       count(*) FILTER (WHERE applicable) AS total,
       count(*) FILTER (WHERE ok AND applicable) AS pass,
       count(*) FILTER (WHERE NOT ok) AS fail,
       count(*) FILTER (WHERE NOT applicable) AS na
FROM zz_check_result
GROUP BY section;

-- 연습 시즌 찾기: 20 파일과 같은 이메일 (문서 P0-14 W4)
CREATE TEMP VIEW zz_cur AS
SELECT s.* FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');

-- 팀 명단: 21_team_buy.sql 이 아니라 docs/erd.md §9 P0-15 메모에서 다시 옮겨 적었다 (종목 코드 · 비중 % · 선언 포지션 — 수량 없음).
CREATE TEMP TABLE zz_team (market text NOT NULL, ticker text NOT NULL, weight_pct integer NOT NULL, declared_position text NOT NULL);
INSERT INTO zz_team VALUES
    ('KR', '411060', 15, 'DEFENDER'),
    ('US', 'KO',     15, 'DEFENDER'),
    ('KR', '005930', 15, 'MIDFIELDER'),
    ('US', 'JNJ',    15, 'MIDFIELDER'),
    ('KR', '069500', 10, 'FORWARD_TARGET'),
    ('US', 'NVDA',   10, 'FORWARD_BREAKOUT'),
    ('US', 'MSFT',   10, 'FORWARD_TARGET'),
    ('US', 'SHY',     5, 'GOALKEEPER');
CREATE TEMP TABLE zz_cash (weight_pct integer NOT NULL);
INSERT INTO zz_cash VALUES (5);

-- 판정 프로시저 ------------------------------------------------------------

CREATE PROCEDURE pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- Informational row for a case that has no target in this portfolio. The only place that writes applicable = false.
CREATE PROCEDURE pg_temp.na_row(p_section text, p_item text)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok, applicable)
    VALUES (p_section, p_item, '(정보)', '적용 불가', true, false);
END $$;

-- 위반 개수를 돌려주는 쿼리 한 개를 실행해 bigint 로 돌려준다
CREATE FUNCTION pg_temp.cnt(p_sql text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v bigint;
BEGIN
    EXECUTE p_sql INTO v;
    RETURN v;
END $$;

-- 규칙 코드의 절을 찾는다
CREATE FUNCTION pg_temp.sec(p_code text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN
    SELECT section INTO v FROM zz_q WHERE code = p_code;
    RETURN v;
END $$;

-- 음성 사례(위반 개수형): 바꾼 뒤 규칙 쿼리를 돌려 위반이 p_min 건 이상 나와야 한다. 서브트랜잭션이라 바꾼 것은 되돌아간다.
-- 바꾸는 문장은 ';' 로 이은 여러 개일 수 있다 (한 사례에 조건 하나만 어긴다 — P0-05 R12 — 는 규칙의 몫이고, 같은 사례를 이루는 문장은 함께 바꾼다).
CREATE PROCEDURE pg_temp.neg_viol(p_code text, p_label text, p_mutate text, p_min bigint, p_when text DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_sql text := (SELECT sql FROM zz_q WHERE code = p_code);
    v_n   text;
BEGIN
    -- p_when (a query that must be true) false = no target in this portfolio (e.g. all cash after trading): leave a not-applicable row.
    IF p_when IS NOT NULL AND pg_temp.cnt(p_when) = 0 THEN
        CALL pg_temp.na_row(pg_temp.sec(p_code), '음성 [' || p_code || '] ' || p_label || ' — 적용 불가 (이 포트폴리오에 대상이 없다) (정보)');
        RETURN;
    END IF;
    BEGIN
        EXECUTE p_mutate;
        v_n := pg_temp.cnt(v_sql)::text;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            v_n := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
        END IF;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (pg_temp.sec(p_code), '음성 [' || p_code || '] ' || p_label || ' 위반 ' || p_min || '건 이상', '≥ ' || p_min, v_n,
            v_n ~ '^[0-9]+$' AND v_n::bigint >= p_min);
END $$;

-- 음성 사례(함수형): 준비 문장을 실행한 뒤 호출이 P0001 + 기대한 HINT 로 실패해야 하고, 실패한 뒤 행 수·identity 가 그대로여야 한다.
-- 호출이 성공해 버리면 '오류 없음' 이고 서브트랜잭션을 되돌린다.
CREATE TEMP TABLE zz_fn (code text NOT NULL, label text NOT NULL, ok boolean NOT NULL);
CREATE PROCEDURE pg_temp.neg_fn(p_code text, p_label text, p_prep text, p_call text, p_hint text)
LANGUAGE plpgsql AS $$
DECLARE
    v_before text := pg_temp.zz_snap();
    v_after  text;
    v_hint   text;
    v_act    text;
    v_ok     boolean;
BEGIN
    BEGIN
        IF p_prep IS NOT NULL THEN EXECUTE p_prep; END IF;
        EXECUTE p_call;
        v_act := '오류 없음';
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
            v_act := SQLSTATE || ' ' || COALESCE(v_hint, '-');
        END IF;
    END;
    v_after := pg_temp.zz_snap();
    v_ok := v_act = 'P0001 ' || p_hint AND v_before = v_after;
    IF v_act = 'P0001 ' || p_hint AND v_before <> v_after THEN
        v_act := v_act || ' (행 수·identity 가 바뀜)';
    END IF;
    INSERT INTO zz_fn VALUES (p_code, p_label, v_ok);
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (pg_temp.sec(p_code), '음성 [' || p_code || '] ' || p_label, 'P0001 ' || p_hint || ', 행 수·identity 그대로', v_act, v_ok);
END $$;

-- 00 전제 -------------------------------------------------------------------
-- 하나라도 FAIL 이면 01절 이후 결과를 믿지 않는다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('격리 수준이 READ COMMITTED 다 (원장 연쇄의 전제)', 'read committed', current_setting('transaction_isolation')),
    ('11개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '11',
        (SELECT count(*)::text FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'turn_price', 'asset', 'asset_position']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('정의 파일을 읽었다 (pg_temp.order_buy 가 있다)', 'true', (to_regprocedure('pg_temp.order_buy(bigint, smallint, bigint, integer, text, text)') IS NOT NULL)::text),
    ('이 검증의 명단이 8줄 · 현금 목표 1줄이다 (문서에서 옮겨 적은 표)', '8/1', (SELECT count(*) FROM zz_team)::text || '/' || (SELECT count(*) FROM zz_cash)::text),
    ('연습 시즌이 정확히 1개 있다 (db/20_season_start.sql 을 먼저 실행한다)', '1', (SELECT count(*)::text FROM zz_cur)),
    ('0턴 매수 주문이 있다 (없으면 db/21_team_buy.sql 을 먼저 실행한다 — 팀 구성 전)', 'true',
        ((SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY') > 0)::text)
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
        RAISE EXCEPTION 'P0-15 전제 실패: 00절 FAIL % 건 (위의 00절 결과를 본다. 01·02·03 스키마와 10 적재, 12 판정, 20 시즌 시작, 21 팀 구성을 먼저 실행했는지 확인한다)', v_fail;
    END IF;
END $$;

-- 검사 쿼리 목록: 위반 개수를 돌려주는 쿼리. 양성(0건)과 음성(위반을 만든 뒤 기대한 개수)에 같은 쿼리를 쓴다.
CREATE TEMP TABLE zz_q (code text PRIMARY KEY, section text NOT NULL, item text NOT NULL, sql text NOT NULL);
INSERT INTO zz_q (code, section, item, sql) VALUES
    ('TEAM_SIZE', '01 팀', '0턴 매수 종목 수가 명단 줄 수(6–8)와 다른 수 + 같은 종목의 0턴 매수가 2건 이상인 종목 수',
     $q$SELECT (SELECT CASE WHEN count(DISTINCT o.asset_id) = (SELECT count(*) FROM zz_team) AND count(DISTINCT o.asset_id) BETWEEN 6 AND 8 THEN 0 ELSE 1 END
                FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY')
             + (SELECT count(*) FROM (SELECT o.asset_id FROM orders o JOIN zz_cur c ON c.id = o.season_id
                                      WHERE o.turn_no = 0 AND o.side = 'BUY' GROUP BY o.asset_id HAVING count(*) > 1) d)$q$),
    ('TEAM_LIST', '01 팀', '0턴 매수 종목 · start_turn = 0 라인업의 선언이 문서의 명단과 양방향으로 다른 수',
     $q$SELECT count(*) FROM (
            (SELECT t.market::text, t.ticker::text, t.declared_position::text FROM zz_team t
             EXCEPT
             SELECT a.market::text, a.ticker::text, lu.declared_position::text
             FROM orders o JOIN zz_cur c ON c.id = o.season_id JOIN asset a ON a.id = o.asset_id
             JOIN lineup lu ON lu.season_id = o.season_id AND lu.asset_id = o.asset_id AND lu.start_turn = 0
             WHERE o.turn_no = 0 AND o.side = 'BUY')
            UNION ALL
            (SELECT a.market::text, a.ticker::text, lu.declared_position::text
             FROM orders o JOIN zz_cur c ON c.id = o.season_id JOIN asset a ON a.id = o.asset_id
             JOIN lineup lu ON lu.season_id = o.season_id AND lu.asset_id = o.asset_id AND lu.start_turn = 0
             WHERE o.turn_no = 0 AND o.side = 'BUY'
             EXCEPT
             SELECT t.market::text, t.ticker::text, t.declared_position::text FROM zz_team t)) d$q$),
    ('ORDER_SHAPE', '01 팀', '0턴 주문 중 매수 · 시장가 · 체결 · DIRECT · 지정가 없음 · 변경/피드 없음이 아닌 수 (대사 규칙 7 일부)',
     $q$SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id
        WHERE o.turn_no = 0
          AND NOT (o.side = 'BUY' AND o.type = 'MARKET' AND o.status = 'FILLED' AND o.source = 'DIRECT'
                   AND o.limit_price IS NULL AND o.formation_change_id IS NULL AND o.turn_feed_id IS NULL)$q$),
    ('QTY_RULE', '02 수량', '명단 줄마다: 주문 · 체결 수량 ≠ 독립 계산(예산 안에서 조건을 만족하는 최대 정수, 예산 = 초기 자금 × 비중 ÷ 100 내림), 수량 < 1, 대금 + 수수료 > 예산인 줄 수',
     $q$SELECT count(*)
        FROM zz_team t
        JOIN asset a ON a.market = t.market AND a.ticker = t.ticker
        CROSS JOIN zz_cur c
        JOIN turn_price tp ON tp.season_id = c.id AND tp.turn_no = 0 AND tp.asset_id = a.id
        CROSS JOIN LATERAL (SELECT div(c.initial_cash * t.weight_pct, 100) AS b,
                                   CASE a.market WHEN 'KR' THEN c.fee_rate_kr ELSE c.fee_rate_us + c.fx_spread_rate END * 100000000 AS r) x
        LEFT JOIN LATERAL (SELECT max(g) AS want FROM generate_series(1, (x.b / tp.price)::bigint + 1) g
                           WHERE tp.price * g * (100000000 + x.r) <= x.b * 100000000) w ON true
        LEFT JOIN orders o ON o.season_id = c.id AND o.asset_id = a.id AND o.turn_no = 0 AND o.side = 'BUY'
        LEFT JOIN execution e ON e.order_id = o.id
        WHERE o.id IS NULL OR e.id IS NULL OR w.want IS NULL OR w.want < 1
           OR o.quantity <> w.want OR e.quantity <> w.want OR tp.price * o.quantity + e.fee > x.b$q$),
    ('FILL_PRICE', '03 체결', '대사 규칙 13: 체결가 ≠ turn_price(주문의 턴, 종목)인 체결 수',
     $q$SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id JOIN execution e ON e.order_id = o.id
        LEFT JOIN turn_price tp ON tp.season_id = o.season_id AND tp.turn_no = o.turn_no AND tp.asset_id = o.asset_id
        WHERE tp.price IS DISTINCT FROM e.fill_price$q$),
    ('FEE_RULE', '03 체결', '대사 규칙 14: 수수료 ≠ 정수 산술 div(2 × 체결가 × 수량 × R + 10^8, 2 × 10^8) 인 체결 수 (R = 요율 × 10^8, 국내 = 국내 수수료율, 미국 = 미국 수수료율 + 환전 비용률)',
     $q$SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id JOIN execution e ON e.order_id = o.id JOIN asset a ON a.id = o.asset_id
        CROSS JOIN LATERAL (SELECT CASE a.market WHEN 'KR' THEN c.fee_rate_kr WHEN 'US' THEN c.fee_rate_us + c.fx_spread_rate END * 100000000 AS r) x
        WHERE e.fee IS DISTINCT FROM div(2 * e.fill_price * e.quantity * x.r + 100000000, 200000000)$q$),
    ('EXEC_VALUES', '03 체결', '대사 규칙 7·8: 체결된 매수 주문의 체결이 1건이 아니거나 세금 ≠ 0, realized_pnl 이 NULL 이 아니거나 체결 수량 ≠ 주문 수량인 주문 수',
     $q$SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id
        WHERE o.status = 'FILLED' AND o.side = 'BUY'
          AND ((SELECT count(*) FROM execution e WHERE e.order_id = o.id) <> 1
               OR EXISTS (SELECT 1 FROM execution e WHERE e.order_id = o.id AND (e.tax <> 0 OR e.realized_pnl IS NOT NULL OR e.quantity <> o.quantity)))$q$),
    ('CASH_SUM', '04 원장', '대사 규칙 2: Σ 원장 amount ≠ 잔액인 시즌 수',
     $q$SELECT count(*) FROM zz_cur c JOIN cash_account a ON a.season_id = c.id
        WHERE a.balance <> (SELECT COALESCE(sum(l.amount), 0) FROM cash_ledger l WHERE l.season_id = c.id)$q$),
    ('LEDGER_CHAIN', '04 원장', '대사 규칙 3: id 순 연쇄(balance_after = 앞 balance_after + amount, 첫 행은 0 에서)가 끊긴 행 수 + 최신 balance_after ≠ 잔액인 시즌 수',
     $q$SELECT (SELECT count(*) FROM (
                    SELECT l.balance_after, l.amount, COALESCE(lag(l.balance_after) OVER (PARTITION BY l.season_id ORDER BY l.id), 0) AS prev
                    FROM cash_ledger l WHERE l.season_id IN (SELECT id FROM zz_cur)) t
                WHERE t.balance_after <> t.prev + t.amount)
             + (SELECT count(*) FROM zz_cur c JOIN cash_account a ON a.season_id = c.id
                WHERE a.balance <> (SELECT l.balance_after FROM cash_ledger l WHERE l.season_id = c.id ORDER BY l.id DESC LIMIT 1))$q$),
    ('ORDER_LEDGER', '04 원장', '대사 규칙 4 + 유형별 진단(P0-01 C3): 매수 주문별로 원장 합 ≠ −(대금 + 수수료), BUY 행 합 ≠ −대금, FEE 행 합 ≠ −수수료, BUY 행이 1건이 아님, FEE 행이 (수수료 > 0 이면 1건, 0 이면 0건)이 아님, 다른 종류가 섞인 주문 수',
     $q$SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id JOIN execution e ON e.order_id = o.id
        CROSS JOIN LATERAL (SELECT COALESCE(sum(l.amount) FILTER (WHERE l.type = 'BUY'), 0) AS buy_sum,
                                   COALESCE(sum(l.amount) FILTER (WHERE l.type = 'FEE'), 0) AS fee_sum,
                                   count(*) FILTER (WHERE l.type = 'BUY') AS buy_n,
                                   count(*) FILTER (WHERE l.type = 'FEE') AS fee_n,
                                   count(*) AS all_n,
                                   COALESCE(sum(l.amount), 0) AS total
                            FROM cash_ledger l WHERE l.ref_order_id = o.id) s
        WHERE o.side = 'BUY'
          AND (s.total <> -(e.fill_price * e.quantity + e.fee) OR s.buy_sum <> -(e.fill_price * e.quantity) OR s.fee_sum <> -e.fee
               OR s.buy_n <> 1 OR s.fee_n <> CASE WHEN e.fee > 0 THEN 1 ELSE 0 END OR s.all_n <> s.buy_n + s.fee_n)$q$),
    ('LEDGER_ORDER', '04 원장', '한 매수의 원장이 BUY 다음 FEE 순서가 아니거나 둘 사이에 같은 시즌의 다른 원장 행이 낀 매수 주문 수 (수수료 > 0 인 주문)',
     $q$SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id JOIN execution e ON e.order_id = o.id
        JOIN cash_ledger b ON b.ref_order_id = o.id AND b.type = 'BUY'
        WHERE o.side = 'BUY' AND e.fee > 0
          AND NOT EXISTS (SELECT 1 FROM cash_ledger f
                          WHERE f.ref_order_id = o.id AND f.type = 'FEE' AND f.id > b.id
                            AND NOT EXISTS (SELECT 1 FROM cash_ledger x WHERE x.season_id = o.season_id AND x.id > b.id AND x.id < f.id))$q$),
    ('HOLDING_QTY', '05 보유', '대사 규칙 6: 보유 수량 ≠ Σ 체결 수량(매수 +, 매도 −)인 (시즌, 종목) 수 (FULL OUTER JOIN — 한쪽에만 있는 것도 센다. 전량 매도 뒤 수량 0 보유 행(D7)이 지워져도 잡는다)',
     $q$SELECT count(*) FROM (SELECT h.season_id, h.asset_id, h.quantity FROM holding h WHERE h.season_id IN (SELECT id FROM zz_cur)) h
        FULL JOIN (SELECT o.season_id, o.asset_id, sum(CASE o.side WHEN 'BUY' THEN e.quantity ELSE -e.quantity END) AS q
                   FROM orders o JOIN execution e ON e.order_id = o.id
                   WHERE o.season_id IN (SELECT id FROM zz_cur) AND o.status = 'FILLED' GROUP BY o.season_id, o.asset_id) x
               ON x.season_id = h.season_id AND x.asset_id = h.asset_id
        WHERE h.asset_id IS NULL OR x.asset_id IS NULL OR h.quantity <> x.q$q$),
    ('AVG_PRICE', '05 보유', 'D9: 보유 평균 단가 ≠ 체결 이력을 id 순으로 재생한 이동평균(매수: (수량 × 평균 + 체결 수량 × 체결가) ÷ 새 수량을 4자리 한 번 반올림, 부분 매도: 유지, 전량 매도: 0)인 보유 수. 정수 산술 재귀',
     $q$WITH RECURSIVE ev AS (
            SELECT o.season_id, o.asset_id, o.side, e.quantity AS q, e.fill_price AS p,
                   row_number() OVER (PARTITION BY o.season_id, o.asset_id ORDER BY e.id) AS rn
            FROM execution e JOIN orders o ON o.id = e.order_id
            WHERE o.season_id IN (SELECT id FROM zz_cur) AND o.status = 'FILLED'),
        r AS (
            SELECT DISTINCT ev.season_id, ev.asset_id, 0::bigint AS rn, 0::numeric AS hq, 0::numeric AS ha FROM ev
            UNION ALL
            SELECT r.season_id, r.asset_id, ev.rn,
                   CASE WHEN ev.side = 'BUY' THEN r.hq + ev.q ELSE r.hq - ev.q END,
                   CASE WHEN ev.side = 'BUY' THEN div(2 * ((r.hq * r.ha + ev.q * ev.p) * 10000) + (r.hq + ev.q), 2 * (r.hq + ev.q))::numeric / 10000
                        WHEN r.hq - ev.q = 0 THEN 0::numeric
                        ELSE r.ha END
            FROM r JOIN ev ON ev.season_id = r.season_id AND ev.asset_id = r.asset_id AND ev.rn = r.rn + 1)
        SELECT count(*) FROM holding h JOIN zz_cur c ON c.id = h.season_id
        JOIN (SELECT DISTINCT ON (season_id, asset_id) season_id, asset_id, ha FROM r ORDER BY season_id, asset_id, rn DESC) f
             ON f.season_id = h.season_id AND f.asset_id = h.asset_id
        WHERE h.avg_price <> f.ha$q$),
    ('LINEUP_HOLDING', '06 라인업', '대사 규칙 17: 열린 라인업 ⇔ 보유 수량 > 0 이 어긋난 (시즌, 종목) 수 (양방향)',
     $q$SELECT count(*) FROM (
            SELECT x.asset_id FROM (SELECT asset_id FROM holding WHERE season_id IN (SELECT id FROM zz_cur)
                                    UNION SELECT asset_id FROM lineup WHERE season_id IN (SELECT id FROM zz_cur)) x
            WHERE EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id IN (SELECT id FROM zz_cur) AND lu.asset_id = x.asset_id AND lu.end_turn IS NULL)
                  IS DISTINCT FROM
                  COALESCE((SELECT h.quantity > 0 FROM holding h WHERE h.season_id IN (SELECT id FROM zz_cur) AND h.asset_id = x.asset_id), false)) d$q$),
    ('LINEUP_OVERLAP', '06 라인업', '대사 규칙 18: 같은 시즌·종목의 구간 쌍이 겹친 수(반열림, 열린 구간 end = +∞) + start_turn 또는 end_turn 이 현재 턴을 넘은 구간 수',
     $q$SELECT (SELECT count(*) FROM lineup a JOIN lineup b ON b.season_id = a.season_id AND b.asset_id = a.asset_id AND b.id > a.id
                JOIN zz_cur c ON c.id = a.season_id
                WHERE a.start_turn < COALESCE(b.end_turn, 32767) AND b.start_turn < COALESCE(a.end_turn, 32767))
             + (SELECT count(*) FROM lineup a JOIN zz_cur c ON c.id = a.season_id
                WHERE a.start_turn > c.current_turn_no OR a.end_turn > c.current_turn_no)$q$),
    ('DECLARED_DIFF', '06 라인업', 'PLAN §4.10: start_turn = 0 라인업 중 선언 ≠ 시스템 판정(asset_position.system_position, 6분류)인 줄이 하나도 없으면 1 (선언 ≠ 판정 사례 ≥ 1건)',
     $q$SELECT CASE WHEN count(*) FILTER (WHERE lu.declared_position <> ap.system_position) >= 1 THEN 0 ELSE 1 END
        FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id
        JOIN asset_position ap ON ap.asset_id = lu.asset_id AND ap.base_date = c.base_date
        WHERE lu.start_turn = 0$q$),
    ('FORMATION_ACTUAL', '07 포메이션', '0턴 포메이션 실제 비중(선언 4분류, 현금은 골키퍼): 네 값의 합 ≠ 초기 자금 − Σ 수수료, 수비·미드필더·공격 값이 시작 목표를 넘음, 골키퍼 투입액(현금 + 골키퍼 줄의 대금 + 수수료)이 시작 목표 미만, 현금이 현금 목표 미만인 조건 수',
     $q$WITH t AS (
            SELECT lu.declared_position AS dp, o.quantity * tp.price AS v, e.fee AS f
            FROM orders o JOIN zz_cur c ON c.id = o.season_id JOIN execution e ON e.order_id = o.id
            JOIN turn_price tp ON tp.season_id = o.season_id AND tp.turn_no = 0 AND tp.asset_id = o.asset_id
            JOIN lineup lu ON lu.season_id = o.season_id AND lu.asset_id = o.asset_id AND lu.start_turn = 0
            WHERE o.turn_no = 0 AND o.side = 'BUY'),
        k AS (
            SELECT c.*,
                   c.initial_cash - (SELECT COALESCE(sum(v + f), 0) FROM t) AS cash,
                   (SELECT COALESCE(sum(f), 0) FROM t) AS fees,
                   (SELECT COALESCE(sum(v), 0) FROM t WHERE dp = 'GOALKEEPER') AS gk_v,
                   (SELECT COALESCE(sum(v + f), 0) FROM t WHERE dp = 'GOALKEEPER') AS gk_inv,
                   (SELECT COALESCE(sum(v), 0) FROM t WHERE dp = 'DEFENDER') AS d_v,
                   (SELECT COALESCE(sum(v), 0) FROM t WHERE dp = 'MIDFIELDER') AS m_v,
                   (SELECT COALESCE(sum(v), 0) FROM t WHERE dp LIKE 'FORWARD\_%') AS f_v
            FROM zz_cur c)
        SELECT (k.cash + k.gk_v + k.d_v + k.m_v + k.f_v <> k.initial_cash - k.fees)::int
             + (k.d_v * 100 > k.initial_cash * k.start_defender)::int
             + (k.m_v * 100 > k.initial_cash * k.start_midfielder)::int
             + (k.f_v * 100 > k.initial_cash * k.start_forward)::int
             + ((k.cash + k.gk_inv) * 100 < k.initial_cash * k.start_goalkeeper)::int
             + (k.cash * 100 < k.initial_cash * (SELECT weight_pct FROM zz_cash))::int
        FROM k$q$),
    ('RESERVED_ZERO', '08 잔액', '대사 규칙 9: 예약금이 0 이 아닌 시즌 수 (지정가가 없어 주문 유무와 관계없이 0)',
     $q$SELECT count(*) FROM zz_cur c JOIN cash_account a ON a.season_id = c.id WHERE a.reserved <> 0$q$),
    ('FN_INSUFFICIENT', '09 함수', '잔액 계열 함수 사례(INSUFFICIENT_CASH 둘 · LEDGER_MISMATCH) 중 기대한 오류와 다르거나 행 수·identity 가 바뀐 사례 수. 사례가 하나도 없으면 1',
     $q$SELECT CASE WHEN count(*) = 0 THEN 1 ELSE count(*) FILTER (WHERE NOT ok) END FROM zz_fn WHERE code = 'FN_INSUFFICIENT'$q$),
    ('FN_TURN', '09 함수', '턴·시즌 상태 함수 사례(TURN_MISMATCH · SEASON_NOT_ACTIVE 둘) 중 기대한 오류와 다른 사례 수. 사례가 하나도 없으면 1',
     $q$SELECT CASE WHEN count(*) = 0 THEN 1 ELSE count(*) FILTER (WHERE NOT ok) END FROM zz_fn WHERE code = 'FN_TURN'$q$),
    ('FN_POOL_QTY_POS', '09 함수', '풀·수량·포지션 함수 사례(ASSET_NOT_IN_POOL · INVALID_QUANTITY 둘 · POSITION_CONFLICT · INVALID_ARGUMENT 둘) 중 기대한 오류와 다른 사례 수. 사례가 하나도 없으면 1',
     $q$SELECT CASE WHEN count(*) = 0 THEN 1 ELSE count(*) FILTER (WHERE NOT ok) END FROM zz_fn WHERE code = 'FN_POOL_QTY_POS'$q$),
    ('UNIT_CALC', '10 단위', '순수 계산 함수 5개의 합성 값 사례(수수료 반올림 경계 · 시장별 요율 · 수량 공식 · 이동평균 · 주문 가능 경계) 중 손으로 계산한 기대값과 다른 수',
     $q$SELECT count(*) FROM (VALUES
            ('수수료: 곱이 정확히 ·5 는 올림',             pg_temp.buy_fee(10000, 1, 0.00005000), 1::numeric),
            ('수수료: ·4999 는 내림',                       pg_temp.buy_fee(10000, 1, 0.00004999), 0::numeric),
            ('수수료: 123456 × 7 × 0.0065 = 5617.248',      pg_temp.buy_fee(123456, 7, 0.00650000), 5617::numeric),
            ('수수료: 100000 × 3 × 0.0065 = 1950 (딱 떨어짐)', pg_temp.buy_fee(100000, 3, 0.00650000), 1950::numeric),
            ('요율: 국내 = 국내 수수료율',                   pg_temp.buy_rate('KR', 0.00015, 0.0025, 0.0040), 0.00015000::numeric),
            ('요율: 미국 = 미국 수수료율 + 환전 비용률',     pg_temp.buy_rate('US', 0.00015, 0.0025, 0.0040), 0.00650000::numeric),
            ('수량: 1000000 ÷ (100000 × 1.0065) = 9.93 → 9', pg_temp.team_qty(1000000, 100000, 0.00650000)::numeric, 9::numeric),
            ('수량: 1000000 ÷ (100000 × 1.00015) → 9',      pg_temp.team_qty(1000000, 100000, 0.00015000)::numeric, 9::numeric),
            ('수량: 1주가 예산보다 비싸면 0',               pg_temp.team_qty(999, 1000, 0.00000000)::numeric, 0::numeric),
            ('수량: 예산이 정확히 1주 값이면 1',             pg_temp.team_qty(1000, 1000, 0.00000000)::numeric, 1::numeric),
            ('수량: 수수료 반올림이 내려가도 공식 값이 정본 (2000 ÷ (1000 × 1.0001) = 1.99 → 1)', pg_temp.team_qty(2000, 1000, 0.00010000)::numeric, 1::numeric),
            ('평균: (2 × 100 + 1 × 101) ÷ 3 = 100.3333',     pg_temp.buy_avg_price(2, 100.0000, 1, 101.0000), 100.3333::numeric),
            ('평균: 기존 수량 0 이면 체결가 (D7)',          pg_temp.buy_avg_price(0, 0.0000, 5, 70000.0000), 70000.0000::numeric),
            ('평균: ·00005 는 올림 (100.00005 → 100.0001)',  pg_temp.buy_avg_price(1, 100.0000, 1, 100.0001), 100.0001::numeric),
            ('평균: ·33333 는 내림, 같은 값 두 번이면 그대로', pg_temp.buy_avg_price(3, 100.0000, 3, 100.0000), 100.0000::numeric),
            ('주문 가능: 대금 + 수수료 = 가능 금액이면 통과 (잔액 0 경계)', pg_temp.buy_affordable(100, 99, 1)::int::numeric, 1::numeric),
            ('주문 가능: 1원 모자라면 실패',                 pg_temp.buy_affordable(100, 99, 2)::int::numeric, 0::numeric),
            ('주문 가능: 가능 금액 0 에 0원 매수는 통과',     pg_temp.buy_affordable(0, 0, 0)::int::numeric, 1::numeric),
            ('주문 가능: 가능 금액 0 에 1원 매수는 실패',     pg_temp.buy_affordable(0, 1, 0)::int::numeric, 0::numeric)
        ) AS u(label, got, want) WHERE got IS DISTINCT FROM want$q$);

-- 양성: 모든 규칙의 위반이 0건. 함수 사례(zz_fn)는 아래 음성 단계에서 채우므로 먼저 채운다.
-- 09 함수 사례 ---------------------------------------------------------------
-- 서브트랜잭션 안에서 INSERT 전에 실패하는 호출만 쓴다 (identity 를 쓰지 않는다). 연습 시즌과 아직 보유 중인(열린 라인업이 있는) 첫 명단 종목을 쓴다.
DO $$
DECLARE
    v_season bigint;
    v_turn   smallint;
    v_asset  bigint;
    v_dp     text;
    v_price  numeric;
    v_market text;
    v_rate   numeric;
    v_fee1   numeric;
    v_avail  numeric;
    v_qbig   bigint;
    v_other  text;
    v_missing bigint;
    v_noseason bigint;
    v_call   text;
    v_big    text := '1000000000000';
    v_live   text;
    v_noopen boolean := false;
BEGIN
    -- 열린 라인업이 있는 첫 종목(번호 순)을 쓴다 — 팀 구성 직후엔 명단 첫 줄이고, 매매·교체 뒤엔 지금 보유한 종목이다.
    SELECT c.id, c.current_turn_no, a.id, lu.declared_position, tp.price, a.market,
           pg_temp.buy_rate(a.market, c.fee_rate_kr, c.fee_rate_us, c.fx_spread_rate)
      INTO v_season, v_turn, v_asset, v_dp, v_price, v_market, v_rate
    FROM zz_cur c
    JOIN lineup lu ON lu.season_id = c.id AND lu.end_turn IS NULL
    JOIN asset a ON a.id = lu.asset_id
    JOIN turn_price tp ON tp.season_id = c.id AND tp.turn_no = c.current_turn_no AND tp.asset_id = a.id
    ORDER BY lu.id
    LIMIT 1;
    IF v_season IS NULL THEN
        -- 열린 구간이 하나도 없다 (예: 전액 현금). 현재 턴 가격 풀의 번호가 가장 작은 종목으로 대신하고 선언 충돌 사례만 건너뛴다.
        v_noopen := true;
        SELECT c.id, c.current_turn_no, a.id, 'MIDFIELDER', tp.price, a.market,
               pg_temp.buy_rate(a.market, c.fee_rate_kr, c.fee_rate_us, c.fx_spread_rate)
          INTO v_season, v_turn, v_asset, v_dp, v_price, v_market, v_rate
        FROM zz_cur c
        JOIN turn_price tp ON tp.season_id = c.id AND tp.turn_no = c.current_turn_no
        JOIN asset a ON a.id = tp.asset_id
        ORDER BY a.id
        LIMIT 1;
    END IF;
    IF v_season IS NULL THEN
        -- 현재 턴 가격 행이 하나도 없으면(0턴 가격으로 대신하지 않는다) 사례를 만들 수 없으므로 FAIL 로 남긴다.
        INSERT INTO zz_check_result VALUES (DEFAULT, '09 함수', '함수 사례 준비: 현재 턴 가격 행이 있는 종목을 찾았다', 'true', 'false', false);
        RETURN;
    END IF;
    v_fee1  := pg_temp.buy_fee(v_price, 1, v_rate);
    SELECT a.balance - a.reserved INTO v_avail FROM cash_account a WHERE a.season_id = v_season;
    v_qbig  := floor(v_avail / v_price)::bigint + 1;
    v_other := CASE WHEN v_dp = 'GOALKEEPER' THEN 'DEFENDER' ELSE 'GOALKEEPER' END;
    SELECT max(id) + 1 INTO v_missing FROM asset;
    SELECT max(id) + 1 INTO v_noseason FROM season;

    -- 끝난 시즌(FINISHED)·포기 중에서도 기대한 오류까지 가도록, 상태 검사를 시험하는 사례가 아니면 진행 중으로 되돌린 서브트랜잭션에서 부른다
    v_live := format('UPDATE season SET status = ''IN_PROGRESS'', abandoned_turn_no = NULL WHERE id = %s', v_season);
    v_call := format('SELECT pg_temp.order_buy(%s, %s::smallint, %s, %s, %L)', v_season, v_turn, v_asset, '%s', v_dp);

    -- FN_INSUFFICIENT: ① 주문 가능 금액보다 대금이 큰 수량 ② 대금 + 수수료가 주문 가능 금액보다 정확히 1원 큰 경계 ③ 계좌 잔액 ≠ 최신 원장
    CALL pg_temp.neg_fn('FN_INSUFFICIENT', '주문 가능 금액을 넘는 수량이면',
        v_live, format(v_call, v_qbig), 'INSUFFICIENT_CASH');
    CALL pg_temp.neg_fn('FN_INSUFFICIENT', '대금 + 수수료가 주문 가능 금액보다 1원 크면 (잔액과 최신 원장을 함께 맞춘 경계)',
        v_live || '; ' || format('UPDATE cash_account SET balance = %s WHERE season_id = %s; UPDATE cash_ledger SET balance_after = %s WHERE id = (SELECT max(id) FROM cash_ledger WHERE season_id = %s)',
               v_price + v_fee1 - 1, v_season, v_price + v_fee1 - 1, v_season),
        format(v_call, 1), 'INSUFFICIENT_CASH');
    CALL pg_temp.neg_fn('FN_INSUFFICIENT', '계좌 잔액이 최신 원장의 거래 후 잔액과 다르면 (망가진 계좌 위에 쓰지 않는다)',
        v_live || '; ' || format('UPDATE cash_account SET balance = balance + 1 WHERE season_id = %s', v_season),
        format(v_call, 1), 'LEDGER_MISMATCH');

    -- FN_TURN
    CALL pg_temp.neg_fn('FN_TURN', '주문이 보는 턴이 시즌의 현재 턴과 다르면',
        v_live, format('SELECT pg_temp.order_buy(%s, %s::smallint, %s, 1, %L)', v_season, v_turn + 1, v_asset, v_dp), 'TURN_MISMATCH');
    CALL pg_temp.neg_fn('FN_TURN', '시즌이 진행 중이 아니면 (포기 중으로 바꾼 서브트랜잭션)',
        format('UPDATE season SET status = ''ABANDONING'', abandoned_turn_no = 0 WHERE id = %s', v_season),
        format(v_call, 1), 'SEASON_NOT_ACTIVE');
    CALL pg_temp.neg_fn('FN_TURN', '없는 시즌이면',
        NULL, format('SELECT pg_temp.order_buy(%s, %s::smallint, %s, 1, %L)', v_noseason, v_turn, v_asset, v_dp), 'SEASON_NOT_ACTIVE');

    -- FN_POOL_QTY_POS (잔액이 모자라 다른 오류가 먼저 나오지 않게 충분한 잔액으로 맞춘 서브트랜잭션을 쓴다)
    CALL pg_temp.neg_fn('FN_POOL_QTY_POS', '현재 턴 가격 풀에 없는 종목이면',
        v_live, format('SELECT pg_temp.order_buy(%s, %s::smallint, %s, 1, %L)', v_season, v_turn, v_missing, v_dp), 'ASSET_NOT_IN_POOL');
    CALL pg_temp.neg_fn('FN_POOL_QTY_POS', '수량이 0 이면',
        NULL, format(v_call, 0), 'INVALID_QUANTITY');
    CALL pg_temp.neg_fn('FN_POOL_QTY_POS', '수량이 음수면',
        NULL, format(v_call, -1), 'INVALID_QUANTITY');
    IF NOT v_noopen THEN
        CALL pg_temp.neg_fn('FN_POOL_QTY_POS', '열린 라인업과 다른 선언 포지션이면',
            v_live || '; ' || format('UPDATE cash_account SET balance = %s WHERE season_id = %s; UPDATE cash_ledger SET balance_after = %s WHERE id = (SELECT max(id) FROM cash_ledger WHERE season_id = %s)',
                   v_big, v_season, v_big, v_season),
            format('SELECT pg_temp.order_buy(%s, %s::smallint, %s, 1, %L)', v_season, v_turn, v_asset, v_other), 'POSITION_CONFLICT');
    ELSE
        CALL pg_temp.na_row('09 함수', '열린 라인업이 없어 선언 포지션 충돌 사례는 적용 불가 (정보)');
    END IF;
    CALL pg_temp.neg_fn('FN_POOL_QTY_POS', '선언 포지션이 여섯 가지 안에 없으면',
        NULL, format('SELECT pg_temp.order_buy(%s, %s::smallint, %s, 1, %L)', v_season, v_turn, v_asset, 'FORWARD'), 'INVALID_ARGUMENT');
    CALL pg_temp.neg_fn('FN_POOL_QTY_POS', 'source 가 DIRECT 가 아니면',
        NULL, format('SELECT pg_temp.order_buy(%s, %s::smallint, %s, 1, %L, %L)', v_season, v_turn, v_asset, v_dp, 'FEED'), 'INVALID_ARGUMENT');
END $$;

-- 양성
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT q.section, '[' || q.code || '] ' || q.item, '0', pg_temp.cnt(q.sql)::text, pg_temp.cnt(q.sql) = 0
FROM zz_q q ORDER BY q.code;

-- 음성 사례 --------------------------------------------------------------------
-- 규칙을 일부러 어겨 보고 잡히는지 본다. UPDATE · DELETE · DDL 만 쓰고 INSERT 는 쓰지 않는다 (번호표를 쓰지 않는다).
-- 한 사례에 조건 하나만 어긴다 (P0-05 R12).

-- 01 팀
CALL pg_temp.neg_viol('TEAM_SIZE', '한 주문의 턴을 1로 바꾸면 (0턴 매수 종목이 하나 줄어든다)',
    $m$UPDATE orders SET turn_no = 1 WHERE id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY')$m$, 1);
CALL pg_temp.neg_viol('TEAM_LIST', '한 라인업(시작 턴 0)의 선언 포지션을 바꾸면',
    $m$UPDATE lineup SET declared_position = 'FORWARD_DEFENSIVE'
       WHERE id = (SELECT min(lu.id) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.start_turn = 0 AND lu.declared_position = 'DEFENDER')$m$, 1);
CALL pg_temp.neg_viol('ORDER_SHAPE', '한 주문의 source 를 RANKING 으로 바꾸면 (피드 번호 규칙은 건드리지 않는다)',
    $m$UPDATE orders SET source = 'RANKING' WHERE id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0)$m$, 1);

-- 02 수량
CALL pg_temp.neg_viol('QTY_RULE', '한 주문과 그 체결의 수량을 +1 하면 (예산을 넘고 공식과 달라진다)',
    $m$UPDATE orders SET quantity = quantity + 1 WHERE id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY');
       UPDATE execution SET quantity = quantity + 1 WHERE order_id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY')$m$, 1);

-- 03 체결
CALL pg_temp.neg_viol('FILL_PRICE', '한 체결의 체결가를 +1 하면',
    $m$UPDATE execution SET fill_price = fill_price + 1 WHERE order_id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY')$m$, 1);
CALL pg_temp.neg_viol('FEE_RULE', '한 체결의 수수료를 +1 하면',
    $m$UPDATE execution SET fee = fee + 1 WHERE order_id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY')$m$, 1);
CALL pg_temp.neg_viol('EXEC_VALUES', '매수 체결의 realized_pnl 을 0 으로 채우면',
    $m$UPDATE execution SET realized_pnl = 0 WHERE order_id = (SELECT min(o.id) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY')$m$, 1);

-- 04 원장
CALL pg_temp.neg_viol('CASH_SUM', '잔액을 1 늘리면 (잔액이 0 이어도 CHECK 를 건드리지 않는다)',
    $m$UPDATE cash_account SET balance = balance + 1 WHERE season_id IN (SELECT id FROM zz_cur)$m$, 1);
CALL pg_temp.neg_viol('LEDGER_CHAIN', '한 원장 행(첫 매수의 BUY)의 거래 후 잔액을 1 줄이면',
    $m$UPDATE cash_ledger SET balance_after = balance_after - 1 WHERE id = (SELECT min(l.id) FROM cash_ledger l JOIN zz_cur c ON c.id = l.season_id WHERE l.type = 'BUY')$m$, 1);
CALL pg_temp.neg_viol('ORDER_LEDGER', '총액은 맞고 분류만 틀리게: BUY 행을 −(대금 + 수수료)로 올리고 FEE 행을 지우면',
    $m$UPDATE cash_ledger b SET amount = b.amount + f.amount
       FROM cash_ledger f
       WHERE f.type = 'FEE' AND f.ref_order_id = b.ref_order_id AND b.type = 'BUY'
         AND b.id = (SELECT min(l.id) FROM cash_ledger l JOIN zz_cur c ON c.id = l.season_id WHERE l.type = 'BUY');
       DELETE FROM cash_ledger WHERE type = 'FEE'
         AND ref_order_id = (SELECT l.ref_order_id FROM cash_ledger l WHERE l.id = (SELECT min(l2.id) FROM cash_ledger l2 JOIN zz_cur c ON c.id = l2.season_id WHERE l2.type = 'BUY'))$m$, 1);
CALL pg_temp.neg_viol('LEDGER_ORDER', '한 매수의 BUY 행과 FEE 행의 유형을 맞바꾸면 (먼저 기록된 행이 FEE 가 된다)',
    $m$UPDATE cash_ledger SET type = CASE type WHEN 'BUY' THEN 'FEE' ELSE 'BUY' END
       WHERE type IN ('BUY', 'FEE')
         AND ref_order_id = (SELECT l.ref_order_id FROM cash_ledger l WHERE l.id = (SELECT min(l2.id) FROM cash_ledger l2 JOIN zz_cur c ON c.id = l2.season_id WHERE l2.type = 'BUY'))$m$, 1);

-- 05 보유
CALL pg_temp.neg_viol('HOLDING_QTY', '한 보유의 수량을 +1 하면',
    $m$UPDATE holding SET quantity = quantity + 1 WHERE id = (SELECT min(h.id) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0)$m$, 1,
    $w$SELECT count(*) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0$w$);
CALL pg_temp.neg_viol('AVG_PRICE', '한 보유의 평균 단가를 +0.0001 하면',
    $m$UPDATE holding SET avg_price = avg_price + 0.0001 WHERE id = (SELECT min(h.id) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0)$m$, 1,
    $w$SELECT count(*) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0$w$);

-- 06 라인업
CALL pg_temp.neg_viol('LINEUP_HOLDING', '보유가 있는 종목의 열린 라인업 구간을 닫으면 (end_turn = start_turn)',
    $m$UPDATE lineup SET end_turn = start_turn WHERE id = (SELECT min(lu.id) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.end_turn IS NULL)$m$, 1,
    $w$SELECT count(*) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.end_turn IS NULL$w$);
CALL pg_temp.neg_viol('LINEUP_OVERLAP', '열린 라인업의 시작 턴을 현재 턴 + 1 로 바꾸면 (현재 턴을 넘는다)',
    $m$UPDATE lineup SET start_turn = (SELECT current_turn_no + 1 FROM zz_cur) WHERE id = (SELECT min(lu.id) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.end_turn IS NULL)$m$, 1,
    $w$SELECT count(*) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.end_turn IS NULL$w$);
CALL pg_temp.neg_viol('DECLARED_DIFF', '모든 선언을 시스템 판정과 같은 값으로 바꾸면 (선언 ≠ 판정 사례가 사라진다)',
    $m$UPDATE lineup lu SET declared_position = ap.system_position
       FROM zz_cur c, asset_position ap
       WHERE lu.season_id = c.id AND lu.start_turn = 0 AND ap.asset_id = lu.asset_id AND ap.base_date = c.base_date$m$, 1);

-- 07 포메이션
CALL pg_temp.neg_viol('FORMATION_ACTUAL', '수비 한 줄의 선언을 공격으로 바꾸면 (공격 분류가 목표를 넘는다)',
    $m$UPDATE lineup SET declared_position = 'FORWARD_TARGET'
       WHERE id = (SELECT min(lu.id) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.start_turn = 0 AND lu.declared_position = 'DEFENDER')$m$, 1);

-- 08 잔액
CALL pg_temp.neg_viol('RESERVED_ZERO', '예약금을 1 로 하면 (잔액이 0 이어도 CHECK 를 건드리지 않게 잔액도 +1)',
    $m$UPDATE cash_account SET balance = balance + 1, reserved = 1 WHERE season_id IN (SELECT id FROM zz_cur)$m$, 1);

-- 10 단위: 정의를 바꾼 변이를 서브트랜잭션 안에서 만들고 기대와 달라지는지 본다 (함수 정의 변경도 되돌아간다)
CALL pg_temp.neg_viol('UNIT_CALC', '변이 "수수료를 버림(trunc)으로": 반올림 경계 사례가 기대와 달라진다',
    $m$CREATE OR REPLACE FUNCTION pg_temp.buy_fee(p_price numeric, p_qty integer, p_rate numeric) RETURNS numeric
       LANGUAGE sql IMMUTABLE AS $f$ SELECT trunc(p_price * p_qty * p_rate, 0) $f$$m$, 1);
CALL pg_temp.neg_viol('UNIT_CALC', '변이 "수량 공식에서 수수료 누락": floor(예산 ÷ 체결가)로 바꾸면 수량 사례가 기대와 달라진다',
    $m$CREATE OR REPLACE FUNCTION pg_temp.team_qty(p_budget numeric, p_price numeric, p_rate numeric) RETURNS integer
       LANGUAGE sql IMMUTABLE AS $f$ SELECT div(p_budget, p_price)::integer $f$$m$, 1);
CALL pg_temp.neg_viol('UNIT_CALC', '변이 "이동평균을 버림으로": 평균 사례가 기대와 달라진다',
    $m$CREATE OR REPLACE FUNCTION pg_temp.buy_avg_price(p_old_q integer, p_old_avg numeric, p_q integer, p_price numeric) RETURNS numeric
       LANGUAGE sql IMMUTABLE AS $f$ SELECT trunc((p_old_q * p_old_avg + p_q * p_price) / (p_old_q + p_q), 4) $f$$m$, 1);

-- 11 정보 (판정에 들어가지 않는다) ----------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '0턴 매수 줄 수 · 열린 라인업 수 · FEE 원장 행 수 (정보)', '(정보)',
       (SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY')::text || ' · '
       || (SELECT count(*) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.end_turn IS NULL)::text || ' · '
       || (SELECT count(*) FROM cash_ledger l JOIN zz_cur c ON c.id = l.season_id WHERE l.type = 'FEE')::text, true;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '선언 ≠ 시스템 판정인 줄 수: 6분류 기준 · 4분류(골키퍼·수비·미드필더·공격) 기준 (정보)', '(정보)',
       count(*) FILTER (WHERE lu.declared_position <> ap.system_position)::text || ' · '
       || count(*) FILTER (WHERE CASE WHEN lu.declared_position LIKE 'FORWARD\_%' THEN 'FORWARD' ELSE lu.declared_position END
                                 <> CASE WHEN ap.system_position LIKE 'FORWARD\_%' THEN 'FORWARD' ELSE ap.system_position END)::text, true
FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id
JOIN asset_position ap ON ap.asset_id = lu.asset_id AND ap.base_date = c.base_date
WHERE lu.start_turn = 0;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '골키퍼로 선언했지만 시스템 판정이 골키퍼가 아닌 줄 수 (정보)', '(정보)',
       count(*)::text, true
FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id
JOIN asset_position ap ON ap.asset_id = lu.asset_id AND ap.base_date = c.base_date
WHERE lu.start_turn = 0 AND lu.declared_position = 'GOALKEEPER' AND ap.system_position <> 'GOALKEEPER';
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '팀 직후 상태다 (보유 수량 = 팀 수량 · 열린 라인업 = 명단 줄 수 · current_turn_no 0 — 이후에는 false, 규칙에는 넣지 않는다) (정보)', '(정보)',
       ((SELECT current_turn_no FROM zz_cur) = 0
        AND (SELECT count(*) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.end_turn IS NULL) = (SELECT count(*) FROM zz_team))::text, true;

-- 12 판정 ---------------------------------------------------------------------
-- 12.1 규칙 커버리지: 음성 사례가 통과한 규칙 코드 수 / 요구 코드 수. 요구 = 이 DB 에서 적용 가능한 사례가 하나라도 있는 코드
--       (매매 뒤 대상이 없는 포트폴리오 — 예: 전액 현금 — 의 "적용 불가" 사례만 있는 코드는 요구에서 빠진다. 적용 불가 코드는 항목 이름에 적는다).
-- applicable is read from the column, never from the item text.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '12 판정', '규칙 커버리지 (적용 가능한 음성 사례가 통과한 규칙 코드 / 적용 가능한 규칙 코드 — 적용 불가 코드: '
       || COALESCE(string_agg(c.code, ',' ORDER BY c.code) FILTER (WHERE c.n_app = 0 AND c.n_na > 0), '없음') || ')',
       count(*) FILTER (WHERE c.n_app > 0 OR c.n_na = 0) || '/' || count(*) FILTER (WHERE c.n_app > 0 OR c.n_na = 0),
       count(*) FILTER (WHERE c.n_app > 0 AND c.n_bad = 0) || '/' || count(*) FILTER (WHERE c.n_app > 0 OR c.n_na = 0),
       count(*) FILTER (WHERE c.n_app > 0 OR c.n_na = 0) > 0
       AND count(*) FILTER (WHERE c.n_app > 0 AND c.n_bad = 0) = count(*) FILTER (WHERE c.n_app > 0 OR c.n_na = 0)
FROM (SELECT q.code,
             count(*) FILTER (WHERE r.applicable) AS n_app,
             count(*) FILTER (WHERE r.applicable AND NOT r.ok) AS n_bad,
             count(*) FILTER (WHERE NOT r.applicable) AS n_na
      FROM zz_q q LEFT JOIN zz_check_result r ON r.item LIKE '음성 [' || q.code || ']%'
      GROUP BY q.code) c;

-- 12.1b Canonical state: right after the team is built (turn 0, every team line still open) every negative case has a target,
--       so no row may be not applicable. Otherwise a silently skipped case would still pass. Elsewhere the check is informational.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '12 판정',
       CASE WHEN s.canon THEN '정본 상태(팀 직후: 현재 턴 0 · 열린 라인업 = 명단 줄 수)에서 적용 불가 행 수'
            ELSE '정본 상태(팀 직후)가 아니라 적용 불가 단언은 건너뜀 (정보)' END,
       CASE WHEN s.canon THEN '0' ELSE '(정보)' END,
       CASE WHEN s.canon THEN s.na::text ELSE '건너뜀' END,
       NOT s.canon OR s.na = 0
FROM (SELECT (SELECT current_turn_no FROM zz_cur) = 0
             AND (SELECT count(*) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.end_turn IS NULL) = (SELECT count(*) FROM zz_team) AS canon,
             (SELECT count(*) FROM zz_check_result WHERE NOT applicable) AS na) s;

-- 12.2 절별 집계 (total / pass / fail / na)
SELECT section, n_rows AS total, pass, fail, na
FROM zz_tally
ORDER BY section;

-- 12.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 12.3b 커버리지 행 (PASS 출력에서도 N/N 이 보이게)
SELECT item, expected, actual FROM zz_check_result WHERE section = '12 판정' ORDER BY seq;

-- 12.4 판정 행
-- 적용 불가 정보 행은 통과 사례 수에 세지 않는다 (P0-19: 대상이 없는 포트폴리오의 음성 사례)
SELECT CASE WHEN sum(total) > 0 AND sum(fail) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       sum(total) AS total, sum(pass) AS pass, sum(fail) AS fail, sum(na) AS not_applicable
FROM zz_tally;

-- 12.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3).
--      판정 표식은 트랜잭션이 되돌아가도 남는다. 통과해도 2(흔적 확인 대기)까지만 올린다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT COALESCE(sum(fail), 0), COALESCE(sum(total), 0) INTO v_fail, v_total FROM zz_tally;
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-15 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 13 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 읽거나 일부러 바꿔 본 표들의 행 수와 identity 마지막 값이 실행 전과 같고, public 에 zz_ 객체가 없다.
-- 검증은 INSERT 를 하지 않으므로 번호표를 소비하지 않는다.
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
        PERFORM setval('pg_temp.zz_verdict', 1);
        RAISE EXCEPTION E'P0-15 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    -- 단언을 모두 통과했고 00–12절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_15_check;
