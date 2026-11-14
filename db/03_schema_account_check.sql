-- =====================================================================
-- my Fund Manager Phase 0 — DDL ③ 검증 SQL (P0-07)
-- 대상: db/03_schema_account.sql이 만든 5개 테이블 (cash_account, orders, execution, holding, cash_ledger).
--       01의 asset과 02의 season, formation_change, turn_feed는 FK 대상이자 픽스처의 부모로 쓰고, 이 파일은 그 구조를 검증하지 않는다
--       (01·02 검증의 몫). 다만 2절 픽스처는 01·02의 컬럼 목록과 CHECK 값(asset.return_basis·listed_on, season의 24개 컬럼,
--       formation_change, turn_feed)을 글자 그대로 쓴다. 01·02가 바뀌면 2절 첫 픽스처가 먼저 FAIL하고 뒤 절이 연쇄 FAIL하므로,
--       원인은 2절 첫 FAIL에서 찾는다.
-- 근거: docs/erd.md 7.3(엔티티 정의서), 7.4(제약과 인덱스, 개수 표), 7.5(대사 규칙 1–19는 이 기능 범위 밖), 7.6(v1 대조표), 9절 P0-07
-- 실행: psql -X -q -v ON_ERROR_STOP=1 -d <검증용 DB> -f db/03_schema_account_check.sql
--       01·02·03 스키마 파일을 순서대로 먼저 실행해 둔다. 검증용 DB(antlab_test)에서만 돌린다 (identity 값을 소비한다).
-- 판정: 마지막 줄 `p0_07_check`가 `PASS`이고 종료 코드가 0이면 통과다. `PASS`는 1–7절이 모두 통과하고
--       8절 흔적 확인까지 끝났을 때만 나온다 (zz_verdict: 1 = 미완료 또는 실패, 2 = 1–7절 통과·흔적 확인 대기, 0 = 최종 통과).
--       0절 전제가 하나라도 FAIL이면 0절 결과를 보여 준 뒤 픽스처를 넣기 전에 오류로 멈춘다.
--       그 밖에 하나라도 어긋나면 7절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다. 마지막 줄은 PASS가 아니라 FAIL이다.
-- 흔적: 파일 전체가 한 트랜잭션이고 7절 뒤에 ROLLBACK한다. 01·02·03의 22개 테이블의 행, 임시 객체가 남지 않는다.
--       identity(번호표) 값 소비만 되돌아오지 않는다.
-- 절:   0 전제(FAIL이면 여기서 멈춤) → 1 구조 → 2 양성 → 3 음성 DML → 4 저장 정밀도 경계 → 5 복합 FK와 DDL 경계
--       → 6 음성 DDL → 7 판정 → ROLLBACK → 8 흔적 확인
-- 판정 함수(pg_temp 프로시저): check_err(실패해야 정상) · check_ok(성공해야 정상) · check_eq(값 비교) + 값 질의 도우미 val().
--       호출은 CALL pg_temp.check_err(...) 처럼 pg_temp. 접두가 필요하다. 이 파일은 행 수 도우미 row_count()와
--       픽스처 번호 도우미 zid()·zlast()를 더 둔다.
-- 이후 검증 파일은 이 03 파일을 복사한다 (일부만 복사하면 헛 PASS 길이 다시 열린다):
--       ① 맨 앞 CREATE TEMP SEQUENCE zz_verdict + pg_temp 존재 단언 + setval('pg_temp.zz_verdict', 1) (모든 참조는 pg_temp. 한정)   ② 결과 표 zz_check_result
--       ③ 기대 목록 표 3개(zz_expect_*)의 모양 — zz_expect_count에 `uqi`(유일 인덱스) 열, zz_expect_column에 `lname`(논리명) 열이 있다
--       ④ 프로시저 check_err·check_ok·check_eq + 함수 val(), row_count()
--       ⑤ 0절 뒤 멈춤과 7.5 판정(→ 표식 2 또는 1, RAISE)   ⑥ 8절 흔적 확인(→ 표식 0)과 마지막 CASE
--       파일마다 바뀌는 것: 기대 목록 내용, 사례, 기대 개수(03은 제약 45·인덱스 15), 메시지의 P0-07, zz_table_list의 행.
-- 02와 달라진 점 (03부터 적용, 01·02 파일은 고치지 않았다):
--       · 테이블 이름 목록은 BEGIN 앞의 임시 표 zz_table_list (tbl, owner) 한 곳에만 있다 (22행: 01 8개, 02 9개, 03 5개).
--         0절과 8절이 이 표를 읽는다. 02는 같은 목록을 4곳에 글자로 썼다 (하나를 빠뜨려도 PASS일 수 있는 길). 위 ①과 달리
--         시퀀스 하나가 아니라 시퀀스 + 표 + row_count() 함수가 BEGIN 앞에 있다 — ROLLBACK 뒤 8절에서도 쓰려고 트랜잭션 밖에 둔다.
--       · zz_expect_column에 lname 열이 있다. 1.10이 컬럼 코멘트 전문 `[태그] 논리명`을 비교한다 (02는 태그만 비교).
--       · 1.7 FK 표기: 단일 FK는 `(a) → t.x`, 복합 FK는 `(a, b, c) → t (x, y, z)` — erd 7.4 정의 칸과 같은 꼴이다.
--       · 3절 가려진 CHECK 2개(ck_orders_type, ck_cash_ledger_type)와 PK에 가려진 UNIQUE 1개(uq_orders_id_season)는 가리는 제약을
--         같은 사례 안에서 지우고 증명한다 (서브트랜잭션이 되돌린다). 가려진 사실 자체도 한 건씩 기록한다.
--       · 5절은 인덱스가 아니라 복합 FK(MATCH SIMPLE)와 "DDL이 통과시키는 것 — 대사의 몫"이다.
--       · 픽스처 행은 zz_key 표(이름 → id)에 등록하고 pg_temp.zid('O0') 로 찾는다 (시즌은 seed가 아니라 이름으로).
-- 새 문법 안내: 사례 SQL은 $q$ ... $q$(달러 인용)로 감싼 문자열이다. 프로시저가 EXECUTE로 실행하고
--       예외를 잡아 SQLSTATE와 제약 이름을 결과 표에 적는다.
-- 명령줄(psql)이 기준이다. VS Code에서는 통합 터미널에서 실행한다. SQL 확장의 실행 기능은 오류 처리 방식이 달라 판정을 믿지 않는다.
-- psql 메타 명령(\set, \echo, \i)은 쓰지 않는다.
-- =====================================================================

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 1–7절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

-- 테이블 이름 목록: 이 파일에서 22개 이름이 적힌 유일한 곳. 트랜잭션 밖에 만들어 ROLLBACK 뒤 8절에서도 읽는다.
CREATE TEMP TABLE IF NOT EXISTS zz_table_list (tbl text NOT NULL, owner text NOT NULL);
TRUNCATE zz_table_list;
INSERT INTO zz_table_list (tbl, owner) VALUES
    ('asset_group', '01'), ('asset', '01'), ('asset_position', '01'), ('month_block', '01'),
    ('asset_month_return', '01'), ('fx_month', '01'), ('month_event', '01'), ('asset_month_attention', '01'),
    ('member', '02'), ('season', '02'), ('season_path', '02'), ('turn', '02'), ('turn_price', '02'),
    ('lineup', '02'), ('formation_change', '02'), ('turn_feed', '02'), ('reconciliation_log', '02'),
    ('cash_account', '03'), ('orders', '03'), ('execution', '03'), ('holding', '03'), ('cash_ledger', '03');

-- 테이블의 행 수. 0절·8절이 22개 테이블이 비어 있는지 셀 때 쓴다 (테이블 이름은 식별자로 인용한다).
CREATE OR REPLACE FUNCTION pg_temp.row_count(p_tbl text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_n bigint;
BEGIN
    EXECUTE format('SELECT count(*) FROM public.%I', p_tbl) INTO v_n;
    RETURN v_n;
END $$;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '0 전제' '1 구조' '2 양성' '3 음성' '4 경계' '5 복합 FK' '6 DDL' '7 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값 (SQLSTATE + 제약 이름, 개수, 정의 ...)
    actual   text,               -- 실제값 (같은 형식)
    ok       boolean NOT NULL
);

-- 기대 목록: erd 7.3·7.4를 그대로 옮겼다 (정의는 $$ ... $$ 안에 erd 글자 그대로)
CREATE TEMP TABLE zz_expect_count (tbl text, tbl_comment text, pk int, uq int, uqi int, fk int, ck int, idx int);        -- 5행
CREATE TEMP TABLE zz_expect_constraint (name text, kind text, tbl text, def text);                                         -- 53행
CREATE TEMP TABLE zz_expect_column (tbl text, pos int, col text, typ text, nn boolean, dflt text, ident boolean, tag text, lname text); -- 41행

INSERT INTO zz_expect_count VALUES
    ('cash_account', '현금 계좌', 1, 0, 0, 1, 4, 0),
    ('orders',       '주문',      1, 1, 0, 4, 9, 4),
    ('execution',    '체결',      1, 0, 0, 1, 8, 1),
    ('holding',      '보유',      1, 1, 0, 2, 1, 1),
    ('cash_ledger',  '현금 원장',   1, 0, 0, 2, 6, 2);
INSERT INTO zz_expect_constraint (name, kind, tbl, def) VALUES
    ('pk_cash_account', 'PK', 'cash_account', $$(season_id)$$),
    ('fk_cash_account_season', 'FK', 'cash_account', $$(season_id) → season.id (R23)$$),
    ('ck_cash_account_balance_nonneg', 'CK', 'cash_account', $$balance >= 0$$),
    ('ck_cash_account_reserved_range', 'CK', 'cash_account', $$reserved >= 0 AND reserved <= balance$$),
    ('ck_cash_account_balance_won', 'CK', 'cash_account', $$balance = ROUND(balance, 0) AND balance <> 'NaN'::numeric$$),
    ('ck_cash_account_reserved_won', 'CK', 'cash_account', $$reserved = ROUND(reserved, 0) AND reserved <> 'NaN'::numeric$$),
    ('pk_orders', 'PK', 'orders', $$(id)$$),
    ('uq_orders_id_season', 'UQ', 'orders', $$(id, season_id)$$),
    ('fk_orders_season', 'FK', 'orders', $$(season_id) → season.id (R24)$$),
    ('fk_orders_asset', 'FK', 'orders', $$(asset_id) → asset.id (R25)$$),
    ('fk_orders_formation_change', 'FK', 'orders', $$(formation_change_id, season_id, turn_no) → formation_change (id, season_id, turn_no) (R26)$$),
    ('fk_orders_turn_feed', 'FK', 'orders', $$(turn_feed_id, season_id) → turn_feed (id, season_id) (R27)$$),
    ('ck_orders_side', 'CK', 'orders', $$side IN ('BUY','SELL')$$),
    ('ck_orders_type', 'CK', 'orders', $$type IN ('MARKET','LIMIT')$$),
    ('ck_orders_quantity_pos', 'CK', 'orders', $$quantity > 0$$),
    ('ck_orders_limit_price', 'CK', 'orders', $$(type = 'MARKET' AND limit_price IS NULL) OR (type = 'LIMIT' AND limit_price IS NOT NULL AND limit_price > 0)$$),
    ('ck_orders_limit_price_won', 'CK', 'orders', $$limit_price = ROUND(limit_price, 0) AND limit_price <> 'NaN'::numeric$$),
    ('ck_orders_status', 'CK', 'orders', $$status IN ('PENDING','FILLED','CANCELED','REJECTED')$$),
    ('ck_orders_turn_no', 'CK', 'orders', $$turn_no >= 0$$),
    ('ck_orders_source', 'CK', 'orders', $$source IN ('RANKING','FEED','ALERT','BENCH','DIRECT')$$),
    ('ck_orders_source_feed', 'CK', 'orders', $$(source IN ('FEED','ALERT')) = (turn_feed_id IS NOT NULL)$$),
    ('idx_orders_season_turn', 'IDX', 'orders', $$(season_id, turn_no)$$),
    ('idx_orders_asset', 'IDX', 'orders', $$(asset_id)$$),
    ('idx_orders_formation_change', 'IDX', 'orders', $$(formation_change_id)$$),
    ('idx_orders_turn_feed', 'IDX', 'orders', $$(turn_feed_id)$$),
    ('pk_execution', 'PK', 'execution', $$(id)$$),
    ('fk_execution_order', 'FK', 'execution', $$(order_id) → orders.id (R28)$$),
    ('ck_execution_fill_price_pos', 'CK', 'execution', $$fill_price > 0$$),
    ('ck_execution_fill_price_won', 'CK', 'execution', $$fill_price = ROUND(fill_price, 0) AND fill_price <> 'NaN'::numeric$$),
    ('ck_execution_quantity_pos', 'CK', 'execution', $$quantity > 0$$),
    ('ck_execution_fee_nonneg', 'CK', 'execution', $$fee >= 0$$),
    ('ck_execution_tax_nonneg', 'CK', 'execution', $$tax >= 0$$),
    ('ck_execution_fee_won', 'CK', 'execution', $$fee = ROUND(fee, 0) AND fee <> 'NaN'::numeric$$),
    ('ck_execution_tax_won', 'CK', 'execution', $$tax = ROUND(tax, 0) AND tax <> 'NaN'::numeric$$),
    ('ck_execution_realized_pnl_not_nan', 'CK', 'execution', $$realized_pnl <> 'NaN'::numeric$$),
    ('idx_execution_order', 'IDX', 'execution', $$(order_id)$$),
    ('pk_holding', 'PK', 'holding', $$(id)$$),
    ('uq_holding_season_asset', 'UQ', 'holding', $$(season_id, asset_id)$$),
    ('fk_holding_season', 'FK', 'holding', $$(season_id) → season.id (R29)$$),
    ('fk_holding_asset', 'FK', 'holding', $$(asset_id) → asset.id (R30)$$),
    ('ck_holding_quantity_avg_price', 'CK', 'holding', $$(quantity = 0 AND avg_price = 0) OR (quantity > 0 AND avg_price > 0 AND avg_price <> 'NaN'::numeric)$$),
    ('idx_holding_asset', 'IDX', 'holding', $$(asset_id)$$),
    ('pk_cash_ledger', 'PK', 'cash_ledger', $$(id)$$),
    ('fk_cash_ledger_cash_account', 'FK', 'cash_ledger', $$(season_id) → cash_account.season_id (R31)$$),
    ('fk_cash_ledger_order', 'FK', 'cash_ledger', $$(ref_order_id, season_id) → orders (id, season_id) (R32)$$),
    ('ck_cash_ledger_type', 'CK', 'cash_ledger', $$type IN ('DEPOSIT','BUY','SELL','FEE','TAX','ADJUSTMENT')$$),
    ('ck_cash_ledger_amount_sign', 'CK', 'cash_ledger', $$(type IN ('DEPOSIT','SELL') AND amount > 0) OR (type IN ('BUY','FEE','TAX') AND amount < 0) OR (type = 'ADJUSTMENT' AND amount <> 0)$$),
    ('ck_cash_ledger_balance_after_nonneg', 'CK', 'cash_ledger', $$balance_after >= 0$$),
    ('ck_cash_ledger_amount_won', 'CK', 'cash_ledger', $$amount = ROUND(amount, 0) AND amount <> 'NaN'::numeric$$),
    ('ck_cash_ledger_balance_after_won', 'CK', 'cash_ledger', $$balance_after = ROUND(balance_after, 0) AND balance_after <> 'NaN'::numeric$$),
    ('ck_cash_ledger_ref_order', 'CK', 'cash_ledger', $$(type IN ('BUY','SELL','FEE','TAX') AND ref_order_id IS NOT NULL) OR (type = 'DEPOSIT' AND ref_order_id IS NULL) OR type = 'ADJUSTMENT'$$),
    ('idx_cash_ledger_season_id', 'IDX', 'cash_ledger', $$(season_id, id)$$),
    ('idx_cash_ledger_ref_order', 'IDX', 'cash_ledger', $$(ref_order_id)$$);
INSERT INTO zz_expect_column (tbl, pos, col, typ, nn, dflt, ident, tag, lname) VALUES
    ('cash_account', 1, 'season_id', 'bigint', true, NULL, false, '[내부]', '시즌번호'),
    ('cash_account', 2, 'balance', 'numeric(19,4)', true, '0', false, '[노출]', '잔액'),
    ('cash_account', 3, 'reserved', 'numeric(19,4)', true, '0', false, '[내부]', '예약금'),
    ('cash_account', 4, 'version', 'bigint', true, '0', false, '[내부]', '낙관적 락 버전'),
    ('cash_account', 5, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]', '생성일시'),
    ('cash_account', 6, 'updated_at', 'timestamp with time zone', true, 'now()', false, '[내부]', '수정일시'),
    ('orders', 1, 'id', 'bigint', true, NULL, true, '[내부]', '주문번호'),
    ('orders', 2, 'season_id', 'bigint', true, NULL, false, '[내부]', '시즌번호'),
    ('orders', 3, 'asset_id', 'bigint', true, NULL, false, '[내부]', '종목번호'),
    ('orders', 4, 'turn_no', 'smallint', true, NULL, false, '[노출]', '턴 번호'),
    ('orders', 5, 'side', 'character varying(4)', true, NULL, false, '[노출]', '매수매도 구분'),
    ('orders', 6, 'type', 'character varying(6)', true, '''MARKET''::character varying', false, '[내부]', '주문 유형'),
    ('orders', 7, 'quantity', 'integer', true, NULL, false, '[노출]', '수량'),
    ('orders', 8, 'limit_price', 'numeric(19,4)', false, NULL, false, '[내부]', '지정가'),
    ('orders', 9, 'status', 'character varying(10)', true, '''PENDING''::character varying', false, '[내부]', '주문 상태'),
    ('orders', 10, 'source', 'character varying(7)', true, NULL, false, '[노출]', '출발점'),
    ('orders', 11, 'formation_change_id', 'bigint', false, NULL, false, '[내부]', '전술 변경번호'),
    ('orders', 12, 'turn_feed_id', 'bigint', false, NULL, false, '[내부]', '피드 항목번호'),
    ('orders', 13, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]', '주문일시'),
    ('execution', 1, 'id', 'bigint', true, NULL, true, '[내부]', '체결번호'),
    ('execution', 2, 'order_id', 'bigint', true, NULL, false, '[내부]', '주문번호'),
    ('execution', 3, 'fill_price', 'numeric(19,4)', true, NULL, false, '[노출]', '체결가'),
    ('execution', 4, 'quantity', 'integer', true, NULL, false, '[노출]', '체결 수량'),
    ('execution', 5, 'fee', 'numeric(19,4)', true, '0', false, '[노출]', '수수료'),
    ('execution', 6, 'tax', 'numeric(19,4)', true, '0', false, '[노출]', '세금'),
    ('execution', 7, 'realized_pnl', 'numeric(19,4)', false, NULL, false, '[노출]', '실현 손익'),
    ('execution', 8, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]', '체결일시'),
    ('holding', 1, 'id', 'bigint', true, NULL, true, '[내부]', '보유번호'),
    ('holding', 2, 'season_id', 'bigint', true, NULL, false, '[내부]', '시즌번호'),
    ('holding', 3, 'asset_id', 'bigint', true, NULL, false, '[내부]', '종목번호'),
    ('holding', 4, 'quantity', 'integer', true, NULL, false, '[노출]', '보유 수량'),
    ('holding', 5, 'avg_price', 'numeric(19,4)', true, '0', false, '[노출]', '평균단가'),
    ('holding', 6, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]', '생성일시'),
    ('holding', 7, 'updated_at', 'timestamp with time zone', true, 'now()', false, '[내부]', '수정일시'),
    ('cash_ledger', 1, 'id', 'bigint', true, NULL, true, '[내부]', '원장번호'),
    ('cash_ledger', 2, 'season_id', 'bigint', true, NULL, false, '[내부]', '시즌번호'),
    ('cash_ledger', 3, 'type', 'character varying(12)', true, NULL, false, '[노출]', '원장 유형'),
    ('cash_ledger', 4, 'amount', 'numeric(19,4)', true, NULL, false, '[노출]', '부호 있는 금액'),
    ('cash_ledger', 5, 'balance_after', 'numeric(19,4)', true, NULL, false, '[노출]', '거래 후 잔액'),
    ('cash_ledger', 6, 'ref_order_id', 'bigint', false, NULL, false, '[내부]', '참조 주문번호'),
    ('cash_ledger', 7, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]', '기록일시');
-- 판정 프로시저 ------------------------------------------------------------

-- 실패해야 정상인 문장. 성공하든 실패하든 문장의 효과는 남지 않는다 (서브트랜잭션으로 되돌린다).
-- p_name은 제약 이름이다 (23502는 컬럼 이름). NULL이면 SQLSTATE만 본다.
CREATE PROCEDURE pg_temp.check_err(p_section text, p_item text, p_sql text, p_state text, p_name text DEFAULT NULL)
LANGUAGE plpgsql AS $$
DECLARE
    v_state text := '00000';
    v_con   text;
    v_col   text;
    v_msg   text;
    v_name  text;
BEGIN
    BEGIN
        EXECUTE p_sql;
        RAISE EXCEPTION 'zz_statement_succeeded' USING ERRCODE = 'P0001';   -- 성공했어도 되돌린다
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_con = CONSTRAINT_NAME,
                                v_col = COLUMN_NAME, v_msg = MESSAGE_TEXT;
    END;
    IF v_state = 'P0001' AND v_msg = 'zz_statement_succeeded' THEN
        v_state := '00000'; v_con := NULL; v_col := NULL;
    END IF;
    v_name := COALESCE(NULLIF(v_con, ''), NULLIF(v_col, ''));
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item,
            p_state || COALESCE(' ' || p_name, ''),
            v_state || COALESCE(' ' || v_name, '') || CASE WHEN v_state NOT IN ('00000') AND v_name IS NULL THEN ' ' || left(v_msg, 60) ELSE '' END,
            v_state = p_state AND (p_name IS NULL OR v_name IS NOT DISTINCT FROM p_name));
END $$;

-- 성공해야 정상인 문장. 효과를 남긴다 (뒤 사례가 쓰는 픽스처). 실패하면 FAIL 행을 남기고 계속 간다.
CREATE PROCEDURE pg_temp.check_ok(p_section text, p_item text, p_sql text)
LANGUAGE plpgsql AS $$
DECLARE
    v_state text;
    v_msg   text;
BEGIN
    BEGIN
        EXECUTE p_sql;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES (p_section, p_item, '00000', '00000', true);
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_state = RETURNED_SQLSTATE, v_msg = MESSAGE_TEXT;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES (p_section, p_item, '00000', v_state || ' ' || left(v_msg, 80), false);
    END;
END $$;

-- 값 비교. NULL끼리는 같다고 본다.
CREATE PROCEDURE pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- 스칼라 질의 한 개를 실행해 값을 text로 돌려준다. CALL 인자에는 서브쿼리를 쓸 수 없어 check_eq의 실제값을 만드는 데 쓴다.
-- 정확히 한 행이어야 한다. 0행은 NULL, 여러 행은 기대값과 같을 수 없는 'ERROR too_many_rows'를 돌려 FAIL 행으로 남긴다.
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

-- 픽스처 번호표: 이름('O0', 'S0' ...) → id. 시즌은 seed가 아니라 이름으로 찾는다. 없는 이름은 오류(P0002)라 사례가 FAIL로 드러난다.
CREATE TEMP TABLE zz_key (k text PRIMARY KEY, id bigint NOT NULL);
CREATE FUNCTION pg_temp.zid(p_k text) RETURNS bigint
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_id bigint;
BEGIN
    SELECT id INTO STRICT v_id FROM zz_key WHERE k = p_k;
    RETURN v_id;
END $$;
-- 방금 이 세션이 만든 행의 id (identity 컬럼 id의 currval). INSERT 바로 뒤에 zz_key에 등록할 때 쓴다.
CREATE FUNCTION pg_temp.zlast(p_tbl text) RETURNS bigint
LANGUAGE sql AS $$ SELECT currval(pg_get_serial_sequence(p_tbl, 'id')) $$;

-- 0 전제 -------------------------------------------------------------------
-- 하나라도 FAIL이면 1절 이후 결과를 믿지 않는다.
-- 22개 = 이 파일의 5개 + 02의 9개 + 01의 8개. 이름은 BEGIN 앞의 zz_table_list에서 읽는다.
-- 픽스처가 01·02 테이블에도 행을 넣으므로 22개가 모두 비어 있어야 한다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('이름 목록 자기 점검: owner별 개수 (01 / 02 / 03)와 03 목록 = 검증 대상 5개', '01=8 02=9 03=5 match=5',
        (SELECT format('01=%s 02=%s 03=%s match=%s',
                       count(*) FILTER (WHERE owner = '01'), count(*) FILTER (WHERE owner = '02'), count(*) FILTER (WHERE owner = '03'),
                       count(*) FILTER (WHERE owner = '03' AND tbl IN (SELECT tbl FROM zz_expect_count)))
         FROM zz_table_list)),
    ('22개 이름이 모두 public으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '22',
        (SELECT count(*)::text FROM zz_table_list t
         WHERE to_regclass(t.tbl) IS NOT NULL AND to_regclass(t.tbl) = to_regclass('public.' || t.tbl))),
    ('public에 22개 테이블이 있다 (01의 8 + 02의 9 + 03의 5)', '22',
        (SELECT count(*)::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname IN (SELECT tbl FROM zz_table_list))),
    ('public의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text)
) AS x(item, expected, actual);

-- 22개 테이블이 비어 있다 (비어 있지 않으면 중복 키 같은 우연한 충돌이 생긴다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', '22개 테이블의 행 수 합', '0', s.n::text, s.n = 0
FROM (SELECT COALESCE(sum(pg_temp.row_count(t.tbl)), 0) AS n
      FROM zz_table_list t
      WHERE to_regclass('public.' || t.tbl) IS NOT NULL) AS s;

-- 정보 행: 현재 DB 이름 (FAIL 아님. 검증용 DB antlab_test인지 눈으로 본다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('0 전제', '현재 DB 이름 (정보)', '(정보)', current_database(), true);

-- 0절 결과를 먼저 보여 주고, FAIL이 있으면 픽스처를 넣기 전에 멈춘다 (표식은 1로 남아 ON_ERROR_STOP 없이도 마지막 줄이 FAIL이다).
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
        RAISE EXCEPTION 'P0-07 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 전제를 맞춘 뒤 다시 실행한다)', v_fail;
    END IF;
END $$;

-- 1 구조 -------------------------------------------------------------------
-- 모든 카탈로그 조회는 이 5개 테이블만 본다. 종류는 contype IN ('p','u','f','c')로 고정한다.
-- 제약이 아닌 유일 인덱스(UQI)는 pg_index에서 따로 센다 (03에는 없어 0이다).

-- 1.1 테이블별 개수 (erd 7.4 개수 표). 유일 인덱스(uqi)는 제약에 딸리지 않은 UNIQUE 인덱스다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '개수 PK·UQ·UQI·FK·CK·idx_: ' || e.tbl,
       format('pk=%s uq=%s uqi=%s fk=%s ck=%s idx=%s', e.pk, e.uq, e.uqi, e.fk, e.ck, e.idx),
       format('pk=%s uq=%s uqi=%s fk=%s ck=%s idx=%s', a.pk, a.uq, a.uqi, a.fk, a.ck, a.idx),
       (e.pk, e.uq, e.uqi, e.fk, e.ck, e.idx) IS NOT DISTINCT FROM (a.pk, a.uq, a.uqi, a.fk, a.ck, a.idx)
FROM zz_expect_count e
CROSS JOIN LATERAL (
    SELECT (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.' || e.tbl) AND contype = 'p') AS pk,
           (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.' || e.tbl) AND contype = 'u') AS uq,
           (SELECT count(*) FROM pg_index i
            WHERE i.indrelid = to_regclass('public.' || e.tbl) AND i.indisunique
              AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid AND c.contype IN ('p', 'u', 'x'))) AS uqi,
           (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.' || e.tbl) AND contype = 'f') AS fk,
           (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.' || e.tbl) AND contype = 'c') AS ck,
           (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename = e.tbl AND indexname LIKE 'idx\_%') AS idx
) a
ORDER BY e.tbl;

-- 1.2 인덱스 총 개수 (PK 5 + UQ 2 + idx_ 8 = 15)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '5개 테이블의 인덱스 총 개수 (PK + UQ + UQI + idx_)', '15', n::text, n = 15
FROM (SELECT count(*) AS n FROM pg_indexes WHERE schemaname = 'public' AND tablename IN (SELECT tbl FROM zz_expect_count)) s;

-- 1.3 테이블 코멘트 (논리명)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '테이블 코멘트: ' || e.tbl, e.tbl_comment, obj_description(to_regclass('public.' || e.tbl), 'pg_class'),
       e.tbl_comment IS NOT DISTINCT FROM obj_description(to_regclass('public.' || e.tbl), 'pg_class')
FROM zz_expect_count e
ORDER BY e.tbl;

-- 1.4 제약·인덱스 53개의 이름·종류·테이블이 1:1 (빠진 것도 남는 것도 한 행씩 FAIL)
--     인덱스는 이름이 idx_인지와 상관없이 "제약에 딸리지 않은 모든 인덱스"를 센다: 유일하면 UQI, 아니면 IDX.
--     그래서 이름 규칙 밖의 인덱스(예: foo)도 "(없어야 함)" 행으로 드러난다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
WITH act AS (
    SELECT c.conname::text AS name,
           CASE c.contype WHEN 'p' THEN 'PK' WHEN 'u' THEN 'UQ' WHEN 'f' THEN 'FK' WHEN 'c' THEN 'CK' END AS kind,
           (SELECT relname::text FROM pg_class WHERE oid = c.conrelid) AS tbl
    FROM pg_constraint c
    WHERE c.contype IN ('p', 'u', 'f', 'c')
      AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)
    UNION ALL
    SELECT ic.relname::text, CASE WHEN i.indisunique THEN 'UQI' ELSE 'IDX' END, tc.relname::text
    FROM pg_index i
    JOIN pg_class ic ON ic.oid = i.indexrelid
    JOIN pg_class tc ON tc.oid = i.indrelid
    WHERE i.indrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)
      AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid AND c.contype IN ('p', 'u', 'x'))
)
SELECT '1 구조', '이름·종류·테이블: ' || COALESCE(e.name, a.name),
       COALESCE(e.kind || ' ' || e.tbl, '(없어야 함)'), COALESCE(a.kind || ' ' || a.tbl, '(없음)'),
       e.kind IS NOT DISTINCT FROM a.kind AND e.tbl IS NOT DISTINCT FROM a.tbl
FROM zz_expect_constraint e FULL JOIN act a ON a.name = e.name
ORDER BY COALESCE(e.name, a.name);

-- 1.5 CHECK 28개의 정의 — 왕복 정규화
--     erd 7.4의 식을 빈 임시 사본(LIKE: 컬럼 이름·타입·NOT NULL만 복사)에 CHECK로 붙여 PostgreSQL이 다시 파싱하게 하고,
--     실제 제약과 pg_get_constraintdef 출력끼리 비교한다. 괄호·캐스트·IN·BETWEEN 표기 차이에 흔들리지 않는다.
--     erd 식에 오타가 있으면 ALTER가 실패해 FAIL로 드러난다. 사본은 1.8의 인덱스 왕복도 쓴다.
DO $$
DECLARE
    t record;
    r record;
    v_probe text;
    v_exp   text;
    v_act   text;
BEGIN
    FOR t IN SELECT tbl FROM zz_expect_count LOOP
        EXECUTE format('CREATE TEMP TABLE %I (LIKE public.%I)', 'zz_probe_' || t.tbl, t.tbl);
    END LOOP;
    FOR r IN SELECT name, tbl, def FROM zz_expect_constraint WHERE kind = 'CK' ORDER BY name LOOP
        v_probe := 'zz_probe_' || r.tbl;
        v_exp := NULL;
        BEGIN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s)', v_probe, r.name, r.def);
            SELECT pg_get_constraintdef(c.oid) INTO v_exp
            FROM pg_constraint c
            WHERE c.conrelid = to_regclass('pg_temp.' || quote_ident(v_probe)) AND c.conname = r.name;
        EXCEPTION WHEN OTHERS THEN
            v_exp := 'ERROR ' || SQLSTATE || ' ' || SQLERRM;
        END;
        SELECT pg_get_constraintdef(c.oid) INTO v_act
        FROM pg_constraint c
        WHERE c.conrelid = to_regclass('public.' || r.tbl) AND c.conname = r.name AND c.contype = 'c';
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('1 구조', 'CHECK 정의(왕복): ' || r.name, v_exp, v_act, v_act IS NOT NULL AND v_act IS NOT DISTINCT FROM v_exp);
    END LOOP;
END $$;

-- 1.6 PK·UNIQUE 정의 전체 (컬럼 순서, DEFERRABLE·NULLS NOT DISTINCT·INCLUDE 같은 덧붙은 속성까지)
--      기대값은 erd 정의 칸 앞에 PRIMARY KEY / UNIQUE 를 붙인 것이고, 실제값은 pg_get_constraintdef 출력 전체다.
--      uq_orders_id_season이 DEFERRABLE이면 복합 FK의 대상이 될 수 없다 (6 DDL D9). uq_holding_season_asset은 참조되지 않아 여기서 잡는다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'PK·UQ 정의: ' || e.name,
       CASE e.kind WHEN 'PK' THEN 'PRIMARY KEY ' ELSE 'UNIQUE ' END || e.def,
       pg_get_constraintdef(c.oid),
       (CASE e.kind WHEN 'PK' THEN 'PRIMARY KEY ' ELSE 'UNIQUE ' END || e.def) IS NOT DISTINCT FROM pg_get_constraintdef(c.oid)
FROM zz_expect_constraint e
LEFT JOIN pg_constraint c ON c.conrelid = to_regclass('public.' || e.tbl) AND c.conname = e.name
                         AND c.contype = CASE e.kind WHEN 'PK' THEN 'p' ELSE 'u' END
WHERE e.kind IN ('PK', 'UQ')
ORDER BY e.name;

-- 1.7 FK 10개: 컬럼 → 대상 테이블 (erd의 (R23) 꼬리표는 뗀다), 동작 RESTRICT·RESTRICT, 지연 없음, MATCH SIMPLE
--     표기는 erd 7.4 정의 칸과 같다: 단일 FK는 `(a) → t.x`, 복합 FK는 `(a, b, c) → t (x, y, z)` (컬럼 순서까지).
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 컬럼·대상: ' || e.name, regexp_replace(e.def, ' \(R[0-9]+\)$', ''), a.def,
       regexp_replace(e.def, ' \(R[0-9]+\)$', '') IS NOT DISTINCT FROM a.def
FROM zz_expect_constraint e
LEFT JOIN LATERAL (
    SELECT '(' || (SELECT string_agg(att.attname, ', ' ORDER BY k.ord)
                   FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                   JOIN pg_attribute att ON att.attrelid = c.conrelid AND att.attnum = k.attnum)
           || ') → ' || (SELECT relname FROM pg_class WHERE oid = c.confrelid)
           || CASE WHEN cardinality(c.confkey) = 1 THEN '.' ELSE ' (' END
           || (SELECT string_agg(att.attname, ', ' ORDER BY k.ord)
               FROM unnest(c.confkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN pg_attribute att ON att.attrelid = c.confrelid AND att.attnum = k.attnum)
           || CASE WHEN cardinality(c.confkey) = 1 THEN '' ELSE ')' END AS def
    FROM pg_constraint c
    WHERE c.conrelid = to_regclass('public.' || e.tbl) AND c.conname = e.name AND c.contype = 'f'
) a ON true
WHERE e.kind = 'FK'
ORDER BY e.name;

-- 복합 FK 3개의 match=s(SIMPLE)가 D29의 NULL 우회 전제다 (자식 컬럼 중 하나라도 NULL이면 검사하지 않는다, 5.1).
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 동작: ' || e.name, 'update=r delete=r deferrable=false match=s',
       format('update=%s delete=%s deferrable=%s match=%s', c.confupdtype, c.confdeltype, c.condeferrable::text, c.confmatchtype),
       (c.confupdtype, c.confdeltype, c.condeferrable, c.confmatchtype) = ('r', 'r', false, 's')
FROM zz_expect_constraint e
LEFT JOIN pg_constraint c ON c.conrelid = to_regclass('public.' || e.tbl) AND c.conname = e.name AND c.contype = 'f'
WHERE e.kind = 'FK'
ORDER BY e.name;

-- 1.8 인덱스 8개(idx_ 8, 유일 인덱스 0)의 정의 — 왕복 정규화
--     erd 정의 칸(예: (season_id, turn_no))을 1.5가 만든 빈 사본에 CREATE [UNIQUE] INDEX로 붙여 PostgreSQL이 다시 파싱하게 하고,
--     그 pg_get_indexdef의 ' USING …' 뒤를 실제 pg_indexes.indexdef 전체와 비교한다. UNIQUE·NULLS NOT DISTINCT·INCLUDE·WHERE가
--     모두 문자열에 들어 있어 덧붙은 속성까지 잡힌다.
DO $$
DECLARE
    r record;
    v_exp text;
    v_act text;
BEGIN
    FOR r IN SELECT name, kind, tbl, def FROM zz_expect_constraint WHERE kind IN ('IDX', 'UQI') ORDER BY name LOOP
        v_exp := NULL;
        BEGIN
            EXECUTE format('CREATE %sINDEX %I ON %I %s', CASE r.kind WHEN 'UQI' THEN 'UNIQUE ' ELSE '' END,
                           'zz_rt_' || r.name, 'zz_probe_' || r.tbl, r.def);
            SELECT 'CREATE ' || CASE r.kind WHEN 'UQI' THEN 'UNIQUE ' ELSE '' END || 'INDEX ' || r.name || ' ON public.' || r.tbl
                   || substring(pg_get_indexdef(to_regclass('pg_temp.' || quote_ident('zz_rt_' || r.name))) from ' USING .*$')
            INTO v_exp;
        EXCEPTION WHEN OTHERS THEN
            v_exp := 'ERROR ' || SQLSTATE || ' ' || SQLERRM;
        END;
        SELECT indexdef INTO v_act FROM pg_indexes WHERE schemaname = 'public' AND indexname = r.name;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('1 구조', '인덱스 정의(왕복): ' || r.name, v_exp, v_act, v_act IS NOT NULL AND v_act IS NOT DISTINCT FROM v_exp);
    END LOOP;
END $$;

-- 1.9 FK 인덱스 짝: 모든 FK의 첫 컬럼이 같은 테이블 어떤 **전체 인덱스**의 첫 컬럼이다 (erd 2절, 10/10)
--     부분 인덱스(indpred)와 표현식 인덱스(indkey[0] = 0)는 짝으로 치지 않는다.
--     짝: cash_account.season_id → pk_cash_account, orders.season_id → idx_orders_season_turn,
--         holding.season_id → uq_holding_season_asset, cash_ledger.season_id → idx_cash_ledger_season_id.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 인덱스 짝: ' || c.conname, 'true',
       EXISTS (SELECT 1 FROM pg_index i
               WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indpred IS NULL AND i.indkey[0] <> 0
                 AND i.indkey[0] = c.conkey[1])::text,
       EXISTS (SELECT 1 FROM pg_index i
               WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indpred IS NULL AND i.indkey[0] <> 0
                 AND i.indkey[0] = c.conkey[1])
FROM pg_constraint c
WHERE c.contype = 'f' AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)
ORDER BY c.conname;

-- 1.10 컬럼 41개: 순서·이름·타입·NULL·기본값·identity(ALWAYS)·코멘트 전문 `[태그] 논리명` (erd 7.3)
--      코멘트는 태그와 논리명을 나눠 비교한다 (태그 형식이 다르면 NULL이 되어 FAIL, 논리명 오타도 FAIL).
--      기본값 칸이 비어 있는 컬럼은 DB 기본값이 없다는 뜻이다 (특히 orders.source·turn_no, cash_ledger.type·amount·balance_after).
INSERT INTO zz_check_result (section, item, expected, actual, ok)
WITH act AS (
    SELECT c.relname::text AS tbl,
           (row_number() OVER (PARTITION BY c.relname ORDER BY a.attnum))::int AS pos,
           a.attname::text AS col,
           format_type(a.atttypid, a.atttypmod) AS typ,
           a.attnotnull AS nn,
           pg_get_expr(d.adbin, d.adrelid) AS dflt,
           (a.attidentity = 'a') AS ident,
           substring(col_description(a.attrelid, a.attnum) from '^(\[(?:노출|복기 전용|내부)\]) .+$') AS tag,
           substring(col_description(a.attrelid, a.attnum) from '^\[(?:노출|복기 전용|내부)\] (.+)$') AS lname
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind = 'r'
      AND c.relname IN (SELECT tbl FROM zz_expect_count)
)
SELECT '1 구조', '컬럼: ' || COALESCE(e.tbl, a.tbl) || ' #' || COALESCE(e.pos, a.pos),
       CASE WHEN e.tbl IS NULL THEN '(없어야 함)'
            ELSE format('%s | %s | nn=%s | default=%s | identity=%s | %s %s', e.col, e.typ, e.nn, COALESCE(e.dflt, '-'), e.ident, e.tag, e.lname) END,
       CASE WHEN a.tbl IS NULL THEN '(없음)'
            ELSE format('%s | %s | nn=%s | default=%s | identity=%s | %s %s', a.col, a.typ, a.nn, COALESCE(a.dflt, '-'), a.ident, a.tag, a.lname) END,
       e.tbl IS NOT NULL AND a.tbl IS NOT NULL
       AND (e.col, e.typ, e.nn, e.dflt, e.ident, e.tag, e.lname) IS NOT DISTINCT FROM (a.col, a.typ, a.nn, a.dflt, a.ident, a.tag, a.lname)
FROM zz_expect_column e
FULL JOIN act a ON a.tbl = e.tbl AND a.pos = e.pos
ORDER BY COALESCE(e.tbl, a.tbl), COALESCE(e.pos, a.pos);

-- 1.11 부동소수 타입(4바이트·8바이트 실수, 타입 번호 700·701)과 정밀도 없는 numeric 컬럼이 없다
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '부동소수·정밀도 없는 numeric 컬럼 수', '0', n::text, n = 0
FROM (SELECT count(*) AS n
      FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace ns ON ns.oid = c.relnamespace AND ns.nspname = 'public'
      WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind = 'r' AND c.relname IN (SELECT tbl FROM zz_expect_count)
        AND (a.atttypid IN (700, 701)
             OR (a.atttypid = 'numeric'::regtype AND a.atttypmod = -1))) s;

-- 1.12 CHECK·FK가 모두 검증된 상태(NOT VALID 없음), FK 내부 트리거가 모두 켜져 있고(10 × 4 = 40),
--      인덱스 15개가 모두 유효하다 (indisvalid·indisready — CREATE INDEX가 중간에 끊긴 흔적이 없다)
--      트리거는 FK의 소유 테이블(conrelid)이 이 5개인 것만 센다. 03 → 01·02 FK가 부모 테이블에 만든 트리거도 FK 소유가 03이라 여기서 센다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'NOT VALID 제약 수 (CHECK·FK)', '0', n::text, n = 0
FROM (SELECT count(*) AS n FROM pg_constraint
      WHERE contype IN ('c', 'f') AND NOT convalidated
        AND conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)) s;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 내부 트리거 (전체 / 켜져 있지 않은 것)', 'total=40 not_enabled=0',
       format('total=%s not_enabled=%s', s.total, s.off), (s.total, s.off) = (40, 0)
FROM (SELECT count(*) AS total, count(*) FILTER (WHERE t.tgenabled <> 'O') AS off
      FROM pg_trigger t JOIN pg_constraint c ON c.oid = t.tgconstraint
      WHERE c.contype = 'f' AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)) s;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '인덱스 (전체 / 유효하지 않거나 준비되지 않은 것)', 'total=15 invalid=0',
       format('total=%s invalid=%s', s.total, s.bad), (s.total, s.bad) = (15, 0)
FROM (SELECT count(*) AS total, count(*) FILTER (WHERE NOT (i.indisvalid AND i.indisready)) AS bad
      FROM pg_index i
      WHERE i.indrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)) s;


-- 2 양성 (과잉 제약이 없다는 증거) ---------------------------------------------
-- 여기서 만든 행이 뒤 절의 픽스처다. 자연 키는 ZZ_ / ZZ 접두라 다른 데이터와 겹치지 않고, 끝의 ROLLBACK이 지운다.
-- 01의 asset, 02의 member·season·formation_change·turn_feed에도 행을 넣는다 — 03의 FK 부모다.
-- 행은 zz_key에 이름으로 등록한다 (pg_temp.zid('O0')). 시즌: S0 기준(seed 100), S1 다른 시즌(계좌·원장 있음),
--   R1(계좌만) R2(주문만) R3(보유만) R4(계좌 없음 — 원장 FK 사례), SB(4절 경계용), SC(5.2 대사의 몫 기록용).
-- 03 기준 행(3절이 UPDATE 한 컬럼으로 어기는 행), 모두 S0:
--   계좌 A0(잔액 28,756,601 = 30,000,000 − 490,700 − 74 − 750,000 − 2,625, 예약 0)
--   주문 O0(턴 3, BUY ZZ0001 7주, DIRECT, FILLED, MARKET) · 체결 E0(체결가 70,100, 수수료 74 = round(70,100 × 7 × 0.00015 = 73.605, 0))
--   · 보유 H0(7주, 평균 70,100) · 원장 L0 DEPOSIT 30,000,000 → L1 BUY −490,700 → L2 FEE −74 (잔액 연속)
--   미국 주문 OU(ZZAAPL 3주, 체결가 250,000, 수수료 2,625 = round(750,000 × (0.0025 + 0.0010), 0) — 요율을 먼저 더하고 한 번 반올림)
--   기준 행은 모두 유효하며 2절이 그것을 증명한다.

-- 01 테이블 (FK 부모)
CALL pg_temp.check_ok('2 양성', '01 픽스처: asset_group ZZ_GROUP',
    $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_GROUP', 'zz 업종', 'SECTOR')$q$);
CALL pg_temp.check_ok('2 양성', '01 픽스처: asset ZZ0001·ZZ0002 (KR), ZZAAPL (US), ZZDEL1 (주문만)·ZZDEL2 (보유만) (RESTRICT용 KR)',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       SELECT CASE WHEN t = 'ZZAAPL' THEN 'US' ELSE 'KR' END, t, 'zz ' || t, 'STOCK',
              CASE WHEN t = 'ZZAAPL' THEN 'USD' ELSE 'KRW' END, (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03',
              CASE WHEN t = 'ZZAAPL' THEN 'TOTAL' ELSE 'PRICE' END
       FROM unnest(ARRAY['ZZ0001', 'ZZ0002', 'ZZAAPL', 'ZZDEL1', 'ZZDEL2']) AS t;
       INSERT INTO zz_key SELECT 'A:' || ticker, id FROM asset WHERE ticker LIKE 'ZZ%'$q$);

-- 02 테이블 (FK 부모)
CALL pg_temp.check_ok('2 양성', '02 픽스처: member zz_a@example.com',
    $q$INSERT INTO member (email, name, password) VALUES ('zz_a@example.com', 'zz 회원', 'zz-hash');
       INSERT INTO zz_key VALUES ('M0', pg_temp.zlast('member'))$q$);
CALL pg_temp.check_ok('2 양성', '02 픽스처: 기준 시즌 S0 (seed 100, IN_PROGRESS, 경로 10, 현재 턴 3, 포메이션 10·30·30·30, 위험 -0.20, 수수료 0.00015·0.0025·0.001, 초기 자금 30,000,000)',
    $q$INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version, data_snapshot_date, base_date,
                           initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder, start_forward,
                           current_goalkeeper, current_defender, current_midfielder, current_forward)
       VALUES (pg_temp.zid('M0'), 'IN_PROGRESS', 10, 3, NULL, 100, 'p0-sql', '2025-01-01', '2025-04-02',
               30000000, 0.00015, 0.0025, 0.001, -0.20, 10, 30, 30, 30, 10, 30, 30, 30);
       INSERT INTO zz_key VALUES ('S0', pg_temp.zlast('season'))$q$);
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM (VALUES ('S1', 101), ('R1', 111), ('R2', 112), ('R3', 113), ('R4', 114), ('SB', 400), ('SC', 401)) AS x(k, seed) LOOP
        CALL pg_temp.check_ok('2 양성', format('02 픽스처: 시즌 %s (seed %s, S0 복사)', r.k, r.seed),
            format($f$INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                                          start_goalkeeper, start_defender, start_midfielder, start_forward,
                                          current_goalkeeper, current_defender, current_midfielder, current_forward)
                      SELECT member_id, path_length, %s, engine_version, data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                             start_goalkeeper, start_defender, start_midfielder, start_forward,
                             current_goalkeeper, current_defender, current_midfielder, current_forward
                      FROM season WHERE id = pg_temp.zid('S0');
                      INSERT INTO zz_key VALUES (%L, pg_temp.zlast('season'))$f$, r.seed, r.k));
    END LOOP;
END $$;
-- formation_change: FC0 (S0 턴 3), FC0b (S0 턴 2), FC1 (S1 턴 3 — 다른 시즌), FCR (S0 턴 3, 주문 하나만 묶는다)
CALL pg_temp.check_ok('2 양성', '02 픽스처: formation_change FC0 (S0 턴 3, 10·30·30·30 → 20·30·30·20)',
    $q$INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                     after_goalkeeper, after_defender, after_midfielder, after_forward)
       VALUES (pg_temp.zid('S0'), 3, 10, 30, 30, 30, 20, 30, 30, 20);
       INSERT INTO zz_key VALUES ('FC0', pg_temp.zlast('formation_change'))$q$);
CALL pg_temp.check_ok('2 양성', '02 픽스처: formation_change FC0b (S0 턴 2 — 같은 시즌 다른 턴)',
    $q$INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                     after_goalkeeper, after_defender, after_midfielder, after_forward)
       VALUES (pg_temp.zid('S0'), 2, 10, 30, 30, 30, 15, 30, 30, 25);
       INSERT INTO zz_key VALUES ('FC0b', pg_temp.zlast('formation_change'))$q$);
CALL pg_temp.check_ok('2 양성', '02 픽스처: formation_change FC1 (S1 턴 3 — 다른 시즌, 턴은 같다)',
    $q$INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                     after_goalkeeper, after_defender, after_midfielder, after_forward)
       VALUES (pg_temp.zid('S1'), 3, 10, 30, 30, 30, 20, 30, 30, 20);
       INSERT INTO zz_key VALUES ('FC1', pg_temp.zlast('formation_change'))$q$);
CALL pg_temp.check_ok('2 양성', '02 픽스처: formation_change FCR (S0 턴 3, RESTRICT용 — 주문 하나만 묶는다)',
    $q$INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                     after_goalkeeper, after_defender, after_midfielder, after_forward)
       VALUES (pg_temp.zid('S0'), 3, 10, 30, 30, 30, 30, 30, 20, 20);
       INSERT INTO zz_key VALUES ('FCR', pg_temp.zlast('formation_change'))$q$);
-- turn_feed: TF0 (S0 턴 3 RISE + ZZ0001), TF1 (S1 — 다른 시즌), TFR (S0 턴 4, RESTRICT용 — 주문 하나만 가리킨다)
CALL pg_temp.check_ok('2 양성', '02 픽스처: turn_feed TF0 (S0 턴 3 seq 1, RISE + ZZ0001)',
    $q$INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, direction, strength, template_id, body)
       VALUES (pg_temp.zid('S0'), 3, 1, 'RISE', pg_temp.zid('A:ZZ0001'), 'UP', 2, 'zz_t', 'zz');
       INSERT INTO zz_key VALUES ('TF0', pg_temp.zlast('turn_feed'))$q$);
CALL pg_temp.check_ok('2 양성', '02 픽스처: turn_feed TF1 (S1 턴 1 seq 1 — 다른 시즌)',
    $q$INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, direction, strength, template_id, body)
       VALUES (pg_temp.zid('S1'), 1, 1, 'RISE', pg_temp.zid('A:ZZ0001'), 'UP', 2, 'zz_t', 'zz');
       INSERT INTO zz_key VALUES ('TF1', pg_temp.zlast('turn_feed'))$q$);
CALL pg_temp.check_ok('2 양성', '02 픽스처: turn_feed TFR (S0 턴 4 seq 1, RESTRICT용)',
    $q$INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, direction, strength, template_id, body)
       VALUES (pg_temp.zid('S0'), 4, 1, 'RISE', pg_temp.zid('A:ZZ0001'), 'UP', 2, 'zz_t', 'zz');
       INSERT INTO zz_key VALUES ('TFR', pg_temp.zlast('turn_feed'))$q$);

-- cash_account
CALL pg_temp.check_ok('2 양성', 'cash_account: 기준 계좌 A0 (S0, 잔액 28,756,601, 예약 0, version 0)',
    $q$INSERT INTO cash_account (season_id, balance, reserved, version) VALUES (pg_temp.zid('S0'), 28756601, 0, 0)$q$);
CALL pg_temp.check_ok('2 양성', 'cash_account: 경계 — 잔액 0·예약 0 (R1, 원장 없는 계좌)',
    $q$INSERT INTO cash_account (season_id, balance, reserved) VALUES (pg_temp.zid('R1'), 0, 0)$q$);
CALL pg_temp.check_ok('2 양성', 'cash_account: 경계 — 예약 = 잔액 (S1, 1,000,000 / 1,000,000, reserved <= balance)',
    $q$INSERT INTO cash_account (season_id, balance, reserved) VALUES (pg_temp.zid('S1'), 1000000, 1000000)$q$);
CALL pg_temp.check_ok('2 양성', 'cash_account: 기본값 컬럼(balance, reserved, version)을 생략한 계좌 (SC)',
    $q$INSERT INTO cash_account (season_id) VALUES (pg_temp.zid('SC'))$q$);
CALL pg_temp.check_eq('2 양성', 'cash_account 기본값: balance·reserved·version = 0, created_at·updated_at이 채워진다', '0.0000 0.0000 0 true',
    pg_temp.val($v$SELECT balance::text || ' ' || reserved::text || ' ' || version::text || ' ' || (created_at IS NOT NULL AND updated_at IS NOT NULL)::text
                    FROM cash_account WHERE season_id = pg_temp.zid('SC')$v$));
CALL pg_temp.check_ok('2 양성', 'cash_account: 시즌 SB (잔액 1,000,000 — 4절 경계용)',
    $q$INSERT INTO cash_account (season_id, balance) VALUES (pg_temp.zid('SB'), 1000000)$q$);

-- orders (S0 턴 3)
CALL pg_temp.check_ok('2 양성', 'orders: 기준 주문 O0 (S0 턴 3 BUY ZZ0001 7주, MARKET, FILLED, DIRECT)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, type, quantity, limit_price, status, source)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 'MARKET', 7, NULL, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('O0', pg_temp.zlast('orders'))$q$);
CALL pg_temp.check_ok('2 양성', 'orders: 미국 종목 주문 OU (S0 턴 3 BUY ZZAAPL 3주)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZAAPL'), 3, 'BUY', 3, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('OU', pg_temp.zlast('orders'))$q$);
CALL pg_temp.check_ok('2 양성', 'orders: SELL 주문 OSELL (S0 턴 3 SELL ZZ0001 2주)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'SELL', 2, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('OSELL', pg_temp.zlast('orders'))$q$);
CALL pg_temp.check_ok('2 양성', 'orders: LIMIT + 지정가 70,000 + PENDING (v1 구조 유지, D2)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, type, quantity, limit_price, status, source)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 'LIMIT', 1, 70000, 'PENDING', 'DIRECT')$q$);
CALL pg_temp.check_ok('2 양성', 'orders: 상태 4개 각각 (PENDING·FILLED·CANCELED·REJECTED)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       SELECT pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 1, s, 'DIRECT' FROM unnest(ARRAY['PENDING', 'FILLED', 'CANCELED', 'REJECTED']) AS s$q$);
CALL pg_temp.check_ok('2 양성', 'orders: 출발점 5개 각각 (RANKING·FEED·ALERT·BENCH·DIRECT, FEED·ALERT은 TF0와 함께)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, turn_feed_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 1, 'FILLED', 'RANKING', NULL),
              (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 1, 'FILLED', 'FEED', pg_temp.zid('TF0')),
              (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 1, 'FILLED', 'ALERT', pg_temp.zid('TF0')),
              (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 1, 'FILLED', 'BENCH', NULL),
              (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 1, 'FILLED', 'DIRECT', NULL)$q$);
CALL pg_temp.check_ok('2 양성', 'orders: turn_no = 0 (0턴, 팀 구성 주문의 턴)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 0, 'BUY', 1, 'FILLED', 'DIRECT')$q$);
CALL pg_temp.check_ok('2 양성', 'orders: FC0에 묶인 주문 (같은 시즌 S0·같은 턴 3)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, formation_change_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 1, 'FILLED', 'DIRECT', pg_temp.zid('FC0'))$q$);
CALL pg_temp.check_ok('2 양성', 'orders: formation_change_id NULL인 턴 2 주문 (복합 FK MATCH SIMPLE — 자식 컬럼 하나가 NULL이면 검사하지 않는다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, formation_change_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 2, 'BUY', 1, 'FILLED', 'DIRECT', NULL)$q$);
CALL pg_temp.check_ok('2 양성', 'orders: type·status를 생략한 주문 ODEF (기본값 MARKET·PENDING)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, source)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 9, 'DIRECT');
       INSERT INTO zz_key VALUES ('ODEF', pg_temp.zlast('orders'))$q$);
CALL pg_temp.check_eq('2 양성', 'orders 기본값: type = MARKET, status = PENDING, created_at이 채워진다', 'MARKET PENDING true',
    pg_temp.val($v$SELECT type || ' ' || status || ' ' || (created_at IS NOT NULL)::text FROM orders WHERE id = pg_temp.zid('ODEF')$v$));
CALL pg_temp.check_ok('2 양성', 'orders: 다른 시즌 S1의 주문 OS1 (S1 턴 3 BUY ZZ0001)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('S1'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('OS1', pg_temp.zlast('orders'))$q$);
CALL pg_temp.check_ok('2 양성', 'orders: RESTRICT용 — O_E (체결만 딸린다), O_L (원장만), O_FCR (FCR에 묶임), O_TFR (TFR을 가리킴)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 11, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('O_E', pg_temp.zlast('orders'));
       INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 12, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('O_L', pg_temp.zlast('orders'));
       INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, formation_change_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 3, 'BUY', 13, 'FILLED', 'DIRECT', pg_temp.zid('FCR'));
       INSERT INTO zz_key VALUES ('O_FCR', pg_temp.zlast('orders'));
       INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, turn_feed_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 4, 'BUY', 14, 'FILLED', 'FEED', pg_temp.zid('TFR'));
       INSERT INTO zz_key VALUES ('O_TFR', pg_temp.zlast('orders'))$q$);
CALL pg_temp.check_ok('2 양성', 'orders: RESTRICT용 — 시즌 R2의 주문 (종목 ZZDEL1, 주문만 딸린 시즌·종목)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('R2'), pg_temp.zid('A:ZZDEL1'), 1, 'BUY', 1, 'FILLED', 'DIRECT')$q$);

-- execution
CALL pg_temp.check_ok('2 양성', 'execution: 기준 체결 E0 (O0, 체결가 70,100, 7주, 수수료 74, 세금 0, 실현 손익 NULL — 매수)',
    $q$INSERT INTO execution (order_id, fill_price, quantity, fee, tax, realized_pnl) VALUES (pg_temp.zid('O0'), 70100, 7, 74, 0, NULL);
       INSERT INTO zz_key VALUES ('E0', pg_temp.zlast('execution'))$q$);
CALL pg_temp.check_ok('2 양성', 'execution: 미국 종목 체결 EU (OU, 체결가 250,000, 3주, 수수료 2,625 = round(750,000 × (0.0025 + 0.0010), 0))',
    $q$INSERT INTO execution (order_id, fill_price, quantity, fee) VALUES (pg_temp.zid('OU'), 250000, 3, 2625)$q$);
CALL pg_temp.check_ok('2 양성', 'execution: 한 주문에 체결 두 건 (O_E, 주문 1 : 체결 N, D3)',
    $q$INSERT INTO execution (order_id, fill_price, quantity, fee) VALUES (pg_temp.zid('O_E'), 1000, 5, 1), (pg_temp.zid('O_E'), 1001, 6, 1)$q$);
CALL pg_temp.check_ok('2 양성', 'execution: 매도 체결 — 실현 손익 2.0001 (소수 4자리, 원 단위 CHECK 없음, D9), fee·tax 생략',
    $q$INSERT INTO execution (order_id, fill_price, quantity, realized_pnl) VALUES (pg_temp.zid('OSELL'), 70200, 2, 2.0001);
       INSERT INTO zz_key VALUES ('E_SELL', pg_temp.zlast('execution'))$q$);
CALL pg_temp.check_eq('2 양성', 'execution 기본값: fee = 0, tax = 0, realized_pnl = 2.0001, created_at이 채워진다', '0.0000 0.0000 2.0001 true',
    pg_temp.val($v$SELECT fee::text || ' ' || tax::text || ' ' || realized_pnl::text || ' ' || (created_at IS NOT NULL)::text FROM execution WHERE id = pg_temp.zid('E_SELL')$v$));

-- holding
CALL pg_temp.check_ok('2 양성', 'holding: 기준 보유 H0 (S0 × ZZ0001, 7주, 평균 70,100)',
    $q$INSERT INTO holding (season_id, asset_id, quantity, avg_price) VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 7, 70100);
       INSERT INTO zz_key VALUES ('H0', pg_temp.zlast('holding'))$q$);
CALL pg_temp.check_ok('2 양성', 'holding: 미국 종목 HU (S0 × ZZAAPL, 3주, 평균 250,000)',
    $q$INSERT INTO holding (season_id, asset_id, quantity, avg_price) VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZAAPL'), 3, 250000)$q$);
CALL pg_temp.check_ok('2 양성', 'holding: 전량 매도 뒤 0·0 행 유지 (S0 × ZZ0002, D7)',
    $q$INSERT INTO holding (season_id, asset_id, quantity, avg_price) VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0002'), 0, 0)$q$);
CALL pg_temp.check_ok('2 양성', 'holding: 같은 종목 ZZ0001, 다른 시즌 S1, 평균단가 100.3333 (소수 4자리, 원 단위 CHECK 없음, D1)',
    $q$INSERT INTO holding (season_id, asset_id, quantity, avg_price) VALUES (pg_temp.zid('S1'), pg_temp.zid('A:ZZ0001'), 3, 100.3333);
       INSERT INTO zz_key VALUES ('H_S1', pg_temp.zlast('holding'))$q$);
CALL pg_temp.check_eq('2 양성', 'holding 저장값: avg_price = 100.3333, created_at·updated_at이 채워진다', '100.3333 true',
    pg_temp.val($v$SELECT avg_price::text || ' ' || (created_at IS NOT NULL AND updated_at IS NOT NULL)::text FROM holding WHERE id = pg_temp.zid('H_S1')$v$));
CALL pg_temp.check_ok('2 양성', 'holding: avg_price 생략 + quantity 0 (기본값 0과 쌍 CHECK) — 시즌 SC × ZZ0001',
    $q$INSERT INTO holding (season_id, asset_id, quantity) VALUES (pg_temp.zid('SC'), pg_temp.zid('A:ZZ0001'), 0)$q$);
CALL pg_temp.check_ok('2 양성', 'holding: RESTRICT용 — 시즌 R3의 보유 (종목 ZZDEL2, 보유만 딸린 시즌·종목)',
    $q$INSERT INTO holding (season_id, asset_id, quantity, avg_price) VALUES (pg_temp.zid('R3'), pg_temp.zid('A:ZZDEL2'), 1, 1000)$q$);

-- cash_ledger (유형 6개 각각의 올바른 부호·참조)
CALL pg_temp.check_ok('2 양성', 'cash_ledger: L0 DEPOSIT +30,000,000 (참조 NULL, 잔액 30,000,000)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('S0'), 'DEPOSIT', 30000000, 30000000, NULL);
       INSERT INTO zz_key VALUES ('L0', pg_temp.zlast('cash_ledger'))$q$);
CALL pg_temp.check_ok('2 양성', 'cash_ledger: L1 BUY −490,700 (O0 참조, 잔액 29,509,300)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('S0'), 'BUY', -490700, 29509300, pg_temp.zid('O0'));
       INSERT INTO zz_key VALUES ('L1', pg_temp.zlast('cash_ledger'))$q$);
CALL pg_temp.check_ok('2 양성', 'cash_ledger: L2 FEE −74 (O0 참조, 잔액 29,509,226)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('S0'), 'FEE', -74, 29509226, pg_temp.zid('O0'));
       INSERT INTO zz_key VALUES ('L2', pg_temp.zlast('cash_ledger'))$q$);
CALL pg_temp.check_ok('2 양성', 'cash_ledger: 미국 매수 BUY −750,000 + FEE −2,625 (OU 참조, 잔액 28,759,226 → 28,756,601)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
       VALUES (pg_temp.zid('S0'), 'BUY', -750000, 28759226, pg_temp.zid('OU')), (pg_temp.zid('S0'), 'FEE', -2625, 28756601, pg_temp.zid('OU'))$q$);
CALL pg_temp.check_ok('2 양성', 'cash_ledger: SELL +140,400 와 TAX −10 (OSELL 참조)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
       VALUES (pg_temp.zid('S0'), 'SELL', 140400, 28897001, pg_temp.zid('OSELL')), (pg_temp.zid('S0'), 'TAX', -10, 28896991, pg_temp.zid('OSELL'))$q$);
CALL pg_temp.check_ok('2 양성', 'cash_ledger: ADJUSTMENT + 참조 NULL, ADJUSTMENT + 참조 있음(같은 시즌 O0), ADJUSTMENT 음수, balance_after 0',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
       VALUES (pg_temp.zid('S0'), 'ADJUSTMENT', 500, 28897491, NULL),
              (pg_temp.zid('S0'), 'ADJUSTMENT', -500, 28896991, pg_temp.zid('O0')),
              (pg_temp.zid('S0'), 'ADJUSTMENT', -1, 0, NULL)$q$);
CALL pg_temp.check_ok('2 양성', 'cash_ledger: RESTRICT용 — S0 원장이 O_L을 참조 (O_L에는 원장만 딸린다)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('S0'), 'FEE', -1, 28756600, pg_temp.zid('O_L'))$q$);
CALL pg_temp.check_ok('2 양성', 'cash_ledger: S1 계좌의 DEPOSIT +1,000,000 (RESTRICT용 — 원장이 딸린 계좌)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (pg_temp.zid('S1'), 'DEPOSIT', 1000000, 1000000)$q$);
CALL pg_temp.check_eq('2 양성', 'cash_ledger 기본값: created_at이 채워진다 (기준 L1)', 'true',
    pg_temp.val($v$SELECT (created_at IS NOT NULL)::text FROM cash_ledger WHERE id = pg_temp.zid('L1')$v$));


-- 3 음성 DML (실패해야 정상) -----------------------------------------------------
-- 사례마다 유효한 기준 행에서 한 컬럼(또는 한 조건)만 어긴다. 한 행이 CHECK를 여러 개 어기면 NOT NULL(23502)이 먼저, 그다음 CHECK를
-- 제약 이름의 알파벳 순으로 검사해 첫 위반 하나만 보고한다. 둘 이상이 걸리는 사례는 주석에 먼저 보고되는 이름을 적었다.
-- ★ 표시는 erd 9절 P0-07 행(7.6 대조표 24행, 새 CHECK 3개, v1 FK 8개, 복합 FK, 원 단위 경계)이 지정한 사례다.
-- FK 위반 사례는 INSERT로 만든다: 기준 주문 O0는 원장·체결이 딸린 부모라, 키 컬럼을 UPDATE 하면 부모 쪽 RESTRICT가 먼저 보고된다.
-- RESTRICT 사례는 자식 테이블이 하나뿐인 부모를 쓴다 (자식이 둘 이상이면 어느 FK가 먼저 보고될지 이름에 기대게 된다).

-- 3.1 cash_account (기준 계좌 A0에 UPDATE 한 컬럼. 잔액 28,756,601 · 예약 0)
CALL pg_temp.check_err('3 음성', 'cash_account ★ 7.6-1 잔액 −1 (예약 0 — reserved_range도 어기지만 이름 순 b < r이라 balance_nonneg가 먼저 보고된다, R12 예외)',
    $q$UPDATE cash_account SET balance = -1 WHERE season_id = pg_temp.zid('S0')$q$, '23514', 'ck_cash_account_balance_nonneg');
CALL pg_temp.check_err('3 음성', 'cash_account ★ 7.6-2 예약 −1',
    $q$UPDATE cash_account SET reserved = -1 WHERE season_id = pg_temp.zid('S0')$q$, '23514', 'ck_cash_account_reserved_range');
CALL pg_temp.check_err('3 음성', 'cash_account ★ 7.6-2 예약 = 잔액 + 1 (reserved <= balance 위반)',
    $q$UPDATE cash_account SET reserved = balance + 1 WHERE season_id = pg_temp.zid('S0')$q$, '23514', 'ck_cash_account_reserved_range');
CALL pg_temp.check_err('3 음성', 'cash_account ★ 7.6-3 잔액 100.5 (원 단위 아님)',
    $q$UPDATE cash_account SET balance = 100.5 WHERE season_id = pg_temp.zid('S0')$q$, '23514', 'ck_cash_account_balance_won');
CALL pg_temp.check_err('3 음성', 'cash_account ★ 7.6-4 예약 0.5 (잔액 이하이지만 원 단위 아님)',
    $q$UPDATE cash_account SET reserved = 0.5 WHERE season_id = pg_temp.zid('S0')$q$, '23514', 'ck_cash_account_reserved_won');
CALL pg_temp.check_err('3 음성', 'cash_account ★ v1 FK: 없는 시즌 999999의 계좌',
    $q$INSERT INTO cash_account (season_id) VALUES (999999)$q$, '23503', 'fk_cash_account_season');
CALL pg_temp.check_err('3 음성', 'cash_account: 같은 시즌 S0의 계좌 두 번째 (PK = 시즌번호, 1:1) — PK는 커버리지 대상이 아니다',
    $q$INSERT INTO cash_account (season_id) VALUES (pg_temp.zid('S0'))$q$, '23505', 'pk_cash_account');
CALL pg_temp.check_err('3 음성', 'cash_account: 잔액 NULL',
    $q$UPDATE cash_account SET balance = NULL WHERE season_id = pg_temp.zid('S0')$q$, '23502', 'balance');

-- 3.2 orders (기준 주문 O0에 UPDATE 한 컬럼. NOT NULL·identity·FK만 INSERT. O0: S0 턴 3 BUY ZZ0001 7주 MARKET FILLED DIRECT, 변경·피드 NULL)
CALL pg_temp.check_err('3 음성', 'orders ★ 7.6-5 side = HOLD',
    $q$UPDATE orders SET side = 'HOLD' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_side');
CALL pg_temp.check_err('3 음성', 'orders: side 소문자 buy',
    $q$UPDATE orders SET side = 'buy' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_side');
-- type = 'STOP'은 ck_orders_type뿐 아니라 ck_orders_limit_price도 어긴다 (MARKET도 LIMIT도 아니므로). 이름 순 limit_price < type이라
-- DML로는 ck_orders_type이 자기 이름으로 보고될 수 없다 (가려진 CHECK, erd 2절 "포함 관계 CHECK"). 먼저 가림 자체를 기록하고,
-- 같은 사례 안에서 가리는 CHECK를 지운 뒤(서브트랜잭션이 되돌린다) ck_orders_type이 단독으로 걸리는 것을 증명한다.
CALL pg_temp.check_err('3 음성', 'orders ★ 7.6-6 type = STOP — 가림의 기록: ck_orders_limit_price가 먼저 보고된다 (l < t)',
    $q$UPDATE orders SET type = 'STOP' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_limit_price');
CALL pg_temp.check_err('3 음성', 'orders ★ 7.6-6 가려진 CHECK ck_orders_type — ck_orders_limit_price를 지우고 type = STOP',
    $q$ALTER TABLE orders DROP CONSTRAINT ck_orders_limit_price;
       UPDATE orders SET type = 'STOP' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_type');
CALL pg_temp.check_err('3 음성', 'orders ★ 7.6-7 수량 0',
    $q$UPDATE orders SET quantity = 0 WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_quantity_pos');
CALL pg_temp.check_err('3 음성', 'orders: 수량 −1',
    $q$UPDATE orders SET quantity = -1 WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_quantity_pos');
CALL pg_temp.check_err('3 음성', 'orders ★ 7.6-8 MARKET + 지정가 70,000',
    $q$UPDATE orders SET limit_price = 70000 WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_limit_price');
CALL pg_temp.check_err('3 음성', 'orders: LIMIT + 지정가 NULL (NULL이 UNKNOWN으로 새지 않고 거부된다)',
    $q$UPDATE orders SET type = 'LIMIT' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_limit_price');
CALL pg_temp.check_err('3 음성', 'orders: LIMIT + 지정가 0',
    $q$UPDATE orders SET type = 'LIMIT', limit_price = 0 WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_limit_price');
CALL pg_temp.check_err('3 음성', 'orders ★ 7.6-9 LIMIT + 지정가 70,000.5 (양수이지만 원 단위 아님)',
    $q$UPDATE orders SET type = 'LIMIT', limit_price = 70000.5 WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_limit_price_won');
CALL pg_temp.check_err('3 음성', 'orders ★ 7.6-10 status = DONE',
    $q$UPDATE orders SET status = 'DONE' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_status');
CALL pg_temp.check_err('3 음성', 'orders ★ 새 CHECK: turn_no = −1 (변경·피드 참조 없음)',
    $q$UPDATE orders SET turn_no = -1 WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_turn_no');
CALL pg_temp.check_err('3 음성', 'orders ★ 새 CHECK: source = NEWS (피드 NULL)',
    $q$UPDATE orders SET source = 'NEWS' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_source');
CALL pg_temp.check_err('3 음성', 'orders ★ 새 CHECK: source = AGENT (erd 8절 — AGENT를 넣지 않는다)',
    $q$UPDATE orders SET source = 'AGENT' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_source');
CALL pg_temp.check_err('3 음성', 'orders ★ 새 CHECK: source = FEED + 피드 NULL',
    $q$UPDATE orders SET source = 'FEED' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_source_feed');
CALL pg_temp.check_err('3 음성', 'orders: source = ALERT + 피드 NULL',
    $q$UPDATE orders SET source = 'ALERT' WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_source_feed');
CALL pg_temp.check_err('3 음성', 'orders: source = DIRECT + 피드 TF0 (FEED·ALERT가 아니면 피드가 NULL이어야 한다)',
    $q$UPDATE orders SET turn_feed_id = pg_temp.zid('TF0') WHERE id = pg_temp.zid('O0')$q$, '23514', 'ck_orders_source_feed');
CALL pg_temp.check_err('3 음성', 'orders ★ v1 FK: 없는 시즌 999999 (변경·피드 NULL이라 다른 FK가 함께 걸리지 않는다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, source) VALUES (999999, pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'DIRECT')$q$, '23503', 'fk_orders_season');
CALL pg_temp.check_err('3 음성', 'orders ★ v1 FK: 없는 종목 999999',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, source) VALUES (pg_temp.zid('S0'), 999999, 3, 'BUY', 1, 'DIRECT')$q$, '23503', 'fk_orders_asset');
CALL pg_temp.check_err('3 음성', 'orders: source를 생략 (DB 기본값이 없다 — 서버가 반드시 기록한다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity) VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1)$q$, '23502', 'source');
CALL pg_temp.check_err('3 음성', 'orders: turn_no를 생략 (DB 기본값이 없다)',
    $q$INSERT INTO orders (season_id, asset_id, side, quantity, source) VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 'BUY', 1, 'DIRECT')$q$, '23502', 'turn_no');
CALL pg_temp.check_err('3 음성', 'orders: id를 직접 지정 (GENERATED ALWAYS)',
    $q$INSERT INTO orders (id, season_id, asset_id, turn_no, side, quantity, source) VALUES (999999, pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'DIRECT')$q$, '428C9');
-- 복합 FK 3개의 시즌·턴 일치 사례(★ D29)는 5.1에 있다.
-- PK에 가려진 UNIQUE uq_orders_id_season (id, season_id)은 id가 PK라 DML로는 PK가 먼저 걸려 자기 이름으로 보고될 수 없다.
-- 그래서 한 사례 안에서 PK를 지우고(서브트랜잭션이 되돌린다) 같은 id·시즌으로 한 행을 더 넣는다. id를 직접 넣으려면 OVERRIDING SYSTEM VALUE가 필요하다.
-- pk_orders를 지우려면 그것을 가리키는 FK를 먼저 지워야 한다 (없으면 2BP01). 가리키는 FK의 conindid로 전제를 확인한다:
--   fk_execution_order(order_id → orders.id)는 pk_orders를 타고, fk_cash_ledger_order((ref_order_id, season_id) → orders (id, season_id))는
--   컬럼 목록이 정확히 같은 uq_orders_id_season을 탄다. CASCADE로 FK를 함께 지우지 않는다 (어느 FK가 가렸는지 사례에 드러나게).
CALL pg_temp.check_eq('3 음성', 'orders 전제: pk_orders를 가리키는 FK 수 = 1 (fk_execution_order)', '1',
    pg_temp.val($v$SELECT count(*)::text FROM pg_constraint WHERE contype = 'f' AND conindid = to_regclass('public.pk_orders')$v$));
CALL pg_temp.check_eq('3 음성', 'orders 전제: uq_orders_id_season을 가리키는 FK 수 = 1 (fk_cash_ledger_order)', '1',
    pg_temp.val($v$SELECT count(*)::text FROM pg_constraint WHERE contype = 'f' AND conindid = to_regclass('public.uq_orders_id_season')$v$));
CALL pg_temp.check_eq('3 음성', '02 전제: 03 → 02 복합 FK 2개는 02의 PK가 아니라 복합 UNIQUE를 탄다 (P9 — 02 검증 3.7·3.8 F4가 성립하는 조건)',
    'fk_orders_formation_change→uq_formation_change_id_season_turn,fk_orders_turn_feed→uq_turn_feed_id_season',
    pg_temp.val($v$SELECT string_agg(c.conname || '→' || i.relname, ',' ORDER BY c.conname)
                   FROM pg_constraint c JOIN pg_class i ON i.oid = c.conindid
                   WHERE c.conname IN ('fk_orders_formation_change', 'fk_orders_turn_feed')$v$));
CALL pg_temp.check_err('3 음성', 'orders ★ PK에 가려진 UNIQUE (id, season_id) — fk_execution_order와 PK를 지우고 같은 id·시즌 한 행 더',
    $q$ALTER TABLE execution DROP CONSTRAINT fk_execution_order;
       ALTER TABLE orders DROP CONSTRAINT pk_orders;
       INSERT INTO orders (id, season_id, asset_id, turn_no, side, quantity, source)
       OVERRIDING SYSTEM VALUE
       SELECT id, season_id, asset_id, turn_no, side, quantity, source FROM orders WHERE id = pg_temp.zid('O0')$q$, '23505', 'uq_orders_id_season');

-- 3.3 execution (기준 체결 E0에 UPDATE 한 컬럼. E0: 체결가 70,100 · 7주 · 수수료 74 · 세금 0)
CALL pg_temp.check_err('3 음성', 'execution ★ 7.6-11 체결가 0',
    $q$UPDATE execution SET fill_price = 0 WHERE id = pg_temp.zid('E0')$q$, '23514', 'ck_execution_fill_price_pos');
CALL pg_temp.check_err('3 음성', 'execution: 체결가 −1',
    $q$UPDATE execution SET fill_price = -1 WHERE id = pg_temp.zid('E0')$q$, '23514', 'ck_execution_fill_price_pos');
CALL pg_temp.check_err('3 음성', 'execution ★ 7.6-12 체결가 70,100.5 (원 단위 아님 — 체결 대금이 정수여야 원장과 오차 없이 맞는다)',
    $q$UPDATE execution SET fill_price = 70100.5 WHERE id = pg_temp.zid('E0')$q$, '23514', 'ck_execution_fill_price_won');
CALL pg_temp.check_err('3 음성', 'execution ★ 7.6-13 체결 수량 0',
    $q$UPDATE execution SET quantity = 0 WHERE id = pg_temp.zid('E0')$q$, '23514', 'ck_execution_quantity_pos');
CALL pg_temp.check_err('3 음성', 'execution ★ 7.6-14 수수료 −1',
    $q$UPDATE execution SET fee = -1 WHERE id = pg_temp.zid('E0')$q$, '23514', 'ck_execution_fee_nonneg');
CALL pg_temp.check_err('3 음성', 'execution ★ 7.6-15 세금 −1',
    $q$UPDATE execution SET tax = -1 WHERE id = pg_temp.zid('E0')$q$, '23514', 'ck_execution_tax_nonneg');
CALL pg_temp.check_err('3 음성', 'execution ★ 7.6-16 수수료 73.6 (공식의 반올림 전 값 — 반올림을 빠뜨린 코드가 DB에서 막힌다)',
    $q$UPDATE execution SET fee = 73.6 WHERE id = pg_temp.zid('E0')$q$, '23514', 'ck_execution_fee_won');
CALL pg_temp.check_err('3 음성', 'execution ★ 7.6-17 세금 0.5',
    $q$UPDATE execution SET tax = 0.5 WHERE id = pg_temp.zid('E0')$q$, '23514', 'ck_execution_tax_won');
CALL pg_temp.check_err('3 음성', 'execution ★ v1 FK: 없는 주문 999999',
    $q$UPDATE execution SET order_id = 999999 WHERE id = pg_temp.zid('E0')$q$, '23503', 'fk_execution_order');
CALL pg_temp.check_err('3 음성', 'execution: 체결가 NULL',
    $q$UPDATE execution SET fill_price = NULL WHERE id = pg_temp.zid('E0')$q$, '23502', 'fill_price');

-- 3.4 holding (기준 보유 H0에 UPDATE 한 컬럼. H0: S0 × ZZ0001 7주 평균 70,100)
CALL pg_temp.check_err('3 음성', 'holding ★ 7.6-18 수량 0 + 평균단가 100 (보유가 없는데 단가가 있다)',
    $q$UPDATE holding SET quantity = 0, avg_price = 100 WHERE id = pg_temp.zid('H0')$q$, '23514', 'ck_holding_quantity_avg_price');
CALL pg_temp.check_err('3 음성', 'holding: 수량 5 + 평균단가 0 (보유가 있는데 단가가 0)',
    $q$UPDATE holding SET quantity = 5, avg_price = 0 WHERE id = pg_temp.zid('H0')$q$, '23514', 'ck_holding_quantity_avg_price');
CALL pg_temp.check_err('3 음성', 'holding: 수량 −1 + 평균단가 0 (0 이상 규칙도 이 CHECK가 강제한다)',
    $q$UPDATE holding SET quantity = -1, avg_price = 0 WHERE id = pg_temp.zid('H0')$q$, '23514', 'ck_holding_quantity_avg_price');
CALL pg_temp.check_err('3 음성', 'holding: 같은 (시즌, 종목) — H0의 종목을 S0에 이미 있는 ZZAAPL로',
    $q$UPDATE holding SET asset_id = pg_temp.zid('A:ZZAAPL') WHERE id = pg_temp.zid('H0')$q$, '23505', 'uq_holding_season_asset');
CALL pg_temp.check_err('3 음성', 'holding ★ v1 FK: 없는 시즌 999999',
    $q$UPDATE holding SET season_id = 999999 WHERE id = pg_temp.zid('H0')$q$, '23503', 'fk_holding_season');
CALL pg_temp.check_err('3 음성', 'holding ★ v1 FK: 없는 종목 999999',
    $q$UPDATE holding SET asset_id = 999999 WHERE id = pg_temp.zid('H0')$q$, '23503', 'fk_holding_asset');
CALL pg_temp.check_err('3 음성', 'holding: 수량 NULL',
    $q$UPDATE holding SET quantity = NULL WHERE id = pg_temp.zid('H0')$q$, '23502', 'quantity');

-- 3.5 cash_ledger (기준 원장 L1 BUY −490,700 · 잔액 29,509,300 · 참조 O0에 UPDATE. 유형을 바꾸는 사례는 부호·참조를 맞춘다)
-- type = 'BONUS'는 ck_cash_ledger_type뿐 아니라 amount_sign·ref_order도 어긴다 (어느 분기에도 걸리지 않으므로). 이름 순 a < r < t라
-- ck_cash_ledger_type은 DML로 자기 이름이 보고될 수 없다 (가려진 CHECK). 가림을 기록하고, 두 CHECK를 지운 사례로 증명한다.
CALL pg_temp.check_err('3 음성', 'cash_ledger ★ 7.6-19 type = BONUS — 가림의 기록: ck_cash_ledger_amount_sign이 먼저 보고된다 (a < r < t)',
    $q$UPDATE cash_ledger SET type = 'BONUS' WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_amount_sign');
CALL pg_temp.check_err('3 음성', 'cash_ledger ★ 7.6-19 가려진 CHECK ck_cash_ledger_type — amount_sign·ref_order를 지우고 type = BONUS',
    $q$ALTER TABLE cash_ledger DROP CONSTRAINT ck_cash_ledger_amount_sign;
       ALTER TABLE cash_ledger DROP CONSTRAINT ck_cash_ledger_ref_order;
       UPDATE cash_ledger SET type = 'BONUS' WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_type');
CALL pg_temp.check_err('3 음성', 'cash_ledger ★ 7.6-20 BUY + 금액 +490,700 (매수인데 입금 부호)',
    $q$UPDATE cash_ledger SET amount = 490700 WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_amount_sign');
CALL pg_temp.check_err('3 음성', 'cash_ledger: DEPOSIT + 금액 −1 (참조 NULL)',
    $q$UPDATE cash_ledger SET type = 'DEPOSIT', amount = -1, ref_order_id = NULL WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_amount_sign');
CALL pg_temp.check_err('3 음성', 'cash_ledger: ADJUSTMENT + 금액 0 (0원 정정은 의미가 없다)',
    $q$UPDATE cash_ledger SET type = 'ADJUSTMENT', amount = 0 WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_amount_sign');
CALL pg_temp.check_err('3 음성', 'cash_ledger ★ 7.6-21 거래 후 잔액 −1',
    $q$UPDATE cash_ledger SET balance_after = -1 WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_balance_after_nonneg');
CALL pg_temp.check_err('3 음성', 'cash_ledger ★ 7.6-23 거래 후 잔액 100.5',
    $q$UPDATE cash_ledger SET balance_after = 100.5 WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_balance_after_won');
CALL pg_temp.check_err('3 음성', 'cash_ledger ★ 7.6-24 BUY + 참조 주문 NULL',
    $q$UPDATE cash_ledger SET ref_order_id = NULL WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_ref_order');
CALL pg_temp.check_err('3 음성', 'cash_ledger: DEPOSIT + 참조 주문 O0 (입금에는 주문이 없다)',
    $q$UPDATE cash_ledger SET type = 'DEPOSIT', amount = 490700 WHERE id = pg_temp.zid('L1')$q$, '23514', 'ck_cash_ledger_ref_order');
-- 7.6-22(원 단위 amount)는 4절의 경계 4건이다.
CALL pg_temp.check_err('3 음성', 'cash_ledger ★ v1 FK: 계좌 없는 시즌 R4의 원장 (R4는 season에 있지만 cash_account에 없다 — 원장은 계좌의 원장, R31)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (pg_temp.zid('R4'), 'DEPOSIT', 1000, 1000)$q$, '23503', 'fk_cash_ledger_cash_account');
CALL pg_temp.check_err('3 음성', 'cash_ledger ★ v1 FK: 없는 주문 999999 (S0 계좌는 있으므로 fk_cash_ledger_cash_account는 통과)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('S0'), 'BUY', -1000, 1000, 999999)$q$, '23503', 'fk_cash_ledger_order');
CALL pg_temp.check_err('3 음성', 'cash_ledger: 금액 NULL',
    $q$UPDATE cash_ledger SET amount = NULL WHERE id = pg_temp.zid('L1')$q$, '23502', 'amount');
CALL pg_temp.check_err('3 음성', 'cash_ledger: 금액 −10^15 (NUMERIC(19,4) 정수 16자리 → 22003)',
    $q$UPDATE cash_ledger SET type = 'ADJUSTMENT', amount = -1000000000000000, ref_order_id = NULL WHERE id = pg_temp.zid('L1')$q$, '22003');
CALL pg_temp.check_err('3 음성', 'cash_ledger: id를 직접 지정 (GENERATED ALWAYS)',
    $q$INSERT INTO cash_ledger (id, season_id, type, amount, balance_after) VALUES (999999, pg_temp.zid('S0'), 'DEPOSIT', 1000, 1000)$q$, '428C9');

-- 3.6 RESTRICT (ON DELETE / ON UPDATE). 부모마다 자식 테이블이 하나뿐인 픽스처를 쓴다 (2절).
--     시즌: R1(계좌만) R2(주문만) R3(보유만). 종목: ZZDEL1(주문만 — R2의 주문) ZZDEL2(보유만 — R3의 보유).
--     변경 FCR과 피드 TFR은 주문 하나씩만 딸린다. 주문 O_E는 체결만, O_L은 원장만. 계좌 S1은 원장이 딸린다.
CALL pg_temp.check_err('3 음성', 'RESTRICT: 계좌만 있는 시즌 R1 삭제', $q$DELETE FROM season WHERE id = pg_temp.zid('R1')$q$, '23503', 'fk_cash_account_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 계좌만 있는 시즌 R1의 id 변경 (ON UPDATE RESTRICT, SET id = DEFAULT)',
    $q$UPDATE season SET id = DEFAULT WHERE id = pg_temp.zid('R1')$q$, '23503', 'fk_cash_account_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 주문만 있는 시즌 R2 삭제', $q$DELETE FROM season WHERE id = pg_temp.zid('R2')$q$, '23503', 'fk_orders_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 보유만 있는 시즌 R3 삭제', $q$DELETE FROM season WHERE id = pg_temp.zid('R3')$q$, '23503', 'fk_holding_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 주문만 있는 종목 ZZDEL1 삭제', $q$DELETE FROM asset WHERE ticker = 'ZZDEL1'$q$, '23503', 'fk_orders_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 보유만 있는 종목 ZZDEL2 삭제', $q$DELETE FROM asset WHERE ticker = 'ZZDEL2'$q$, '23503', 'fk_holding_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 주문만 있는 종목 ZZDEL1의 id 변경 (ON UPDATE RESTRICT)',
    $q$UPDATE asset SET id = DEFAULT WHERE ticker = 'ZZDEL1'$q$, '23503', 'fk_orders_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 주문 하나만 묶인 변경 FCR 삭제 (복합 FK의 부모 쪽 ON DELETE)',
    $q$DELETE FROM formation_change WHERE id = pg_temp.zid('FCR')$q$, '23503', 'fk_orders_formation_change');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 변경 FCR의 턴 번호 변경 (복합 FK의 부모 쪽 ON UPDATE — 키 (id, season_id, turn_no)가 바뀐다)',
    $q$UPDATE formation_change SET turn_no = 2 WHERE id = pg_temp.zid('FCR')$q$, '23503', 'fk_orders_formation_change');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 주문 하나만 가리키는 피드 TFR 삭제',
    $q$DELETE FROM turn_feed WHERE id = pg_temp.zid('TFR')$q$, '23503', 'fk_orders_turn_feed');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 체결만 딸린 주문 O_E 삭제', $q$DELETE FROM orders WHERE id = pg_temp.zid('O_E')$q$, '23503', 'fk_execution_order');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 체결만 딸린 주문 O_E의 id 변경 (ON UPDATE RESTRICT)',
    $q$UPDATE orders SET id = DEFAULT WHERE id = pg_temp.zid('O_E')$q$, '23503', 'fk_execution_order');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 원장만 딸린 주문 O_L 삭제 (복합 FK fk_cash_ledger_order의 부모 쪽)',
    $q$DELETE FROM orders WHERE id = pg_temp.zid('O_L')$q$, '23503', 'fk_cash_ledger_order');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 원장이 딸린 계좌 S1 삭제', $q$DELETE FROM cash_account WHERE season_id = pg_temp.zid('S1')$q$, '23503', 'fk_cash_ledger_cash_account');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 원장이 딸린 계좌 S1의 시즌번호를 계좌 없는 R4로 변경 (자식 쪽 FK fk_cash_account_season은 통과, 부모 쪽이 막는다)',
    $q$UPDATE cash_account SET season_id = pg_temp.zid('R4') WHERE season_id = pg_temp.zid('S1')$q$, '23503', 'fk_cash_ledger_cash_account');


-- 4 저장 정밀도 경계 ------------------------------------------------------------
-- "CHECK는 반올림된 저장값을 본다"(erd D1). NUMERIC(p,s)는 소수 자리가 넘치면 경고 없이 0에서 먼 쪽으로 반올림하고,
-- 정수 자리가 넘치면 22003으로 거부한다. SMALLINT·INTEGER에 소수를 넣으면 경고 없이 반올림된다.
-- 경계용 시즌 SB의 행(주문 OSB, 체결 ESB, 보유 HSB, 원장 LSB)을 UPDATE 하며 저장값을 읽는다 (통과하는 값은 SB에 남는다).
CALL pg_temp.check_ok('4 경계', '경계용 행 (SB): 주문 OSB (턴 0 BUY ZZ0001 3주), 체결 ESB, 보유 HSB, 원장 LSB DEPOSIT 1,000',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('SB'), pg_temp.zid('A:ZZ0001'), 0, 'BUY', 3, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('OSB', pg_temp.zlast('orders'));
       INSERT INTO execution (order_id, fill_price, quantity) VALUES (pg_temp.zid('OSB'), 70100, 3);
       INSERT INTO zz_key VALUES ('ESB', pg_temp.zlast('execution'));
       INSERT INTO holding (season_id, asset_id, quantity, avg_price) VALUES (pg_temp.zid('SB'), pg_temp.zid('A:ZZ0001'), 3, 70100);
       INSERT INTO zz_key VALUES ('HSB', pg_temp.zlast('holding'));
       INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (pg_temp.zid('SB'), 'DEPOSIT', 1000, 1000);
       INSERT INTO zz_key VALUES ('LSB', pg_temp.zlast('cash_ledger'))$q$);

-- 4.1 cash_ledger.amount NUMERIC(19,4), CHECK amount = ROUND(amount, 0) — 네 값 (erd D1, §9 P0-07 ★ 4건 + D1의 100.00004)
CALL pg_temp.check_ok('4 경계', 'amount = 100.00001 대입 (저장 100.0000 → 통과)', $q$UPDATE cash_ledger SET amount = 100.00001 WHERE id = pg_temp.zid('LSB')$q$);
CALL pg_temp.check_eq('4 경계', 'amount 100.00001 → 저장값', '100.0000', pg_temp.val($v$SELECT amount::text FROM cash_ledger WHERE id = pg_temp.zid('LSB')$v$));
CALL pg_temp.check_ok('4 경계', 'amount = 100.00004 대입 (저장 100.0000 → 통과)', $q$UPDATE cash_ledger SET amount = 100.00004 WHERE id = pg_temp.zid('LSB')$q$);
CALL pg_temp.check_eq('4 경계', 'amount 100.00004 → 저장값', '100.0000', pg_temp.val($v$SELECT amount::text FROM cash_ledger WHERE id = pg_temp.zid('LSB')$v$));
CALL pg_temp.check_err('4 경계', 'amount = 100.00005 ★ (저장 100.0001 → 원 단위 CHECK 위반)', $q$UPDATE cash_ledger SET amount = 100.00005 WHERE id = pg_temp.zid('LSB')$q$, '23514', 'ck_cash_ledger_amount_won');
CALL pg_temp.check_err('4 경계', 'amount = 100.5 ★ (원 단위 CHECK 위반)', $q$UPDATE cash_ledger SET amount = 100.5 WHERE id = pg_temp.zid('LSB')$q$, '23514', 'ck_cash_ledger_amount_won');
CALL pg_temp.check_err('4 경계', 'ADJUSTMENT amount = −100.00005 ★ (저장 −100.0001 → 원 단위 CHECK 위반, 부호가 음수여도 같다)',
    $q$UPDATE cash_ledger SET type = 'ADJUSTMENT', amount = -100.00005 WHERE id = pg_temp.zid('LSB')$q$, '23514', 'ck_cash_ledger_amount_won');

-- 4.2 execution.fill_price NUMERIC(19,4), CHECK fill_price = ROUND(fill_price, 0) — 같은 값 (가격도 정수, D1 "체결 대금이 정수")
CALL pg_temp.check_ok('4 경계', 'fill_price = 100.00001 대입 (저장 100.0000 → 통과)', $q$UPDATE execution SET fill_price = 100.00001 WHERE id = pg_temp.zid('ESB')$q$);
CALL pg_temp.check_eq('4 경계', 'fill_price 100.00001 → 저장값', '100.0000', pg_temp.val($v$SELECT fill_price::text FROM execution WHERE id = pg_temp.zid('ESB')$v$));
CALL pg_temp.check_ok('4 경계', 'fill_price = 100.00004 대입 (저장 100.0000 → 통과)', $q$UPDATE execution SET fill_price = 100.00004 WHERE id = pg_temp.zid('ESB')$q$);
CALL pg_temp.check_err('4 경계', 'fill_price = 100.00005 (저장 100.0001 → 원 단위 CHECK 위반)', $q$UPDATE execution SET fill_price = 100.00005 WHERE id = pg_temp.zid('ESB')$q$, '23514', 'ck_execution_fill_price_won');
CALL pg_temp.check_err('4 경계', 'fill_price = 100.5 (원 단위 CHECK 위반)', $q$UPDATE execution SET fill_price = 100.5 WHERE id = pg_temp.zid('ESB')$q$, '23514', 'ck_execution_fill_price_won');
CALL pg_temp.check_err('4 경계', 'fill_price = 0.00004 (저장 0.0000 → fill_price > 0 위반)', $q$UPDATE execution SET fill_price = 0.00004 WHERE id = pg_temp.zid('ESB')$q$, '23514', 'ck_execution_fill_price_pos');

-- 4.3 holding.avg_price NUMERIC(19,4): 원 단위 CHECK가 없고 소수 4자리로 반올림된다 (D1·D9)
CALL pg_temp.check_ok('4 경계', 'avg_price = 100.33335 대입 (저장 100.3334 → 통과, 원 단위 CHECK 없음)', $q$UPDATE holding SET avg_price = 100.33335 WHERE id = pg_temp.zid('HSB')$q$);
CALL pg_temp.check_eq('4 경계', 'avg_price 100.33335 → 저장값 (0에서 먼 쪽 반올림)', '100.3334', pg_temp.val($v$SELECT avg_price::text FROM holding WHERE id = pg_temp.zid('HSB')$v$));

-- 4.4 execution.realized_pnl NUMERIC(19,4): 원 단위 CHECK가 없다 (D1·D9)
CALL pg_temp.check_ok('4 경계', 'realized_pnl = 2.00005 대입 (저장 2.0001 → 통과)', $q$UPDATE execution SET realized_pnl = 2.00005 WHERE id = pg_temp.zid('ESB')$q$);
CALL pg_temp.check_eq('4 경계', 'realized_pnl 2.00005 → 저장값', '2.0001', pg_temp.val($v$SELECT realized_pnl::text FROM execution WHERE id = pg_temp.zid('ESB')$v$));
CALL pg_temp.check_ok('4 경계', 'realized_pnl = −2.00005 대입 (저장 −2.0001 → 통과)', $q$UPDATE execution SET realized_pnl = -2.00005 WHERE id = pg_temp.zid('ESB')$q$);
CALL pg_temp.check_eq('4 경계', 'realized_pnl −2.00005 → 저장값', '-2.0001', pg_temp.val($v$SELECT realized_pnl::text FROM execution WHERE id = pg_temp.zid('ESB')$v$));

-- 4.5 cash_account.balance NUMERIC(19,4), CHECK balance = ROUND(balance, 0)
CALL pg_temp.check_ok('4 경계', 'balance = 100.00004 대입 (저장 100.0000 → 통과)', $q$UPDATE cash_account SET balance = 100.00004 WHERE season_id = pg_temp.zid('SB')$q$);
CALL pg_temp.check_eq('4 경계', 'balance 100.00004 → 저장값', '100.0000', pg_temp.val($v$SELECT balance::text FROM cash_account WHERE season_id = pg_temp.zid('SB')$v$));
CALL pg_temp.check_err('4 경계', 'balance = 100.00005 (저장 100.0001 → 원 단위 CHECK 위반)', $q$UPDATE cash_account SET balance = 100.00005 WHERE season_id = pg_temp.zid('SB')$q$, '23514', 'ck_cash_account_balance_won');

-- 4.6 orders.side VARCHAR(4): 폭을 넘는 공백만 오류 없이 잘리고, 폭 안의 공백은 값의 일부다 (기본 비교는 뒤 공백을 구분)
CALL pg_temp.check_ok('4 경계', 'side = ''SELL '' (폭 4 초과 공백 1개 → 잘려 통과)', $q$UPDATE orders SET side = 'SELL ' WHERE id = pg_temp.zid('OSB')$q$);
CALL pg_temp.check_eq('4 경계', 'side ''SELL '' → 저장값의 길이', '4', pg_temp.val($v$SELECT length(side)::text FROM orders WHERE id = pg_temp.zid('OSB')$v$));
CALL pg_temp.check_err('4 경계', 'side = ''BUY '' (폭 4 안의 뒤 공백 → CHECK 위반)', $q$UPDATE orders SET side = 'BUY ' WHERE id = pg_temp.zid('OSB')$q$, '23514', 'ck_orders_side');

-- 4.7 orders.status VARCHAR(10): 폭에 여유가 있는 컬럼(가장 긴 값 CANCELED·REJECTED 8자)에서는 "잘려서 통과"가 생기지 않는다.
--     'FILLED' + 공백 5개(11자)는 10자로 잘려도 공백 4개가 남아 값이 다르다 (erd 7.6: v1 폭 그대로).
CALL pg_temp.check_err('4 경계', 'status = ''FILLED'' + 공백 5개 (11자 → 10자로 잘려도 뒤 공백 4개가 남아 CHECK 위반)',
    $q$UPDATE orders SET status = 'FILLED     ' WHERE id = pg_temp.zid('OSB')$q$, '23514', 'ck_orders_status');

-- 4.8 INTEGER와 SMALLINT: 소수 입력은 경고 없이 반올림되고, 범위를 넘으면 22003이다
CALL pg_temp.check_ok('4 경계', 'orders.quantity = 1.5 대입 (INTEGER 컬럼에 소수 — 저장 2)', $q$UPDATE orders SET quantity = 1.5 WHERE id = pg_temp.zid('OSB')$q$);
CALL pg_temp.check_eq('4 경계', 'quantity 1.5 → 저장값 (0에서 먼 쪽 반올림, 경고 없음)', '2', pg_temp.val($v$SELECT quantity::text FROM orders WHERE id = pg_temp.zid('OSB')$v$));
CALL pg_temp.check_err('4 경계', 'orders.quantity = 2147483648 (INTEGER 범위 초과 → 22003)', $q$UPDATE orders SET quantity = 2147483648 WHERE id = pg_temp.zid('OSB')$q$, '22003');
CALL pg_temp.check_err('4 경계', 'orders.turn_no = 32768 (SMALLINT 범위 초과 → 22003)', $q$UPDATE orders SET turn_no = 32768 WHERE id = pg_temp.zid('OSB')$q$, '22003');

-- 4.9 NUMERIC NaN: NaN은 자기와 같고 모든 수보다 크게 비교되어 `>= 0`·`= ROUND`만으로는 막히지 않는다 (erd D32).
--     금액 CHECK의 <> 'NaN'::numeric이 실제로 걸리는지 컬럼마다 한 건씩 본다. 무한대는 정밀도 지정 NUMERIC이 22003으로 막는다.
CALL pg_temp.check_err('4 경계', 'cash_account.balance = NaN', $q$UPDATE cash_account SET balance = 'NaN' WHERE season_id = pg_temp.zid('SB')$q$, '23514', 'ck_cash_account_balance_won');
--     reserved만 NaN이면 balance가 유한하므로 범위 CHECK(NaN <= balance가 거짓)가 이름 순으로 먼저 보고한다. reserved_won의 커버리지는 0.5 사례가 맡는다.
CALL pg_temp.check_err('4 경계', 'cash_account.reserved = NaN (balance가 유한 → ck_cash_account_reserved_range가 먼저)', $q$UPDATE cash_account SET reserved = 'NaN' WHERE season_id = pg_temp.zid('SB')$q$, '23514', 'ck_cash_account_reserved_range');
CALL pg_temp.check_err('4 경계', 'orders.limit_price = NaN (LIMIT)', $q$UPDATE orders SET type = 'LIMIT', limit_price = 'NaN' WHERE id = pg_temp.zid('OSB')$q$, '23514', 'ck_orders_limit_price_won');
CALL pg_temp.check_err('4 경계', 'execution.fill_price = NaN', $q$UPDATE execution SET fill_price = 'NaN' WHERE id = pg_temp.zid('ESB')$q$, '23514', 'ck_execution_fill_price_won');
CALL pg_temp.check_err('4 경계', 'execution.fee = NaN', $q$UPDATE execution SET fee = 'NaN' WHERE id = pg_temp.zid('ESB')$q$, '23514', 'ck_execution_fee_won');
CALL pg_temp.check_err('4 경계', 'execution.tax = NaN', $q$UPDATE execution SET tax = 'NaN' WHERE id = pg_temp.zid('ESB')$q$, '23514', 'ck_execution_tax_won');
CALL pg_temp.check_err('4 경계', 'execution.realized_pnl = NaN (원 단위 CHECK가 없어 전용 CHECK)', $q$UPDATE execution SET realized_pnl = 'NaN' WHERE id = pg_temp.zid('ESB')$q$, '23514', 'ck_execution_realized_pnl_not_nan');
CALL pg_temp.check_err('4 경계', 'holding.avg_price = NaN (수량 > 0)', $q$UPDATE holding SET avg_price = 'NaN' WHERE id = pg_temp.zid('HSB')$q$, '23514', 'ck_holding_quantity_avg_price');
CALL pg_temp.check_err('4 경계', 'cash_ledger.amount = NaN (DEPOSIT)', $q$UPDATE cash_ledger SET amount = 'NaN' WHERE id = pg_temp.zid('LSB')$q$, '23514', 'ck_cash_ledger_amount_won');
CALL pg_temp.check_err('4 경계', 'cash_ledger.amount = NaN (ADJUSTMENT)', $q$UPDATE cash_ledger SET type = 'ADJUSTMENT', amount = 'NaN' WHERE id = pg_temp.zid('LSB')$q$, '23514', 'ck_cash_ledger_amount_won');
CALL pg_temp.check_err('4 경계', 'cash_ledger.balance_after = NaN', $q$UPDATE cash_ledger SET balance_after = 'NaN' WHERE id = pg_temp.zid('LSB')$q$, '23514', 'ck_cash_ledger_balance_after_won');
CALL pg_temp.check_err('4 경계', 'cash_ledger.amount = Infinity (정밀도 지정 NUMERIC이 거부 → 22003)', $q$UPDATE cash_ledger SET amount = 'Infinity' WHERE id = pg_temp.zid('LSB')$q$, '22003');


-- 5 복합 FK와 DDL 경계 (erd 7.3 MATCH SIMPLE, D29, 7.5) ----------------------------
-- 복합 FK 3개는 MATCH SIMPLE이다: 자식 컬럼 중 하나라도 NULL이면 부모를 검사하지 않는다. 그래서 formation_change_id·turn_feed_id가 NULL인
-- 주문은 통과하고, 나머지 컬럼(season_id·turn_no)이 NOT NULL이라 NULL 우회는 그 하나뿐이다.
-- 5.1은 "시즌·턴이 맞으면 통과, 다르면 23503"을, 5.2는 DDL이 막지 않아 대사(erd 7.5)가 잡는 것을 기록한다.

-- 5.1 복합 FK (★ D29). 새 주문은 INSERT로 만든다.
CALL pg_temp.check_ok('5 복합 FK', '통과: S0 턴 3 주문 + FC0 (S0 턴 3 — 같은 시즌·같은 턴)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, formation_change_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'FILLED', 'DIRECT', pg_temp.zid('FC0'))$q$);
CALL pg_temp.check_ok('5 복합 FK', '통과: formation_change_id NULL인 S0 턴 2 주문 (자식 컬럼 하나가 NULL이면 검사하지 않는다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, formation_change_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 2, 'BUY', 1, 'FILLED', 'DIRECT', NULL)$q$);
CALL pg_temp.check_err('5 복합 FK', 'D29 ★ S0 턴 3 주문 + FC1 (다른 시즌 S1의 변경 — 턴은 같다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, formation_change_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'FILLED', 'DIRECT', pg_temp.zid('FC1'))$q$, '23503', 'fk_orders_formation_change');
CALL pg_temp.check_err('5 복합 FK', 'D29 ★ S0 턴 3 주문 + FC0b (같은 시즌 S0의 다른 턴 2의 변경)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, formation_change_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'FILLED', 'DIRECT', pg_temp.zid('FC0b'))$q$, '23503', 'fk_orders_formation_change');
CALL pg_temp.check_err('5 복합 FK', 'D29: 변경 FCR에 묶인 주문 O_FCR의 turn_no를 4로 UPDATE (자식 쪽 UPDATE 경로 — 이제 S0 턴 4 ≠ FCR의 턴 3)',
    $q$UPDATE orders SET turn_no = 4 WHERE id = pg_temp.zid('O_FCR')$q$, '23503', 'fk_orders_formation_change');
CALL pg_temp.check_ok('5 복합 FK', '통과: S0 FEED 주문 + TF0 (S0의 피드)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, turn_feed_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'FILLED', 'FEED', pg_temp.zid('TF0'))$q$);
CALL pg_temp.check_err('5 복합 FK', 'D29 ★ S0 FEED 주문 + TF1 (다른 시즌 S1의 피드)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, turn_feed_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'FILLED', 'FEED', pg_temp.zid('TF1'))$q$, '23503', 'fk_orders_turn_feed');
CALL pg_temp.check_ok('5 복합 FK', '통과: S0 ADJUSTMENT 원장 + 참조 NULL (ref_order_id가 NULL이면 검사하지 않는다)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('S0'), 'ADJUSTMENT', 7, 28897498, NULL)$q$);
CALL pg_temp.check_ok('5 복합 FK', '통과: S1 BUY 원장 + S1 주문 OS1 (같은 시즌)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('S1'), 'BUY', -1000, 999000, pg_temp.zid('OS1'))$q$);
CALL pg_temp.check_err('5 복합 FK', 'D29 ★ S0 BUY 원장 + S1 주문 OS1 (다른 시즌의 주문 — S0 계좌는 있으므로 fk_cash_ledger_cash_account는 통과한다)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('S0'), 'BUY', -1000, 1000, pg_temp.zid('OS1'))$q$, '23503', 'fk_cash_ledger_order');

-- 5.2 DDL이 통과시키는 것 — 대사의 몫 (erd 7.5). 모두 성공해야 정상이다(check_ok). item에 규칙 번호를 적는다.
--     P0-16이 이 행들을 음성 픽스처의 씨앗으로 쓸 수 있다. 시즌 SC(계좌 있음, 원장 없음)를 쓴다.
--     5.2의 행은 모두 시즌 SC에 쌓여 서로 영향을 준다(규칙 2의 원장 합에 뒤 사례 행이 더해지고, 규칙 8·13 체결도 OSC_B에 붙는다).
--     P0-16은 이 행들을 씨앗으로만 쓰고, 규칙마다 따로 시즌·주문을 만들어 한 규칙만 어기게 옮긴다.
-- 수수료 공식: fee = round(fill_price × quantity × rate, 0). rate(KR) = fee_rate_kr, rate(US) = fee_rate_us + fx_spread_rate (요율을 먼저 더하고 한 번 반올림).
-- 공식은 세 테이블(execution → orders → asset, orders → season)에 걸치고 CHECK는 다른 테이블을 읽을 수 없다 (6 DDL D8).
-- DDL은 fee >= 0과 fee = ROUND(fee, 0)만 막는다. 공식 자체는 P0-15·P0-19가 계산하고 대사 규칙 14가 검사한다.
CALL pg_temp.check_eq('5 복합 FK', '수수료 공식 (2절 기준 체결 E0): round(70,100 × 7 × fee_rate_kr, 0) = 74', '74',
    pg_temp.val($v$SELECT round(70100 * 7 * fee_rate_kr, 0)::text FROM season WHERE id = pg_temp.zid('SC')$v$));
CALL pg_temp.check_eq('5 복합 FK', '수수료 공식 (2절 미국 체결 EU): round(250,000 × 3 × (fee_rate_us + fx_spread_rate), 0) = 2,625', '2625',
    pg_temp.val($v$SELECT round(250000 * 3 * (fee_rate_us + fx_spread_rate), 0)::text FROM season WHERE id = pg_temp.zid('SC')$v$));
CALL pg_temp.check_eq('5 복합 FK', '규칙 1 ACCOUNT_OPENING: 원장이 없는 계좌 (SC 계좌 1개, 원장 0행)', '1 0',
    pg_temp.val($v$SELECT (SELECT count(*) FROM cash_account WHERE season_id = pg_temp.zid('SC'))::text || ' '
                      || (SELECT count(*) FROM cash_ledger WHERE season_id = pg_temp.zid('SC'))::text$v$));
CALL pg_temp.check_ok('5 복합 FK', '규칙 1 ACCOUNT_OPENING: 첫 행이 BUY인 원장 (DEPOSIT가 먼저여야 하지만 DDL은 순서를 모른다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('SC'), pg_temp.zid('A:ZZ0001'), 1, 'BUY', 1, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('OSC_B', pg_temp.zlast('orders'));
       INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('SC'), 'BUY', -1000, 1000, pg_temp.zid('OSC_B'))$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 2 CASH_SUM: cash_account.balance ≠ Σ amount (잔액 12,345, 원장 합 −1,000)',
    $q$UPDATE cash_account SET balance = 12345 WHERE season_id = pg_temp.zid('SC')$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 3 LEDGER_CHAIN: balance_after가 직전 행 + amount와 다른 원장 (직전 1,000 + 500 ≠ 99,999)',
    $q$INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (pg_temp.zid('SC'), 'DEPOSIT', 500, 99999)$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 4 ORDER_LEDGER: 매도 주문을 참조하는 BUY 원장 (유형 ≠ 참조 주문의 side)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('SC'), pg_temp.zid('A:ZZ0001'), 1, 'SELL', 1, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('OSC_S', pg_temp.zlast('orders'));
       INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('SC'), 'BUY', -1000, 1000, pg_temp.zid('OSC_S'))$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 4 ORDER_LEDGER: FEE를 BUY에 합친 원장 (별도 주문 OSC_F — 체결 1,000 × 1 + 수수료 10 = BUY −1,010 한 행: 총액은 맞고 분류가 틀리다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('SC'), pg_temp.zid('A:ZZ0001'), 1, 'BUY', 1, 'FILLED', 'DIRECT');
       INSERT INTO zz_key VALUES ('OSC_F', pg_temp.zlast('orders'));
       INSERT INTO execution (order_id, fill_price, quantity, fee) VALUES (pg_temp.zid('OSC_F'), 1000, 1, 10);
       INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id) VALUES (pg_temp.zid('SC'), 'BUY', -1010, 1000, pg_temp.zid('OSC_F'))$q$);
CALL pg_temp.check_eq('5 복합 FK', '규칙 4 ORDER_LEDGER: OSC_F 원장 합 = 체결 기준 기대액 (총액은 맞다)', '-1010 -1010',
    pg_temp.val($v$SELECT (SELECT sum(amount)::bigint FROM cash_ledger WHERE ref_order_id = pg_temp.zid('OSC_F'))::text || ' '
                      || (SELECT -sum(fill_price * quantity + fee + tax)::bigint FROM execution WHERE order_id = pg_temp.zid('OSC_F'))::text$v$));
CALL pg_temp.check_eq('5 복합 FK', '규칙 4 ORDER_LEDGER: OSC_F 원장 FEE 합 0 ≠ 체결 수수료 −10 (분류가 틀리다)', '0 -10',
    pg_temp.val($v$SELECT (SELECT COALESCE(sum(amount) FILTER (WHERE type = 'FEE'), 0)::bigint FROM cash_ledger WHERE ref_order_id = pg_temp.zid('OSC_F'))::text || ' '
                      || (SELECT -sum(fee)::bigint FROM execution WHERE order_id = pg_temp.zid('OSC_F'))::text$v$));
CALL pg_temp.check_ok('5 복합 FK', '규칙 6 HOLDING_QTY: holding.quantity ≠ 체결 합 (보유 5주, SC × ZZ0002에 체결 없음)',
    $q$INSERT INTO holding (season_id, asset_id, quantity, avg_price) VALUES (pg_temp.zid('SC'), pg_temp.zid('A:ZZ0002'), 5, 1000)$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 7 ORDER_FILL: PENDING 주문(체결 없음)과 LIMIT 주문 (v2는 MARKET·FILLED만 쓴다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, source) VALUES (pg_temp.zid('SC'), pg_temp.zid('A:ZZ0001'), 1, 'BUY', 1, 'DIRECT');
       INSERT INTO orders (season_id, asset_id, turn_no, side, type, quantity, limit_price, source) VALUES (pg_temp.zid('SC'), pg_temp.zid('A:ZZ0001'), 1, 'BUY', 'LIMIT', 1, 1000, 'DIRECT')$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 8 EXEC_VALUES: 매수 체결에 tax = 1과 realized_pnl 5 (매수는 realized_pnl NULL·세금 0이어야 한다)',
    $q$INSERT INTO execution (order_id, fill_price, quantity, fee, tax, realized_pnl) VALUES (pg_temp.zid('OSC_B'), 1000, 1, 0, 1, 5)$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 9 RESERVED_ZERO: reserved = 1 (≤ 잔액, v2는 항상 0이어야 한다)',
    $q$UPDATE cash_account SET reserved = 1 WHERE season_id = pg_temp.zid('SC')$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 13 FILL_PRICE: 체결가 ≠ turn_price (SC에는 turn_price 행이 없다)',
    $q$INSERT INTO execution (order_id, fill_price, quantity, fee) VALUES (pg_temp.zid('OSC_B'), 12345, 1, 2)$q$);
CALL pg_temp.check_ok('5 복합 FK', '규칙 14 FEE_RULE ★: 수수료가 공식과 1원 다른 체결 (70,100 × 7주 → 공식 74, 입력 73)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)
       VALUES (pg_temp.zid('SC'), pg_temp.zid('A:ZZ0001'), 1, 'BUY', 7, 'FILLED', 'DIRECT');
       INSERT INTO execution (order_id, fill_price, quantity, fee) VALUES (pg_temp.zlast('orders'), 70100, 7, 73)$q$);
CALL pg_temp.check_ok('5 복합 FK', '검증 규칙 S6 (erd 6.5): ALERT 주문이 RISE 피드 TF0를 참조 (ALERT는 NUDGE 피드여야 한다)',
    $q$INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, turn_feed_id)
       VALUES (pg_temp.zid('S0'), pg_temp.zid('A:ZZ0001'), 3, 'BUY', 1, 'FILLED', 'ALERT', pg_temp.zid('TF0'))$q$);

-- 6 음성 DDL (트랜잭션 안에서 실행하고 되돌린다 — PostgreSQL의 DDL은 ROLLBACK된다) ------------
-- zz_ 테이블은 public에 만든다 (오류로 끝나면 서브트랜잭션이 되돌려 남지 않는다).
CALL pg_temp.check_err('6 DDL', 'D1: 컬럼 CHECK가 다른 컬럼을 참조 — PostgreSQL은 막지 않는다 (그래서 CHECK 위치 통일은 erd 2절 규칙이 지킨다)',
    $q$ALTER TABLE holding ADD COLUMN zz_x integer CHECK (zz_x > quantity)$q$, '00000');
CALL pg_temp.check_err('6 DDL', 'D2: 유일하지 않은 컬럼(orders.season_id)을 참조하는 FK',
    $q$ALTER TABLE execution ADD CONSTRAINT zz_fk FOREIGN KEY (order_id) REFERENCES orders (season_id)$q$, '42830');
CALL pg_temp.check_err('6 DDL', 'D3: 없는 부모 테이블을 참조하는 FK',
    $q$ALTER TABLE execution ADD CONSTRAINT zz_fk FOREIGN KEY (order_id) REFERENCES zz_no_such_table (id)$q$, '42P01');
CALL pg_temp.check_err('6 DDL', 'D4a: 자식이 있는 03 부모 테이블 DROP (CASCADE 없음) — 재실행 블록이 역순인 이유',
    $q$DROP TABLE orders$q$, '2BP01');
-- D4b 규칙: "다음 파일이 있을 때 재실행이 멈추는 이유"는 자기 파일 안에 자식이 없는 앞 번호 테이블로 증명한다.
-- turn_feed는 02 안에 자식이 없다. 03의 orders만 가리킨다(fk_orders_turn_feed).
CALL pg_temp.check_err('6 DDL', 'D4b: 03이 가리키는 02 테이블 turn_feed DROP — 03이 있을 때 02 재실행이 2BP01로 멈추는 이유',
    $q$DROP TABLE turn_feed$q$, '2BP01');
CALL pg_temp.check_err('6 DDL', 'D5: 같은 테이블에 같은 CHECK 이름 두 번',
    $q$ALTER TABLE orders ADD CONSTRAINT ck_orders_side CHECK (true)$q$, '42710');
CALL pg_temp.check_err('6 DDL', 'D5b: 다른 테이블에 같은 CHECK 이름 — PostgreSQL CHECK 이름은 테이블 안에서만 유일하다 (MySQL은 스키마 안에서 유일)',
    $q$ALTER TABLE holding ADD CONSTRAINT ck_orders_side CHECK (true)$q$, '00000');
CALL pg_temp.check_err('6 DDL', 'D6: 기존 UQ 이름과 같은 인덱스 이름 (인덱스는 스키마 안에서 이름이 유일)',
    $q$CREATE INDEX uq_holding_season_asset ON orders (side)$q$, '42P07');
CALL pg_temp.check_err('6 DDL', 'D7a: 복합 FK (o_id, season_id) → orders (id, season_id) 는 대상 UNIQUE uq_orders_id_season이 있어 만들어진다',
    $q$CREATE TABLE zz_o_ref (id bigint PRIMARY KEY, o_id bigint, season_id bigint,
           FOREIGN KEY (o_id, season_id) REFERENCES orders (id, season_id))$q$, '00000');
CALL pg_temp.check_err('6 DDL', 'D7b: 복합 FK (o_id, asset_id) → orders (id, asset_id) 는 컬럼 목록이 정확히 같은 UNIQUE가 없어 42830 (erd 7.3)',
    $q$CREATE TABLE zz_o_ref (id bigint PRIMARY KEY, o_id bigint, asset_id bigint,
           FOREIGN KEY (o_id, asset_id) REFERENCES orders (id, asset_id))$q$, '42830');
CALL pg_temp.check_err('6 DDL', 'D8: CHECK 안의 서브쿼리 — 수수료 공식을 CHECK로 못 넣는 이유 (공식은 season·asset을 읽어야 한다)',
    $q$ALTER TABLE execution ADD CONSTRAINT zz_ck CHECK (fee = (SELECT 0))$q$, '0A000');
CALL pg_temp.check_err('6 DDL', 'D9: 복합 FK의 대상 UNIQUE를 DEFERRABLE로 바꾸면 FK를 다시 만들 수 없다 (FK를 지우고 UNIQUE를 재생성한 뒤 FK 재생성 → 55000 "cannot use a deferrable unique constraint for referenced table")',
    $q$ALTER TABLE cash_ledger DROP CONSTRAINT fk_cash_ledger_order;
       ALTER TABLE orders DROP CONSTRAINT uq_orders_id_season;
       ALTER TABLE orders ADD CONSTRAINT uq_orders_id_season UNIQUE (id, season_id) DEFERRABLE;
       ALTER TABLE cash_ledger ADD CONSTRAINT fk_cash_ledger_order FOREIGN KEY (ref_order_id, season_id) REFERENCES orders (id, season_id)$q$, '55000');

-- 7 판정 ---------------------------------------------------------------------
-- 7.1 커버리지: 5개 테이블의 ck_ 28 + uq_ 제약 2 + fk_ 10 = 40개 이름이 각각 1건 이상 자기 이름으로 보고되어야 한다.
--     (보고된 이름 = 3·4·5절 성공한 음성 사례 결과의 실제값 둘째 칸, SQLSTATE 23514·23505·23503인 것)
--     가려진 CHECK(다른 CHECK에 가려 단독으로 못 걸리는 것)나 PK에 가려진 UNIQUE가 있으면 여기서 빠진 이름으로 드러난다.
--     03에는 제약이 아닌 유일 인덱스가 없다 (아래 need는 02와 같은 정의를 그대로 둔다).
INSERT INTO zz_check_result (section, item, expected, actual, ok)
WITH covered AS (
    SELECT DISTINCT split_part(actual, ' ', 2) AS name
    FROM zz_check_result
    WHERE ok AND section IN ('3 음성', '4 경계', '5 복합 FK') AND split_part(actual, ' ', 1) IN ('23514', '23505', '23503')
), need AS (
    SELECT c.conname::text AS name
    FROM pg_constraint c
    WHERE c.contype IN ('u', 'f', 'c') AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)
    UNION ALL
    SELECT ic.relname::text
    FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid
    WHERE i.indrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count) AND i.indisunique
      AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid AND c.contype IN ('p', 'u', 'x'))
)
SELECT '7 판정', '커버리지: 자기 이름으로 보고된 ck_·uq_·fk_ 이름 수', '40/40',
       (SELECT count(*) FROM need WHERE name IN (SELECT name FROM covered))::text || '/' || (SELECT count(*) FROM need)::text
       || COALESCE(' 빠짐: ' || (SELECT string_agg(name, ', ' ORDER BY name) FROM need WHERE name NOT IN (SELECT name FROM covered)), ''),
       (SELECT count(*) FROM need WHERE name IN (SELECT name FROM covered)) = 40 AND (SELECT count(*) FROM need) = 40;

-- 7.2 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 7.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 7.4 판정 행
SELECT CASE WHEN count(*) > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result;

-- 7.5 판정을 종료 코드로: FAIL이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1이면 종료 코드 3).
--     판정 표식(zz_verdict)은 트랜잭션이 되돌아가도 남는다. 여기서는 통과해도 2(흔적 확인 대기)까지만 올리고,
--     8절의 어느 조회가 오류로 끊겨도 표식이 2로 남아 ON_ERROR_STOP 없이 돌려도 마지막 줄이 PASS로 나오지 않는다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    -- 2 = 1–7절 통과 (결과 표가 비어 있으면 통과로 치지 않는다), 1 = 실패. 최종 통과(0)는 8절 끝에서만 만든다.
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-07 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 8 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 22개 테이블(01의 8 + 02의 9 + 03의 5)의 행 0, public의 zz_ 객체 0, 이 파일 5개 테이블의 제약 45개(PK 5 + UQ 2 + FK 10 + CK 28)와
-- 인덱스 15개(PK 5 + UQ 2 + idx_ 8)가 그대로다. 01·02 테이블의 제약·인덱스 개수는 세지 않는다(01·02 검증의 몫).
-- 테이블 이름은 BEGIN 앞의 zz_table_list에서 읽는다 (임시 표는 트랜잭션 밖에서 만들어 ROLLBACK 뒤에도 있다).
-- identity 값 소비만 되돌아오지 않는다 (db/README.md).
DO $$
DECLARE
    v_list integer;
    v_rows bigint;
    v_zz   integer;
    v_con  integer;
    v_idx  integer;
BEGIN
    SELECT count(*), COALESCE(sum(pg_temp.row_count(tbl)), 0) INTO v_list, v_rows FROM zz_table_list;
    SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
         + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%') INTO v_zz;
    SELECT count(*) INTO v_con FROM pg_constraint
    WHERE contype IN ('p', 'u', 'f', 'c') AND conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_table_list WHERE owner = '03');
    SELECT count(*) INTO v_idx FROM pg_indexes
    WHERE schemaname = 'public' AND tablename IN (SELECT tbl FROM zz_table_list WHERE owner = '03');
    IF (v_list, v_rows, v_zz, v_con, v_idx) <> (22, 0, 0, 45, 15) THEN
        PERFORM setval('pg_temp.zz_verdict', 1);
        RAISE EXCEPTION 'P0-07 흔적 확인 실패: 목록 % (기대 22), 행 % (기대 0), zz_ 객체 % (기대 0), 제약 % (기대 45), 인덱스 % (기대 15)', v_list, v_rows, v_zz, v_con, v_idx;
    END IF;
    -- 단언을 모두 통과했고 1–7절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_07_check;
