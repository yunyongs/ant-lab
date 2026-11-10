-- =====================================================================
-- my Fund Manager Phase 0 — 턴 사이 매매와 전술 변경 검증 SQL (P0-19)
-- 대상: db/25_trade_formation.sql 이 만든 1턴 이후 주문·체결·보유·원장·라인업·전술 변경과 db/25_trade_formation_def.sql 의 함수 (기록은 일반 상태 — 시나리오가 아닌 다른 매매도 같은 규칙으로 본다)
-- 근거: docs/erd.md 6.3·6.5 S6·S7·7.5(대사 규칙 2·3·4·6·7·8·9·13–18)·D4·D7·D9·D23·D26·D29–D31·9절 P0-19, docs/PLAN.md §4.6·§4.10·§4.13·§5.2, db/21_team_buy_check.sql·db/23_turn_advance_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/25_trade_formation_check.sql
--       25 시나리오(또는 다른 매매)를 실행한 DB 에서 한다. INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아 실습 DB 에서도 돌릴 수 있다. 정의 파일(21·22·25)을 \ir 로 읽는다.
--       범위: 1턴 이후 주문이 하나 이상 있어야 한다 (없으면 0절 FAIL). 턴 중간(매매 직후)에도, 턴 전환 뒤·FINISHED 뒤에도 PASS 해야 한다 (규칙 11·19 는 보지 않는다 — 23 검증의 몫).
-- 판정: 마지막 줄 `p0_19_check` 가 `PASS` 이고 종료 코드가 0이면 통과다. 0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고, 그 밖에 하나라도 어긋나면 13절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
-- 흔적: 파일 전체가 한 트랜잭션이고 13절 뒤에 ROLLBACK 한다. 14절이 행 수·identity 마지막 값이 실행 전과 같고 public 에 zz_ 객체가 없는지 확인한다.
-- 절:   00 전제 → 01 주문·체결 모양 → 02 원장 → 03 이력 재생 → 04 라인업 → 05 전술 변경 → 06 출발점 → 07 변경 비용 → 08 대사 연동 → 09 거부 사례 → 10 함수 양성 → 11 정보 → 12 (예약) → 13 판정 → 14 흔적
-- 판정의 틀: 규칙마다 위반 개수를 돌려주는 쿼리(양성 0건). 같은 쿼리로 음성 사례(UPDATE·DELETE 로 규칙을 일부러 어긴 뒤 개수 ≥ 기대)를 서브트랜잭션에서 시험한다.
--       음성 사례의 대상이 이 DB 의 포트폴리오에 없으면(예: 구간이 둘인 종목이 없음) 사례는 "적용 불가" 정보 행이 되고 그 규칙은 커버리지 요구에서 빠진다. 적용 불가 행은 통과 사례 수에 세지 않는다.
--       기대값은 생성 함수의 반환이 아니라 저장된 체결 이력을 순서대로 다시 재생해 독립 계산한다 (zz_replay: 수량·평균단가·실현 손익).
-- 출력: 건수와 판정만. 가격·수량·금액은 내지 않는다.
-- 이 파일이 INSERT 없이 시험하지 못하는 것 (드릴 db/25_trade_formation_drill.sql 이 버리는 DB 에서 한다): 정상 매매 성공 경로, 실제 롤백·원자성, 두 세션 경합, 주문 없는 전술 변경, 같은 턴 여러 변경,
--       길이 없는 구간, ALERT 성공 사례(합성 NUDGE). 다른 시즌 피드는 DDL 의 복합 FK 가 막고 함수는 "없는 피드"와 같은 경로로 거절한다.
-- 새 문법: 절차형 반복으로 체결 이력 재생(RETURN NEXT), LATERAL 로 앞선 마지막 변경 찾기, 창 함수 lag() 로 사슬 확인, generate_series × 시점 조건, 서브트랜잭션 변이, 적용 가능 커버리지.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\ir 21_team_buy_def.sql
\ir 22_reconcile_season_def.sql
\ir 25_trade_formation_def.sql

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 00–13절 통과·흔적 확인 대기, 0 = 최종 통과)
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
    FOREACH t IN ARRAY ARRAY['season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'turn_price', 'turn', 'formation_change',
                             'turn_feed', 'reconciliation_log', 'asset_position'] LOOP
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

-- 연습 시즌 찾기: 20·21·22·23 파일과 같은 이메일
CREATE TEMP VIEW zz_cur AS
SELECT s.* FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
-- 1턴 이후 주문 (이 파일의 검사 대상)
CREATE TEMP VIEW zz_o AS
SELECT o.* FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no >= 1;

-- 판정 프로시저 ------------------------------------------------------------

CREATE FUNCTION pg_temp.cnt(p_sql text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v bigint;
BEGIN
    EXECUTE p_sql INTO v;
    RETURN v;
END $$;

CREATE PROCEDURE pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- 체결 이력 재생 (독립 계산): 종목마다 id 순서로 주문을 재생해 수량·평균단가(이동평균, 수수료 제외, 소수 4자리 HALF_UP)·실현 손익을 다시 만든다.
--   생성 함수(trade_*)·21 의 정수 산술과 다른 코드다 (numeric 나눗셈 + ROUND). 나눗셈은 소수 16자리 이상 계산되어 4자리 반올림 경계를 건드리지 않는다.
CREATE FUNCTION pg_temp.zz_replay(p_season bigint)
RETURNS TABLE (seq integer, order_id bigint, asset_id bigint, turn_no smallint, side text, qty_before integer, qty_after integer,
               avg_before numeric, avg_after numeric, pnl numeric, bad boolean)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    r    record;
    q    integer := 0;
    a    numeric := 0;
    prev bigint := -1;
    n    integer := 0;
BEGIN
    FOR r IN SELECT o.id, o.asset_id AS aid, o.turn_no AS tno, o.side AS sd, e.quantity AS qty, e.fill_price AS px
             FROM orders o JOIN execution e ON e.order_id = o.id
             WHERE o.season_id = p_season AND o.status = 'FILLED'
             ORDER BY o.asset_id, o.id LOOP
        IF r.aid <> prev THEN q := 0; a := 0; prev := r.aid; END IF;
        n := n + 1;
        seq := n; order_id := r.id; asset_id := r.aid; turn_no := r.tno; side := r.sd; qty_before := q; avg_before := a;
        IF r.sd = 'BUY' THEN
            a := ROUND((q * a + r.qty * r.px) / (q + r.qty), 4);
            q := q + r.qty;
            pnl := NULL; bad := false;
        ELSE
            bad := r.qty > q;
            pnl := ROUND((r.px - a) * r.qty, 4);
            q := q - r.qty;
            IF q <= 0 THEN a := 0; END IF;
        END IF;
        qty_after := q; avg_after := a;
        RETURN NEXT;
    END LOOP;
END $$;

-- 검사 쿼리 목록: 위반 개수를 돌려주는 쿼리. 양성(0건)과 음성(위반을 만든 뒤 기대한 개수)에 같은 쿼리를 쓴다.
CREATE TEMP TABLE zz_q (code text PRIMARY KEY, section text NOT NULL, item text NOT NULL, sql text NOT NULL);

CREATE FUNCTION pg_temp.sec(p_code text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN
    SELECT section INTO v FROM zz_q WHERE code = p_code;
    RETURN v;
END $$;

-- 음성 사례(위반 개수형): p_when(참이어야 하는 쿼리, NULL 이면 항상)이 거짓이면 "적용 불가" 정보 행. 아니면 바꾼 뒤 규칙 쿼리를 돌려 위반이 p_min 건 이상 나와야 한다.
CREATE PROCEDURE pg_temp.neg_viol(p_code text, p_label text, p_mutate text, p_min bigint, p_when text DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_sql text := (SELECT sql FROM zz_q WHERE code = p_code);
    v_n   text;
BEGIN
    IF p_when IS NOT NULL AND NOT (SELECT x FROM (SELECT pg_temp.cnt(p_when) > 0 AS x) y) THEN
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES (pg_temp.sec(p_code), '음성 [' || p_code || '] ' || p_label || ' — 적용 불가 (이 포트폴리오에 대상이 없다) (정보)', '(정보)', '적용 불가', true);
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

-- 음성 사례(대사 규칙형): 바꾼 뒤 22 의 수동 검사 15개에서 불일치로 잡힌 규칙 번호가 기대 집합을 모두 포함해야 한다 (연쇄로 더 걸려도 된다).
CREATE PROCEDURE pg_temp.neg_rules(p_label text, p_mutate text, p_expect int[], p_when text DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_got int[];
    v_err text;
BEGIN
    IF p_when IS NOT NULL AND NOT (SELECT x FROM (SELECT pg_temp.cnt(p_when) > 0 AS x) y) THEN
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('08 대사', '음성 [RECON15] ' || p_label || ' — 적용 불가 (이 포트폴리오에 대상이 없다) (정보)', '(정보)', '적용 불가', true);
        RETURN;
    END IF;
    BEGIN
        EXECUTE p_mutate;
        SELECT COALESCE(array_agg(q.rule_no::int ORDER BY q.rule_no) FILTER (WHERE q.status = 'MISMATCH'), ARRAY[]::int[]) INTO v_got
        FROM pg_temp.recon_rules((SELECT id FROM zz_cur), (SELECT current_turn_no FROM zz_cur)) q;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            v_err := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
        END IF;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('08 대사', '음성 [RECON15] ' || p_label, '불일치 규칙 ⊇ {' || array_to_string(p_expect, ',') || '}',
            COALESCE(v_err, '불일치 규칙 {' || array_to_string(v_got, ',') || '}'),
            v_err IS NULL AND v_got @> p_expect);
END $$;

-- 음성 사례(함수형): 준비 문장을 실행한 뒤 호출이 P0001 + 기대한 HINT 로 실패해야 하고, 실패한 뒤 행 수·identity 가 그대로여야 한다.
CREATE PROCEDURE pg_temp.neg_fn(p_label text, p_prep text, p_call text, p_hint text)
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
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('09 거부', '거부 [' || p_hint || '] ' || p_label, 'P0001 ' || p_hint || ', 행 수·identity 그대로', v_act, v_ok);
END $$;

-- 양성 사례(지난 턴 피드): 출발점 검사 tr_source 가 오류 없이 돌아오고, 그 피드의 턴이 현재 턴보다 앞서며 종류가 출발점과 맞아야 한다.
-- 이 파일은 identity 를 소비하는 쓰기를 하지 않으므로(14절이 마지막 값을 비교한다) trade_buy 성공 경로는 드릴 pastfeed 가 맡는다. tr_source 는 쓰지 않는다.
CREATE PROCEDURE pg_temp.pos_past_feed(p_label text, p_season bigint, p_turn smallint, p_source text, p_feed bigint)
LANGUAGE plpgsql AS $$
DECLARE
    v_before text := pg_temp.zz_snap();
    v_act    text := '검사 통과';
    v_ok     boolean;
BEGIN
    IF p_feed IS NULL THEN
        INSERT INTO zz_check_result VALUES (DEFAULT, '10 양성', '적용 불가 (정보): 지난 턴에 ' || p_source || ' 용 피드가 없어 ' || p_label || ' 는 건너뜀', '(정보)', '적용 불가', true);
        RETURN;
    END IF;
    BEGIN
        PERFORM pg_temp.tr_source(p_season, p_turn, p_source, p_feed);
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_act = PG_EXCEPTION_HINT;
        v_act := '실패 ' || SQLSTATE || ' ' || COALESCE(v_act, '-');
    END;
    v_ok := COALESCE(v_act = '검사 통과'
            AND (SELECT f.turn_no < p_turn AND f.season_id = p_season AND ((p_source = 'ALERT') = (f.kind = 'NUDGE')) FROM turn_feed f WHERE f.id = p_feed)
            AND v_before = pg_temp.zz_snap(), false);  -- 피드 행이 없으면 NULL 이 되므로 실패로 본다
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('10 양성', '양성 ' || p_label, '검사 통과, 피드 턴 < 현재 턴, 종류 일치, 행 수·identity 그대로', CASE WHEN v_ok THEN '검사 통과, 피드 턴 < 현재 턴, 종류 일치, 행 수·identity 그대로' ELSE v_act || ' (조건 불일치)' END, v_ok);
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
    ('15개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '15',
        (SELECT count(*)::text FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'formation_change', 'turn_feed',
                                                 'turn_price', 'season_path', 'asset', 'asset_position', 'reconciliation_log']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('정의 파일을 읽었다 (trade_buy·trade_sell·formation_change·lineup_change·formation_costs·recon_rules 가 있다)', '6',
        ((to_regprocedure('pg_temp.trade_buy(bigint, smallint, bigint, integer, text, text, bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.trade_sell(bigint, smallint, bigint, integer, text, bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.formation_change(bigint, smallint, smallint, smallint, smallint, smallint, text)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.lineup_change(bigint, smallint, bigint, text)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.formation_costs(bigint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.recon_rules(bigint, smallint)') IS NOT NULL)::int)::text),
    ('연습 시즌이 정확히 1개 있다', '1', (SELECT count(*)::text FROM zz_cur)),
    ('1턴 이후 주문이 있다 (없으면 db/25_trade_formation.sql 을 먼저 실행한다)', 'true', ((SELECT count(*) FROM zz_o) > 0)::text),
    ('현재 턴 ≥ 1 이다 (0턴 진입점은 매매를 거부한다)', 'true', ((SELECT current_turn_no FROM zz_cur) >= 1)::text)
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
        RAISE EXCEPTION 'P0-19 전제 실패: 00절 FAIL % 건 (위의 00절 결과를 본다. 01–03 스키마와 10 적재, 12 판정, 20 시즌 시작, 21 팀 구성, 23 턴 진행 5회, 25 시나리오를 먼저 실행했는지 확인한다)', v_fail;
    END IF;
END $$;

\o /dev/null
-- 규칙 쿼리 -------------------------------------------------------------------
INSERT INTO zz_q (code, section, item, sql) VALUES
    -- 01 주문·체결 모양
    ('ORDER_SHAPE', '01 모양', '1턴 이후 주문 중 시장가·체결 완료·지정가 없음·체결 정확히 1건·체결 수량 = 주문 수량·세금 0·(매수 ⇔ 실현 손익 NULL)이 아닌 수',
     $q$SELECT count(*) FROM zz_o o
        WHERE o.type <> 'MARKET' OR o.status <> 'FILLED' OR o.limit_price IS NOT NULL OR o.side NOT IN ('BUY', 'SELL')
           OR (SELECT count(*) FROM execution e WHERE e.order_id = o.id) <> 1
           OR EXISTS (SELECT 1 FROM execution e WHERE e.order_id = o.id AND (e.quantity <> o.quantity OR e.tax <> 0 OR (o.side = 'BUY') <> (e.realized_pnl IS NULL)))$q$),
    ('FILL_PRICE', '01 모양', '체결가 ≠ 그 턴의 turn_price 인 체결 수 (가격 행이 없어도 위반) (대사 규칙 13)',
     $q$SELECT count(*) FROM zz_o o JOIN execution e ON e.order_id = o.id
        LEFT JOIN turn_price tp ON tp.season_id = o.season_id AND tp.turn_no = o.turn_no AND tp.asset_id = o.asset_id
        WHERE tp.price IS NULL OR e.fill_price <> tp.price$q$),
    ('FEE_INDEP', '01 모양', '수수료 ≠ ROUND(체결가 × 수량 × 시장 요율, 0) 인 체결 수 (국내 = 국내 요율, 미국 = 미국 요율 + 환전 비용률 — 독립 식, 대사 규칙 14)',
     $q$SELECT count(*) FROM zz_o o JOIN execution e ON e.order_id = o.id JOIN asset a ON a.id = o.asset_id JOIN zz_cur s ON s.id = o.season_id
        WHERE e.fee IS DISTINCT FROM ROUND(e.fill_price * e.quantity * CASE a.market WHEN 'KR' THEN s.fee_rate_kr ELSE s.fee_rate_us + s.fx_spread_rate END, 0)$q$),
    ('TURN_RANGE', '01 모양', '주문 턴이 현재 턴을 넘거나 경로 길이 이상이거나 포기한 턴을 넘는 주문 수 (S7)',
     $q$SELECT count(*) FROM zz_o o JOIN zz_cur s ON s.id = o.season_id
        WHERE o.turn_no > s.current_turn_no OR o.turn_no >= s.path_length OR (s.status = 'ABANDONING' AND o.turn_no > s.abandoned_turn_no)$q$),
    -- 02 원장
    ('LEDGER_KIND', '02 원장', '주문마다 원장 행 수 = 1 + (수수료 > 0), 첫 행 유형 = 방향·금액 = ∓체결 대금, 수수료 행은 그 뒤(id)에 −수수료, TAX 행 없음 — 어긋난 주문 수',
     $q$SELECT count(*) FROM zz_o o JOIN execution e ON e.order_id = o.id
        WHERE (SELECT count(*) FROM cash_ledger l WHERE l.ref_order_id = o.id) <> 1 + (e.fee > 0)::int
           OR NOT EXISTS (SELECT 1 FROM cash_ledger l WHERE l.ref_order_id = o.id AND l.type = o.side
                          AND l.amount = CASE o.side WHEN 'BUY' THEN -1 ELSE 1 END * e.fill_price * e.quantity)
           OR (e.fee > 0 AND NOT EXISTS (SELECT 1 FROM cash_ledger b JOIN cash_ledger f ON f.ref_order_id = b.ref_order_id AND f.type = 'FEE' AND f.id > b.id
                                         WHERE b.ref_order_id = o.id AND b.type = o.side AND f.amount = -e.fee))
           OR EXISTS (SELECT 1 FROM cash_ledger l WHERE l.ref_order_id = o.id AND l.type = 'TAX')$q$),
    ('LEDGER_CHAIN', '02 원장', '시즌 원장의 거래 후 잔액 연쇄(앞 행 + 금액)가 어긋난 행 수 + 마지막 행 ≠ 계좌 잔액 (대사 규칙 3)',
     $q$SELECT (SELECT count(*) FROM (SELECT l.balance_after, l.amount, lag(l.balance_after) OVER (ORDER BY l.id) AS prev
                                      FROM cash_ledger l WHERE l.season_id = (SELECT id FROM zz_cur)) x
                WHERE x.balance_after <> COALESCE(x.prev, 0) + x.amount)
             + (SELECT count(*) FROM cash_account a WHERE a.season_id = (SELECT id FROM zz_cur)
                  AND a.balance <> COALESCE((SELECT l.balance_after FROM cash_ledger l WHERE l.season_id = a.season_id ORDER BY l.id DESC LIMIT 1), -1))$q$),
    -- 03 이력 재생
    ('REPLAY_OVERSELL', '03 재생', '체결 이력 재생에서 보유보다 많이 판 매도 수 (공매도 없음)',
     $q$SELECT count(*) FROM pg_temp.zz_replay((SELECT id FROM zz_cur)) r WHERE r.bad$q$),
    ('REPLAY_PNL', '03 재생', '매도 체결의 realized_pnl ≠ 재생한 ROUND((체결가 − 직전 평균단가) × 수량, 4) 인 수 (D9, NULL 포함)',
     $q$SELECT count(*) FROM pg_temp.zz_replay((SELECT id FROM zz_cur)) r JOIN execution e ON e.order_id = r.order_id
        WHERE r.side = 'SELL' AND e.realized_pnl IS DISTINCT FROM r.pnl$q$),
    ('REPLAY_HOLDING', '03 재생', '보유 행(수량·평균단가)과 재생한 마지막 상태가 다른 종목 수 (양방향 — 한쪽에만 있는 종목 포함) (D7·D9)',
     $q$SELECT count(*) FROM holding h FULL JOIN (SELECT DISTINCT ON (r.asset_id) r.asset_id, r.qty_after, r.avg_after
                                                  FROM pg_temp.zz_replay((SELECT id FROM zz_cur)) r ORDER BY r.asset_id, r.seq DESC) f ON f.asset_id = h.asset_id
        WHERE (h.season_id = (SELECT id FROM zz_cur) OR h.season_id IS NULL)
          AND (h.asset_id IS NULL OR f.asset_id IS NULL OR h.quantity <> f.qty_after OR h.avg_price <> f.avg_after)$q$),
    -- 04 라인업
    ('LINEUP_SNAPSHOT', '04 라인업', '스냅샷 k(1..현재 턴 + 1)마다 종목의 기여 구간 수(start_turn < k ≤ end_turn, 열린 끝은 무한대) ≠ 1[앞선 턴(< k) 체결의 순수량 > 0] 인 (k, 종목) 수',
     $q$SELECT count(*) FROM generate_series(1, (SELECT current_turn_no FROM zz_cur) + 1) AS k(k)
        CROSS JOIN (SELECT o.asset_id FROM orders o WHERE o.season_id = (SELECT id FROM zz_cur)
                    UNION SELECT lu.asset_id FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur)) a
        WHERE (COALESCE((SELECT sum(CASE o.side WHEN 'BUY' THEN o.quantity ELSE -o.quantity END) FROM orders o
                         WHERE o.season_id = (SELECT id FROM zz_cur) AND o.asset_id = a.asset_id AND o.turn_no < k.k AND o.status = 'FILLED'), 0) > 0)::int
              <> (SELECT count(*) FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND lu.asset_id = a.asset_id
                  AND lu.start_turn < k.k AND (lu.end_turn IS NULL OR k.k <= lu.end_turn))$q$),
    ('LINEUP_EDGES', '04 라인업', '새로 보유한 주문(직전 수량 0)의 턴에 시작하는 구간이 없거나, 전량 매도 주문의 턴에 끝나는 구간이 없는 주문 수 (D26)',
     $q$SELECT count(*) FROM pg_temp.zz_replay((SELECT id FROM zz_cur)) r
        WHERE (r.side = 'SELL' AND r.qty_after = 0 AND NOT EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND lu.asset_id = r.asset_id AND lu.end_turn = r.turn_no))
           OR (r.side = 'BUY' AND r.qty_before = 0 AND NOT EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND lu.asset_id = r.asset_id AND lu.start_turn = r.turn_no))$q$),
    ('LINEUP_OVERLAP', '04 라인업', '같은 종목의 구간 쌍이 겹친 수(반열림 [start, end), 열린 끝은 무한대) + 열린 구간이 둘 이상인 종목 수 + 끝 < 시작인 구간 수 + 현재 턴을 넘는 구간 수 (대사 규칙 18)',
     $q$SELECT (SELECT count(*) FROM lineup a JOIN lineup b ON b.season_id = a.season_id AND b.asset_id = a.asset_id AND b.id > a.id
                WHERE a.season_id = (SELECT id FROM zz_cur) AND a.start_turn < COALESCE(b.end_turn, 32767) AND b.start_turn < COALESCE(a.end_turn, 32767))
             + (SELECT count(*) FROM (SELECT 1 FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND lu.end_turn IS NULL GROUP BY lu.asset_id HAVING count(*) > 1) d)
             + (SELECT count(*) FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND (lu.end_turn < lu.start_turn
                  OR lu.start_turn > (SELECT current_turn_no FROM zz_cur) OR lu.end_turn > (SELECT current_turn_no FROM zz_cur)))$q$),
    -- 05 전술 변경
    ('FORMATION_CHAIN', '05 전술', '현재 포메이션 ≠ 마지막 변경의 after(없으면 시작 값) + before ≠ 직전 변경의 after(첫 변경은 시작 값)인 변경 수 (대사 규칙 15)',
     $q$SELECT (SELECT count(*) FROM zz_cur c WHERE ARRAY[c.current_goalkeeper, c.current_defender, c.current_midfielder, c.current_forward]
                  <> COALESCE((SELECT ARRAY[fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward] FROM formation_change fc WHERE fc.season_id = c.id ORDER BY fc.id DESC LIMIT 1),
                              ARRAY[c.start_goalkeeper, c.start_defender, c.start_midfielder, c.start_forward]))
             + (SELECT count(*) FROM (SELECT ARRAY[fc.before_goalkeeper, fc.before_defender, fc.before_midfielder, fc.before_forward] AS b,
                                             lag(ARRAY[fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward]) OVER (ORDER BY fc.id) AS prev
                                      FROM formation_change fc WHERE fc.season_id = (SELECT id FROM zz_cur)) x, zz_cur c
                WHERE x.b <> COALESCE(x.prev, ARRAY[c.start_goalkeeper, c.start_defender, c.start_midfielder, c.start_forward]))$q$),
    ('FORMATION_TURN', '05 전술', '변경의 턴이 1..현재 턴 밖이거나 id 순서에서 턴이 줄거나 같은 턴 안에서 시각(created_at)이 앞 변경보다 늦지 않은 변경 수 (D30 변경 순서 — 턴을 넘는 시각은 보지 않는다: 쓰기 가드도 같은 턴만 본다)',
     $q$SELECT count(*) FROM (SELECT fc.turn_no, fc.created_at, lag(fc.turn_no) OVER (ORDER BY fc.id) AS pt, lag(fc.created_at) OVER (ORDER BY fc.id) AS pc
                              FROM formation_change fc WHERE fc.season_id = (SELECT id FROM zz_cur)) x
        WHERE x.turn_no NOT BETWEEN 1 AND (SELECT current_turn_no FROM zz_cur) OR x.turn_no < x.pt OR (x.turn_no = x.pt AND x.created_at <= x.pc)$q$),
    ('BIND', '05 전술', '주문의 formation_change_id ≠ 같은 시즌·턴에서 자기보다 앞선 마지막 변경(없으면 NULL)인 수 + 주문과 변경의 시각이 같은 수 (대사 규칙 16)',
     $q$SELECT count(*) FROM orders o
        LEFT JOIN LATERAL (SELECT fc.id FROM formation_change fc
                           WHERE fc.season_id = o.season_id AND fc.turn_no = o.turn_no AND fc.created_at < o.created_at
                           ORDER BY fc.created_at DESC, fc.id DESC LIMIT 1) l ON true
        WHERE o.season_id = (SELECT id FROM zz_cur)
          AND (o.formation_change_id IS DISTINCT FROM l.id
               OR EXISTS (SELECT 1 FROM formation_change fc WHERE fc.season_id = o.season_id AND fc.turn_no = o.turn_no AND fc.created_at = o.created_at))$q$),
    -- 06 출발점
    ('SOURCE_FEED', '06 출발점', 'source ∈ {FEED, ALERT} ⇔ 피드 번호, 피드는 같은 시즌, ALERT ⇔ 종류 NUDGE, 피드 턴 ≤ 주문 턴(미래 피드 아님)이 아닌 주문 수 (S6·S7)',
     $q$SELECT count(*) FROM orders o LEFT JOIN turn_feed f ON f.id = o.turn_feed_id
        WHERE o.season_id = (SELECT id FROM zz_cur)
          AND ((o.source IN ('FEED', 'ALERT')) <> (o.turn_feed_id IS NOT NULL)
               OR (o.turn_feed_id IS NOT NULL AND (f.id IS NULL OR f.season_id <> o.season_id OR (o.source = 'ALERT') <> (f.kind = 'NUDGE') OR f.turn_no > o.turn_no)))$q$),
    -- 07 변경 비용
    ('COST_AGG', '07 비용', '변경 비용 조회 행 수 ≠ 변경 수 + 조회 값(주문 수·매수 대금·매도 대금·수수료·세금)이 체결·원장에서 독립 집계한 값과 다른 변경 수 (주문 없는 변경도 한 줄)',
     $q$SELECT abs((SELECT count(*) FROM pg_temp.formation_costs((SELECT id FROM zz_cur))) - (SELECT count(*) FROM formation_change fc WHERE fc.season_id = (SELECT id FROM zz_cur)))
             + (SELECT count(*) FROM pg_temp.formation_costs((SELECT id FROM zz_cur)) k
                WHERE k.order_count <> (SELECT count(*) FROM orders o WHERE o.formation_change_id = k.formation_change_id)
                   OR k.buy_notional <> COALESCE((SELECT sum(e.fill_price * e.quantity) FROM orders o JOIN execution e ON e.order_id = o.id WHERE o.formation_change_id = k.formation_change_id AND o.side = 'BUY'), 0)
                   OR k.sell_notional <> COALESCE((SELECT sum(e.fill_price * e.quantity) FROM orders o JOIN execution e ON e.order_id = o.id WHERE o.formation_change_id = k.formation_change_id AND o.side = 'SELL'), 0)
                   OR k.fee_sum <> COALESCE((SELECT -sum(l.amount) FROM cash_ledger l JOIN orders o ON o.id = l.ref_order_id WHERE o.formation_change_id = k.formation_change_id AND l.type = 'FEE'), 0)
                   OR k.tax_sum <> COALESCE((SELECT -sum(l.amount) FROM cash_ledger l JOIN orders o ON o.id = l.ref_order_id WHERE o.formation_change_id = k.formation_change_id AND l.type = 'TAX'), 0))$q$),
    -- 08 대사 연동
    ('RECON15', '08 대사', '22 수동 검사 15개 중 MATCH 가 아닌 규칙 수 + (행 수 ≠ 15 이면 1)',
     $q$SELECT count(*) FILTER (WHERE r.status <> 'MATCH') + (CASE WHEN count(*) <> 15 THEN 1 ELSE 0 END)
        FROM pg_temp.recon_rules((SELECT id FROM zz_cur), (SELECT current_turn_no FROM zz_cur)) r$q$);

-- 양성: 모든 규칙의 위반이 0건
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT q.section, '[' || q.code || '] ' || q.item, '0', pg_temp.cnt(q.sql)::text, pg_temp.cnt(q.sql) = 0
FROM zz_q q ORDER BY q.code;

-- 음성 사례 --------------------------------------------------------------------
-- 규칙을 일부러 어겨 보고 잡히는지 본다. UPDATE · DELETE 만 쓰고 INSERT 는 쓰지 않는다 (번호표를 쓰지 않는다). 서브트랜잭션이라 바꾼 것은 되돌아간다.
-- 01
CALL pg_temp.neg_viol('ORDER_SHAPE', '한 체결의 세금을 1 로 하면',
    $m$UPDATE execution SET tax = 1 WHERE order_id = (SELECT min(id) FROM zz_o)$m$, 1);
CALL pg_temp.neg_viol('ORDER_SHAPE', '한 매도 체결의 실현 손익을 NULL 로 하면 (매도는 값이 있어야 한다 — 대사 규칙 8)',
    $m$UPDATE execution SET realized_pnl = NULL WHERE order_id = (SELECT min(id) FROM zz_o WHERE side = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE side = 'SELL'$w$);
CALL pg_temp.neg_viol('FILL_PRICE', '한 매도 체결가를 +1 하면',
    $m$UPDATE execution SET fill_price = fill_price + 1 WHERE order_id = (SELECT min(id) FROM zz_o WHERE side = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE side = 'SELL'$w$);
CALL pg_temp.neg_viol('FEE_INDEP', '한 매도 체결의 수수료를 +1 하면 (매도 수수료도 매수와 같은 규칙)',
    $m$UPDATE execution SET fee = fee + 1 WHERE order_id = (SELECT min(id) FROM zz_o WHERE side = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE side = 'SELL'$w$);
CALL pg_temp.neg_viol('TURN_RANGE', '전술 변경에 묶이지 않은 주문 하나의 턴을 현재 턴 + 1 로 바꾸면 (미래 턴 주문)',
    $m$UPDATE orders SET turn_no = (SELECT current_turn_no + 1 FROM zz_cur) WHERE id = (SELECT min(id) FROM zz_o WHERE formation_change_id IS NULL AND turn_feed_id IS NULL)$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE formation_change_id IS NULL AND turn_feed_id IS NULL$w$);
-- 02
CALL pg_temp.neg_viol('LEDGER_KIND', '한 SELL 원장 행의 유형을 ADJUSTMENT 로 바꾸면 (매도 유형 오류)',
    $m$UPDATE cash_ledger SET type = 'ADJUSTMENT' WHERE id = (SELECT min(l.id) FROM cash_ledger l JOIN zz_o o ON o.id = l.ref_order_id WHERE l.type = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM cash_ledger l JOIN zz_o o ON o.id = l.ref_order_id WHERE l.type = 'SELL'$w$);
CALL pg_temp.neg_viol('LEDGER_KIND', '한 매도의 FEE 행을 지우면 (비용 누락)',
    $m$DELETE FROM cash_ledger WHERE id = (SELECT min(l.id) FROM cash_ledger l JOIN zz_o o ON o.id = l.ref_order_id WHERE l.type = 'FEE' AND o.side = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM cash_ledger l JOIN zz_o o ON o.id = l.ref_order_id WHERE l.type = 'FEE' AND o.side = 'SELL'$w$);
CALL pg_temp.neg_viol('LEDGER_KIND', '한 매도의 SELL 행과 FEE 행의 유형·부호를 맞바꾸면 (비용이 먼저 기록된 순서 오류)',
    $m$UPDATE cash_ledger SET type = CASE type WHEN 'SELL' THEN 'FEE' ELSE 'SELL' END, amount = -amount
       WHERE type IN ('SELL', 'FEE') AND ref_order_id = (SELECT min(o.id) FROM zz_o o JOIN execution e ON e.order_id = o.id WHERE o.side = 'SELL' AND e.fee > 0)$m$, 1,
    $w$SELECT count(*) FROM zz_o o JOIN execution e ON e.order_id = o.id WHERE o.side = 'SELL' AND e.fee > 0$w$);
CALL pg_temp.neg_viol('LEDGER_CHAIN', '한 SELL 원장 행의 거래 후 잔액을 1 줄이면',
    $m$UPDATE cash_ledger SET balance_after = balance_after - 1 WHERE id = (SELECT min(l.id) FROM cash_ledger l JOIN zz_o o ON o.id = l.ref_order_id WHERE l.type = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM cash_ledger l JOIN zz_o o ON o.id = l.ref_order_id WHERE l.type = 'SELL'$w$);
-- 03
CALL pg_temp.neg_viol('REPLAY_OVERSELL', '한 매도의 주문·체결 수량을 보유보다 훨씬 크게 하면 (초과 매도)',
    $m$UPDATE orders SET quantity = quantity + 1000000 WHERE id = (SELECT min(id) FROM zz_o WHERE side = 'SELL');
       UPDATE execution SET quantity = quantity + 1000000 WHERE order_id = (SELECT min(id) FROM zz_o WHERE side = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE side = 'SELL'$w$);
CALL pg_temp.neg_viol('REPLAY_PNL', '한 매도 체결의 실현 손익을 +1 하면 (독립 재생과 다른 값)',
    $m$UPDATE execution SET realized_pnl = realized_pnl + 1 WHERE order_id = (SELECT min(id) FROM zz_o WHERE side = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE side = 'SELL'$w$);
CALL pg_temp.neg_viol('REPLAY_PNL', '한 매도 체결의 실현 손익을 NULL 로 하면',
    $m$UPDATE execution SET realized_pnl = NULL WHERE order_id = (SELECT min(id) FROM zz_o WHERE side = 'SELL')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE side = 'SELL'$w$);
CALL pg_temp.neg_viol('REPLAY_HOLDING', '보유 수량이 있는 한 보유의 수량을 +1 하면',
    $m$UPDATE holding SET quantity = quantity + 1 WHERE id = (SELECT min(h.id) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0)$m$, 1,
    $w$SELECT count(*) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0$w$);
CALL pg_temp.neg_viol('REPLAY_HOLDING', '보유 수량이 있는 한 보유의 평균단가를 +0.0001 하면',
    $m$UPDATE holding SET avg_price = avg_price + 0.0001 WHERE id = (SELECT min(h.id) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0)$m$, 1,
    $w$SELECT count(*) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0$w$);
-- 04
CALL pg_temp.neg_viol('LINEUP_SNAPSHOT', '전량 매도한 종목의 닫힌 구간을 다시 열면 (전량 매도 뒤 열린 구간)',
    $m$UPDATE lineup SET end_turn = NULL WHERE id = (SELECT max(lu.id) FROM lineup lu JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id
                                                    WHERE h.quantity = 0 AND lu.end_turn IS NOT NULL AND lu.season_id = (SELECT id FROM zz_cur))$m$, 1,
    $w$SELECT count(*) FROM lineup lu JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id WHERE h.quantity = 0 AND lu.end_turn IS NOT NULL AND lu.season_id = (SELECT id FROM zz_cur)$w$);
CALL pg_temp.neg_viol('LINEUP_SNAPSHOT', '보유 중인 종목의 열린 구간을 닫으면 (보유 중 구간 없음)',
    $m$UPDATE lineup SET end_turn = start_turn WHERE id = (SELECT min(lu.id) FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND lu.end_turn IS NULL)$m$, 1,
    $w$SELECT count(*) FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND lu.end_turn IS NULL$w$);
CALL pg_temp.neg_viol('LINEUP_EDGES', '1턴 이후 전량 매도한 종목의 구간이 끝난 턴을 1 앞당기면',
    $m$UPDATE lineup SET end_turn = end_turn - 1 WHERE id = (SELECT max(lu.id) FROM lineup lu JOIN pg_temp.zz_replay((SELECT id FROM zz_cur)) r ON r.asset_id = lu.asset_id
                                                           AND r.side = 'SELL' AND r.qty_after = 0 AND r.turn_no >= 1 AND lu.end_turn = r.turn_no AND lu.end_turn > lu.start_turn
                                                           WHERE lu.season_id = (SELECT id FROM zz_cur))$m$, 1,
    $w$SELECT count(*) FROM lineup lu JOIN pg_temp.zz_replay((SELECT id FROM zz_cur)) r ON r.asset_id = lu.asset_id AND r.side = 'SELL' AND r.qty_after = 0 AND r.turn_no >= 1 AND lu.end_turn = r.turn_no AND lu.end_turn > lu.start_turn WHERE lu.season_id = (SELECT id FROM zz_cur)$w$);
CALL pg_temp.neg_viol('LINEUP_OVERLAP', '구간이 둘인 종목의 열린 구간 시작을 0 으로 당기면 (구간 겹침)',
    $m$UPDATE lineup SET start_turn = 0 WHERE id = (SELECT max(lu.id) FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND lu.end_turn IS NULL AND lu.start_turn > 0
                                                  AND (SELECT count(*) FROM lineup x WHERE x.season_id = lu.season_id AND x.asset_id = lu.asset_id) >= 2)$m$, 1,
    $w$SELECT count(*) FROM lineup lu WHERE lu.season_id = (SELECT id FROM zz_cur) AND lu.end_turn IS NULL AND lu.start_turn > 0 AND (SELECT count(*) FROM lineup x WHERE x.season_id = lu.season_id AND x.asset_id = lu.asset_id) >= 2$w$);
-- 05
CALL pg_temp.neg_viol('FORMATION_CHAIN', '한 변경의 before 수비를 +1·공격을 −1 하면 (사슬 끊김)',
    $m$UPDATE formation_change SET before_defender = before_defender + 1, before_forward = before_forward - 1
       WHERE id = (SELECT min(id) FROM formation_change WHERE season_id = (SELECT id FROM zz_cur) AND before_forward >= 1)$m$, 1,
    $w$SELECT count(*) FROM formation_change WHERE season_id = (SELECT id FROM zz_cur) AND before_forward >= 1$w$);
CALL pg_temp.neg_viol('FORMATION_CHAIN', '시즌의 현재 수비를 +1·공격을 −1 하면 (현재 값 ≠ 이력)',
    $m$UPDATE season SET current_defender = current_defender + 1, current_forward = current_forward - 1 WHERE id = (SELECT id FROM zz_cur) AND current_forward >= 1$m$, 1,
    $w$SELECT count(*) FROM zz_cur WHERE current_forward >= 1$w$);
CALL pg_temp.neg_viol('FORMATION_TURN', '시즌의 현재 턴을 첫 변경의 턴 − 1 로 하면 (변경이 현재 턴을 넘는다)',
    $m$UPDATE season SET status = 'IN_PROGRESS', abandoned_turn_no = NULL, current_turn_no = (SELECT min(turn_no) - 1 FROM formation_change WHERE season_id = (SELECT id FROM zz_cur)) WHERE id = (SELECT id FROM zz_cur)$m$, 1,
    $w$SELECT count(*) FROM formation_change WHERE season_id = (SELECT id FROM zz_cur)$w$);
CALL pg_temp.neg_viol('BIND', '변경에 묶인 주문 하나의 묶음을 NULL 로 하면 (연결 누락)',
    $m$UPDATE orders SET formation_change_id = NULL WHERE id = (SELECT min(id) FROM zz_o WHERE formation_change_id IS NOT NULL)$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE formation_change_id IS NOT NULL$w$);
CALL pg_temp.neg_viol('BIND', '변경 전 주문 하나를 그 턴의 변경에 묶으면 (이전이 아닌 뒤 변경으로 오연결)',
    $m$UPDATE orders SET formation_change_id = (SELECT fc.id FROM formation_change fc WHERE fc.season_id = orders.season_id AND fc.turn_no = orders.turn_no ORDER BY fc.id LIMIT 1)
       WHERE id = (SELECT min(o.id) FROM zz_o o WHERE o.formation_change_id IS NULL AND EXISTS (SELECT 1 FROM formation_change fc WHERE fc.season_id = o.season_id AND fc.turn_no = o.turn_no))$m$, 1,
    $w$SELECT count(*) FROM zz_o o WHERE o.formation_change_id IS NULL AND EXISTS (SELECT 1 FROM formation_change fc WHERE fc.season_id = o.season_id AND fc.turn_no = o.turn_no)$w$);
CALL pg_temp.neg_viol('BIND', '변경의 시각을 묶인 주문의 시각과 같게 하면 (동일 시각)',
    $m$UPDATE formation_change SET created_at = (SELECT o.created_at FROM zz_o o WHERE o.formation_change_id = formation_change.id ORDER BY o.id LIMIT 1)
       WHERE id = (SELECT min(o.formation_change_id) FROM zz_o o WHERE o.formation_change_id IS NOT NULL)$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE formation_change_id IS NOT NULL$w$);
-- 06
CALL pg_temp.neg_viol('SOURCE_FEED', 'FEED 주문이 참조한 피드의 종류를 NUDGE 로 바꾸면 (FEED/ALERT 종류 뒤바뀜)',
    $m$UPDATE turn_feed SET kind = 'NUDGE' WHERE id = (SELECT min(turn_feed_id) FROM zz_o WHERE source = 'FEED')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE source = 'FEED'$w$);
CALL pg_temp.neg_viol('SOURCE_FEED', 'FEED 주문을 ALERT 로 바꾸면 (종류가 NUDGE 가 아닌 피드)',
    $m$UPDATE orders SET source = 'ALERT' WHERE id = (SELECT min(id) FROM zz_o WHERE source = 'FEED')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE source = 'FEED'$w$);
CALL pg_temp.neg_viol('SOURCE_FEED', 'FEED 주문이 참조한 피드의 턴을 현재 턴 + 1 로 바꾸면 (미래 피드)',
    $m$UPDATE turn_feed SET turn_no = (SELECT current_turn_no + 1 FROM zz_cur) WHERE id = (SELECT min(turn_feed_id) FROM zz_o WHERE source = 'FEED')$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE source = 'FEED'$w$);
-- 07
CALL pg_temp.neg_viol('COST_AGG', '변경에 묶인 한 체결의 수수료를 +1 하면 (조회 값 ≠ 원장 수수료)',
    $m$UPDATE execution SET fee = fee + 1 WHERE order_id = (SELECT min(id) FROM zz_o WHERE formation_change_id IS NOT NULL)$m$, 1,
    $w$SELECT count(*) FROM zz_o WHERE formation_change_id IS NOT NULL$w$);
-- 08 (22 규칙 번호: 4 ORDER_LEDGER · 6 HOLDING_QTY · 8 EXEC_VALUES · 15 FORMATION · 16 FORMATION_BIND · 17 LINEUP_HOLDING)
CALL pg_temp.neg_rules('한 매도 체결의 실현 손익을 NULL 로 하면', $m$UPDATE execution SET realized_pnl = NULL WHERE order_id = (SELECT min(id) FROM zz_o WHERE side = 'SELL')$m$, ARRAY[8],
    $w$SELECT count(*) FROM zz_o WHERE side = 'SELL'$w$);
CALL pg_temp.neg_rules('한 SELL 원장 행의 유형을 ADJUSTMENT 로 바꾸면 (규칙 4 유형별 진단)', $m$UPDATE cash_ledger SET type = 'ADJUSTMENT' WHERE id = (SELECT min(l.id) FROM cash_ledger l JOIN zz_o o ON o.id = l.ref_order_id WHERE l.type = 'SELL')$m$, ARRAY[4],
    $w$SELECT count(*) FROM cash_ledger l JOIN zz_o o ON o.id = l.ref_order_id WHERE l.type = 'SELL'$w$);
CALL pg_temp.neg_rules('한 변경의 before 수비 +1·공격 −1 (규칙 15 사슬)', $m$UPDATE formation_change SET before_defender = before_defender + 1, before_forward = before_forward - 1 WHERE id = (SELECT min(id) FROM formation_change WHERE season_id = (SELECT id FROM zz_cur) AND before_forward >= 1)$m$, ARRAY[15],
    $w$SELECT count(*) FROM formation_change WHERE season_id = (SELECT id FROM zz_cur) AND before_forward >= 1$w$);
CALL pg_temp.neg_rules('변경에 묶인 주문의 묶음을 NULL 로 (규칙 16 누락)', $m$UPDATE orders SET formation_change_id = NULL WHERE id = (SELECT min(id) FROM zz_o WHERE formation_change_id IS NOT NULL)$m$, ARRAY[16],
    $w$SELECT count(*) FROM zz_o WHERE formation_change_id IS NOT NULL$w$);
CALL pg_temp.neg_rules('변경의 시각을 묶인 주문과 같게 (규칙 16 동일 시각)', $m$UPDATE formation_change SET created_at = (SELECT o.created_at FROM zz_o o WHERE o.formation_change_id = formation_change.id ORDER BY o.id LIMIT 1) WHERE id = (SELECT min(o.formation_change_id) FROM zz_o o WHERE o.formation_change_id IS NOT NULL)$m$, ARRAY[16],
    $w$SELECT count(*) FROM zz_o WHERE formation_change_id IS NOT NULL$w$);
CALL pg_temp.neg_rules('전량 매도한 종목의 닫힌 구간을 다시 열면 (규칙 17 전량 매도 뒤 구간 닫기 누락)',
    $m$UPDATE lineup SET end_turn = NULL WHERE id = (SELECT max(lu.id) FROM lineup lu JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id WHERE h.quantity = 0 AND lu.end_turn IS NOT NULL AND lu.season_id = (SELECT id FROM zz_cur))$m$, ARRAY[17],
    $w$SELECT count(*) FROM lineup lu JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id WHERE h.quantity = 0 AND lu.end_turn IS NOT NULL AND lu.season_id = (SELECT id FROM zz_cur)$w$);
CALL pg_temp.neg_rules('한 보유의 수량을 +1 하면 (규칙 6)', $m$UPDATE holding SET quantity = quantity + 1 WHERE id = (SELECT min(h.id) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0)$m$, ARRAY[6],
    $w$SELECT count(*) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity > 0$w$);

-- 09 거부 사례 ---------------------------------------------------------------
-- 서브트랜잭션 안에서 INSERT 전에 실패하는 호출만 쓴다 (identity 를 쓰지 않는다). 시즌·보유 종목·풀 안의 미보유 종목·피드는 저장 상태에서 고른다.
DO $$
DECLARE
    v_s      bigint;
    v_t      smallint;
    v_h      bigint;
    v_hp     text;
    v_hq     integer;
    v_u      bigint;
    v_up     text;
    v_missing bigint;
    v_nosea  bigint;
    v_fcur   bigint;
    v_fpast  bigint;
    v_npast  bigint;
    v_other  text;
    v_big    text := '1000000000000';
    v_live   text;
    v_bigcash text;
    v_cur    smallint[];
    v_nofeed bigint;
BEGIN
    SELECT c.id, c.current_turn_no INTO v_s, v_t FROM zz_cur c;
    v_live := format('UPDATE season SET status = ''IN_PROGRESS'', abandoned_turn_no = NULL WHERE id = %s', v_s);
    -- 보유 중이고 현재 턴 가격이 있는 종목 (열린 구간의 선언 포함)
    SELECT lu.asset_id, lu.declared_position, h.quantity INTO v_h, v_hp, v_hq
    FROM lineup lu JOIN holding h ON h.season_id = lu.season_id AND h.asset_id = lu.asset_id AND h.quantity > 0
    JOIN turn_price tp ON tp.season_id = lu.season_id AND tp.turn_no = v_t AND tp.asset_id = lu.asset_id
    WHERE lu.season_id = v_s AND lu.end_turn IS NULL ORDER BY lu.asset_id LIMIT 1;
    -- 풀 안의 미보유 종목
    SELECT tp.asset_id INTO v_u FROM turn_price tp
    WHERE tp.season_id = v_s AND tp.turn_no = v_t
      AND NOT EXISTS (SELECT 1 FROM holding h WHERE h.season_id = v_s AND h.asset_id = tp.asset_id AND h.quantity > 0)
    ORDER BY tp.asset_id LIMIT 1;
    SELECT max(id) + 1 INTO v_missing FROM asset;
    SELECT max(id) + 1 INTO v_nosea FROM season;
    SELECT f.id INTO v_fcur FROM turn_feed f WHERE f.season_id = v_s AND f.turn_no = v_t AND f.kind <> 'NUDGE' ORDER BY f.seq LIMIT 1;
    SELECT f.id INTO v_fpast FROM turn_feed f WHERE f.season_id = v_s AND f.turn_no < v_t AND f.kind <> 'NUDGE' ORDER BY f.turn_no DESC, f.seq LIMIT 1;
    SELECT f.id INTO v_npast FROM turn_feed f WHERE f.season_id = v_s AND f.turn_no < v_t AND f.kind = 'NUDGE' ORDER BY f.turn_no DESC, f.seq LIMIT 1;
    v_up := 'MIDFIELDER';
    v_bigcash := format('UPDATE cash_account SET balance = %s WHERE season_id = %s; UPDATE cash_ledger SET balance_after = %s WHERE id = (SELECT max(id) FROM cash_ledger WHERE season_id = %s)', v_big, v_s, v_big, v_s);
    SELECT COALESCE(max(id), 0) + 1 INTO v_nofeed FROM turn_feed;
    SELECT ARRAY[c.current_goalkeeper, c.current_defender, c.current_midfielder, c.current_forward] INTO v_cur FROM zz_cur c;

    -- 입력
    CALL pg_temp.neg_fn('매수 수량 0', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 0, %L)', v_s, v_t, v_u, v_up), 'INVALID_QUANTITY');
    CALL pg_temp.neg_fn('매도 수량 −1', v_live, format('SELECT pg_temp.trade_sell(%s, %s::smallint, %s, -1)', v_s, v_t, v_u), 'INVALID_QUANTITY');
    CALL pg_temp.neg_fn('선언 포지션이 여섯 가지 밖', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_u, 'FORWARD'), 'INVALID_ARGUMENT');
    CALL pg_temp.neg_fn('출발점이 다섯 가지 밖', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L, %L)', v_s, v_t, v_u, v_up, 'MARKETING'), 'INVALID_ARGUMENT');
    CALL pg_temp.neg_fn('FEED 인데 피드 번호 없음', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L, %L, NULL)', v_s, v_t, v_u, v_up, 'FEED'), 'INVALID_ARGUMENT');
    CALL pg_temp.neg_fn('DIRECT 인데 피드 번호 있음', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L, %L, %s)', v_s, v_t, v_u, v_up, 'DIRECT', COALESCE(v_fcur, 1)), 'INVALID_ARGUMENT');
    CALL pg_temp.neg_fn('기대 턴 NULL', v_live, format('SELECT pg_temp.trade_buy(%s, NULL::smallint, %s, 1, %L)', v_s, v_u, v_up), 'INVALID_ARGUMENT');
    CALL pg_temp.neg_fn('매도 출발점이 다섯 가지 밖', v_live, format('SELECT pg_temp.trade_sell(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_u, 'MARKETING'), 'INVALID_ARGUMENT');
    CALL pg_temp.neg_fn('포지션 변경 선언이 여섯 가지 밖', v_live, format('SELECT pg_temp.lineup_change(%s, %s::smallint, %s, %L)', v_s, v_t, COALESCE(v_h, v_u), 'FORWARD'), 'INVALID_ARGUMENT');
    -- 상태·턴
    CALL pg_temp.neg_fn('지나간 턴·미래 기대 턴 (기대 턴 + 1)', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t + 1, v_u, v_up), 'TURN_MISMATCH');
    CALL pg_temp.neg_fn('지나간 턴 (기대 턴 − 1)', v_live, format('SELECT pg_temp.trade_sell(%s, %s::smallint, %s, 1)', v_s, v_t - 1, v_u), 'TURN_MISMATCH');
    CALL pg_temp.neg_fn('0턴 진입 (시즌을 0턴으로 되돌린 서브트랜잭션)', format('UPDATE season SET status = ''IN_PROGRESS'', abandoned_turn_no = NULL, current_turn_no = 0 WHERE id = %s', v_s),
        format('SELECT pg_temp.trade_buy(%s, 0::smallint, %s, 1, %L)', v_s, v_u, v_up), 'TURN_MISMATCH');
    CALL pg_temp.neg_fn('포기 중인 시즌의 매수', format('UPDATE season SET status = ''ABANDONING'', abandoned_turn_no = %s WHERE id = %s', v_t, v_s),
        format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_u, v_up), 'SEASON_NOT_ACTIVE');
    CALL pg_temp.neg_fn('포기 중인 시즌의 전술 변경', format('UPDATE season SET status = ''ABANDONING'', abandoned_turn_no = %s WHERE id = %s', v_t, v_s),
        format('SELECT pg_temp.formation_change(%s, %s::smallint, 1::smallint, 30::smallint, 30::smallint, 39::smallint)', v_s, v_t), 'SEASON_NOT_ACTIVE');
    CALL pg_temp.neg_fn('없는 시즌', NULL, format('SELECT pg_temp.trade_sell(%s, %s::smallint, %s, 1)', v_nosea, v_t, v_u), 'SEASON_NOT_ACTIVE');
    -- 회계
    CALL pg_temp.neg_fn('현재 턴 가격 풀에 없는 종목', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_missing, v_up), 'ASSET_NOT_IN_POOL');
    CALL pg_temp.neg_fn('주문 가능 금액을 넘는 수량', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1000000000, %L)', v_s, v_t, v_u, v_up), 'INSUFFICIENT_CASH');
    CALL pg_temp.neg_fn('계좌 잔액 ≠ 최신 원장 (매수)', v_live || '; ' || format('UPDATE cash_account SET balance = balance + 1 WHERE season_id = %s', v_s),
        format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_u, v_up), 'LEDGER_MISMATCH');
    CALL pg_temp.neg_fn('계좌 잔액 ≠ 최신 원장 (매도)', v_live || '; ' || format('UPDATE cash_account SET balance = balance + 1 WHERE season_id = %s', v_s),
        format('SELECT pg_temp.trade_sell(%s, %s::smallint, %s, 1)', v_s, v_t, COALESCE(v_h, v_u)), 'LEDGER_MISMATCH');
    CALL pg_temp.neg_fn('보유하지 않은 종목 매도', v_live, format('SELECT pg_temp.trade_sell(%s, %s::smallint, %s, 1)', v_s, v_t, v_u), 'INSUFFICIENT_HOLDING');
    IF v_h IS NOT NULL THEN
        CALL pg_temp.neg_fn('보유보다 1주 많이 매도', v_live, format('SELECT pg_temp.trade_sell(%s, %s::smallint, %s, %s)', v_s, v_t, v_h, v_hq + 1), 'INSUFFICIENT_HOLDING');
        CALL pg_temp.neg_fn('열린 구간과 다른 선언 포지션으로 추가 매수', v_live || '; ' || v_bigcash,
            format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_h, CASE WHEN v_hp = 'GOALKEEPER' THEN 'DEFENDER' ELSE 'GOALKEEPER' END), 'POSITION_CONFLICT');
        CALL pg_temp.neg_fn('보유 중인데 열린 구간이 없는 종목의 매도', v_live || '; ' || format('UPDATE lineup SET end_turn = start_turn WHERE season_id = %s AND asset_id = %s AND end_turn IS NULL', v_s, v_h),
            format('SELECT pg_temp.trade_sell(%s, %s::smallint, %s, 1)', v_s, v_t, v_h), 'LINEUP_MISMATCH');
        CALL pg_temp.neg_fn('보유 중인데 열린 구간이 없는 종목의 추가 매수', v_live || '; ' || v_bigcash || '; ' || format('UPDATE lineup SET end_turn = start_turn WHERE season_id = %s AND asset_id = %s AND end_turn IS NULL', v_s, v_h),
            format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_h, v_hp), 'LINEUP_MISMATCH');
        CALL pg_temp.neg_fn('보유 없는데 열린 구간이 있는 종목의 매수', v_live || '; ' || v_bigcash || '; ' || format('UPDATE holding SET quantity = 0, avg_price = 0 WHERE season_id = %s AND asset_id = %s', v_s, v_h),
            format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_h, v_hp), 'LINEUP_MISMATCH');
    ELSE
        INSERT INTO zz_check_result VALUES (DEFAULT, '09 거부', '보유 종목이 없어 초과 매도·선언 충돌·구간 불일치 거부 사례는 적용 불가 (정보)', '(정보)', '적용 불가', true);
    END IF;
    -- 피드 참조
    CALL pg_temp.neg_fn('없는 피드 번호', v_live, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L, %L, %s)', v_s, v_t, v_u, v_up, 'FEED', v_nofeed), 'INVALID_FEED_REFERENCE');
    IF v_fcur IS NOT NULL THEN
        CALL pg_temp.neg_fn('ALERT 인데 NUDGE 가 아닌 피드', v_live || '; ' || v_bigcash, format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L, %L, %s)', v_s, v_t, v_u, v_up, 'ALERT', v_fcur), 'INVALID_FEED_REFERENCE');
        CALL pg_temp.neg_fn('FEED 인데 피드 종류가 NUDGE', v_live || '; ' || v_bigcash || '; ' || format('UPDATE turn_feed SET kind = ''NUDGE'' WHERE id = %s', v_fcur),
            format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L, %L, %s)', v_s, v_t, v_u, v_up, 'FEED', v_fcur), 'INVALID_FEED_REFERENCE');
        CALL pg_temp.neg_fn('아직 노출되지 않은 피드 (턴을 현재 턴 + 1 로 바꾼 서브트랜잭션)', v_live || '; ' || v_bigcash || '; ' || format('UPDATE turn_feed SET turn_no = %s WHERE id = %s', v_t + 1, v_fcur),
            format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L, %L, %s)', v_s, v_t, v_u, v_up, 'FEED', v_fcur), 'INVALID_FEED_REFERENCE');
    ELSE
        INSERT INTO zz_check_result VALUES (DEFAULT, '09 거부', '현재 턴에 NUDGE 가 아닌 피드가 없어 피드 종류·미노출 거부 사례는 적용 불가 (정보)', '(정보)', '적용 불가', true);
    END IF;
    -- 소유자 결정(2026-11-10, 이슈 #94): 이미 노출된 지난 턴 피드로 현재 턴 주문을 시작할 수 있다 (양성 사례, 쓰기 없는 tr_source 호출).
    CALL pg_temp.pos_past_feed('지난 턴 FEED 피드는 현재 턴 출발점으로 받는다 (주문 턴 = 현재 턴, 피드는 원래 턴)', v_s, v_t, 'FEED', v_fpast);
    CALL pg_temp.pos_past_feed('지난 턴 NUDGE 피드는 ALERT 출발점으로 받는다 (주문 턴 = 현재 턴, 피드는 원래 턴)', v_s, v_t, 'ALERT', v_npast);
    -- R1: 1턴 이후에는 21 의 order_buy 를 쓰지 않는다 (전술 변경 연결이 없어 규칙 16 이 어긋난다). 모든 검사 뒤에 거부한다.
    CALL pg_temp.neg_fn('1턴 이후 order_buy (변경 연결이 없는 경로)', v_live || '; ' || v_bigcash,
        format('SELECT pg_temp.order_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_u, v_up), 'USE_TRADE_BUY');
    -- 시각 순서: 같은 시즌·턴의 앞선 주문(또는 변경)을 미래 시각으로 만든 서브트랜잭션
    IF EXISTS (SELECT 1 FROM orders o WHERE o.season_id = v_s AND o.turn_no = v_t) THEN
        CALL pg_temp.neg_fn('앞선 주문의 시각이 지금보다 늦다 (매수)', v_live || '; ' || format('UPDATE orders SET created_at = clock_timestamp() + interval ''1 hour'' WHERE id = (SELECT max(id) FROM orders WHERE season_id = %s AND turn_no = %s)', v_s, v_t),
            format('SELECT pg_temp.trade_buy(%s, %s::smallint, %s, 1, %L)', v_s, v_t, v_u, v_up), 'EVENT_TIME_CONFLICT');
        CALL pg_temp.neg_fn('앞선 주문의 시각이 지금보다 늦다 (전술 변경)', v_live || '; ' || format('UPDATE orders SET created_at = clock_timestamp() + interval ''1 hour'' WHERE id = (SELECT max(id) FROM orders WHERE season_id = %s AND turn_no = %s)', v_s, v_t),
            format('SELECT pg_temp.formation_change(%s, %s::smallint, %s::smallint, %s::smallint, %s::smallint, %s::smallint)', v_s, v_t, v_cur[1], v_cur[2] - 1, v_cur[3] + 1, v_cur[4]), 'EVENT_TIME_CONFLICT');
    ELSE
        INSERT INTO zz_check_result VALUES (DEFAULT, '09 거부', '현재 턴에 주문이 없어 시각 순서 거부 사례는 적용 불가 (정보)', '(정보)', '적용 불가', true);
    END IF;
    -- 전술 변경
    CALL pg_temp.neg_fn('비중 합 ≠ 100', v_live, format('SELECT pg_temp.formation_change(%s, %s::smallint, 25::smallint, 25::smallint, 25::smallint, 24::smallint)', v_s, v_t), 'INVALID_FORMATION');
    CALL pg_temp.neg_fn('골키퍼 0', v_live, format('SELECT pg_temp.formation_change(%s, %s::smallint, 0::smallint, 40::smallint, 30::smallint, 30::smallint)', v_s, v_t), 'INVALID_FORMATION');
    CALL pg_temp.neg_fn('비중 범위 밖 (음수)', v_live, format('SELECT pg_temp.formation_change(%s, %s::smallint, 50::smallint, 60::smallint, (-10)::smallint, 0::smallint)', v_s, v_t), 'INVALID_FORMATION');
    CALL pg_temp.neg_fn('현재와 같은 포메이션', v_live, format('SELECT pg_temp.formation_change(%s, %s::smallint, %s::smallint, %s::smallint, %s::smallint, %s::smallint)', v_s, v_t, v_cur[1], v_cur[2], v_cur[3], v_cur[4]), 'INVALID_FORMATION');
    CALL pg_temp.neg_fn('현재 포메이션이 이력의 마지막 값과 다름 (사슬 변조)', v_live || '; ' || format('UPDATE season SET current_defender = current_defender + 1, current_forward = current_forward - 1 WHERE id = %s AND current_forward >= 1', v_s),
        format('SELECT pg_temp.formation_change(%s, %s::smallint, %s::smallint, %s::smallint, %s::smallint, %s::smallint)', v_s, v_t, v_cur[1], v_cur[2] - 1, v_cur[3] + 2, v_cur[4] - 1), 'INVALID_FORMATION');
    CALL pg_temp.neg_fn('전술 변경 NULL 비중', v_live, format('SELECT pg_temp.formation_change(%s, %s::smallint, NULL::smallint, 30::smallint, 30::smallint, 40::smallint)', v_s, v_t), 'INVALID_ARGUMENT');
    CALL pg_temp.neg_fn('전술 변경 사유 201자', v_live, format('SELECT pg_temp.formation_change(%s, %s::smallint, %s::smallint, %s::smallint, %s::smallint, %s::smallint, repeat(''가'', 201))', v_s, v_t, v_cur[1], v_cur[2] - 1, v_cur[3] + 1, v_cur[4]), 'INVALID_ARGUMENT');
    -- 포지션 변경
    CALL pg_temp.neg_fn('보유하지 않은 종목의 포지션 변경', v_live, format('SELECT pg_temp.lineup_change(%s, %s::smallint, %s, %L)', v_s, v_t, v_u, 'DEFENDER'), 'INSUFFICIENT_HOLDING');
END $$;

-- 10 함수 양성 ---------------------------------------------------------------
-- 같은 포지션 요청은 새 행 없이 열린 구간을 돌려준다 (쓰기 없음 — identity 불변)
DO $$
DECLARE
    v_s bigint; v_t smallint; v_h bigint; v_hp text; v_open bigint; v_ret bigint; v_n_before bigint; v_n_after bigint;
BEGIN
    SELECT c.id, c.current_turn_no INTO v_s, v_t FROM zz_cur c;
    SELECT lu.asset_id, lu.declared_position, lu.id INTO v_h, v_hp, v_open FROM lineup lu WHERE lu.season_id = v_s AND lu.end_turn IS NULL ORDER BY lu.asset_id LIMIT 1;
    IF v_h IS NULL OR v_t >= (SELECT path_length FROM zz_cur) OR (SELECT status FROM zz_cur) <> 'IN_PROGRESS' THEN
        INSERT INTO zz_check_result VALUES (DEFAULT, '10 양성', '같은 포지션 요청은 새 구간 없이 열린 구간을 돌려준다 — 적용 불가 (보유 없음·진행 중이 아님) (정보)', '(정보)', '적용 불가', true);
        RETURN;
    END IF;
    SELECT count(*) INTO v_n_before FROM lineup WHERE season_id = v_s;
    v_ret := pg_temp.lineup_change(v_s, v_t, v_h, v_hp);
    SELECT count(*) INTO v_n_after FROM lineup WHERE season_id = v_s;
    INSERT INTO zz_check_result VALUES (DEFAULT, '10 양성', '같은 포지션 요청은 새 구간 없이 열린 구간을 돌려준다 (구간 수 불변·반환 = 열린 구간 번호)', 'true',
        (v_n_before = v_n_after AND v_ret = v_open)::text, v_n_before = v_n_after AND v_ret = v_open);
END $$;
\o

-- 11 정보 (판정에 들어가지 않는다) ----------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '1턴 이후 주문 수 · 매수 · 매도 · 전술 변경에 묶인 주문 수 · 전술 변경 수 (정보)', '(정보)',
       count(*)::text || ' · ' || count(*) FILTER (WHERE o.side = 'BUY')::text || ' · ' || count(*) FILTER (WHERE o.side = 'SELL')::text || ' · '
       || count(*) FILTER (WHERE o.formation_change_id IS NOT NULL)::text || ' · ' || (SELECT count(*) FROM formation_change fc JOIN zz_cur c ON c.id = fc.season_id)::text, true
FROM zz_o o;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '출발점별 주문 수: DIRECT · RANKING · FEED · ALERT · BENCH (정보)', '(정보)',
       count(*) FILTER (WHERE source = 'DIRECT')::text || ' · ' || count(*) FILTER (WHERE source = 'RANKING')::text || ' · ' || count(*) FILTER (WHERE source = 'FEED')::text || ' · '
       || count(*) FILTER (WHERE source = 'ALERT')::text || ' · ' || count(*) FILTER (WHERE source = 'BENCH')::text, true
FROM zz_o;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '지난 턴 피드를 참조한 주문 수 (2026-11-10 허용 — 수만 보인다) (정보)', '(정보)',
       count(*)::text, true
FROM zz_o o JOIN turn_feed f ON f.id = o.turn_feed_id WHERE f.turn_no < o.turn_no;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '전량 매도 종목 수 · 구간이 둘 이상인 종목 수 · 길이 없는 구간 수 (정보)', '(정보)',
       (SELECT count(*) FROM holding h JOIN zz_cur c ON c.id = h.season_id WHERE h.quantity = 0)::text || ' · '
       || (SELECT count(*) FROM (SELECT 1 FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id GROUP BY lu.asset_id HAVING count(*) >= 2) d)::text || ' · '
       || (SELECT count(*) FROM lineup lu JOIN zz_cur c ON c.id = lu.season_id WHERE lu.end_turn = lu.start_turn)::text, true;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '11 정보', '매도 체결 수 · 실현 손익이 양수 · 음수 · 0 인 매도 수 (정보 — 값은 내지 않는다)', '(정보)',
       count(*)::text || ' · ' || count(*) FILTER (WHERE e.realized_pnl > 0)::text || ' · ' || count(*) FILTER (WHERE e.realized_pnl < 0)::text || ' · ' || count(*) FILTER (WHERE e.realized_pnl = 0)::text, true
FROM zz_o o JOIN execution e ON e.order_id = o.id WHERE o.side = 'SELL';

-- 13 판정 ---------------------------------------------------------------------
-- 13.1 규칙 커버리지: 요구 = 이 DB 에서 적용 가능한 음성 사례가 하나라도 있는 규칙 코드 (적용 불가 사례만 있는 코드는 요구에서 빠진다). 통과 = 적용 가능한 사례가 모두 통과.
\o /dev/null
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '13 판정', '규칙 커버리지 (적용 가능한 음성 사례가 통과한 규칙 코드 / 적용 가능한 규칙 코드 — 적용 불가 코드: ' || COALESCE(na.list, '없음') || ')',
       req.n || '/' || req.n, cov.n || '/' || req.n, req.n > 0 AND cov.n = req.n
FROM (SELECT count(*) AS n FROM zz_q q
      WHERE NOT (EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%적용 불가%')
                 AND NOT EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%' AND r.item NOT LIKE '%적용 불가%'))) req,
     (SELECT count(*) AS n FROM zz_q q
      WHERE EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%' AND r.item NOT LIKE '%적용 불가%' AND r.ok)
        AND NOT EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%' AND r.item NOT LIKE '%적용 불가%' AND NOT r.ok)) cov,
     (SELECT string_agg(q.code, ',' ORDER BY q.code) AS list FROM zz_q q
      WHERE EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%적용 불가%')
        AND NOT EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%' AND r.item NOT LIKE '%적용 불가%')) na;
-- 13.1b 거부 사례 종류 커버리지: 함수가 낼 수 있는 HINT 중 이 파일이 한 번 이상 시험한 수
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '13 판정', '거부 종류 커버리지 (통과한 거부 HINT / 요구 HINT — 시각 순서 EVENT_TIME_CONFLICT 는 현재 턴에 주문이 있을 때만, POSITION_CONFLICT·LINEUP_MISMATCH 는 보유 수량 > 0 이고 열린 구간이 있는 종목이 있을 때만 요구, ISOLATION_LEVEL 은 격리 수준을 바꿔야 하므로 드릴·README 몫)',
       req.n || '/' || req.n, cov.n || '/' || req.n, cov.n = req.n
FROM (SELECT count(*) AS n FROM unnest(ARRAY['INVALID_QUANTITY', 'INVALID_ARGUMENT', 'TURN_MISMATCH', 'SEASON_NOT_ACTIVE', 'ASSET_NOT_IN_POOL', 'INSUFFICIENT_CASH', 'LEDGER_MISMATCH',
                                             'INSUFFICIENT_HOLDING', 'INVALID_FEED_REFERENCE', 'INVALID_FORMATION', 'EVENT_TIME_CONFLICT', 'USE_TRADE_BUY', 'POSITION_CONFLICT', 'LINEUP_MISMATCH']) AS h
      WHERE (h NOT IN ('EVENT_TIME_CONFLICT', 'POSITION_CONFLICT', 'LINEUP_MISMATCH')
             OR (h = 'EVENT_TIME_CONFLICT' AND EXISTS (SELECT 1 FROM zz_o o JOIN zz_cur c ON c.id = o.season_id AND o.turn_no = c.current_turn_no))
             OR (h IN ('POSITION_CONFLICT', 'LINEUP_MISMATCH') AND EXISTS (SELECT 1 FROM holding hh JOIN lineup lu ON lu.season_id = hh.season_id AND lu.asset_id = hh.asset_id AND lu.end_turn IS NULL
                                                                            JOIN zz_cur c ON c.id = hh.season_id JOIN turn_price tp ON tp.season_id = c.id AND tp.turn_no = c.current_turn_no AND tp.asset_id = hh.asset_id
                                                                            WHERE hh.quantity > 0))) ) req,
     (SELECT count(DISTINCT h) AS n
      FROM unnest(ARRAY['INVALID_QUANTITY', 'INVALID_ARGUMENT', 'TURN_MISMATCH', 'SEASON_NOT_ACTIVE', 'ASSET_NOT_IN_POOL', 'INSUFFICIENT_CASH', 'LEDGER_MISMATCH',
                        'INSUFFICIENT_HOLDING', 'INVALID_FEED_REFERENCE', 'INVALID_FORMATION', 'EVENT_TIME_CONFLICT', 'USE_TRADE_BUY', 'POSITION_CONFLICT', 'LINEUP_MISMATCH']) AS h
      WHERE EXISTS (SELECT 1 FROM zz_check_result r WHERE r.section = '09 거부' AND r.ok AND r.item LIKE '거부 [' || h || ']%')) cov;
\o

-- 13.2 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok AND actual IS DISTINCT FROM '적용 불가') AS pass, count(*) FILTER (WHERE NOT ok) AS fail,
       count(*) FILTER (WHERE actual = '적용 불가') AS na
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 13.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 13.3b 커버리지 행·정보 행 (PASS 출력에서도 N/N 이 보이게)
SELECT section, item, expected, actual FROM zz_check_result WHERE section IN ('13 판정', '11 정보') ORDER BY seq;

-- 13.4 판정 행 (적용 불가 정보 행은 통과 사례 수에 세지 않는다)
SELECT CASE WHEN count(*) FILTER (WHERE actual IS DISTINCT FROM '적용 불가') > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) FILTER (WHERE actual IS DISTINCT FROM '적용 불가') AS total, count(*) FILTER (WHERE ok AND actual IS DISTINCT FROM '적용 불가') AS pass,
       count(*) FILTER (WHERE NOT ok) AS fail, count(*) FILTER (WHERE actual = '적용 불가') AS not_applicable
FROM zz_check_result;

-- 13.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3). 판정 표식은 트랜잭션이 되돌아가도 남는다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) FILTER (WHERE actual IS DISTINCT FROM '적용 불가') INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-19 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 14 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
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
        RAISE EXCEPTION E'P0-19 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    IF (SELECT last_value FROM zz_verdict) = 2 THEN
        PERFORM setval('zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_19_check;
