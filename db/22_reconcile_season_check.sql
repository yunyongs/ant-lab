-- =====================================================================
-- my Fund Manager Phase 0 — 수동 대사 검증 SQL (P0-16)
-- 대상: db/22_reconcile_season_def.sql 의 수동 검사 15개 규칙·결과 변환·로그 함수와 db/22_reconcile_season.sql 이 남기는 reconciliation_log 구조
-- 근거: docs/erd.md 7.5(대사 규칙 1–19)·6.3(reconciliation_log)·D28·D36·9절 P0-16, docs/PLAN.md §5.2, db/21_team_buy_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/22_reconcile_season_check.sql
--       21 팀 구성까지 실행한 DB 에서 한다 (20 만 실행한 DB 는 0절 전제가 FAIL 이다). INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아
--       실습 DB(antlab)에서도 돌릴 수 있다. 정의 파일을 \ir 로 읽으므로 같은 폴더의 22_reconcile_season_def.sql 이 있어야 한다.
--       범위: 턴 진행(P0-17)·매매(P0-19) 뒤에도 PASS 해야 한다 (21 검증과 같은 계약). 음성 사례의 전제(잔액 ≥ 1, 주문 없는 풀 종목, 보유 중인 종목, 열린 라인업,
--       current_midfielder ≥ 1, 국내 체결 대금 ≥ 100원)가 진행으로 깨진 극단 상태(전량 매도·잔액 0 등)에서는 헛 PASS 가 아니라 0절 FAIL 로 멈춘다.
--       음성 사례 하나(N16)가 서브트랜잭션 안에서 orders 의 제약을 지웠다 되돌리므로 그동안 orders 를 쓰는 다른 세션이 기다린다 — 실행 중에는 다른 세션을 쓰지 않는다.
-- 판정: 마지막 줄 `p0_16_check` 가 `PASS` 이고 종료 코드가 0이면 통과다. 0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고,
--       그 밖에 하나라도 어긋나면 06절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 06절 뒤에 ROLLBACK 한다. 07절이 행 수·identity 마지막 값이 실행 전과 같은지, 지웠다 되돌린 제약이 살아 있는지 확인한다.
-- 절:   00 전제(FAIL 이면 여기서 멈춤) → 01 양성 → 02 구조 → 03 음성 → 04 단위 → 05 정보 → 06 판정 → ROLLBACK → 07 흔적
-- 판정의 틀 (단독성): 음성 사례마다 서브트랜잭션 안에서 바꾸고 recon_rules 15행을 읽어 "불일치 코드 집합 = 기대 집합"인지 본다. 기대 코드의 불일치 수 ≥ 1 은
--       집합이 같다는 것에 들어 있다. 대부분 그 규칙 하나뿐이고, 논리적 함의가 있는 규칙 2 만 {2, 3}이다 (원장이 1행 이상이고 계좌가 있으면 규칙 3 이 참일 때 2 도 참 — 원장이 비면 3 은 대상이 없어 일치하고 1 이 함께 걸린다. D36).
--       같은 15행을 reconciliation_log 의 제약 사본 zz_log(CHECK 9개를 복사한 임시 표)에 넣어 로그 제약을 통과하는지도 본다.
-- 규칙 코드 15개 (규칙마다 양성 1개 + 음성 사례 1개 이상, 06절이 커버리지 15/15 를 확인한다):
--       ACCOUNT_OPENING CASH_SUM LEDGER_CHAIN ORDER_LEDGER HOLDING_QTY ORDER_FILL EXEC_VALUES RESERVED_ZERO TURN_PRICE FILL_PRICE FEE_RULE FORMATION FORMATION_BIND LINEUP_HOLDING LINEUP_OVERLAP
--       (규칙 5·10 은 쿼리 대상이 아니고 11·19 는 턴 전환 전용이라 15개다.)
-- 이 파일이 INSERT 없이 시험하지 못하는 것 (README 실습 (h)와 격리 DB 의 합성 시즌에서 확인한다): 원장 행 추가, 매도 체결·세금, 전술 변경 사슬·묶기·동시각,
--       겹친 라인업 구간, 1턴 이후 가격, recon_log 의 시도 번호. 연습 DB 에는 매도·전술 변경 데이터가 없어 규칙 8 매도·15 사슬·16 묶기의 양성은 빈 집합 일치다.
-- 출력: 건수와 판정만. 가격·수량·금액은 내지 않는다 (04절 단위 사례는 합성 값이라 시세가 아니다). 사례의 diff 값은 일부러 넣은 ±1 이다.
-- 판정 계약 (21 판정 계약에서 복사): ① pg_temp.zz_verdict ② 결과 표 zz_check_result ④ check_eq ⑤ 0절 뒤 멈춤과 판정(표식 2 또는 1) ⑥ 07절 흔적 확인(표식 0)과 마지막 CASE.
-- 새 문법: 서브트랜잭션으로 "바꿔 보고 되돌리기", 임시 표 LIKE … INCLUDING CONSTRAINTS, jsonb_path_exists 로 JSON 안의 종류 찾기, 함수 정의 바꿔치기(변이) 시험.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\ir 22_reconcile_season_def.sql

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 00–06절 통과·흔적 확인 대기, 0 = 최종 통과)
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
    FOREACH t IN ARRAY ARRAY['season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'turn_price', 'formation_change',
                             'reconciliation_log', 'season_path', 'asset_position'] LOOP
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

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '00 전제' … '06 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

-- 연습 시즌 찾기: 20·21 파일과 같은 이메일
CREATE TEMP VIEW zz_cur AS
SELECT s.* FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');

-- 음성 사례가 바꿀 대상: 연습 시즌의 첫 매수 주문·첫 BUY 원장 행·FEE 행이 있는 주문·보유 중인 보유 행·열린 라인업·주문 없는 풀 종목(0턴 가격이 있는 것)
-- 바꾸는 문장이 실행될 때마다 다시 계산된다 (바뀐 뒤 상태로). 사례는 대상을 한 번 고른 뒤 바꾸는 순서를 지킨다.
CREATE TEMP VIEW zz_t AS
SELECT c.id AS season_id, c.current_turn_no AS turn_no,
       (SELECT min(o.id) FROM orders o WHERE o.season_id = c.id AND o.turn_no = 0 AND o.side = 'BUY') AS buy_order,
       (SELECT min(l.id) FROM cash_ledger l WHERE l.season_id = c.id AND l.type = 'BUY') AS buy_ledger,
       (SELECT l.ref_order_id FROM cash_ledger l WHERE l.season_id = c.id AND l.type = 'FEE' ORDER BY l.id LIMIT 1) AS fee_order,
       (SELECT min(h.id) FROM holding h WHERE h.season_id = c.id AND h.quantity > 0) AS held_holding,
       (SELECT min(lu.id) FROM lineup lu WHERE lu.season_id = c.id AND lu.end_turn IS NULL) AS open_lineup,
       (SELECT ap.asset_id FROM asset_position ap
        WHERE ap.base_date = c.base_date
          AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.season_id = c.id AND o.asset_id = ap.asset_id)
          AND EXISTS (SELECT 1 FROM turn_price tp WHERE tp.season_id = c.id AND tp.turn_no = 0 AND tp.asset_id = ap.asset_id)
        ORDER BY ap.asset_id LIMIT 1) AS unheld_asset
FROM zz_cur c;

-- 로그 제약 사본: reconciliation_log 의 CHECK 를 복사한 임시 표 (FK·PK·UNIQUE 는 복사되지 않는다). id 는 기본값이 없으므로 넣을 때 정한다.
CREATE TEMP TABLE zz_log (LIKE public.reconciliation_log INCLUDING DEFAULTS INCLUDING CONSTRAINTS);

-- 판정 프로시저 ------------------------------------------------------------

-- 함수로 둔 이유: 프로시저의 CALL 인자에는 서브쿼리를 쓸 수 없다 (SELECT 인자에는 쓸 수 있다)
CREATE FUNCTION pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- 위반 개수를 돌려주는 쿼리 한 개를 실행해 bigint 로 돌려준다
CREATE FUNCTION pg_temp.cnt(p_sql text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v bigint;
BEGIN
    EXECUTE p_sql INTO v;
    RETURN v;
END $$;

-- 음성 사례: 서브트랜잭션 안에서 p_mutate 를 실행하고 recon_rules(연습 시즌, 현재 턴) 15행을 읽는다.
--   ① 불일치 규칙 번호 집합 = p_expect (단독성)  ② 15행 모양: MATCH 는 분류·상세 NULL·불일치 0, MISMATCH 는 INVESTIGATE·상세 있음·total = 불일치 수·항목 ≤ 100
--   ③ p_kind 가 있으면 대상 규칙의 detail 항목에 그 종류가 있다  ④ p_diff 가 있으면 대상 규칙의 diff_value = p_diff
--   ⑤ 15행이 zz_log 의 CHECK 9개를 통과한다.  끝나면 RAISE 로 서브트랜잭션을 되돌린다. 코드(p_code)는 대상 규칙의 check_code 이고 06절 커버리지가 이 이름을 센다.
CREATE PROCEDURE pg_temp.neg_rule(p_code text, p_label text, p_mutate text, p_expect int[], p_kind text, p_diff numeric)
LANGUAGE plpgsql AS $$
DECLARE
    v_season bigint;
    v_turn   smallint;
    v_rule   smallint;
    v_got    int[];
    v_shape  boolean;
    v_kind   boolean;
    v_diff   boolean;
    v_log    boolean;
    v_note   text := '';
    v_err    text;
    v_ok     boolean;
BEGIN
    SELECT t.season_id, t.turn_no INTO v_season, v_turn FROM zz_t t;
    SELECT c.rule_no INTO v_rule FROM pg_temp.recon_code c WHERE c.check_code = p_code;
    BEGIN
        EXECUTE p_mutate;
        SELECT COALESCE(array_agg(r.rule_no::int ORDER BY r.rule_no) FILTER (WHERE r.status = 'MISMATCH'), ARRAY[]::int[]) INTO v_got
        FROM pg_temp.recon_rules(v_season, v_turn) r;
        SELECT COALESCE(bool_and(
                   (r.status = 'MATCH' AND r.classification IS NULL AND r.detail IS NULL AND r.mismatch_count = 0)
                OR (r.status = 'MISMATCH' AND r.classification = 'INVESTIGATE' AND r.detail IS NOT NULL
                    AND (r.detail ->> 'total')::int = r.mismatch_count
                    AND jsonb_array_length(r.detail -> 'items') = LEAST(r.mismatch_count, 100)
                    AND r.mismatch_count >= 1)), false) INTO v_shape
        FROM pg_temp.recon_rules(v_season, v_turn) r;
        v_kind := true;
        IF p_kind IS NOT NULL THEN
            SELECT COALESCE(bool_or(jsonb_path_exists(r.detail, '$.items[*] ? (@.kind == $k || @.kinds[*] == $k)', jsonb_build_object('k', p_kind))), false) INTO v_kind
            FROM pg_temp.recon_rules(v_season, v_turn) r WHERE r.rule_no = v_rule;
        END IF;
        v_diff := true;
        IF p_diff IS NOT NULL THEN
            SELECT COALESCE(bool_or(r.status = 'MISMATCH' AND r.diff_value = p_diff), false) INTO v_diff
            FROM pg_temp.recon_rules(v_season, v_turn) r WHERE r.rule_no = v_rule;
        END IF;
        BEGIN
            INSERT INTO zz_log (id, season_id, turn_no, attempt_no, check_code, status, expected_value, actual_value, diff_value,
                                mismatch_count, classification, detail)
            SELECT r.rule_no, v_season, v_turn, 1, r.check_code, r.status, r.expected_value, r.actual_value, r.diff_value,
                   r.mismatch_count, r.classification, r.detail
            FROM pg_temp.recon_rules(v_season, v_turn) r;
            v_log := true;
        EXCEPTION WHEN OTHERS THEN
            v_log := false;
            v_note := ' (로그 제약 ' || SQLSTATE || ')';
        END;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            v_err := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
        END IF;
    END;
    IF v_err IS NOT NULL THEN
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('03 음성', '음성 [' || p_code || '] ' || p_label, '불일치 규칙 ' || array_to_string(p_expect, ','), v_err, false);
        RETURN;
    END IF;
    v_ok := v_got = p_expect AND v_shape AND v_kind AND v_diff AND v_log;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('03 음성', '음성 [' || p_code || '] ' || p_label ||
                       CASE WHEN p_kind IS NULL THEN '' ELSE ' (종류 ' || p_kind || ')' END ||
                       CASE WHEN p_diff IS NULL THEN '' ELSE ' (차이 ' || p_diff || ')' END,
            '불일치 규칙 {' || array_to_string(p_expect, ',') || '}',
            '불일치 규칙 {' || array_to_string(v_got, ',') || '}' ||
                CASE WHEN NOT v_shape THEN ' (판정 모양 어긋남)' ELSE '' END ||
                CASE WHEN NOT v_kind  THEN ' (종류 없음)' ELSE '' END ||
                CASE WHEN NOT v_diff  THEN ' (차이 다름)' ELSE '' END || v_note,
            v_ok);
END $$;

-- 변이 시험: 정의 함수를 바꿔치기(서브트랜잭션이라 되돌아간다)한 뒤 p_probe 쿼리가 p_want_bad 건(기대와 달라지는 사례 수) 이상 나와야 한다.
CREATE PROCEDURE pg_temp.neg_unit(p_label text, p_mutate text, p_probe text, p_min bigint)
LANGUAGE plpgsql AS $$
DECLARE
    v_n text;
BEGIN
    BEGIN
        EXECUTE p_mutate;
        v_n := pg_temp.cnt(p_probe)::text;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            v_n := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
        END IF;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('04 단위', '변이 ' || p_label || ' — 사례 어긋남 ' || p_min || '건 이상', '≥ ' || p_min, v_n,
            v_n ~ '^[0-9]+$' AND v_n::bigint >= p_min);
END $$;

-- 00 전제 -------------------------------------------------------------------
-- 하나라도 FAIL 이면 01절 이후 결과를 믿지 않는다.

CREATE TEMP TABLE zz_base AS
SELECT r.* FROM pg_temp.recon_rules((SELECT season_id FROM zz_t), (SELECT turn_no FROM zz_t)) r;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('격리 수준이 READ COMMITTED 다 (한 스냅샷 가정의 전제)', 'read committed', current_setting('transaction_isolation')),
    ('16개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '16',
        (SELECT count(*)::text FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'formation_change',
                                                 'turn_price', 'season_path', 'asset', 'asset_position', 'asset_month_return', 'fx_month', 'reconciliation_log']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('정의 파일을 읽었다 (pg_temp.recon_rules 가 있다)', 'true', (to_regprocedure('pg_temp.recon_rules(bigint, smallint)') IS NOT NULL)::text),
    ('연습 시즌이 정확히 1개 있다 (db/20_season_start.sql 을 먼저 실행한다)', '1', (SELECT count(*)::text FROM zz_cur)),
    ('0턴 매수 주문이 있다 (없으면 db/21_team_buy.sql 을 먼저 실행한다)', 'true', ((SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY') > 0)::text),
    ('FEE 원장 행이 있는 매수가 있다 (N4a)', 'true', ((SELECT fee_order FROM zz_t) IS NOT NULL)::text),
    ('첫 BUY 원장 행과 그 뒤 모든 행의 거래 후 잔액이 1 이상이다 (N3·N4b 가 1 을 뺀다)', 'true',
        ((SELECT min(l.balance_after) FROM cash_ledger l JOIN zz_t t ON t.season_id = l.season_id WHERE l.id >= t.buy_ledger) >= 1)::text),
    ('잔액이 1 이상이다 (N9 가 예약금 1 을 넣는다)', 'true', ((SELECT a.balance FROM cash_account a JOIN zz_cur c ON c.id = a.season_id) >= 1)::text),
    ('기준일 풀에 주문이 없고 0턴 가격이 있는 종목이 있다 (N12)', 'true', ((SELECT unheld_asset FROM zz_t) IS NOT NULL)::text),
    ('국내 시장 체결 중 대금이 100원 이상인 것이 있다 (N14 — 요율을 0.01 올리면 수수료가 1 이상 달라진다)', 'true',
        (EXISTS (SELECT 1 FROM execution e JOIN orders o ON o.id = e.order_id JOIN asset a ON a.id = o.asset_id JOIN zz_cur c ON c.id = o.season_id
                 WHERE a.market = 'KR' AND e.fill_price * e.quantity >= 100))::text),
    ('current_midfielder 가 1 이상이다 (N15 가 1 을 뺀다)', 'true', ((SELECT current_midfielder FROM zz_cur) >= 1)::text),
    ('보유 수량 > 0 인 보유 행이 있다 (N6)', 'true', ((SELECT held_holding FROM zz_t) IS NOT NULL)::text),
    ('열린 라인업이 있다 (N17·N18)', 'true', ((SELECT open_lineup FROM zz_t) IS NOT NULL)::text),
    ('매수 체결의 realized_pnl 이 NULL 이다 (N8 이 0 을 넣는다)', 'true',
        (EXISTS (SELECT 1 FROM execution e WHERE e.order_id = (SELECT buy_order FROM zz_t) AND e.realized_pnl IS NULL))::text),
    ('zz_log 의 CHECK 가 9개다 (LIKE … INCLUDING CONSTRAINTS)', '9', (SELECT count(*)::text FROM pg_constraint WHERE conrelid = 'zz_log'::regclass AND contype = 'c')),
    ('양성: 연습 시즌의 수동 검사 15개가 모두 일치한다 (깨져 있으면 음성 결과를 믿지 않는다)', '15/15',
        (SELECT count(*) FILTER (WHERE b.status = 'MATCH')::text || '/' || count(*)::text FROM zz_base b))
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
        RAISE EXCEPTION 'P0-16 전제 실패: 00절 FAIL % 건 (위의 00절 결과를 본다. 01·02·03 스키마와 10 적재, 12 판정, 20 시즌 시작, 21 팀 구성을 먼저 실행했는지 확인한다)', v_fail;
    END IF;
END $$;

-- 01 양성 ---------------------------------------------------------------------
\o /dev/null
-- 수동 검사 15개가 모두 MATCH 이고 15행 모양이 맞다 (스칼라 5개만 세 값, 일치면 분류·상세 NULL).
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '01 양성', '규칙 ' || c.rule_no || ' ' || c.check_code || ' 가 일치한다', 'MATCH', COALESCE(b.status, '(행 없음)'), b.status IS NOT DISTINCT FROM 'MATCH'
FROM pg_temp.recon_code c
LEFT JOIN zz_base b ON b.rule_no = c.rule_no
WHERE c.manual
ORDER BY c.rule_no;

SELECT pg_temp.check_eq('01 양성', 'recon_rules 가 정확히 15행이고 rule_no 가 수동 규칙 집합과 같다', '15 · 같음',
    (SELECT count(*)::text FROM zz_base) || ' · ' ||
    CASE WHEN (SELECT array_agg(rule_no ORDER BY rule_no) FROM zz_base) = (SELECT array_agg(rule_no ORDER BY rule_no) FROM pg_temp.recon_code WHERE manual) THEN '같음' ELSE '다름' END);
SELECT pg_temp.check_eq('01 양성', '일치 행은 분류·상세가 NULL 이고 불일치 수 0 이다 (어긋난 행 수)', '0',
    (SELECT count(*) FROM zz_base b WHERE b.status = 'MATCH' AND (b.classification IS NOT NULL OR b.detail IS NOT NULL OR b.mismatch_count <> 0))::text);
SELECT pg_temp.check_eq('01 양성', '스칼라 규칙 5개만 세 값이 채워지고 나머지는 세 값 NULL 이다 (어긋난 규칙 수)', '0',
    (SELECT count(*) FROM zz_base b JOIN pg_temp.recon_code c ON c.rule_no = b.rule_no
     WHERE CASE WHEN c.scalar THEN num_nonnulls(b.expected_value, b.actual_value, b.diff_value) <> 3
                ELSE num_nonnulls(b.expected_value, b.actual_value, b.diff_value) <> 0 END)::text);
SELECT pg_temp.check_eq('01 양성', '일치한 스칼라 규칙의 차이는 0 이다 (어긋난 규칙 수)', '0',
    (SELECT count(*) FROM zz_base b WHERE b.diff_value IS NOT NULL AND b.diff_value <> 0)::text);

-- 02 구조 ---------------------------------------------------------------------
SELECT pg_temp.check_eq('02 구조', 'recon_code 가 17행 · 수동 15 · 스칼라 5 이다', '17 · 15 · 5',
    (SELECT count(*)::text || ' · ' || count(*) FILTER (WHERE manual)::text || ' · ' || count(*) FILTER (WHERE scalar)::text FROM pg_temp.recon_code));
SELECT pg_temp.check_eq('02 구조', '정의의 코드 목록 = 카탈로그 ck_reconciliation_log_check_code 의 목록 (erd 7.5)', 'true',
    ((SELECT array_agg(c.check_code ORDER BY c.check_code) FROM pg_temp.recon_code c)
     = (SELECT array_agg(m[1] ORDER BY m[1])
        FROM regexp_matches((SELECT pg_get_constraintdef(oid) FROM pg_constraint
                             WHERE conname = 'ck_reconciliation_log_check_code' AND conrelid = 'public.reconciliation_log'::regclass),
                            '''([A-Z_]+)''', 'g') AS m))::text);
SELECT pg_temp.check_eq('02 구조', '수동 15개 = 17개 − {VALUATION, POSITION_VALUE} (턴 전환 전용 둘을 뺀 집합)', 'true',
    ((SELECT array_agg(c.check_code ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.manual)
     = (SELECT array_agg(c.check_code ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.check_code NOT IN ('VALUATION', 'POSITION_VALUE')))::text);
SELECT pg_temp.check_eq('02 구조', '스칼라 5개 = {CASH_SUM, ORDER_LEDGER, HOLDING_QTY, RESERVED_ZERO, FEE_RULE}', 'true',
    ((SELECT array_agg(c.check_code ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.scalar)
     = (SELECT array_agg(x ORDER BY x) FROM unnest(ARRAY['CASH_SUM', 'ORDER_LEDGER', 'HOLDING_QTY', 'RESERVED_ZERO', 'FEE_RULE']) AS x))::text);
SELECT pg_temp.check_eq('02 구조', 'reconciliation_log 의 CHECK 수 = 제약 사본 zz_log 의 CHECK 수 (LIKE … INCLUDING CONSTRAINTS 가 빠짐없이 복사)', 'true',
    ((SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.reconciliation_log'::regclass AND contype = 'c')
     = (SELECT count(*) FROM pg_constraint WHERE conrelid = 'zz_log'::regclass AND contype = 'c'))::text);
SELECT pg_temp.check_eq('02 구조', '양성 15행이 로그 제약 사본의 CHECK 9개를 통과한다 (넣은 행 수)', '15',
    (SELECT pg_temp.cnt($q$WITH i AS (INSERT INTO zz_log (id, season_id, turn_no, attempt_no, check_code, status, expected_value, actual_value, diff_value,
                                                           mismatch_count, classification, detail)
                                       SELECT b.rule_no, 1, 0, 1, b.check_code, b.status, b.expected_value, b.actual_value, b.diff_value, b.mismatch_count, b.classification, b.detail
                                       FROM zz_base b RETURNING 1) SELECT count(*) FROM i$q$))::text);
DELETE FROM zz_log;

-- 시즌이 없으면 P0001 SEASON_NOT_FOUND (recon_rules 와 recon_log 모두). recon_log 는 잠그기 전에 거절하므로 쓰기가 없다.
DO $$
DECLARE
    v_missing bigint;
    v_hint    text;
    v_act     text;
BEGIN
    SELECT max(id) + 1 INTO v_missing FROM season;
    BEGIN
        PERFORM * FROM pg_temp.recon_rules(v_missing, 0::smallint);
        v_act := '오류 없음';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
        v_act := SQLSTATE || ' ' || COALESCE(v_hint, '-');
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('02 구조', '없는 시즌의 recon_rules 는 P0001 SEASON_NOT_FOUND 로 실패한다', 'P0001 SEASON_NOT_FOUND', v_act, v_act = 'P0001 SEASON_NOT_FOUND');
    BEGIN
        PERFORM pg_temp.recon_log(v_missing, 0::smallint, '[{"check_code": "CASH_SUM"}]'::jsonb);
        v_act := '오류 없음';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
        v_act := SQLSTATE || ' ' || COALESCE(v_hint, '-');
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('02 구조', '없는 시즌의 recon_log 는 P0001 SEASON_NOT_FOUND 로 실패한다 (INSERT 없음)', 'P0001 SEASON_NOT_FOUND', v_act, v_act = 'P0001 SEASON_NOT_FOUND');
    BEGIN
        PERFORM pg_temp.recon_log((SELECT id FROM zz_cur), 0::smallint, '[]'::jsonb);
        v_act := '오류 없음';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
        v_act := SQLSTATE || ' ' || COALESCE(v_hint, '-');
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('02 구조', '빈 행 묶음의 recon_log 는 P0001 INVALID_ARGUMENT 로 실패한다 (INSERT 없음)', 'P0001 INVALID_ARGUMENT', v_act, v_act = 'P0001 INVALID_ARGUMENT');
END $$;

-- 검사 턴이 NULL·음수면 INVALID_ARGUMENT, recon_log 에 불완전·중복 코드 묶음이면 INVALID_ARGUMENT (모두 쓰기 전에 거절한다 — INSERT 없음)
DO $$
DECLARE
    v_sid  bigint := (SELECT id FROM zz_cur);
    v_hint text;
    v_act  text;
    r      record;
BEGIN
    FOR r IN SELECT * FROM (VALUES
        ('검사 턴 NULL 의 recon_rules', 'SELECT * FROM pg_temp.recon_rules(' || v_sid || ', NULL)'),
        ('검사 턴 NULL 의 recon_log', 'SELECT pg_temp.recon_log(' || v_sid || ', NULL, (SELECT jsonb_agg(to_jsonb(x)) FROM zz_base x))'),
        ('검사 턴 음수의 recon_rules',  'SELECT * FROM pg_temp.recon_rules(' || v_sid || ', (-1)::smallint)'),
        ('코드 3개만 담은 묶음의 recon_log', 'SELECT pg_temp.recon_log(' || v_sid || ', 0::smallint, (SELECT jsonb_agg(to_jsonb(x)) FROM (SELECT * FROM zz_base ORDER BY rule_no LIMIT 3) x))'),
        ('같은 코드가 두 번인 묶음의 recon_log', 'SELECT pg_temp.recon_log(' || v_sid || ', 0::smallint, (SELECT jsonb_agg(to_jsonb(x)) FROM (SELECT * FROM zz_base UNION ALL SELECT * FROM zz_base WHERE rule_no = 2) x))'),
        ('check_code 가 없는 행이 섞인 묶음의 recon_log', 'SELECT pg_temp.recon_log(' || v_sid || ', 0::smallint, (SELECT jsonb_agg(to_jsonb(x) - ''check_code'') FROM zz_base x))'),
        ('검사 턴 음수의 recon_log', 'SELECT pg_temp.recon_log(' || v_sid || ', (-1)::smallint, (SELECT jsonb_agg(to_jsonb(x)) FROM zz_base x))')
    ) AS v(label, call) LOOP
        BEGIN
            EXECUTE r.call;
            v_act := '오류 없음';
        EXCEPTION WHEN OTHERS THEN
            GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
            v_act := SQLSTATE || ' ' || COALESCE(v_hint, '-');
        END;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('02 구조', r.label || ' 는 P0001 INVALID_ARGUMENT 로 실패한다', 'P0001 INVALID_ARGUMENT', v_act, v_act = 'P0001 INVALID_ARGUMENT');
    END LOOP;
END $$;

-- 로그가 있으면 이 시즌의 (턴, 시도) 묶음은 모두 수동 15개 집합이거나 17개 전체 집합이다 (수동 검사와 턴 전환을 코드 집합으로 구분한다 — erd D36).
SELECT pg_temp.check_eq('02 구조', '이 시즌의 reconciliation_log (턴, 시도) 묶음 중 15개 집합도 17개 집합도 아닌 묶음 수 (로그가 없으면 0)', '0',
    (SELECT count(*) FROM (
         SELECT array_agg(l.check_code::text ORDER BY l.check_code) AS codes
         FROM reconciliation_log l JOIN zz_cur c ON c.id = l.season_id
         GROUP BY l.turn_no, l.attempt_no) g
     WHERE g.codes IS DISTINCT FROM (SELECT array_agg(c.check_code ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.manual)
       AND g.codes IS DISTINCT FROM (SELECT array_agg(c.check_code ORDER BY c.check_code) FROM pg_temp.recon_code c))::text);
SELECT pg_temp.check_eq('02 구조', '이 시즌의 reconciliation_log 에서 코드가 한 시도에 두 번 나온 묶음 수 (유일 제약이 막아야 하는 것)', '0',
    (SELECT count(*) FROM (SELECT 1 FROM reconciliation_log l JOIN zz_cur c ON c.id = l.season_id GROUP BY l.turn_no, l.attempt_no, l.check_code HAVING count(*) > 1) d)::text);

\o
-- 03 음성 ---------------------------------------------------------------------
-- 규칙을 일부러 어겨 보고 기대한 규칙 집합만 불일치로 잡히는지 본다. UPDATE · DELETE · DDL 만 쓰고 INSERT 는 쓰지 않는다 (번호표를 쓰지 않는다).
-- 한 사례에 조건 하나만 어긴다 (P0-05 R12).

-- 규칙 1
CALL pg_temp.neg_rule('ACCOUNT_OPENING', 'N1a 시즌의 초기 자금을 +1 하면 (원장·잔액은 그대로)',
    $m$UPDATE season SET initial_cash = initial_cash + 1 WHERE id = (SELECT season_id FROM zz_t)$m$, ARRAY[1], 'DEPOSIT_AMOUNT', NULL);
CALL pg_temp.neg_rule('ACCOUNT_OPENING', 'N1b 첫 원장 행의 유형을 DEPOSIT 에서 ADJUSTMENT 로 바꾸면 (금액·사슬은 그대로)',
    $m$UPDATE cash_ledger SET type = 'ADJUSTMENT' WHERE id = (SELECT min(l.id) FROM cash_ledger l WHERE l.season_id = (SELECT season_id FROM zz_t))$m$, ARRAY[1], 'FIRST_NOT_DEPOSIT', NULL);

-- 규칙 2 (함의: 원장이 1행 이상이고 계좌가 있으면 3 이 참일 때 2 가 참이라 2 를 깨면 3 도 깨진다 — 연습 시즌은 둘 다 있다, 01절 양성 규칙 1)
CALL pg_temp.neg_rule('CASH_SUM', 'N2 계좌 잔액을 +1 하면 (규칙 3 도 함께 걸린다 — 3 ⇒ 2)',
    $m$UPDATE cash_account SET balance = balance + 1 WHERE season_id = (SELECT season_id FROM zz_t)$m$, ARRAY[2, 3], 'SUM', 1);

-- 규칙 3
CALL pg_temp.neg_rule('LEDGER_CHAIN', 'N3 첫 BUY 원장 행(최신 아님)의 거래 후 잔액을 1 줄이면 (금액 합·잔액은 그대로)',
    $m$UPDATE cash_ledger SET balance_after = balance_after - 1 WHERE id = (SELECT buy_ledger FROM zz_t)$m$, ARRAY[3], 'CHAIN', NULL);

-- 규칙 4
CALL pg_temp.neg_rule('ORDER_LEDGER', 'N4a 총액은 맞고 분류만 틀리게: BUY 행에 FEE 금액을 합치고 FEE 행을 지우면 (사슬은 이어 붙인다)',
    $m$UPDATE cash_ledger b SET amount = b.amount + f.amount, balance_after = f.balance_after
       FROM cash_ledger f
       WHERE b.type = 'BUY' AND f.type = 'FEE' AND b.ref_order_id = f.ref_order_id AND f.ref_order_id = (SELECT fee_order FROM zz_t);
       DELETE FROM cash_ledger WHERE type = 'FEE' AND ref_order_id = (SELECT fee_order FROM zz_t)$m$, ARRAY[4], 'TYPE', 0);
CALL pg_temp.neg_rule('ORDER_LEDGER', 'N4b 총액이 틀리게: BUY 금액을 1 더 줄이고 그 행부터 최신까지 거래 후 잔액과 계좌 잔액을 1 줄이면 (2·3 은 맞춰 둔다)',
    $m$UPDATE cash_ledger SET amount = amount - 1 WHERE id = (SELECT buy_ledger FROM zz_t);
       UPDATE cash_ledger SET balance_after = balance_after - 1 WHERE season_id = (SELECT season_id FROM zz_t) AND id >= (SELECT buy_ledger FROM zz_t);
       UPDATE cash_account SET balance = balance - 1 WHERE season_id = (SELECT season_id FROM zz_t)$m$, ARRAY[4], 'TOTAL', -1);

-- 규칙 6
CALL pg_temp.neg_rule('HOLDING_QTY', 'N6a 보유 중인 한 보유의 수량을 +1 하면 (여전히 0 보다 커서 규칙 17 은 그대로)',
    $m$UPDATE holding SET quantity = quantity + 1 WHERE id = (SELECT held_holding FROM zz_t)$m$, ARRAY[6], 'QTY', 1);
CALL pg_temp.neg_rule('HOLDING_QTY', 'N6b 한 보유 행을 지우고 그 종목의 열린 라인업을 닫으면 (체결만 남은 한쪽 누락, 17·18 은 그대로)',
    $m$WITH d AS (DELETE FROM holding WHERE id = (SELECT held_holding FROM zz_t) RETURNING season_id, asset_id)
       UPDATE lineup lu SET end_turn = lu.start_turn FROM d WHERE lu.season_id = d.season_id AND lu.asset_id = d.asset_id AND lu.end_turn IS NULL$m$, ARRAY[6], 'NO_HOLDING', NULL);

-- 규칙 7
CALL pg_temp.neg_rule('ORDER_FILL', 'N7a 한 주문의 수량을 +1 하면 (체결은 그대로)',
    $m$UPDATE orders SET quantity = quantity + 1 WHERE id = (SELECT buy_order FROM zz_t)$m$, ARRAY[7], 'FILL_QTY', NULL);
CALL pg_temp.neg_rule('ORDER_FILL', 'N7b 한 주문의 상태를 PENDING 으로 바꾸면 (규칙 6 은 상태로 거르지 않아 그대로)',
    $m$UPDATE orders SET status = 'PENDING' WHERE id = (SELECT buy_order FROM zz_t)$m$, ARRAY[7], 'STATUS', NULL);

-- 규칙 8
CALL pg_temp.neg_rule('EXEC_VALUES', 'N8 매수 체결의 realized_pnl 을 0 으로 채우면',
    $m$UPDATE execution SET realized_pnl = 0 WHERE order_id = (SELECT buy_order FROM zz_t)$m$, ARRAY[8], 'BUY_PNL', NULL);

-- 규칙 9
CALL pg_temp.neg_rule('RESERVED_ZERO', 'N9 예약금을 1 로 하면 (잔액·원장은 그대로)',
    $m$UPDATE cash_account SET reserved = 1 WHERE season_id = (SELECT season_id FROM zz_t)$m$, ARRAY[9], 'RESERVED', 1);

-- 규칙 12
CALL pg_temp.neg_rule('TURN_PRICE', 'N12a 주문이 없는 풀 종목의 0턴 가격을 +1 하면 (그 종목 체결이 없어 규칙 13 은 그대로)',
    $m$UPDATE turn_price SET price = price + 1 WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = 0 AND asset_id = (SELECT unheld_asset FROM zz_t)$m$, ARRAY[12], 'PRICE', NULL);
CALL pg_temp.neg_rule('TURN_PRICE', 'N12b 주문이 없는 풀 종목의 0턴 가격 행을 지우면',
    $m$DELETE FROM turn_price WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = 0 AND asset_id = (SELECT unheld_asset FROM zz_t)$m$, ARRAY[12], 'MISSING', NULL);

-- 규칙 13
CALL pg_temp.neg_rule('FILL_PRICE', 'N13 한 0턴 주문의 턴 번호를 현재 턴 + 1 로 바꾸면 (검사 턴 뒤라 가격 행이 없다 — 턴 진행 뒤에도 성립. 변경 id 가 NULL 이라 복합 FK 는 검사하지 않는다)',
    $m$UPDATE orders SET turn_no = (SELECT turn_no + 1 FROM zz_t) WHERE id = (SELECT buy_order FROM zz_t)$m$, ARRAY[13], 'NO_PRICE', NULL);

-- 규칙 14
CALL pg_temp.neg_rule('FEE_RULE', 'N14 시즌의 국내 수수료율을 +0.01 하면 (저장된 수수료·원장은 그대로)',
    $m$UPDATE season SET fee_rate_kr = fee_rate_kr + 0.01 WHERE id = (SELECT season_id FROM zz_t)$m$, ARRAY[14], 'FEE', NULL);

-- 규칙 15
CALL pg_temp.neg_rule('FORMATION', 'N15 현재 포메이션 중복 컬럼만 수비 +1·미드필더 −1 로 바꾸면 (합 100 은 유지, 이력은 그대로)',
    $m$UPDATE season SET current_defender = current_defender + 1, current_midfielder = current_midfielder - 1 WHERE id = (SELECT season_id FROM zz_t)$m$, ARRAY[15], 'CURRENT', NULL);

-- 규칙 16 (전술 변경이 연습 DB 에 없으므로 가리는 제약을 사례 안에서 지워 증명한다 — 03 검증 선례. 제약은 서브트랜잭션과 함께 되돌아간다)
CALL pg_temp.neg_rule('FORMATION_BIND', 'N16 복합 FK 를 지운 뒤 한 주문의 변경 번호를 없는 번호로 채우면 (변경이 없는데 묶임)',
    $m$ALTER TABLE orders DROP CONSTRAINT fk_orders_formation_change;
       UPDATE orders SET formation_change_id = 9223372036854775807 WHERE id = (SELECT buy_order FROM zz_t)$m$, ARRAY[16], 'WRONG', NULL);
\o /dev/null
-- 서브트랜잭션이 끝나면 orders 의 ACCESS EXCLUSIVE 잠금이 풀렸는지 (다른 세션이 기다리지 않는다) — 이 세션이 지금 그 잠금을 쥐고 있지 않아야 한다
SELECT pg_temp.check_eq('03 음성', 'N16 뒤 이 세션이 orders 의 ACCESS EXCLUSIVE 잠금을 쥐고 있지 않다 (서브트랜잭션 되돌림이 잠금도 푼다)', '0',
    (SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'relation' AND relation = 'public.orders'::regclass AND mode = 'AccessExclusiveLock')::text);
SELECT pg_temp.check_eq('03 음성', 'N16 뒤 fk_orders_formation_change 가 되살아나 있다 (DDL 이 서브트랜잭션과 함께 되돌아갔다)', '1',
    (SELECT count(*) FROM pg_constraint WHERE conname = 'fk_orders_formation_change' AND conrelid = 'public.orders'::regclass)::text);

\o
-- 규칙 17
CALL pg_temp.neg_rule('LINEUP_HOLDING', 'N17 보유 중인 종목의 열린 라인업 구간을 닫으면 (end_turn = start_turn — 닫힌 끝이 현재 턴 이하라 규칙 18 은 그대로)',
    $m$UPDATE lineup SET end_turn = start_turn WHERE id = (SELECT open_lineup FROM zz_t)$m$, ARRAY[17], 'HOLDING_NO_OPEN', NULL);

-- 규칙 18
CALL pg_temp.neg_rule('LINEUP_OVERLAP', 'N18 열린 라인업의 시작 턴을 현재 턴 + 1 로 바꾸면 (여전히 열려 있어 규칙 17 은 그대로)',
    $m$UPDATE lineup SET start_turn = (SELECT turn_no + 1 FROM zz_t) WHERE id = (SELECT open_lineup FROM zz_t)$m$, ARRAY[18], 'START_AFTER', NULL);

-- 04 단위 ---------------------------------------------------------------------
-- 합성 값이다 (시세가 아니다). 손으로 계산한 기대값과 같은지 본다.
CREATE TEMP TABLE zz_unit (label text NOT NULL, got text, want text);
INSERT INTO zz_unit VALUES
    ('recon_price: 곱이 정확히 ·5 는 올림 (1001 × 1.5 = 1501.5 → 1502)',       pg_temp.recon_price(1001, ARRAY[1.5])::text, '1502'),
    ('recon_price: ·4999 는 내림 (10000 × 1.00004999 = 10000.4999 → 10000)',   pg_temp.recon_price(10000, ARRAY[1.00004999])::text, '10000'),
    ('recon_price: 마지막 한 번 반올림 (1000 × 1.0004 × 1.0004 = 1000.80016 → 1001)', pg_temp.recon_price(1000, ARRAY[1.0004, 1.0004])::text, '1001'),
    ('recon_price: 매달 반올림하면 다른 값 (1000 × 1.0004 → 1000, × 1.0004 → 1000)', pg_temp.recon_price(pg_temp.recon_price(1000, ARRAY[1.0004]), ARRAY[1.0004])::text, '1000'),
    ('recon_price: 정수로 딱 떨어짐 (70000 × 0.5 = 35000)',                      pg_temp.recon_price(70000, ARRAY[0.5])::text, '35000'),
    ('recon_price: 세 인자 (333 × 1.5 × 1.5 = 749.25 → 749)',                    pg_temp.recon_price(333, ARRAY[1.5, 1.5])::text, '749'),
    ('recon_price: 인자 배열이 비면 시작가 그대로',                                pg_temp.recon_price(5000, ARRAY[]::numeric[])::text, '5000'),
    ('recon_price: 인자에 NULL 이 있으면 NULL (수익률 행 없음)',                  COALESCE(pg_temp.recon_price(5000, ARRAY[1.1, NULL]::numeric[])::text, 'NULL'), 'NULL'),
    ('recon_detail: NULL 이면 NULL',                                              COALESCE(pg_temp.recon_detail(NULL)::text, 'NULL'), 'NULL'),
    ('recon_detail: 빈 배열이면 NULL',                                            COALESCE(pg_temp.recon_detail('[]'::jsonb)::text, 'NULL'), 'NULL'),
    ('recon_detail: 항목 3개는 total 3 · 잘리지 않음 · 항목 3개',
        (SELECT (d ->> 'total') || '/' || (d ->> 'truncated') || '/' || jsonb_array_length(d -> 'items')
         FROM (SELECT pg_temp.recon_detail(jsonb_build_array('{"kind":"A"}'::jsonb, '{"kind":"B"}'::jsonb, '{"kind":"C"}'::jsonb)) AS d) x), '3/false/3'),
    ('recon_detail: 항목 100개는 잘리지 않음',
        (SELECT (d ->> 'total') || '/' || (d ->> 'truncated') || '/' || jsonb_array_length(d -> 'items')
         FROM (SELECT pg_temp.recon_detail((SELECT jsonb_agg(jsonb_build_object('n', g) ORDER BY g) FROM generate_series(1, 100) g)) AS d) x), '100/false/100'),
    ('recon_detail: 항목 150개는 total 150 · 잘림 · 앞 100개 · 첫 항목이 그대로',
        (SELECT (d ->> 'total') || '/' || (d ->> 'truncated') || '/' || jsonb_array_length(d -> 'items') || '/' || (d -> 'items' -> 0 ->> 'n') || '/' || (d -> 'items' -> 99 ->> 'n')
         FROM (SELECT pg_temp.recon_detail((SELECT jsonb_agg(jsonb_build_object('n', g) ORDER BY g) FROM generate_series(1, 150) g)) AS d) x), '150/true/100/1/100');

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '04 단위', u.label, u.want, u.got, u.got IS NOT DISTINCT FROM u.want FROM zz_unit u;

\o /dev/null
-- 기준일 풀 크기만큼 0턴 기대 가격이 나온다 (p_upto = 0 — 1턴 이후 블록은 읽지 않는다)
SELECT pg_temp.check_eq('04 단위', 'recon_expected_price(시즌, 0) 의 행 수 = 기준일 풀 크기이고 모두 턴 0 이다', 'true',
    ((SELECT count(*) FROM pg_temp.recon_expected_price((SELECT season_id FROM zz_t), 0::smallint) WHERE turn_no = 0)
     = (SELECT count(*) FROM asset_position ap JOIN zz_cur c ON c.id = (SELECT season_id FROM zz_t) WHERE ap.base_date = c.base_date))::text);

\o
-- 변이: recon_price 를 버림·매달 반올림으로 바꿔치기하면 단위 사례가 어긋난다
CALL pg_temp.neg_unit('recon_price 를 버림(trunc)으로',
    $m$CREATE OR REPLACE FUNCTION pg_temp.recon_price(p_start numeric, p_factors numeric[]) RETURNS numeric
       LANGUAGE sql IMMUTABLE AS $f$ SELECT trunc(p_start * COALESCE((SELECT exp(sum(ln(x))) FROM unnest(p_factors) x), 1), 0) $f$$m$,
    $q$SELECT count(*) FROM (VALUES (pg_temp.recon_price(1001, ARRAY[1.5]), 1502::numeric),
                                    (pg_temp.recon_price(10000, ARRAY[1.00004999]), 10000::numeric),
                                    (pg_temp.recon_price(1000, ARRAY[1.0004, 1.0004]), 1001::numeric)) u(got, want) WHERE got IS DISTINCT FROM want$q$, 1);
CALL pg_temp.neg_unit('recon_price 를 매달 반올림으로',
    $m$CREATE OR REPLACE FUNCTION pg_temp.recon_price(p_start numeric, p_factors numeric[]) RETURNS numeric
       LANGUAGE sql IMMUTABLE AS $f$
       WITH RECURSIVE r(i, acc) AS (SELECT 0, p_start UNION ALL SELECT r.i + 1, ROUND(r.acc * p_factors[r.i + 1], 0) FROM r WHERE r.i < COALESCE(cardinality(p_factors), 0))
       SELECT acc FROM r ORDER BY i DESC LIMIT 1 $f$$m$,
    $q$SELECT count(*) FROM (VALUES (pg_temp.recon_price(1000, ARRAY[1.0004, 1.0004]), 1001::numeric),
                                    (pg_temp.recon_price(333, ARRAY[1.5, 1.5]), 749::numeric)) u(got, want) WHERE got IS DISTINCT FROM want$q$, 1);

-- 05 정보 (판정에 들어가지 않는다) ----------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '05 정보', '이 시즌의 reconciliation_log 행 수 · (턴, 시도) 묶음 수 (정보)', '(정보)',
       count(*)::text || ' · ' || count(DISTINCT (l.turn_no, l.attempt_no))::text, true
FROM reconciliation_log l JOIN zz_cur c ON c.id = l.season_id;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '05 정보', '검사한 턴 · 주문 수 · 체결 수 · 원장 행 수 (정보)', '(정보)',
       (SELECT turn_no FROM zz_t)::text || ' · '
       || (SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id)::text || ' · '
       || (SELECT count(*) FROM execution e JOIN orders o ON o.id = e.order_id JOIN zz_cur c ON c.id = o.season_id)::text || ' · '
       || (SELECT count(*) FROM cash_ledger l JOIN zz_cur c ON c.id = l.season_id)::text, true;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '05 정보', '전술 변경 수 · 매도 주문 수 (0 이면 규칙 8 매도·15 사슬·16 묶기의 양성은 빈 집합 일치다) (정보)', '(정보)',
       (SELECT count(*) FROM formation_change f JOIN zz_cur c ON c.id = f.season_id)::text || ' · '
       || (SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.side = 'SELL')::text, true;

-- 06 판정 ---------------------------------------------------------------------
-- 06.1 규칙 커버리지: 음성 사례가 통과한 규칙 코드 수 / 수동 규칙 코드 수 (15/15 이어야 한다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '06 판정', '규칙 커버리지 (음성 사례가 통과한 규칙 코드 / 수동 규칙 코드)',
       (SELECT count(*) FROM pg_temp.recon_code WHERE manual) || '/' || (SELECT count(*) FROM pg_temp.recon_code WHERE manual),
       cov.n || '/' || (SELECT count(*) FROM pg_temp.recon_code WHERE manual),
       cov.n = (SELECT count(*) FROM pg_temp.recon_code WHERE manual)
FROM (SELECT count(*) AS n
      FROM pg_temp.recon_code c
      WHERE c.manual
        AND EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || c.check_code || ']%' AND r.ok)) cov;

-- 06.2 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 06.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 06.3b 커버리지 행 (PASS 출력에서도 N/N 이 보이게)
SELECT item, expected, actual FROM zz_check_result WHERE section = '06 판정' ORDER BY seq;

-- 06.4 판정 행
SELECT CASE WHEN count(*) > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result;

-- 06.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3).
--      판정 표식은 트랜잭션이 되돌아가도 남는다. 통과해도 2(흔적 확인 대기)까지만 올린다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-16 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 07 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 읽거나 일부러 바꿔 본 표들의 행 수와 identity 마지막 값이 실행 전과 같고, public 에 zz_ 객체가 없고, 지웠다 되돌린 제약이 살아 있다.
-- 검증은 INSERT 를 하지 않으므로 번호표를 소비하지 않는다.
DO $$
DECLARE
    v_before text;
    v_after  text;
    v_zz     integer;
    v_fk     integer;
BEGIN
    SELECT snap INTO v_before FROM zz_before;
    v_after := pg_temp.zz_snap();
    SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
         + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%') INTO v_zz;
    SELECT count(*) INTO v_fk FROM pg_constraint WHERE conname = 'fk_orders_formation_change' AND conrelid = 'public.orders'::regclass;
    IF v_before IS DISTINCT FROM v_after OR v_zz <> 0 OR v_fk <> 1 THEN
        PERFORM setval('pg_temp.zz_verdict', 1);
        RAISE EXCEPTION E'P0-16 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0), fk_orders_formation_change % (기대 1)', v_before, v_after, v_zz, v_fk;
    END IF;
    -- 단언을 모두 통과했고 00–06절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_16_check;
