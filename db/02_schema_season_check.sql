-- =====================================================================
-- my Fund Manager Phase 0 — DDL ② 검증 SQL (P0-06)
-- 대상: db/02_schema_season.sql이 만든 9개 테이블 (member, season, season_path, turn, turn_price,
--       lineup, formation_change, turn_feed, reconciliation_log). 01의 4개 테이블(asset_group, asset, month_block,
--       month_event)은 FK 대상이자 픽스처의 부모로 쓰고, 이 파일은 그 구조를 검증하지 않는다 (01 검증의 몫).
--       다만 2절 픽스처는 01의 컬럼 목록과 CHECK 값(asset.return_basis·listed_on, month_event.category·event_type·phase·strength·source)을
--       글자 그대로 쓴다. 01이 바뀌면 2절 첫 4건(01 픽스처)이 먼저 FAIL하고 뒤 절이 연쇄 FAIL하므로, 원인은 2절 첫 FAIL에서 찾는다.
-- 근거: docs/erd.md 6.3(엔티티 정의서), 6.4(제약과 인덱스, 개수 표), 6.5(검증 규칙 S1–S7은 이 기능 범위 밖), 9절 P0-06
-- 실행: psql -X -q -v ON_ERROR_STOP=1 -d <검증용 DB> -f db/02_schema_season_check.sql
--       01·02 스키마 파일을 순서대로 먼저 실행해 둔다. 검증용 DB(antlab_test)에서만 돌린다 (identity 값을 소비한다).
-- 판정: 마지막 줄 `p0_06_check`가 `PASS`이고 종료 코드가 0이면 통과다. `PASS`는 1–7절이 모두 통과하고
--       8절 흔적 확인까지 끝났을 때만 나온다 (zz_verdict: 1 = 미완료 또는 실패, 2 = 1–7절 통과·흔적 확인 대기, 0 = 최종 통과).
--       0절 전제가 하나라도 FAIL이면 0절 결과를 보여 준 뒤 픽스처를 넣기 전에 오류로 멈춘다.
--       그 밖에 하나라도 어긋나면 7절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다. 마지막 줄은 PASS가 아니라 FAIL이다.
-- 흔적: 파일 전체가 한 트랜잭션이고 7절 뒤에 ROLLBACK한다. 01·02의 17개 테이블의 행, 임시 객체가 남지 않는다.
--       identity(번호표) 값 소비만 되돌아오지 않는다.
-- 절:   0 전제(FAIL이면 여기서 멈춤) → 1 구조 → 2 양성 → 3 음성 DML → 4 저장 정밀도 경계 → 5 부분·표현식 유일 인덱스 동작
--       → 6 음성 DDL → 7 판정 → ROLLBACK → 8 흔적 확인
-- 판정 함수(pg_temp 프로시저): check_err(실패해야 정상) · check_ok(성공해야 정상) · check_eq(값 비교) + 값 질의 도우미 val().
--       호출은 CALL pg_temp.check_err(...) 처럼 pg_temp. 접두가 필요하다. 02는 행 수 도우미 row_count()를 하나 더 둔다.
-- 03 검증 파일(P0-07)은 이 02 파일을 복사한다 (일부만 복사하면 헛 PASS 길이 다시 열린다):
--       ① 맨 앞 CREATE TEMP SEQUENCE zz_verdict + pg_temp 존재 단언 + setval('pg_temp.zz_verdict', 1) (모든 참조는 pg_temp. 한정)   ② 결과 표 zz_check_result
--       ③ 기대 목록 표 3개(zz_expect_*)의 모양 — 01과 달리 zz_expect_count에 `uqi`(유일 인덱스) 열이 있다
--       ④ 프로시저 check_err·check_ok·check_eq + 함수 val(), row_count()
--       ⑤ 0절 뒤 멈춤과 7.5 판정(→ 표식 2 또는 1, RAISE)   ⑥ 8절 흔적 확인(→ 표식 0)과 마지막 CASE
--       파일마다 바뀌는 것: 기대 목록 내용, 사례, 테이블 이름 목록, 기대 개수(90·27), 메시지의 P0-06.
-- 01과 달라진 점 (02부터 적용, 01 파일은 고치지 않았다):
--       · 1.4 이름·종류·테이블: 제약 + "제약에 딸리지 않은 모든 인덱스"를 센다 (이름이 idx_가 아니어도 드러난다).
--       · 1.8 인덱스 정의: erd 식을 빈 사본에 CREATE INDEX로 다시 파싱시켜 전체 문자열을 비교한다 (idx_ 9 + 유일 인덱스 2).
--       · 1.9 FK 인덱스 짝: 부분·표현식 인덱스는 짝으로 치지 않는다.
--       · 1.12 인덱스 27개 모두 유효(indisvalid·indisready).
--       · 3절 사례는 기준 행 + UPDATE 한 컬럼을 기본으로 한다 (한 사례에 조건 하나).
--       · 3절에서 PK에 가려진 UNIQUE 2개는 PK를 트랜잭션 안에서 지우고 증명한다 (OVERRIDING SYSTEM VALUE).
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

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '0 전제' '1 구조' '2 양성' '3 음성' '4 경계' '5 인덱스' '6 DDL' '7 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값 (SQLSTATE + 제약 이름, 개수, 정의 ...)
    actual   text,               -- 실제값 (같은 형식)
    ok       boolean NOT NULL
);

-- 기대 목록: erd 6.3·6.4를 그대로 옮겼다 (정의는 $$ ... $$ 안에 erd 글자 그대로)
CREATE TEMP TABLE zz_expect_count (tbl text, tbl_comment text, pk int, uq int, uqi int, fk int, ck int, idx int);   -- 9행
CREATE TEMP TABLE zz_expect_constraint (name text, kind text, tbl text, def text);                                    -- 101행
CREATE TEMP TABLE zz_expect_column (tbl text, pos int, col text, typ text, nn boolean, dflt text, ident boolean, tag text); -- 105행

INSERT INTO zz_expect_count VALUES
    ('member',            '회원',                  1, 0, 1, 0,  0, 0),
    ('season',            '시즌',                  1, 0, 0, 1, 19, 1),
    ('season_path',       '시즌 경로',               1, 1, 0, 2,  1, 1),
    ('turn',              '턴 스냅샷',               1, 1, 0, 1,  7, 0),
    ('turn_price',        '턴 가격',                1, 1, 0, 2,  3, 1),
    ('lineup',            '라인업',                 1, 0, 1, 2,  2, 2),
    ('formation_change',  '포메이션 변경',             1, 1, 0, 1,  8, 1),
    ('turn_feed',         '턴 피드',                1, 2, 0, 4, 11, 3),
    ('reconciliation_log', '대사 기록',               1, 1, 0, 1,  9, 0);
INSERT INTO zz_expect_constraint (name, kind, tbl, def) VALUES
    ('pk_member', 'PK', 'member', $$(id)$$),
    ('uq_member_email', 'UQI', 'member', $$(lower(email))$$),
    ('pk_season', 'PK', 'season', $$(id)$$),
    ('fk_season_member', 'FK', 'season', $$(member_id) → member.id (R9)$$),
    ('ck_season_status', 'CK', 'season', $$status IN ('IN_PROGRESS','ABANDONING','FINISHED')$$),
    ('ck_season_path_length', 'CK', 'season', $$path_length > 0$$),
    ('ck_season_current_turn_no', 'CK', 'season', $$current_turn_no BETWEEN 0 AND path_length$$),
    ('ck_season_finished_turn', 'CK', 'season', $$status <> 'FINISHED' OR current_turn_no = path_length$$),
    ('ck_season_abandoned_turn_no', 'CK', 'season', $$abandoned_turn_no IS NULL OR abandoned_turn_no BETWEEN 0 AND current_turn_no$$),
    ('ck_season_abandoning', 'CK', 'season', $$status <> 'ABANDONING' OR abandoned_turn_no IS NOT NULL$$),
    ('ck_season_abandoned_status', 'CK', 'season', $$status <> 'IN_PROGRESS' OR abandoned_turn_no IS NULL$$),
    ('ck_season_initial_cash_pos', 'CK', 'season', $$initial_cash > 0$$),
    ('ck_season_initial_cash_won', 'CK', 'season', $$initial_cash = ROUND(initial_cash, 0) AND initial_cash <> 'NaN'::numeric$$),
    ('ck_season_fee_rate_kr', 'CK', 'season', $$fee_rate_kr >= 0 AND fee_rate_kr < 1$$),
    ('ck_season_fee_rate_us', 'CK', 'season', $$fee_rate_us >= 0 AND fee_rate_us < 1$$),
    ('ck_season_fx_spread_rate', 'CK', 'season', $$fx_spread_rate >= 0 AND fx_spread_rate < 1$$),
    ('ck_season_risk_limit', 'CK', 'season', $$risk_limit IN (-0.10, -0.20, -0.30, -0.40)$$),
    ('ck_season_start_formation_sum', 'CK', 'season', $$start_goalkeeper + start_defender + start_midfielder + start_forward = 100$$),
    ('ck_season_start_formation_range', 'CK', 'season', $$start_goalkeeper BETWEEN 0 AND 100 AND start_defender BETWEEN 0 AND 100 AND start_midfielder BETWEEN 0 AND 100 AND start_forward BETWEEN 0 AND 100$$),
    ('ck_season_start_goalkeeper_min', 'CK', 'season', $$start_goalkeeper >= 1$$),
    ('ck_season_current_formation_sum', 'CK', 'season', $$current_goalkeeper + current_defender + current_midfielder + current_forward = 100$$),
    ('ck_season_current_formation_range', 'CK', 'season', $$current_goalkeeper BETWEEN 0 AND 100 AND current_defender BETWEEN 0 AND 100 AND current_midfielder BETWEEN 0 AND 100 AND current_forward BETWEEN 0 AND 100$$),
    ('ck_season_current_goalkeeper_min', 'CK', 'season', $$current_goalkeeper >= 1$$),
    ('idx_season_member', 'IDX', 'season', $$(member_id)$$),
    ('pk_season_path', 'PK', 'season_path', $$(id)$$),
    ('uq_season_path_season_turn', 'UQ', 'season_path', $$(season_id, turn_no)$$),
    ('fk_season_path_season', 'FK', 'season_path', $$(season_id) → season.id (R10)$$),
    ('fk_season_path_month_block', 'FK', 'season_path', $$(month_block_id) → month_block.id (R11)$$),
    ('ck_season_path_turn_no', 'CK', 'season_path', $$turn_no >= 1$$),
    ('idx_season_path_month_block', 'IDX', 'season_path', $$(month_block_id)$$),
    ('pk_turn', 'PK', 'turn', $$(id)$$),
    ('uq_turn_season_turn', 'UQ', 'turn', $$(season_id, turn_no)$$),
    ('fk_turn_season', 'FK', 'turn', $$(season_id) → season.id (R12)$$),
    ('ck_turn_turn_no', 'CK', 'turn', $$turn_no >= 1$$),
    ('ck_turn_total_value', 'CK', 'turn', $$total_value = holdings_value + cash_balance$$),
    ('ck_turn_position_sum', 'CK', 'turn', $$goalkeeper_value + defender_value + midfielder_value + forward_value = total_value$$),
    ('ck_turn_goalkeeper_cash', 'CK', 'turn', $$goalkeeper_value >= cash_balance$$),
    ('ck_turn_drawdown', 'CK', 'turn', $$drawdown BETWEEN -1 AND 0$$),
    ('ck_turn_amounts_nonneg', 'CK', 'turn', $$cash_balance >= 0 AND holdings_value >= 0 AND total_value >= 0 AND goalkeeper_value >= 0 AND defender_value >= 0 AND midfielder_value >= 0 AND forward_value >= 0$$),
    ('ck_turn_amounts_won', 'CK', 'turn', $$cash_balance = ROUND(cash_balance, 0) AND holdings_value = ROUND(holdings_value, 0) AND total_value = ROUND(total_value, 0) AND goalkeeper_value = ROUND(goalkeeper_value, 0) AND defender_value = ROUND(defender_value, 0) AND midfielder_value = ROUND(midfielder_value, 0) AND forward_value = ROUND(forward_value, 0) AND cash_balance <> 'NaN'::numeric AND holdings_value <> 'NaN'::numeric AND total_value <> 'NaN'::numeric AND goalkeeper_value <> 'NaN'::numeric AND defender_value <> 'NaN'::numeric AND midfielder_value <> 'NaN'::numeric AND forward_value <> 'NaN'::numeric$$),
    ('pk_turn_price', 'PK', 'turn_price', $$(id)$$),
    ('uq_turn_price_season_turn_asset', 'UQ', 'turn_price', $$(season_id, turn_no, asset_id)$$),
    ('fk_turn_price_season', 'FK', 'turn_price', $$(season_id) → season.id (R13)$$),
    ('fk_turn_price_asset', 'FK', 'turn_price', $$(asset_id) → asset.id (R14)$$),
    ('ck_turn_price_turn_no', 'CK', 'turn_price', $$turn_no >= 0$$),
    ('ck_turn_price_price_pos', 'CK', 'turn_price', $$price > 0$$),
    ('ck_turn_price_price_won', 'CK', 'turn_price', $$price = ROUND(price, 0) AND price <> 'NaN'::numeric$$),
    ('idx_turn_price_asset', 'IDX', 'turn_price', $$(asset_id)$$),
    ('pk_lineup', 'PK', 'lineup', $$(id)$$),
    ('uq_lineup_open', 'UQI', 'lineup', $$(season_id, asset_id) WHERE end_turn IS NULL$$),
    ('fk_lineup_season', 'FK', 'lineup', $$(season_id) → season.id (R15)$$),
    ('fk_lineup_asset', 'FK', 'lineup', $$(asset_id) → asset.id (R16)$$),
    ('ck_lineup_declared_position', 'CK', 'lineup', $$declared_position IN ('GOALKEEPER','DEFENDER','MIDFIELDER','FORWARD_TARGET','FORWARD_BREAKOUT','FORWARD_DEFENSIVE')$$),
    ('ck_lineup_turns', 'CK', 'lineup', $$start_turn >= 0 AND (end_turn IS NULL OR end_turn >= start_turn)$$),
    ('idx_lineup_season_asset', 'IDX', 'lineup', $$(season_id, asset_id)$$),
    ('idx_lineup_asset', 'IDX', 'lineup', $$(asset_id)$$),
    ('pk_formation_change', 'PK', 'formation_change', $$(id)$$),
    ('uq_formation_change_id_season_turn', 'UQ', 'formation_change', $$(id, season_id, turn_no)$$),
    ('fk_formation_change_season', 'FK', 'formation_change', $$(season_id) → season.id (R17)$$),
    ('ck_formation_change_turn_no', 'CK', 'formation_change', $$turn_no >= 0$$),
    ('ck_formation_change_before_sum', 'CK', 'formation_change', $$before_goalkeeper + before_defender + before_midfielder + before_forward = 100$$),
    ('ck_formation_change_before_range', 'CK', 'formation_change', $$before_goalkeeper BETWEEN 0 AND 100 AND before_defender BETWEEN 0 AND 100 AND before_midfielder BETWEEN 0 AND 100 AND before_forward BETWEEN 0 AND 100$$),
    ('ck_formation_change_before_goalkeeper_min', 'CK', 'formation_change', $$before_goalkeeper >= 1$$),
    ('ck_formation_change_after_sum', 'CK', 'formation_change', $$after_goalkeeper + after_defender + after_midfielder + after_forward = 100$$),
    ('ck_formation_change_after_range', 'CK', 'formation_change', $$after_goalkeeper BETWEEN 0 AND 100 AND after_defender BETWEEN 0 AND 100 AND after_midfielder BETWEEN 0 AND 100 AND after_forward BETWEEN 0 AND 100$$),
    ('ck_formation_change_after_goalkeeper_min', 'CK', 'formation_change', $$after_goalkeeper >= 1$$),
    ('ck_formation_change_changed', 'CK', 'formation_change', $$(before_goalkeeper, before_defender, before_midfielder, before_forward) <> (after_goalkeeper, after_defender, after_midfielder, after_forward)$$),
    ('idx_formation_change_season_turn', 'IDX', 'formation_change', $$(season_id, turn_no)$$),
    ('pk_turn_feed', 'PK', 'turn_feed', $$(id)$$),
    ('uq_turn_feed_season_turn_seq', 'UQ', 'turn_feed', $$(season_id, turn_no, seq)$$),
    ('uq_turn_feed_id_season', 'UQ', 'turn_feed', $$(id, season_id)$$),
    ('fk_turn_feed_season', 'FK', 'turn_feed', $$(season_id) → season.id (R18)$$),
    ('fk_turn_feed_month_event', 'FK', 'turn_feed', $$(month_event_id) → month_event.id (R19)$$),
    ('fk_turn_feed_asset', 'FK', 'turn_feed', $$(asset_id) → asset.id (R20)$$),
    ('fk_turn_feed_asset_group', 'FK', 'turn_feed', $$(asset_group_id) → asset_group.id (R21)$$),
    ('ck_turn_feed_turn_no', 'CK', 'turn_feed', $$turn_no >= 1$$),
    ('ck_turn_feed_seq', 'CK', 'turn_feed', $$seq >= 1$$),
    ('ck_turn_feed_kind', 'CK', 'turn_feed', $$kind IN ('RISE','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE')$$),
    ('ck_turn_feed_category_hidden', 'CK', 'turn_feed', $$NOT category_hidden OR kind = 'EVENT'$$),
    ('ck_turn_feed_direction', 'CK', 'turn_feed', $$direction IN ('UP','DOWN')$$),
    ('ck_turn_feed_strength', 'CK', 'turn_feed', $$strength BETWEEN 1 AND 3$$),
    ('ck_turn_feed_repeat_count', 'CK', 'turn_feed', $$repeat_count >= 0 AND (kind <> 'ECHO' OR repeat_count >= 1)$$),
    ('ck_turn_feed_template_vars', 'CK', 'turn_feed', $$jsonb_typeof(template_vars) = 'object'$$),
    ('ck_turn_feed_target_one', 'CK', 'turn_feed', $$num_nonnulls(asset_id, asset_group_id) <= 1$$),
    ('ck_turn_feed_target_kind', 'CK', 'turn_feed', $$(kind IN ('RISE','FALL','ECHO','FADE') AND num_nonnulls(asset_id, asset_group_id) = 1) OR (kind = 'SPREAD' AND asset_group_id IS NOT NULL) OR (kind = 'EVENT' AND asset_id IS NULL AND asset_group_id IS NULL) OR kind = 'NUDGE'$$),
    ('ck_turn_feed_event', 'CK', 'turn_feed', $$(kind = 'EVENT') = (month_event_id IS NOT NULL)$$),
    ('idx_turn_feed_asset', 'IDX', 'turn_feed', $$(asset_id)$$),
    ('idx_turn_feed_asset_group', 'IDX', 'turn_feed', $$(asset_group_id)$$),
    ('idx_turn_feed_month_event', 'IDX', 'turn_feed', $$(month_event_id)$$),
    ('pk_reconciliation_log', 'PK', 'reconciliation_log', $$(id)$$),
    ('uq_reconciliation_log_season_turn_attempt_code', 'UQ', 'reconciliation_log', $$(season_id, turn_no, attempt_no, check_code)$$),
    ('fk_reconciliation_log_season', 'FK', 'reconciliation_log', $$(season_id) → season.id (R22)$$),
    ('ck_reconciliation_log_turn_no', 'CK', 'reconciliation_log', $$turn_no >= 0$$),
    ('ck_reconciliation_log_attempt_no', 'CK', 'reconciliation_log', $$attempt_no >= 1$$),
    ('ck_reconciliation_log_check_code', 'CK', 'reconciliation_log', $$check_code IN ('ACCOUNT_OPENING','CASH_SUM','LEDGER_CHAIN','ORDER_LEDGER','HOLDING_QTY','ORDER_FILL','EXEC_VALUES','RESERVED_ZERO','VALUATION','TURN_PRICE','FILL_PRICE','FEE_RULE','FORMATION','FORMATION_BIND','LINEUP_HOLDING','LINEUP_OVERLAP','POSITION_VALUE')$$),
    ('ck_reconciliation_log_status', 'CK', 'reconciliation_log', $$status IN ('MATCH','MISMATCH')$$),
    ('ck_reconciliation_log_values', 'CK', 'reconciliation_log', $$num_nulls(expected_value, actual_value, diff_value) IN (0, 3) AND (diff_value IS NULL OR diff_value = actual_value - expected_value) AND expected_value <> 'NaN'::numeric AND actual_value <> 'NaN'::numeric$$),
    ('ck_reconciliation_log_match_diff', 'CK', 'reconciliation_log', $$status <> 'MATCH' OR diff_value IS NULL OR diff_value = 0$$),
    ('ck_reconciliation_log_mismatch', 'CK', 'reconciliation_log', $$mismatch_count >= 0 AND (status = 'MATCH') = (mismatch_count = 0)$$),
    ('ck_reconciliation_log_classification', 'CK', 'reconciliation_log', $$classification IN ('TIMING','ADJUSTMENT','INVESTIGATE')$$),
    ('ck_reconciliation_log_status_classification', 'CK', 'reconciliation_log', $$(status = 'MATCH') = (classification IS NULL)$$);
INSERT INTO zz_expect_column (tbl, pos, col, typ, nn, dflt, ident, tag) VALUES
    ('member', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('member', 2, 'email', 'character varying(255)', true, NULL, false, '[내부]'),
    ('member', 3, 'name', 'character varying(50)', true, NULL, false, '[노출]'),
    ('member', 4, 'password', 'character varying(255)', true, NULL, false, '[내부]'),
    ('member', 5, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('member', 6, 'updated_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('season', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('season', 2, 'member_id', 'bigint', true, NULL, false, '[내부]'),
    ('season', 3, 'status', 'character varying(11)', true, '''IN_PROGRESS''::character varying', false, '[노출]'),
    ('season', 4, 'path_length', 'smallint', true, NULL, false, '[노출]'),
    ('season', 5, 'current_turn_no', 'smallint', true, '0', false, '[노출]'),
    ('season', 6, 'abandoned_turn_no', 'smallint', false, NULL, false, '[노출]'),
    ('season', 7, 'seed', 'bigint', true, NULL, false, '[내부]'),
    ('season', 8, 'engine_version', 'character varying(20)', true, NULL, false, '[내부]'),
    ('season', 9, 'data_snapshot_date', 'date', true, NULL, false, '[내부]'),
    ('season', 10, 'base_date', 'date', true, NULL, false, '[내부]'),
    ('season', 11, 'initial_cash', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('season', 12, 'fee_rate_kr', 'numeric(12,8)', true, NULL, false, '[노출]'),
    ('season', 13, 'fee_rate_us', 'numeric(12,8)', true, NULL, false, '[노출]'),
    ('season', 14, 'fx_spread_rate', 'numeric(12,8)', true, NULL, false, '[노출]'),
    ('season', 15, 'risk_limit', 'numeric(12,8)', true, NULL, false, '[노출]'),
    ('season', 16, 'start_goalkeeper', 'smallint', true, NULL, false, '[노출]'),
    ('season', 17, 'start_defender', 'smallint', true, NULL, false, '[노출]'),
    ('season', 18, 'start_midfielder', 'smallint', true, NULL, false, '[노출]'),
    ('season', 19, 'start_forward', 'smallint', true, NULL, false, '[노출]'),
    ('season', 20, 'current_goalkeeper', 'smallint', true, NULL, false, '[노출]'),
    ('season', 21, 'current_defender', 'smallint', true, NULL, false, '[노출]'),
    ('season', 22, 'current_midfielder', 'smallint', true, NULL, false, '[노출]'),
    ('season', 23, 'current_forward', 'smallint', true, NULL, false, '[노출]'),
    ('season', 24, 'version', 'bigint', true, '0', false, '[내부]'),
    ('season', 25, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('season', 26, 'updated_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('season_path', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('season_path', 2, 'season_id', 'bigint', true, NULL, false, '[내부]'),
    ('season_path', 3, 'turn_no', 'smallint', true, NULL, false, '[노출]'),
    ('season_path', 4, 'month_block_id', 'bigint', true, NULL, false, '[복기 전용]'),
    ('season_path', 5, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('turn', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('turn', 2, 'season_id', 'bigint', true, NULL, false, '[내부]'),
    ('turn', 3, 'turn_no', 'smallint', true, NULL, false, '[노출]'),
    ('turn', 4, 'cash_balance', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('turn', 5, 'holdings_value', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('turn', 6, 'total_value', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('turn', 7, 'goalkeeper_value', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('turn', 8, 'defender_value', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('turn', 9, 'midfielder_value', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('turn', 10, 'forward_value', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('turn', 11, 'drawdown', 'numeric(12,8)', true, NULL, false, '[노출]'),
    ('turn', 12, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('turn_price', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('turn_price', 2, 'season_id', 'bigint', true, NULL, false, '[내부]'),
    ('turn_price', 3, 'turn_no', 'smallint', true, NULL, false, '[노출]'),
    ('turn_price', 4, 'asset_id', 'bigint', true, NULL, false, '[내부]'),
    ('turn_price', 5, 'price', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('turn_price', 6, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('lineup', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('lineup', 2, 'season_id', 'bigint', true, NULL, false, '[내부]'),
    ('lineup', 3, 'asset_id', 'bigint', true, NULL, false, '[내부]'),
    ('lineup', 4, 'declared_position', 'character varying(17)', true, NULL, false, '[노출]'),
    ('lineup', 5, 'start_turn', 'smallint', true, NULL, false, '[노출]'),
    ('lineup', 6, 'end_turn', 'smallint', false, NULL, false, '[노출]'),
    ('lineup', 7, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('lineup', 8, 'updated_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('formation_change', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('formation_change', 2, 'season_id', 'bigint', true, NULL, false, '[내부]'),
    ('formation_change', 3, 'turn_no', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 4, 'before_goalkeeper', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 5, 'before_defender', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 6, 'before_midfielder', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 7, 'before_forward', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 8, 'after_goalkeeper', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 9, 'after_defender', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 10, 'after_midfielder', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 11, 'after_forward', 'smallint', true, NULL, false, '[노출]'),
    ('formation_change', 12, 'reason', 'character varying(200)', false, NULL, false, '[노출]'),
    ('formation_change', 13, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('turn_feed', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('turn_feed', 2, 'season_id', 'bigint', true, NULL, false, '[내부]'),
    ('turn_feed', 3, 'turn_no', 'smallint', true, NULL, false, '[노출]'),
    ('turn_feed', 4, 'seq', 'smallint', true, NULL, false, '[노출]'),
    ('turn_feed', 5, 'kind', 'character varying(6)', true, NULL, false, '[복기 전용]'),
    ('turn_feed', 6, 'asset_id', 'bigint', false, NULL, false, '[노출]'),
    ('turn_feed', 7, 'asset_group_id', 'bigint', false, NULL, false, '[노출]'),
    ('turn_feed', 8, 'month_event_id', 'bigint', false, NULL, false, '[복기 전용]'),
    ('turn_feed', 9, 'category_hidden', 'boolean', true, 'false', false, '[내부]'),
    ('turn_feed', 10, 'direction', 'character varying(4)', false, NULL, false, '[내부]'),
    ('turn_feed', 11, 'strength', 'smallint', false, NULL, false, '[내부]'),
    ('turn_feed', 12, 'repeat_count', 'smallint', true, '0', false, '[내부]'),
    ('turn_feed', 13, 'template_id', 'character varying(40)', true, NULL, false, '[내부]'),
    ('turn_feed', 14, 'template_vars', 'jsonb', true, '''{}''::jsonb', false, '[내부]'),
    ('turn_feed', 15, 'body', 'character varying(300)', true, NULL, false, '[노출]'),
    ('turn_feed', 16, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('reconciliation_log', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('reconciliation_log', 2, 'season_id', 'bigint', true, NULL, false, '[내부]'),
    ('reconciliation_log', 3, 'turn_no', 'smallint', true, NULL, false, '[내부]'),
    ('reconciliation_log', 4, 'attempt_no', 'smallint', true, NULL, false, '[내부]'),
    ('reconciliation_log', 5, 'check_code', 'character varying(20)', true, NULL, false, '[내부]'),
    ('reconciliation_log', 6, 'status', 'character varying(8)', true, NULL, false, '[내부]'),
    ('reconciliation_log', 7, 'expected_value', 'numeric(19,4)', false, NULL, false, '[내부]'),
    ('reconciliation_log', 8, 'actual_value', 'numeric(19,4)', false, NULL, false, '[내부]'),
    ('reconciliation_log', 9, 'diff_value', 'numeric(19,4)', false, NULL, false, '[내부]'),
    ('reconciliation_log', 10, 'mismatch_count', 'integer', true, NULL, false, '[내부]'),
    ('reconciliation_log', 11, 'classification', 'character varying(11)', false, NULL, false, '[내부]'),
    ('reconciliation_log', 12, 'detail', 'jsonb', false, NULL, false, '[내부]'),
    ('reconciliation_log', 13, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]');
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

-- 테이블의 행 수. 0절·8절이 17개 테이블이 비어 있는지 셀 때 쓴다 (테이블 이름은 식별자로 인용한다).
CREATE FUNCTION pg_temp.row_count(p_tbl text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_n bigint;
BEGIN
    EXECUTE format('SELECT count(*) FROM public.%I', p_tbl) INTO v_n;
    RETURN v_n;
END $$;

-- 0 전제 -------------------------------------------------------------------
-- 하나라도 FAIL이면 1절 이후 결과를 믿지 않는다.
-- 17개 = 이 파일의 9개 + 01의 8개. 픽스처가 01 테이블 4개에도 행을 넣으므로 17개가 모두 비어 있어야 한다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('17개 이름이 모두 public으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '17',
        (SELECT count(*)::text FROM unnest(ARRAY['member', 'season', 'season_path', 'turn', 'turn_price', 'lineup', 'formation_change', 'turn_feed', 'reconciliation_log',
                                                 'asset_group', 'asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention']) AS n
         WHERE to_regclass(n) IS NOT NULL AND to_regclass(n) = to_regclass('public.' || n))),
    ('public에 17개 테이블이 있다 (01의 8 + 02의 9)', '17',
        (SELECT count(*)::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'r'
           AND c.relname IN ('member', 'season', 'season_path', 'turn', 'turn_price', 'lineup', 'formation_change', 'turn_feed', 'reconciliation_log',
                             'asset_group', 'asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention'))),
    ('public의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text)
) AS x(item, expected, actual);

-- 17개 테이블이 비어 있다 (비어 있지 않으면 중복 키 같은 우연한 충돌이 생긴다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', '17개 테이블의 행 수 합', '0', s.n::text, s.n = 0
FROM (SELECT COALESCE(sum(pg_temp.row_count(t)), 0) AS n
      FROM unnest(ARRAY['member', 'season', 'season_path', 'turn', 'turn_price', 'lineup', 'formation_change', 'turn_feed', 'reconciliation_log',
                        'asset_group', 'asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention']) AS t
      WHERE to_regclass('public.' || t) IS NOT NULL) AS s;

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
        RAISE EXCEPTION 'P0-06 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 전제를 맞춘 뒤 다시 실행한다)', v_fail;
    END IF;
END $$;

-- 1 구조 -------------------------------------------------------------------
-- 모든 카탈로그 조회는 이 9개 테이블만 본다. 종류는 contype IN ('p','u','f','c')로 고정한다.
-- 제약이 아닌 유일 인덱스(UQI)는 pg_index에서 따로 센다.

-- 1.1 테이블별 개수 (erd 6.4 개수 표). 유일 인덱스(uqi)는 제약에 딸리지 않은 UNIQUE 인덱스다.
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

-- 1.2 인덱스 총 개수 (PK 9 + UQ 7 + UQI 2 + idx_ 9 = 27)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '9개 테이블의 인덱스 총 개수 (PK + UQ + UQI + idx_)', '27', n::text, n = 27
FROM (SELECT count(*) AS n FROM pg_indexes WHERE schemaname = 'public' AND tablename IN (SELECT tbl FROM zz_expect_count)) s;

-- 1.3 테이블 코멘트 (논리명)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '테이블 코멘트: ' || e.tbl, e.tbl_comment, obj_description(to_regclass('public.' || e.tbl), 'pg_class'),
       e.tbl_comment IS NOT DISTINCT FROM obj_description(to_regclass('public.' || e.tbl), 'pg_class')
FROM zz_expect_count e
ORDER BY e.tbl;

-- 1.4 제약·인덱스 101개의 이름·종류·테이블이 1:1 (빠진 것도 남는 것도 한 행씩 FAIL)
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

-- 1.5 CHECK 60개의 정의 — 왕복 정규화
--     erd 6.4의 식을 빈 임시 사본(LIKE: 컬럼 이름·타입·NOT NULL만 복사)에 CHECK로 붙여 PostgreSQL이 다시 파싱하게 하고,
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

-- 1.7 FK 14개: 컬럼 → 대상 테이블.컬럼 (erd의 (R9) 꼬리표는 뗀다), 동작 RESTRICT·RESTRICT, 지연 없음, MATCH SIMPLE
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 컬럼·대상: ' || e.name, regexp_replace(e.def, ' \(R[0-9]+\)$', ''), a.def,
       regexp_replace(e.def, ' \(R[0-9]+\)$', '') IS NOT DISTINCT FROM a.def
FROM zz_expect_constraint e
LEFT JOIN LATERAL (
    SELECT '(' || (SELECT string_agg(att.attname, ', ' ORDER BY k.ord)
                   FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                   JOIN pg_attribute att ON att.attrelid = c.conrelid AND att.attnum = k.attnum)
           || ') → ' || (SELECT relname FROM pg_class WHERE oid = c.confrelid) || '.'
           || (SELECT string_agg(att.attname, ', ' ORDER BY k.ord)
               FROM unnest(c.confkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN pg_attribute att ON att.attrelid = c.confrelid AND att.attnum = k.attnum) AS def
    FROM pg_constraint c
    WHERE c.conrelid = to_regclass('public.' || e.tbl) AND c.conname = e.name AND c.contype = 'f'
) a ON true
WHERE e.kind = 'FK'
ORDER BY e.name;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 동작: ' || e.name, 'update=r delete=r deferrable=false match=s',
       format('update=%s delete=%s deferrable=%s match=%s', c.confupdtype, c.confdeltype, c.condeferrable::text, c.confmatchtype),
       (c.confupdtype, c.confdeltype, c.condeferrable, c.confmatchtype) = ('r', 'r', false, 's')
FROM zz_expect_constraint e
LEFT JOIN pg_constraint c ON c.conrelid = to_regclass('public.' || e.tbl) AND c.conname = e.name AND c.contype = 'f'
WHERE e.kind = 'FK'
ORDER BY e.name;

-- 1.8 인덱스 11개(idx_ 9 + 유일 인덱스 2)의 정의 — 왕복 정규화
--     erd 정의 칸(예: (lower(email)), (season_id, asset_id) WHERE end_turn IS NULL)을 1.5가 만든 빈 사본에
--     CREATE [UNIQUE] INDEX로 붙여 PostgreSQL이 다시 파싱하게 하고, 그 pg_get_indexdef의 ' USING …' 뒤를
--     실제 pg_indexes.indexdef 전체와 비교한다. UNIQUE·NULLS NOT DISTINCT·INCLUDE·WHERE가 모두 문자열에 들어 있어
--     덧붙은 속성까지 잡히고, lower((email)::text) 같은 카탈로그 표기 차이에 흔들리지 않는다.
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

-- 1.9 FK 인덱스 짝: 모든 FK의 첫 컬럼이 같은 테이블 어떤 **전체 인덱스**의 첫 컬럼이다 (erd 2절, 14/14)
--     부분 인덱스(indpred)와 표현식 인덱스(indkey[0] = 0)는 짝으로 치지 않는다. uq_lineup_open은 열린 행만 담아
--     FK 인덱스를 대신하지 못한다 (erd 6.3 lineup, D26).
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

-- 1.10 컬럼 105개: 순서·이름·타입·NULL·기본값·identity(ALWAYS)·노출 태그 (erd 6.3)
--      노출 태그는 컬럼 코멘트 앞머리 `[노출]`/`[복기 전용]`/`[내부]`다. 형식이 다르면 태그가 NULL이 되어 FAIL이다.
--      기본값 칸이 비어 있는 컬럼은 DB 기본값이 없다는 뜻이다 (특히 season의 D30 컬럼 8개).
INSERT INTO zz_check_result (section, item, expected, actual, ok)
WITH act AS (
    SELECT c.relname::text AS tbl,
           (row_number() OVER (PARTITION BY c.relname ORDER BY a.attnum))::int AS pos,
           a.attname::text AS col,
           format_type(a.atttypid, a.atttypmod) AS typ,
           a.attnotnull AS nn,
           pg_get_expr(d.adbin, d.adrelid) AS dflt,
           (a.attidentity = 'a') AS ident,
           substring(col_description(a.attrelid, a.attnum) from '^(\[(?:노출|복기 전용|내부)\]) .+$') AS tag
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind = 'r'
      AND c.relname IN (SELECT tbl FROM zz_expect_count)
)
SELECT '1 구조', '컬럼: ' || COALESCE(e.tbl, a.tbl) || ' #' || COALESCE(e.pos, a.pos),
       CASE WHEN e.tbl IS NULL THEN '(없어야 함)'
            ELSE format('%s | %s | nn=%s | default=%s | identity=%s | %s', e.col, e.typ, e.nn, COALESCE(e.dflt, '-'), e.ident, e.tag) END,
       CASE WHEN a.tbl IS NULL THEN '(없음)'
            ELSE format('%s | %s | nn=%s | default=%s | identity=%s | %s', a.col, a.typ, a.nn, COALESCE(a.dflt, '-'), a.ident, a.tag) END,
       e.tbl IS NOT NULL AND a.tbl IS NOT NULL
       AND (e.col, e.typ, e.nn, e.dflt, e.ident, e.tag) IS NOT DISTINCT FROM (a.col, a.typ, a.nn, a.dflt, a.ident, a.tag)
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

-- 1.12 CHECK·FK가 모두 검증된 상태(NOT VALID 없음), FK 내부 트리거가 모두 켜져 있고(14 × 4 = 56),
--      인덱스 27개가 모두 유효하다 (indisvalid·indisready — CREATE INDEX가 중간에 끊긴 흔적이 없다)
--      트리거는 FK의 소유 테이블(conrelid)이 이 9개인 것만 센다. 02 → 01 FK가 01 테이블에 만든 부모 쪽 트리거도 여기서 센다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'NOT VALID 제약 수 (CHECK·FK)', '0', n::text, n = 0
FROM (SELECT count(*) AS n FROM pg_constraint
      WHERE contype IN ('c', 'f') AND NOT convalidated
        AND conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)) s;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 내부 트리거 (전체 / 켜져 있지 않은 것)', 'total=56 not_enabled=0',
       format('total=%s not_enabled=%s', s.total, s.off), (s.total, s.off) = (56, 0)
FROM (SELECT count(*) AS total, count(*) FILTER (WHERE t.tgenabled <> 'O') AS off
      FROM pg_trigger t JOIN pg_constraint c ON c.oid = t.tgconstraint
      WHERE c.contype = 'f' AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)) s;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '인덱스 (전체 / 유효하지 않거나 준비되지 않은 것)', 'total=27 invalid=0',
       format('total=%s invalid=%s', s.total, s.bad), (s.total, s.bad) = (27, 0)
FROM (SELECT count(*) AS total, count(*) FILTER (WHERE NOT (i.indisvalid AND i.indisready)) AS bad
      FROM pg_index i
      WHERE i.indrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)) s;


-- 2 양성 (과잉 제약이 없다는 증거) ---------------------------------------------
-- 여기서 만든 행이 뒤 절의 픽스처다. 자연 키는 ZZ_ / ZZ 접두라 다른 데이터와 겹치지 않고, 끝의 ROLLBACK이 지운다.
-- 01의 4개 테이블(asset_group, asset, month_block, month_event)에도 행을 넣는다 — 02의 FK 부모다.
-- 기준 행(3절이 UPDATE 한 컬럼으로 어기는 행): 시즌 S0(seed 100), 그 시즌의 경로 턴 1, 턴 1 스냅샷, 턴 0 가격, 열린 라인업,
--   턴 2 포메이션 변경, 턴 1 피드(RISE), 턴 3 대사 기록(CASH_SUM). 기준 행은 모두 유효하며 2절이 그것을 증명한다.
-- 시즌은 seed로 찾는다: S0 = 100, 양성 사례 201–211, RESTRICT용 S1–S7 = 101–107, 경계용 B = 400.

-- 01 테이블 (FK 부모)
CALL pg_temp.check_ok('2 양성', '01 픽스처: asset_group ZZ_GROUP (종목 있음), ZZ_EMPTY (종목 없음, 피드만 가리킨다)',
    $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_GROUP', 'zz 업종', 'SECTOR'), ('ZZ_EMPTY', 'zz 빈 업종', 'SECTOR')$q$);
CALL pg_temp.check_ok('2 양성', '01 픽스처: asset ZZ0001 (KR), ZZAAPL (US), ZZDEL1–3 (RESTRICT용 KR)',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       SELECT CASE WHEN t = 'ZZAAPL' THEN 'US' ELSE 'KR' END, t, 'zz ' || t, 'STOCK',
              CASE WHEN t = 'ZZAAPL' THEN 'USD' ELSE 'KRW' END, (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03',
              CASE WHEN t = 'ZZAAPL' THEN 'TOTAL' ELSE 'PRICE' END
       FROM unnest(ARRAY['ZZ0001', 'ZZAAPL', 'ZZDEL1', 'ZZDEL2', 'ZZDEL3']) AS t$q$);
CALL pg_temp.check_ok('2 양성', '01 픽스처: month_block 1990-01, 1990-02, 1991-01 (경로만 가리키는 달)',
    $q$INSERT INTO month_block (month_start) VALUES ('1990-01-01'), ('1990-02-01'), ('1991-01-01')$q$);
CALL pg_temp.check_ok('2 양성', '01 픽스처: month_event ZZ_EV1 (1990-01)',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source)
       VALUES ('ZZ_EV1', (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 'POLITICS', 'US_MIDTERM', 'START', 2, 'zz 사건', 'zz 출처')$q$);

-- member
CALL pg_temp.check_ok('2 양성', 'member: 기준 회원 zz_a@example.com',
    $q$INSERT INTO member (email, name, password) VALUES ('zz_a@example.com', 'zz 회원', 'zz-hash')$q$);
CALL pg_temp.check_ok('2 양성', 'member: 다른 이메일 회원 zz_other@example.com',
    $q$INSERT INTO member (email, name, password) VALUES ('zz_other@example.com', 'zz 다른 회원', 'zz-hash')$q$);
CALL pg_temp.check_eq('2 양성', 'member 기본값: created_at·updated_at이 채워진다', 'true',
    pg_temp.val($v$SELECT (created_at IS NOT NULL AND updated_at IS NOT NULL)::text FROM member WHERE email = 'zz_a@example.com'$v$));

-- season: 기준 시즌 S0 (경로 10, 현재 턴 3, 포메이션 10·30·30·30 / 10·30·30·30, 위험 -0.20, 국내 수수료 0.00015·미국 수수료 0.0025·환전 비용 0.001, 초기 자금 30,000,000)
CALL pg_temp.check_ok('2 양성', 'season: 기준 시즌 S0 (IN_PROGRESS, 경로 10, 현재 턴 3, 포메이션 10·30·30·30, 위험 -0.20)',
    $q$INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version, data_snapshot_date, base_date,
                           initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder, start_forward,
                           current_goalkeeper, current_defender, current_midfielder, current_forward)
       VALUES ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'IN_PROGRESS', 10, 3, NULL, 100, 'p0-sql', '2025-01-01', '2025-04-02',
               30000000, 0.00015, 0.0025, 0.001, -0.20, 10, 30, 30, 30, 10, 30, 30, 30)$q$);
CALL pg_temp.check_ok('2 양성', 'season: 포메이션 경계 100·0·0·0 과 1·0·0·99 (시작·현재 모두)',
    $q$INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version, data_snapshot_date, base_date,
                           initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder, start_forward,
                           current_goalkeeper, current_defender, current_midfielder, current_forward)
       VALUES ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'IN_PROGRESS', 10, 0, NULL, 201, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.20, 100, 0, 0, 0, 100, 0, 0, 0),
              ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'IN_PROGRESS', 10, 0, NULL, 202, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.20, 1, 0, 0, 99, 1, 0, 0, 99)$q$);
CALL pg_temp.check_ok('2 양성', 'season: FINISHED + 현재 턴 = 경로 길이, IN_PROGRESS + 현재 턴 = 경로 길이',
    $q$INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version, data_snapshot_date, base_date,
                           initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder, start_forward,
                           current_goalkeeper, current_defender, current_midfielder, current_forward)
       VALUES ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'FINISHED', 10, 10, NULL, 203, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.20, 10, 30, 30, 30, 10, 30, 30, 30),
              ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'IN_PROGRESS', 10, 10, NULL, 204, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.20, 10, 30, 30, 30, 10, 30, 30, 30)$q$);
CALL pg_temp.check_ok('2 양성', 'season: ABANDONING + 포기 턴 2 (현재 3), FINISHED + 포기 턴 4 (FINISHED는 양쪽 허용)',
    $q$INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version, data_snapshot_date, base_date,
                           initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder, start_forward,
                           current_goalkeeper, current_defender, current_midfielder, current_forward)
       VALUES ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'ABANDONING', 10, 3, 2, 205, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.20, 10, 30, 30, 30, 10, 30, 30, 30),
              ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'FINISHED', 10, 10, 4, 206, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.20, 10, 30, 30, 30, 10, 30, 30, 30)$q$);
CALL pg_temp.check_ok('2 양성', 'season: 위험 선언 -0.10·-0.30·-0.40 (기준 시즌이 -0.20), 요율 세 컬럼 모두 0',
    $q$INSERT INTO season (member_id, status, path_length, current_turn_no, abandoned_turn_no, seed, engine_version, data_snapshot_date, base_date,
                           initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit, start_goalkeeper, start_defender, start_midfielder, start_forward,
                           current_goalkeeper, current_defender, current_midfielder, current_forward)
       VALUES ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'IN_PROGRESS', 10, 0, NULL, 207, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0, 0, 0, -0.10, 10, 30, 30, 30, 10, 30, 30, 30),
              ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'IN_PROGRESS', 10, 0, NULL, 208, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.30, 10, 30, 30, 30, 10, 30, 30, 30),
              ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 'IN_PROGRESS', 10, 0, NULL, 209, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.40, 10, 30, 30, 30, 10, 30, 30, 30)$q$);
CALL pg_temp.check_ok('2 양성', 'season: 기본값이 있는 컬럼(status, current_turn_no, version)을 생략한 행',
    $q$INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                           start_goalkeeper, start_defender, start_midfielder, start_forward,
                           current_goalkeeper, current_defender, current_midfielder, current_forward)
       VALUES ((SELECT id FROM member WHERE email = 'zz_a@example.com'), 10, 211, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.20, 10, 30, 30, 30, 10, 30, 30, 30)$q$);
CALL pg_temp.check_eq('2 양성', 'season 기본값: status = IN_PROGRESS', 'IN_PROGRESS', pg_temp.val($v$SELECT status FROM season WHERE seed = 211$v$));
CALL pg_temp.check_eq('2 양성', 'season 기본값: current_turn_no = 0', '0', pg_temp.val($v$SELECT current_turn_no::text FROM season WHERE seed = 211$v$));
CALL pg_temp.check_eq('2 양성', 'season 기본값: version = 0', '0', pg_temp.val($v$SELECT version::text FROM season WHERE seed = 211$v$));
CALL pg_temp.check_eq('2 양성', 'season 기본값: abandoned_turn_no는 NULL', 'true', pg_temp.val($v$SELECT (abandoned_turn_no IS NULL)::text FROM season WHERE seed = 211$v$));

-- season_path
CALL pg_temp.check_ok('2 양성', 'season_path: S0 턴 1 = 1990-01 (기준 행), 턴 2 = 1990-02, 턴 3 = 1990-01 (같은 블록을 다른 턴에 배정)',
    $q$INSERT INTO season_path (season_id, turn_no, month_block_id)
       VALUES ((SELECT id FROM season WHERE seed = 100), 1, (SELECT id FROM month_block WHERE month_start = '1990-01-01')), ((SELECT id FROM season WHERE seed = 100), 2, (SELECT id FROM month_block WHERE month_start = '1990-02-01')), ((SELECT id FROM season WHERE seed = 100), 3, (SELECT id FROM month_block WHERE month_start = '1990-01-01'))$q$);
CALL pg_temp.check_ok('2 양성', 'season_path: 다른 시즌 201의 턴 1 = 1990-01 (같은 블록을 다른 시즌에 배정)',
    $q$INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES ((SELECT id FROM season WHERE seed = 201), 1, (SELECT id FROM month_block WHERE month_start = '1990-01-01'))$q$);

-- turn (기준 스냅샷: 현금 1,000,000 · 보유 9,000,000 · 총액 10,000,000 · 골키퍼 2,500,000 · 수비수 3,000,000 · 미드필더 2,500,000 · 공격수 2,000,000 · 낙폭 -0.05)
CALL pg_temp.check_ok('2 양성', 'turn: S0 턴 1 기준 스냅샷',
    $q$INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown)
       VALUES ((SELECT id FROM season WHERE seed = 100), 1, 1000000, 9000000, 10000000, 2500000, 3000000, 2500000, 2000000, -0.05)$q$);
CALL pg_temp.check_ok('2 양성', 'turn: 낙폭 0 + 골키퍼 평가액 = 현금 (골키퍼 종목 없음, 전액 현금)',
    $q$INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown)
       VALUES ((SELECT id FROM season WHERE seed = 100), 2, 10000000, 0, 10000000, 10000000, 0, 0, 0, 0)$q$);
CALL pg_temp.check_ok('2 양성', 'turn: 낙폭 -1 (전액 손실, 모든 평가액 0)',
    $q$INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown)
       VALUES ((SELECT id FROM season WHERE seed = 100), 3, 0, 0, 0, 0, 0, 0, 0, -1)$q$);

-- turn_price
CALL pg_temp.check_ok('2 양성', 'turn_price: S0 턴 0 가격 (기준 행), 같은 (시즌, 턴)에 다른 종목, 다음 턴',
    $q$INSERT INTO turn_price (season_id, turn_no, asset_id, price)
       VALUES ((SELECT id FROM season WHERE seed = 100), 0, (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 70000), ((SELECT id FROM season WHERE seed = 100), 0, (SELECT id FROM asset WHERE ticker = 'ZZAAPL'), 290000), ((SELECT id FROM season WHERE seed = 100), 1, (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 71000)$q$);

-- lineup (기준 행: ZZ0001 MIDFIELDER 열린 구간, 시작 턴 0)
CALL pg_temp.check_ok('2 양성', 'lineup: S0 × ZZ0001 열린 구간 (기준 행, end_turn NULL)',
    $q$INSERT INTO lineup (season_id, asset_id, declared_position, start_turn) VALUES ((SELECT id FROM season WHERE seed = 100), (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 'MIDFIELDER', 0)$q$);
CALL pg_temp.check_ok('2 양성', 'lineup: 같은 종목의 닫힌 구간 여럿 + end_turn = start_turn (D26 허용)',
    $q$INSERT INTO lineup (season_id, asset_id, declared_position, start_turn, end_turn)
       VALUES ((SELECT id FROM season WHERE seed = 100), (SELECT id FROM asset WHERE ticker = 'ZZAAPL'), 'FORWARD_TARGET', 1, 1), ((SELECT id FROM season WHERE seed = 100), (SELECT id FROM asset WHERE ticker = 'ZZAAPL'), 'DEFENDER', 2, 3)$q$);
CALL pg_temp.check_eq('2 양성', 'lineup 기본값: created_at·updated_at이 채워진다', 'true',
    pg_temp.val($v$SELECT (min(created_at) IS NOT NULL AND min(updated_at) IS NOT NULL)::text FROM lineup$v$));

-- formation_change (기준 행: 턴 2, 10·30·30·30 → 20·30·30·20)
CALL pg_temp.check_ok('2 양성', 'formation_change: S0 턴 2 기준 변경 (10·30·30·30 → 20·30·30·20, 사유 있음)',
    $q$INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                     after_goalkeeper, after_defender, after_midfielder, after_forward, reason)
       VALUES ((SELECT id FROM season WHERE seed = 100), 2, 10, 30, 30, 30, 20, 30, 30, 20, 'zz 사유')$q$);
CALL pg_temp.check_ok('2 양성', 'formation_change: 같은 턴의 두 번째 변경, 골키퍼는 그대로이고 나머지만 바뀐 변경, 사유 NULL',
    $q$INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                     after_goalkeeper, after_defender, after_midfielder, after_forward)
       VALUES ((SELECT id FROM season WHERE seed = 100), 2, 20, 30, 30, 20, 20, 40, 20, 20)$q$);
CALL pg_temp.check_eq('2 양성', 'formation_change: reason을 생략하면 NULL', 'true',
    pg_temp.val($v$SELECT (min(reason) IS NULL)::text FROM formation_change WHERE after_defender = 40$v$));

-- turn_feed (기준 행: 턴 1 seq 1 RISE + 종목 ZZ0001, 방향 UP, 강도 2, template_id zz_base)
CALL pg_temp.check_ok('2 양성', 'turn_feed: S0 턴 1 기준 행 (RISE + 종목, UP, 강도 2)',
    $q$INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, direction, strength, template_id, body)
       VALUES ((SELECT id FROM season WHERE seed = 100), 1, 1, 'RISE', (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 'UP', 2, 'zz_base', 'zz 문장')$q$);
CALL pg_temp.check_ok('2 양성', 'turn_feed: 종류별 올바른 대상 — FALL(종목), ECHO(종목, repeat 1), FADE(그룹), SPREAD(그룹), RISE(그룹)',
    $q$INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, asset_group_id, repeat_count, template_id, body)
       VALUES ((SELECT id FROM season WHERE seed = 100), 2, 1, 'FALL', (SELECT id FROM asset WHERE ticker = 'ZZAAPL'), NULL, 0, 'zz_t', 'zz'),
              ((SELECT id FROM season WHERE seed = 100), 2, 2, 'ECHO', (SELECT id FROM asset WHERE ticker = 'ZZ0001'), NULL, 1, 'zz_t', 'zz'),
              ((SELECT id FROM season WHERE seed = 100), 2, 3, 'FADE', NULL, (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), 0, 'zz_t', 'zz'),
              ((SELECT id FROM season WHERE seed = 100), 2, 4, 'SPREAD', NULL, (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), 0, 'zz_t', 'zz'),
              ((SELECT id FROM season WHERE seed = 100), 2, 5, 'RISE', NULL, (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), 0, 'zz_t', 'zz')$q$);
CALL pg_temp.check_ok('2 양성', 'turn_feed: EVENT(대상 없음 + 사건 태그), EVENT + category_hidden TRUE, NUDGE(대상 없음), NUDGE + 종목, direction·strength NULL',
    $q$INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, month_event_id, category_hidden, template_id, body)
       VALUES ((SELECT id FROM season WHERE seed = 100), 3, 1, 'EVENT', NULL, (SELECT id FROM month_event WHERE event_key = 'ZZ_EV1'), false, 'zz_t', 'zz'),
              ((SELECT id FROM season WHERE seed = 100), 3, 2, 'EVENT', NULL, (SELECT id FROM month_event WHERE event_key = 'ZZ_EV1'), true, 'zz_t', 'zz'),
              ((SELECT id FROM season WHERE seed = 100), 3, 3, 'NUDGE', NULL, NULL, false, 'zz_t', 'zz'),
              ((SELECT id FROM season WHERE seed = 100), 3, 4, 'NUDGE', (SELECT id FROM asset WHERE ticker = 'ZZ0001'), NULL, false, 'zz_t', 'zz')$q$);
CALL pg_temp.check_eq('2 양성', 'turn_feed 기본값: template_vars = {}', '{}', pg_temp.val($v$SELECT template_vars::text FROM turn_feed WHERE template_id = 'zz_base'$v$));
CALL pg_temp.check_eq('2 양성', 'turn_feed 기본값: category_hidden = false, repeat_count = 0', 'false 0',
    pg_temp.val($v$SELECT category_hidden::text || ' ' || repeat_count::text FROM turn_feed WHERE template_id = 'zz_base'$v$));
CALL pg_temp.check_ok('2 양성', 'turn_feed: template_vars에 객체 값',
    $q$INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, template_id, template_vars, body)
       VALUES ((SELECT id FROM season WHERE seed = 100), 4, 1, 'RISE', (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 'zz_t', '{"name": "zz", "rank": 3}', 'zz')$q$);

-- reconciliation_log (기준 행: 턴 3 시도 1 CASH_SUM MISMATCH, 기대 100 · 실제 90 · 차이 -10, 불일치 1, INVESTIGATE)
CALL pg_temp.check_ok('2 양성', 'reconciliation_log: S0 턴 3 기준 행 (MISMATCH, 100/90/-10, INVESTIGATE, detail 있음)',
    $q$INSERT INTO reconciliation_log (season_id, turn_no, attempt_no, check_code, status, expected_value, actual_value, diff_value, mismatch_count, classification, detail)
       VALUES ((SELECT id FROM season WHERE seed = 100), 3, 1, 'CASH_SUM', 'MISMATCH', 100, 90, -10, 1, 'INVESTIGATE', '{"rows": [1, 2]}')$q$);
CALL pg_temp.check_ok('2 양성', 'reconciliation_log: MATCH + 값 3개 NULL(스칼라가 아닌 규칙), MATCH + 차이 0',
    $q$INSERT INTO reconciliation_log (season_id, turn_no, attempt_no, check_code, status, expected_value, actual_value, diff_value, mismatch_count, classification)
       VALUES ((SELECT id FROM season WHERE seed = 100), 3, 1, 'LEDGER_CHAIN', 'MATCH', NULL, NULL, NULL, 0, NULL),
              ((SELECT id FROM season WHERE seed = 100), 3, 1, 'VALUATION', 'MATCH', 100, 100, 0, 0, NULL)$q$);
CALL pg_temp.check_ok('2 양성', 'reconciliation_log: MISMATCH + 차이 0 (역방향은 강제하지 않는다 — 항목별 상쇄), 분류 TIMING',
    $q$INSERT INTO reconciliation_log (season_id, turn_no, attempt_no, check_code, status, expected_value, actual_value, diff_value, mismatch_count, classification)
       VALUES ((SELECT id FROM season WHERE seed = 100), 3, 1, 'FEE_RULE', 'MISMATCH', 5, 5, 0, 1, 'TIMING')$q$);
CALL pg_temp.check_ok('2 양성', 'reconciliation_log: 같은 턴 다른 시도 (CASH_SUM, 시도 2, 분류 ADJUSTMENT), 다른 코드',
    $q$INSERT INTO reconciliation_log (season_id, turn_no, attempt_no, check_code, status, expected_value, actual_value, diff_value, mismatch_count, classification)
       VALUES ((SELECT id FROM season WHERE seed = 100), 3, 2, 'CASH_SUM', 'MISMATCH', 100, 95, -5, 1, 'ADJUSTMENT'),
              ((SELECT id FROM season WHERE seed = 100), 3, 2, 'ORDER_FILL', 'MATCH', NULL, NULL, NULL, 0, NULL)$q$);


-- 3 음성 DML (실패해야 정상) -----------------------------------------------------
-- 사례마다 유효한 기준 행에서 한 컬럼(또는 한 조건)만 어긴다. 한 행이 CHECK를 여러 개 어기면 NOT NULL(23502)이 먼저, 그다음 CHECK를
-- 제약 이름의 알파벳 순으로 검사해 첫 위반 하나만 보고한다. 둘 이상이 걸리는 사례는 주석에 먼저 보고되는 이름을 적었다.
-- ★ 표시는 erd 9절 P0-06 행(또는 기능 표 §10)이 지정한 사례다.
-- RESTRICT 사례는 자식 테이블이 하나뿐인 부모를 쓴다 (자식이 둘 이상이면 어느 FK가 먼저 보고될지 이름에 기대게 된다).

-- 3.1 member
CALL pg_temp.check_err('3 음성', 'member ★ 이메일 대소문자만 다른 두 번째 회원 ZZ_A@EXAMPLE.COM (기존 zz_a@example.com)',
    $q$INSERT INTO member (email, name, password) VALUES ('ZZ_A@EXAMPLE.COM', 'x', 'x')$q$, '23505', 'uq_member_email');
CALL pg_temp.check_err('3 음성', 'member: 같은 이메일 zz_a@example.com 그대로',
    $q$INSERT INTO member (email, name, password) VALUES ('zz_a@example.com', 'x', 'x')$q$, '23505', 'uq_member_email');
CALL pg_temp.check_err('3 음성', 'member: email NULL', $q$INSERT INTO member (email, name, password) VALUES (NULL, 'x', 'x')$q$, '23502', 'email');
CALL pg_temp.check_err('3 음성', 'member: name NULL', $q$INSERT INTO member (email, name, password) VALUES ('zz_n@example.com', NULL, 'x')$q$, '23502', 'name');
CALL pg_temp.check_err('3 음성', 'member: password NULL', $q$INSERT INTO member (email, name, password) VALUES ('zz_n@example.com', 'x', NULL)$q$, '23502', 'password');
CALL pg_temp.check_err('3 음성', 'member: id를 직접 지정 (GENERATED ALWAYS)', $q$INSERT INTO member (id, email, name, password) VALUES (999999, 'zz_n@example.com', 'x', 'x')$q$, '428C9');

-- 3.2 season (기준 행 S0에 UPDATE 한 컬럼. 포메이션 10·30·30·30, 현재 턴 3 / 경로 10, 위험 -0.20)
CALL pg_temp.check_err('3 음성', 'season ★ 시작 포메이션 합 99 (10·30·30·29)',
    $q$UPDATE season SET start_forward = 29 WHERE seed = 100$q$, '23514', 'ck_season_start_formation_sum');
CALL pg_temp.check_err('3 음성', 'season ★ 시작 골키퍼 0 (0·40·30·30 — 합 100, 범위 안)',
    $q$UPDATE season SET start_goalkeeper = 0, start_defender = 40 WHERE seed = 100$q$, '23514', 'ck_season_start_goalkeeper_min');
-- 범위 -10은 수비수에 넣는다: 골키퍼에 넣으면 goalkeeper_min도 어겨 알파벳 순으로 formation_range가 먼저 보고되지만 의도가 흐려진다.
-- 상한(101)만 어기는 사례는 합 100이면 다른 값이 음수가 되어 만들 수 없으므로 하한 사례로 대신한다.
CALL pg_temp.check_err('3 음성', 'season ★ 시작 포메이션 범위 -10 (10·-10·50·50 — 합 100)',
    $q$UPDATE season SET start_defender = -10, start_midfielder = 50, start_forward = 50 WHERE seed = 100$q$, '23514', 'ck_season_start_formation_range');
CALL pg_temp.check_err('3 음성', 'season: 현재 포메이션 합 99 (10·30·30·29)',
    $q$UPDATE season SET current_forward = 29 WHERE seed = 100$q$, '23514', 'ck_season_current_formation_sum');
CALL pg_temp.check_err('3 음성', 'season: 현재 골키퍼 0 (0·40·30·30)',
    $q$UPDATE season SET current_goalkeeper = 0, current_defender = 40 WHERE seed = 100$q$, '23514', 'ck_season_current_goalkeeper_min');
CALL pg_temp.check_err('3 음성', 'season: 현재 포메이션 범위 -10 (10·-10·50·50 — 합 100)',
    $q$UPDATE season SET current_defender = -10, current_midfielder = 50, current_forward = 50 WHERE seed = 100$q$, '23514', 'ck_season_current_formation_range');
CALL pg_temp.check_err('3 음성', 'season ★ 위험 선언 -0.25', $q$UPDATE season SET risk_limit = -0.25 WHERE seed = 100$q$, '23514', 'ck_season_risk_limit');
CALL pg_temp.check_err('3 음성', 'season: 위험 선언 +0.20 (부호가 반대)', $q$UPDATE season SET risk_limit = 0.20 WHERE seed = 100$q$, '23514', 'ck_season_risk_limit');
CALL pg_temp.check_err('3 음성', 'season: 상태 DONE', $q$UPDATE season SET status = 'DONE' WHERE seed = 100$q$, '23514', 'ck_season_status');
CALL pg_temp.check_err('3 음성', 'season: 상태 소문자 in_progress', $q$UPDATE season SET status = 'in_progress' WHERE seed = 100$q$, '23514', 'ck_season_status');
-- path_length -1이면 current_turn_no BETWEEN 0 AND -1도 어겨 알파벳 순으로 ck_season_current_turn_no가 먼저 보고된다. 0과 현재 턴 0으로 path_length만 어긴다.
CALL pg_temp.check_err('3 음성', 'season: 경로 길이 0 (현재 턴 0)',
    $q$UPDATE season SET path_length = 0, current_turn_no = 0 WHERE seed = 100$q$, '23514', 'ck_season_path_length');
CALL pg_temp.check_err('3 음성', 'season: 현재 턴 11 (경로 10)', $q$UPDATE season SET current_turn_no = 11 WHERE seed = 100$q$, '23514', 'ck_season_current_turn_no');
CALL pg_temp.check_err('3 음성', 'season: 현재 턴 -1', $q$UPDATE season SET current_turn_no = -1 WHERE seed = 100$q$, '23514', 'ck_season_current_turn_no');
CALL pg_temp.check_err('3 음성', 'season: FINISHED인데 현재 턴 9 (경로 10)',
    $q$UPDATE season SET status = 'FINISHED', current_turn_no = 9 WHERE seed = 100$q$, '23514', 'ck_season_finished_turn');
CALL pg_temp.check_err('3 음성', 'season ★ IN_PROGRESS인데 포기 턴 2',
    $q$UPDATE season SET abandoned_turn_no = 2 WHERE seed = 100$q$, '23514', 'ck_season_abandoned_status');
CALL pg_temp.check_err('3 음성', 'season ★ ABANDONING + 포기 턴 4 > 현재 턴 3 (기능 표 §10 재기준: 새 CHECK가 아니라 이 CHECK)',
    $q$UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 4 WHERE seed = 100$q$, '23514', 'ck_season_abandoned_turn_no');
CALL pg_temp.check_err('3 음성', 'season: ABANDONING + 포기 턴 -1',
    $q$UPDATE season SET status = 'ABANDONING', abandoned_turn_no = -1 WHERE seed = 100$q$, '23514', 'ck_season_abandoned_turn_no');
CALL pg_temp.check_err('3 음성', 'season: ABANDONING인데 포기 턴 NULL',
    $q$UPDATE season SET status = 'ABANDONING' WHERE seed = 100$q$, '23514', 'ck_season_abandoning');
CALL pg_temp.check_err('3 음성', 'season: 초기 자금 0', $q$UPDATE season SET initial_cash = 0 WHERE seed = 100$q$, '23514', 'ck_season_initial_cash_pos');
CALL pg_temp.check_err('3 음성', 'season: 초기 자금 30,000,000.5 (원 단위 아님)', $q$UPDATE season SET initial_cash = 30000000.5 WHERE seed = 100$q$, '23514', 'ck_season_initial_cash_won');
CALL pg_temp.check_err('3 음성', 'season: 국내 수수료율 -0.0001', $q$UPDATE season SET fee_rate_kr = -0.0001 WHERE seed = 100$q$, '23514', 'ck_season_fee_rate_kr');
CALL pg_temp.check_err('3 음성', 'season: 국내 수수료율 1', $q$UPDATE season SET fee_rate_kr = 1 WHERE seed = 100$q$, '23514', 'ck_season_fee_rate_kr');
CALL pg_temp.check_err('3 음성', 'season: 미국 수수료율 -0.0001', $q$UPDATE season SET fee_rate_us = -0.0001 WHERE seed = 100$q$, '23514', 'ck_season_fee_rate_us');
CALL pg_temp.check_err('3 음성', 'season: 미국 수수료율 1', $q$UPDATE season SET fee_rate_us = 1 WHERE seed = 100$q$, '23514', 'ck_season_fee_rate_us');
CALL pg_temp.check_err('3 음성', 'season: 환전 비용률 -0.0001', $q$UPDATE season SET fx_spread_rate = -0.0001 WHERE seed = 100$q$, '23514', 'ck_season_fx_spread_rate');
CALL pg_temp.check_err('3 음성', 'season: 환전 비용률 1', $q$UPDATE season SET fx_spread_rate = 1 WHERE seed = 100$q$, '23514', 'ck_season_fx_spread_rate');
CALL pg_temp.check_err('3 음성', 'season: 없는 회원 999999', $q$UPDATE season SET member_id = 999999 WHERE seed = 100$q$, '23503', 'fk_season_member');
CALL pg_temp.check_err('3 음성', 'season: id를 직접 지정 (GENERATED ALWAYS)',
    $q$INSERT INTO season (id, member_id, path_length, seed, engine_version, data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                           start_goalkeeper, start_defender, start_midfielder, start_forward, current_goalkeeper, current_defender, current_midfielder, current_forward)
       VALUES (999999, (SELECT id FROM member WHERE email = 'zz_a@example.com'), 10, 300, 'p0-sql', '2025-01-01', '2025-04-02', 30000000, 0.00015, 0.0025, 0.001, -0.20, 10, 30, 30, 30, 10, 30, 30, 30)$q$, '428C9');
-- D30: 시즌에 박아 두는 값 8개는 DB 기본값이 없다. 기준 시즌을 복사하되 컬럼 하나만 INSERT 목록에서 뺀다 → 기본값이 없으니 NOT NULL 위반이다.
-- status, current_turn_no, version은 기본값이 있어 빼도 통과한다 (2절).
DO $$
DECLARE
    c      text;
    v_cols text;
BEGIN
    FOREACH c IN ARRAY ARRAY['seed', 'engine_version', 'data_snapshot_date', 'base_date', 'initial_cash', 'fee_rate_kr', 'fee_rate_us', 'fx_spread_rate'] LOOP
        SELECT string_agg(x, ', ') INTO v_cols
        FROM unnest(ARRAY['member_id', 'path_length', 'seed', 'engine_version', 'data_snapshot_date', 'base_date', 'initial_cash', 'fee_rate_kr', 'fee_rate_us', 'fx_spread_rate', 'risk_limit',
                          'start_goalkeeper', 'start_defender', 'start_midfielder', 'start_forward',
                          'current_goalkeeper', 'current_defender', 'current_midfielder', 'current_forward']) AS x
        WHERE x <> c;
        CALL pg_temp.check_err('3 음성', format('season ★ D30: %s를 생략 — DB 기본값이 없다', c),
            format('INSERT INTO season (%1$s) SELECT %1$s FROM season WHERE seed = 100', v_cols), '23502', c);
    END LOOP;
END $$;

-- 3.3 season_path (기준 행: S0 턴 1 → 1990-01)
CALL pg_temp.check_err('3 음성', 'season_path: 턴 0 (0턴은 기준일이라 블록이 없다)',
    $q$INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES ((SELECT id FROM season WHERE seed = 100), 0, (SELECT id FROM month_block WHERE month_start = '1990-01-01'))$q$, '23514', 'ck_season_path_turn_no');
CALL pg_temp.check_err('3 음성', 'season_path ★ 같은 (시즌, 턴) = (S0, 1)',
    $q$INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES ((SELECT id FROM season WHERE seed = 100), 1, (SELECT id FROM month_block WHERE month_start = '1990-02-01'))$q$, '23505', 'uq_season_path_season_turn');
CALL pg_temp.check_err('3 음성', 'season_path: 없는 시즌 999999',
    $q$INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES (999999, 1, (SELECT id FROM month_block WHERE month_start = '1990-01-01'))$q$, '23503', 'fk_season_path_season');
CALL pg_temp.check_err('3 음성', 'season_path: 없는 블록 999999',
    $q$INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES ((SELECT id FROM season WHERE seed = 100), 9, 999999)$q$, '23503', 'fk_season_path_month_block');
CALL pg_temp.check_err('3 음성', 'season_path: month_block_id NULL',
    $q$INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES ((SELECT id FROM season WHERE seed = 100), 9, NULL)$q$, '23502', 'month_block_id');

-- 3.4 turn (기준 스냅샷 S0 턴 1에 UPDATE. 현금 1,000,000 · 보유 9,000,000 · 총액 10,000,000 · 포지션 2.5M·3M·2.5M·2M · 낙폭 -0.05)
CALL pg_temp.check_err('3 음성', 'turn: 턴 0 (0턴 행은 없다)',
    $q$UPDATE turn SET turn_no = 0 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23514', 'ck_turn_turn_no');
CALL pg_temp.check_err('3 음성', 'turn ★ 같은 (시즌, 턴) = (S0, 1)',
    $q$INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown)
       SELECT season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown
       FROM turn WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23505', 'uq_turn_season_turn');
CALL pg_temp.check_err('3 음성', 'turn: 보유 평가액만 바꿔 현금 + 보유 ≠ 총액 (총액을 바꾸면 position_sum이 먼저 걸린다)',
    $q$UPDATE turn SET holdings_value = 8999999 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23514', 'ck_turn_total_value');
CALL pg_temp.check_err('3 음성', 'turn: 수비수 평가액만 바꿔 포지션 합 ≠ 총액',
    $q$UPDATE turn SET defender_value = 3000001 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23514', 'ck_turn_position_sum');
CALL pg_temp.check_err('3 음성', 'turn: 골키퍼 평가액 500,000 < 현금 1,000,000 (수비수 5,000,000으로 포지션 합 유지)',
    $q$UPDATE turn SET goalkeeper_value = 500000, defender_value = 5000000 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23514', 'ck_turn_goalkeeper_cash');
CALL pg_temp.check_err('3 음성', 'turn: 낙폭 0.1', $q$UPDATE turn SET drawdown = 0.1 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23514', 'ck_turn_drawdown');
CALL pg_temp.check_err('3 음성', 'turn: 낙폭 -1.5', $q$UPDATE turn SET drawdown = -1.5 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23514', 'ck_turn_drawdown');
CALL pg_temp.check_err('3 음성', 'turn: 현금 -1,000 (보유 10,001,000으로 총액 유지)',
    $q$UPDATE turn SET cash_balance = -1000, holdings_value = 10001000 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23514', 'ck_turn_amounts_nonneg');
CALL pg_temp.check_err('3 음성', 'turn: 현금 1,000,000.5 + 보유 8,999,999.5 (총액 유지, 원 단위 아님)',
    $q$UPDATE turn SET cash_balance = 1000000.5, holdings_value = 8999999.5 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23514', 'ck_turn_amounts_won');
CALL pg_temp.check_err('3 음성', 'turn: 없는 시즌 999999', $q$UPDATE turn SET season_id = 999999 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23503', 'fk_turn_season');
CALL pg_temp.check_err('3 음성', 'turn: drawdown NULL', $q$UPDATE turn SET drawdown = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1$q$, '23502', 'drawdown');

-- 3.5 turn_price (기준 행: S0 턴 0 × ZZ0001 70,000)
CALL pg_temp.check_err('3 음성', 'turn_price: 턴 -1',
    $q$UPDATE turn_price SET turn_no = -1 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 0 AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23514', 'ck_turn_price_turn_no');
CALL pg_temp.check_err('3 음성', 'turn_price: 가격 0',
    $q$UPDATE turn_price SET price = 0 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 0 AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23514', 'ck_turn_price_price_pos');
CALL pg_temp.check_err('3 음성', 'turn_price: 가격 1,000.5 (원 단위 아님)',
    $q$UPDATE turn_price SET price = 1000.5 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 0 AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23514', 'ck_turn_price_price_won');
CALL pg_temp.check_err('3 음성', 'turn_price ★ 같은 (시즌, 턴, 종목) = (S0, 0, ZZ0001)',
    $q$INSERT INTO turn_price (season_id, turn_no, asset_id, price) SELECT season_id, turn_no, asset_id, price FROM turn_price
       WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 0 AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23505', 'uq_turn_price_season_turn_asset');
CALL pg_temp.check_err('3 음성', 'turn_price: 없는 시즌 999999', $q$INSERT INTO turn_price (season_id, turn_no, asset_id, price) VALUES (999999, 5, (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 1000)$q$, '23503', 'fk_turn_price_season');
CALL pg_temp.check_err('3 음성', 'turn_price: 없는 종목 999999', $q$INSERT INTO turn_price (season_id, turn_no, asset_id, price) VALUES ((SELECT id FROM season WHERE seed = 100), 5, 999999, 1000)$q$, '23503', 'fk_turn_price_asset');
CALL pg_temp.check_err('3 음성', 'turn_price: price NULL', $q$INSERT INTO turn_price (season_id, turn_no, asset_id, price) VALUES ((SELECT id FROM season WHERE seed = 100), 5, (SELECT id FROM asset WHERE ticker = 'ZZ0001'), NULL)$q$, '23502', 'price');

-- 3.6 lineup (기준 행: S0 × ZZ0001 열린 구간, 시작 턴 0, MIDFIELDER)
CALL pg_temp.check_err('3 음성', 'lineup: 선언 포지션 STRIKER',
    $q$UPDATE lineup SET declared_position = 'STRIKER' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23514', 'ck_lineup_declared_position');
CALL pg_temp.check_err('3 음성', 'lineup: 선언 포지션 소문자 goalkeeper',
    $q$UPDATE lineup SET declared_position = 'goalkeeper' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23514', 'ck_lineup_declared_position');
CALL pg_temp.check_err('3 음성', 'lineup: 시작 턴 -1 (끝 턴 NULL)',
    $q$UPDATE lineup SET start_turn = -1 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23514', 'ck_lineup_turns');
CALL pg_temp.check_err('3 음성', 'lineup: 끝 턴 < 시작 턴 (시작 2, 끝 1)',
    $q$UPDATE lineup SET start_turn = 2, end_turn = 1 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23514', 'ck_lineup_turns');
CALL pg_temp.check_err('3 음성', 'lineup: 없는 시즌 999999', $q$UPDATE lineup SET season_id = 999999 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23503', 'fk_lineup_season');
CALL pg_temp.check_err('3 음성', 'lineup: 없는 종목 999999', $q$UPDATE lineup SET asset_id = 999999 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23503', 'fk_lineup_asset');
CALL pg_temp.check_err('3 음성', 'lineup: declared_position NULL', $q$UPDATE lineup SET declared_position = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$q$, '23502', 'declared_position');
-- ★ 열린 구간 둘 (uq_lineup_open)은 5절

-- 3.7 formation_change (기준 행: S0 턴 2, 이전 10·30·30·30 → 이후 20·30·30·20)
CALL pg_temp.check_err('3 음성', 'formation_change: 턴 -1',
    $q$UPDATE formation_change SET turn_no = -1 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23514', 'ck_formation_change_turn_no');
CALL pg_temp.check_err('3 음성', 'formation_change ★ 이전 합 99 (10·30·30·29)',
    $q$UPDATE formation_change SET before_forward = 29 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23514', 'ck_formation_change_before_sum');
CALL pg_temp.check_err('3 음성', 'formation_change ★ 이전 골키퍼 0 (0·40·30·30)',
    $q$UPDATE formation_change SET before_goalkeeper = 0, before_defender = 40 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23514', 'ck_formation_change_before_goalkeeper_min');
CALL pg_temp.check_err('3 음성', 'formation_change ★ 이전 범위 -10 (10·-10·50·50)',
    $q$UPDATE formation_change SET before_defender = -10, before_midfielder = 50, before_forward = 50 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23514', 'ck_formation_change_before_range');
CALL pg_temp.check_err('3 음성', 'formation_change ★ 이후 합 99 (20·30·30·19)',
    $q$UPDATE formation_change SET after_forward = 19 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23514', 'ck_formation_change_after_sum');
CALL pg_temp.check_err('3 음성', 'formation_change ★ 이후 골키퍼 0 (0·50·30·20)',
    $q$UPDATE formation_change SET after_goalkeeper = 0, after_defender = 50 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23514', 'ck_formation_change_after_goalkeeper_min');
CALL pg_temp.check_err('3 음성', 'formation_change ★ 이후 범위 -10 (20·-10·50·40)',
    $q$UPDATE formation_change SET after_defender = -10, after_midfielder = 50, after_forward = 40 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23514', 'ck_formation_change_after_range');
CALL pg_temp.check_err('3 음성', 'formation_change: 이전 = 이후 (아무것도 바꾸지 않은 유효한 변경)',
    $q$UPDATE formation_change SET after_goalkeeper = 10, after_defender = 30, after_midfielder = 30, after_forward = 30 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23514', 'ck_formation_change_changed');
CALL pg_temp.check_err('3 음성', 'formation_change: 없는 시즌 999999',
    $q$UPDATE formation_change SET season_id = 999999 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23503', 'fk_formation_change_season');
-- PK에 가려진 UNIQUE를 증명하는 아래 두 사례(PK 삭제)는 PK 인덱스를 가리키는 FK가 없다는 전제에서만 성립한다.
-- 가리키는 FK가 있으면 DROP CONSTRAINT가 2BP01로 실패해 커버리지에서 빠진다. 03이 02를 가리키는 FK는 복합 UNIQUE만 탄다(erd D29).
-- FK의 conindid는 참조되는 쪽 유일 인덱스다.
CALL pg_temp.check_eq('3 음성', 'formation_change 전제: pk_formation_change를 가리키는 FK 수 = 0 (F4 사례의 전제)', '0',
    pg_temp.val($v$SELECT count(*)::text FROM pg_constraint WHERE contype = 'f' AND conindid = 'public.pk_formation_change'::regclass$v$));
-- uq_formation_change_id_season_turn (id, season_id, turn_no)은 id가 PK라 DML로는 PK가 먼저 걸려 자기 이름으로 보고될 수 없다.
-- 그래서 한 사례 안에서 PK를 지우고(서브트랜잭션이 되돌린다) 같은 id·시즌·턴으로 한 행을 더 넣는다. id를 직접 넣으려면 OVERRIDING SYSTEM VALUE가 필요하다.
CALL pg_temp.check_err('3 음성', 'formation_change ★ PK에 가려진 UNIQUE (id, season_id, turn_no) — PK를 지우고 같은 id·시즌·턴 한 행 더',
    $q$ALTER TABLE formation_change DROP CONSTRAINT pk_formation_change;
       INSERT INTO formation_change (id, season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                     after_goalkeeper, after_defender, after_midfielder, after_forward)
       OVERRIDING SYSTEM VALUE
       SELECT id, season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
              after_goalkeeper, after_defender, after_midfielder, after_forward
       FROM formation_change WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND before_goalkeeper = 10$q$, '23505', 'uq_formation_change_id_season_turn');

-- 3.8 turn_feed (기준 행: S0 턴 1 seq 1, RISE + 종목 ZZ0001, UP, 강도 2, template_id zz_base)
CALL pg_temp.check_err('3 음성', 'turn_feed: 턴 0', $q$UPDATE turn_feed SET turn_no = 0 WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_turn_no');
CALL pg_temp.check_err('3 음성', 'turn_feed: seq 0', $q$UPDATE turn_feed SET seq = 0 WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_seq');
-- 목록 밖 종류는 target_kind도 어기지만 알파벳 순으로 kind가 먼저 보고된다 (k < t).
CALL pg_temp.check_err('3 음성', 'turn_feed ★ 종류 NEWS (목록 밖)', $q$UPDATE turn_feed SET kind = 'NEWS' WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_kind');
CALL pg_temp.check_err('3 음성', 'turn_feed: 종류 소문자 rise', $q$UPDATE turn_feed SET kind = 'rise' WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_kind');
CALL pg_temp.check_err('3 음성', 'turn_feed: RISE + category_hidden TRUE (EVENT만 허용)',
    $q$UPDATE turn_feed SET category_hidden = true WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_category_hidden');
CALL pg_temp.check_err('3 음성', 'turn_feed: 방향 FLAT', $q$UPDATE turn_feed SET direction = 'FLAT' WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_direction');
CALL pg_temp.check_err('3 음성', 'turn_feed: 강도 4', $q$UPDATE turn_feed SET strength = 4 WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_strength');
CALL pg_temp.check_err('3 음성', 'turn_feed: repeat_count -1', $q$UPDATE turn_feed SET repeat_count = -1 WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_repeat_count');
CALL pg_temp.check_err('3 음성', 'turn_feed: ECHO + repeat_count 0', $q$UPDATE turn_feed SET kind = 'ECHO' WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_repeat_count');
CALL pg_temp.check_err('3 음성', 'turn_feed: template_vars가 배열 []', $q$UPDATE turn_feed SET template_vars = '[]' WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_template_vars');
CALL pg_temp.check_err('3 음성', 'turn_feed: template_vars가 JSON null', $q$UPDATE turn_feed SET template_vars = 'null' WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_template_vars');
-- NUDGE로 해야 target_one만 걸린다: RISE이면 target_kind(종목 하나 또는 그룹 하나)가 먼저 보고된다 (k < o).
CALL pg_temp.check_err('3 음성', 'turn_feed: NUDGE + 종목 + 그룹 둘 다',
    $q$UPDATE turn_feed SET kind = 'NUDGE', asset_group_id = (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP') WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_target_one');
CALL pg_temp.check_err('3 음성', 'turn_feed ★ RISE인데 대상 없음', $q$UPDATE turn_feed SET asset_id = NULL WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_target_kind');
CALL pg_temp.check_err('3 음성', 'turn_feed ★ SPREAD인데 종목만 (그룹 없음)', $q$UPDATE turn_feed SET kind = 'SPREAD' WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_target_kind');
-- EVENT + 종목: 사건 태그도 함께 넣어야 event가 아니라 target_kind만 어긴다 (event < target_kind).
CALL pg_temp.check_err('3 음성', 'turn_feed ★ EVENT + 종목 (사건 태그 있음)',
    $q$UPDATE turn_feed SET kind = 'EVENT', month_event_id = (SELECT id FROM month_event WHERE event_key = 'ZZ_EV1') WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_target_kind');
CALL pg_temp.check_err('3 음성', 'turn_feed ★ RISE + 사건 태그', $q$UPDATE turn_feed SET month_event_id = (SELECT id FROM month_event WHERE event_key = 'ZZ_EV1') WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_event');
CALL pg_temp.check_err('3 음성', 'turn_feed ★ EVENT인데 사건 태그 NULL (대상 없음)',
    $q$UPDATE turn_feed SET kind = 'EVENT', asset_id = NULL WHERE template_id = 'zz_base'$q$, '23514', 'ck_turn_feed_event');
CALL pg_temp.check_err('3 음성', 'turn_feed: 같은 (시즌, 턴, seq) = (S0, 1, 1)',
    $q$INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, template_id, body)
       SELECT season_id, turn_no, seq, kind, asset_id, template_id, body FROM turn_feed WHERE template_id = 'zz_base'$q$, '23505', 'uq_turn_feed_season_turn_seq');
CALL pg_temp.check_err('3 음성', 'turn_feed: 없는 시즌 999999', $q$UPDATE turn_feed SET season_id = 999999 WHERE template_id = 'zz_base'$q$, '23503', 'fk_turn_feed_season');
CALL pg_temp.check_err('3 음성', 'turn_feed: 없는 사건 태그 999999 (EVENT)',
    $q$UPDATE turn_feed SET kind = 'EVENT', asset_id = NULL, month_event_id = 999999 WHERE template_id = 'zz_base'$q$, '23503', 'fk_turn_feed_month_event');
CALL pg_temp.check_err('3 음성', 'turn_feed: 없는 종목 999999', $q$UPDATE turn_feed SET asset_id = 999999 WHERE template_id = 'zz_base'$q$, '23503', 'fk_turn_feed_asset');
CALL pg_temp.check_err('3 음성', 'turn_feed: 없는 그룹 999999 (RISE + 그룹)',
    $q$UPDATE turn_feed SET asset_id = NULL, asset_group_id = 999999 WHERE template_id = 'zz_base'$q$, '23503', 'fk_turn_feed_asset_group');
CALL pg_temp.check_err('3 음성', 'turn_feed: template_id NULL', $q$UPDATE turn_feed SET template_id = NULL WHERE template_id = 'zz_base'$q$, '23502', 'template_id');
CALL pg_temp.check_err('3 음성', 'turn_feed: body NULL', $q$UPDATE turn_feed SET body = NULL WHERE template_id = 'zz_base'$q$, '23502', 'body');
CALL pg_temp.check_eq('3 음성', 'turn_feed 전제: pk_turn_feed를 가리키는 FK 수 = 0 (F4 사례의 전제)', '0',
    pg_temp.val($v$SELECT count(*)::text FROM pg_constraint WHERE contype = 'f' AND conindid = 'public.pk_turn_feed'::regclass$v$));
-- uq_turn_feed_id_season (id, season_id)도 PK에 가린다. seq를 바꿔 (시즌, 턴, seq) 유일에는 걸리지 않게 한다.
CALL pg_temp.check_err('3 음성', 'turn_feed ★ PK에 가려진 UNIQUE (id, season_id) — PK를 지우고 같은 id·시즌, 다른 seq 한 행 더',
    $q$ALTER TABLE turn_feed DROP CONSTRAINT pk_turn_feed;
       INSERT INTO turn_feed (id, season_id, turn_no, seq, kind, asset_id, template_id, body)
       OVERRIDING SYSTEM VALUE
       SELECT id, season_id, turn_no, seq + 100, kind, asset_id, template_id, body FROM turn_feed WHERE template_id = 'zz_base'$q$, '23505', 'uq_turn_feed_id_season');

-- 3.9 reconciliation_log (기준 행: S0 턴 3 시도 1 CASH_SUM, MISMATCH, 기대 100 · 실제 90 · 차이 -10, 불일치 1, INVESTIGATE)
CALL pg_temp.check_err('3 음성', 'reconciliation_log: 턴 -1', $q$UPDATE reconciliation_log SET turn_no = -1 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_turn_no');
CALL pg_temp.check_err('3 음성', 'reconciliation_log: 시도 0', $q$UPDATE reconciliation_log SET attempt_no = 0 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_attempt_no');
CALL pg_temp.check_err('3 음성', 'reconciliation_log: 검사 코드 CASH (17개 목록 밖)', $q$UPDATE reconciliation_log SET check_code = 'CASH' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_check_code');
CALL pg_temp.check_err('3 음성', 'reconciliation_log ★ 상태 OK (분류 INVESTIGATE, 불일치 1 유지)', $q$UPDATE reconciliation_log SET status = 'OK' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_status');
CALL pg_temp.check_err('3 음성', 'reconciliation_log: 기대·실제만 있고 차이 NULL', $q$UPDATE reconciliation_log SET diff_value = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_values');
CALL pg_temp.check_err('3 음성', 'reconciliation_log: 차이 5 (≠ 90 - 100)', $q$UPDATE reconciliation_log SET diff_value = 5 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_values');
CALL pg_temp.check_err('3 음성', 'reconciliation_log ★ MATCH + 차이 -10 (불일치 0, 분류 NULL — 차이만 어긴다)',
    $q$UPDATE reconciliation_log SET status = 'MATCH', mismatch_count = 0, classification = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_match_diff');
-- MATCH + 불일치 1: 값 3개를 NULL로 두어야 match_diff(m-a < m-i)가 아니라 mismatch가 어긴다.
CALL pg_temp.check_err('3 음성', 'reconciliation_log: MATCH + 불일치 1 (값 NULL, 분류 NULL)',
    $q$UPDATE reconciliation_log SET status = 'MATCH', classification = NULL, expected_value = NULL, actual_value = NULL, diff_value = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_mismatch');
CALL pg_temp.check_err('3 음성', 'reconciliation_log: 불일치 -1 (MISMATCH)', $q$UPDATE reconciliation_log SET mismatch_count = -1 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_mismatch');
CALL pg_temp.check_err('3 음성', 'reconciliation_log ★ 분류 OTHER', $q$UPDATE reconciliation_log SET classification = 'OTHER' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_classification');
CALL pg_temp.check_err('3 음성', 'reconciliation_log ★ MATCH + 분류 INVESTIGATE (불일치 0, 값 NULL)',
    $q$UPDATE reconciliation_log SET status = 'MATCH', mismatch_count = 0, expected_value = NULL, actual_value = NULL, diff_value = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_status_classification');
CALL pg_temp.check_err('3 음성', 'reconciliation_log ★ MISMATCH + 분류 NULL', $q$UPDATE reconciliation_log SET classification = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_status_classification');
CALL pg_temp.check_err('3 음성', 'reconciliation_log: 같은 (시즌, 턴, 시도, 코드) = (S0, 3, 1, CASH_SUM)',
    $q$INSERT INTO reconciliation_log (season_id, turn_no, attempt_no, check_code, status, mismatch_count)
       VALUES ((SELECT id FROM season WHERE seed = 100), 3, 1, 'CASH_SUM', 'MATCH', 0)$q$, '23505', 'uq_reconciliation_log_season_turn_attempt_code');
CALL pg_temp.check_err('3 음성', 'reconciliation_log: 없는 시즌 999999', $q$UPDATE reconciliation_log SET season_id = 999999 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23503', 'fk_reconciliation_log_season');
CALL pg_temp.check_err('3 음성', 'reconciliation_log: mismatch_count NULL', $q$UPDATE reconciliation_log SET mismatch_count = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23502', 'mismatch_count');

-- 3.10 RESTRICT (ON DELETE / ON UPDATE). 부모마다 자식 테이블이 하나뿐인 픽스처를 만든다.
--      회원 zz_a는 시즌만 자식으로 둔다. 시즌 S1–S7(seed 101–107)은 각각 자식 테이블 하나만: 경로·턴·가격·라인업·변경·피드·대사.
--      01 쪽 부모: 달 1991-01은 season_path만, 종목 ZZDEL1은 turn_price만, ZZDEL2는 lineup만, ZZDEL3은 turn_feed만,
--      그룹 ZZ_EMPTY는 turn_feed만(종목 없음), 사건 ZZ_EV1은 turn_feed만 자식으로 둔다.
DO $$
DECLARE
    i integer;
BEGIN
    FOR i IN 1..7 LOOP
        CALL pg_temp.check_ok('3 음성', format('RESTRICT 픽스처: 시즌 S%s (seed %s)', i, 100 + i),
            format($f$INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                                          start_goalkeeper, start_defender, start_midfielder, start_forward,
                                          current_goalkeeper, current_defender, current_midfielder, current_forward)
                      SELECT member_id, path_length, %s, engine_version, data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                             start_goalkeeper, start_defender, start_midfielder, start_forward,
                             current_goalkeeper, current_defender, current_midfielder, current_forward
                      FROM season WHERE seed = 100$f$, 100 + i));
    END LOOP;
END $$;
CALL pg_temp.check_ok('3 음성', 'RESTRICT 픽스처: S1 경로(1991-01) · S2 턴 · S3 가격(ZZDEL1) · S4 라인업(ZZDEL2) · S5 변경 · S6 피드(ZZDEL3, ZZ_EMPTY) · S7 대사',
    $q$INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES ((SELECT id FROM season WHERE seed = 101), 1, (SELECT id FROM month_block WHERE month_start = '1991-01-01'));
       INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown)
       VALUES ((SELECT id FROM season WHERE seed = 102), 1, 1000000, 9000000, 10000000, 2500000, 3000000, 2500000, 2000000, -0.05);
       INSERT INTO turn_price (season_id, turn_no, asset_id, price) VALUES ((SELECT id FROM season WHERE seed = 103), 0, (SELECT id FROM asset WHERE ticker = 'ZZDEL1'), 1000);
       INSERT INTO lineup (season_id, asset_id, declared_position, start_turn) VALUES ((SELECT id FROM season WHERE seed = 104), (SELECT id FROM asset WHERE ticker = 'ZZDEL2'), 'DEFENDER', 0);
       INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                     after_goalkeeper, after_defender, after_midfielder, after_forward)
       VALUES ((SELECT id FROM season WHERE seed = 105), 1, 10, 30, 30, 30, 20, 30, 30, 20);
       INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, asset_group_id, template_id, body)
       VALUES ((SELECT id FROM season WHERE seed = 106), 1, 1, 'RISE', (SELECT id FROM asset WHERE ticker = 'ZZDEL3'), NULL, 'zz_t', 'zz'), ((SELECT id FROM season WHERE seed = 106), 1, 2, 'FADE', NULL, (SELECT id FROM asset_group WHERE code = 'ZZ_EMPTY'), 'zz_t', 'zz');
       INSERT INTO reconciliation_log (season_id, turn_no, attempt_no, check_code, status, mismatch_count)
       VALUES ((SELECT id FROM season WHERE seed = 107), 1, 1, 'CASH_SUM', 'MATCH', 0)$q$);
CALL pg_temp.check_err('3 음성', 'RESTRICT: 시즌이 있는 회원 삭제', $q$DELETE FROM member WHERE email = 'zz_a@example.com'$q$, '23503', 'fk_season_member');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 시즌이 있는 회원의 id 변경 (ON UPDATE RESTRICT, SET id = DEFAULT)',
    $q$UPDATE member SET id = DEFAULT WHERE email = 'zz_a@example.com'$q$, '23503', 'fk_season_member');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 경로 행만 있는 시즌 S1 삭제', $q$DELETE FROM season WHERE seed = 101$q$, '23503', 'fk_season_path_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 턴 스냅샷만 있는 시즌 S2 삭제', $q$DELETE FROM season WHERE seed = 102$q$, '23503', 'fk_turn_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 가격 행만 있는 시즌 S3 삭제', $q$DELETE FROM season WHERE seed = 103$q$, '23503', 'fk_turn_price_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 라인업만 있는 시즌 S4 삭제', $q$DELETE FROM season WHERE seed = 104$q$, '23503', 'fk_lineup_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 포메이션 변경만 있는 시즌 S5 삭제', $q$DELETE FROM season WHERE seed = 105$q$, '23503', 'fk_formation_change_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 피드만 있는 시즌 S6 삭제', $q$DELETE FROM season WHERE seed = 106$q$, '23503', 'fk_turn_feed_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 대사 기록만 있는 시즌 S7 삭제', $q$DELETE FROM season WHERE seed = 107$q$, '23503', 'fk_reconciliation_log_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 턴 스냅샷만 있는 시즌 S2의 id 변경 (ON UPDATE RESTRICT)',
    $q$UPDATE season SET id = DEFAULT WHERE seed = 102$q$, '23503', 'fk_turn_season');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 경로 행만 가리키는 달 1991-01 삭제', $q$DELETE FROM month_block WHERE month_start = '1991-01-01'$q$, '23503', 'fk_season_path_month_block');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 가격 행만 있는 종목 ZZDEL1 삭제', $q$DELETE FROM asset WHERE ticker = 'ZZDEL1'$q$, '23503', 'fk_turn_price_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 라인업만 있는 종목 ZZDEL2 삭제', $q$DELETE FROM asset WHERE ticker = 'ZZDEL2'$q$, '23503', 'fk_lineup_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 피드만 있는 종목 ZZDEL3 삭제', $q$DELETE FROM asset WHERE ticker = 'ZZDEL3'$q$, '23503', 'fk_turn_feed_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 피드만 가리키는 빈 그룹 ZZ_EMPTY 삭제', $q$DELETE FROM asset_group WHERE code = 'ZZ_EMPTY'$q$, '23503', 'fk_turn_feed_asset_group');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 피드만 가리키는 사건 ZZ_EV1 삭제', $q$DELETE FROM month_event WHERE event_key = 'ZZ_EV1'$q$, '23503', 'fk_turn_feed_month_event');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 가격 행만 있는 종목 ZZDEL1의 id 변경 (ON UPDATE RESTRICT)',
    $q$UPDATE asset SET id = DEFAULT WHERE ticker = 'ZZDEL1'$q$, '23503', 'fk_turn_price_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 경로 행만 가리키는 달 1991-01의 id 변경 (ON UPDATE RESTRICT)',
    $q$UPDATE month_block SET id = DEFAULT WHERE month_start = '1991-01-01'$q$, '23503', 'fk_season_path_month_block');


-- 4 저장 정밀도 경계 ------------------------------------------------------------
-- "CHECK는 반올림된 저장값을 본다"(erd D1). NUMERIC(p,s)는 소수 자리가 넘치면 경고 없이 0에서 먼 쪽으로 반올림하고,
-- 정수 자리가 넘치면 22003으로 거부한다. SMALLINT에 소수를 넣으면 경고 없이 반올림된다.
-- 경계용 시즌 B(seed 400)는 기준 시즌 S0를 복사한 것이고, 4절은 B의 컬럼을 UPDATE 하며 저장값을 읽는다 (통과하는 값은 B에 남는다).
CALL pg_temp.check_ok('4 경계', '경계용 시즌 B (seed 400, S0 복사) + B의 턴 스냅샷·가격·피드 한 행씩',
    $q$INSERT INTO season (member_id, path_length, seed, engine_version, data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                           start_goalkeeper, start_defender, start_midfielder, start_forward,
                           current_goalkeeper, current_defender, current_midfielder, current_forward)
       SELECT member_id, path_length, 400, engine_version, data_snapshot_date, base_date, initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
              start_goalkeeper, start_defender, start_midfielder, start_forward,
              current_goalkeeper, current_defender, current_midfielder, current_forward
       FROM season WHERE seed = 100;
       INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown)
       SELECT (SELECT id FROM season WHERE seed = 400), turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown
       FROM turn WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND turn_no = 1;
       INSERT INTO turn_price (season_id, turn_no, asset_id, price) VALUES ((SELECT id FROM season WHERE seed = 400), 0, (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 70000);
       INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, template_id, body)
       SELECT (SELECT id FROM season WHERE seed = 400), turn_no, seq, kind, asset_id, 'zz_b', body FROM turn_feed WHERE template_id = 'zz_base'$q$);

-- 4.1 season.initial_cash NUMERIC(19,4), CHECK initial_cash = ROUND(initial_cash, 0) — 네 값 (erd D1, P0-04 QA §6 ⑤)
CALL pg_temp.check_ok('4 경계', 'initial_cash = 100.00001 대입 (저장 100.0000 → 통과)', $q$UPDATE season SET initial_cash = 100.00001 WHERE seed = 400$q$);
CALL pg_temp.check_eq('4 경계', 'initial_cash 100.00001 → 저장값', '100.0000', pg_temp.val($v$SELECT initial_cash::text FROM season WHERE seed = 400$v$));
CALL pg_temp.check_ok('4 경계', 'initial_cash = 100.00004 대입 (저장 100.0000 → 통과)', $q$UPDATE season SET initial_cash = 100.00004 WHERE seed = 400$q$);
CALL pg_temp.check_eq('4 경계', 'initial_cash 100.00004 → 저장값', '100.0000', pg_temp.val($v$SELECT initial_cash::text FROM season WHERE seed = 400$v$));
CALL pg_temp.check_err('4 경계', 'initial_cash = 100.00005 (저장 100.0001 → 원 단위 CHECK 위반)', $q$UPDATE season SET initial_cash = 100.00005 WHERE seed = 400$q$, '23514', 'ck_season_initial_cash_won');
CALL pg_temp.check_err('4 경계', 'initial_cash = 100.5 (원 단위 CHECK 위반)', $q$UPDATE season SET initial_cash = 100.5 WHERE seed = 400$q$, '23514', 'ck_season_initial_cash_won');
CALL pg_temp.check_err('4 경계', 'initial_cash = 10^15 (정수 16자리 → 22003)', $q$UPDATE season SET initial_cash = 1000000000000000 WHERE seed = 400$q$, '22003');

-- 4.2 turn_price.price NUMERIC(19,4), CHECK price = ROUND(price, 0) — 같은 네 값
CALL pg_temp.check_ok('4 경계', 'price = 100.00001 대입 (저장 100.0000 → 통과)', $q$UPDATE turn_price SET price = 100.00001 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$);
CALL pg_temp.check_eq('4 경계', 'price 100.00001 → 저장값', '100.0000', pg_temp.val($v$SELECT price::text FROM turn_price WHERE season_id = (SELECT id FROM season WHERE seed = 400)$v$));
CALL pg_temp.check_ok('4 경계', 'price = 100.00004 대입 (저장 100.0000 → 통과)', $q$UPDATE turn_price SET price = 100.00004 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$);
CALL pg_temp.check_err('4 경계', 'price = 100.00005 (저장 100.0001 → 원 단위 CHECK 위반)', $q$UPDATE turn_price SET price = 100.00005 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '23514', 'ck_turn_price_price_won');
CALL pg_temp.check_err('4 경계', 'price = 100.5 (원 단위 CHECK 위반)', $q$UPDATE turn_price SET price = 100.5 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '23514', 'ck_turn_price_price_won');
CALL pg_temp.check_err('4 경계', 'price = 0.00004 (저장 0.0000 → price > 0 위반)', $q$UPDATE turn_price SET price = 0.00004 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '23514', 'ck_turn_price_price_pos');

-- 4.3 season.risk_limit NUMERIC(12,8), CHECK risk_limit IN (-0.10, -0.20, -0.30, -0.40)
CALL pg_temp.check_ok('4 경계', 'risk_limit = -0.200000004 대입 (저장 -0.20000000 → 목록과 같음 → 통과)', $q$UPDATE season SET risk_limit = -0.200000004 WHERE seed = 400$q$);
CALL pg_temp.check_eq('4 경계', 'risk_limit -0.200000004 → 저장값', '-0.20000000', pg_temp.val($v$SELECT risk_limit::text FROM season WHERE seed = 400$v$));
CALL pg_temp.check_err('4 경계', 'risk_limit = -0.200000005 (저장 -0.20000001 → 목록 밖)', $q$UPDATE season SET risk_limit = -0.200000005 WHERE seed = 400$q$, '23514', 'ck_season_risk_limit');
CALL pg_temp.check_err('4 경계', 'risk_limit = -0.20000001 (목록 밖)', $q$UPDATE season SET risk_limit = -0.20000001 WHERE seed = 400$q$, '23514', 'ck_season_risk_limit');

-- 4.4 season.fee_rate_kr·fee_rate_us·fx_spread_rate NUMERIC(12,8), CHECK 컬럼 >= 0 AND 컬럼 < 1 (컬럼마다)
CALL pg_temp.check_ok('4 경계', 'fee_rate_kr = 0.999999994 대입 (저장 0.99999999 → 통과)', $q$UPDATE season SET fee_rate_kr = 0.999999994 WHERE seed = 400$q$);
CALL pg_temp.check_eq('4 경계', 'fee_rate_kr 0.999999994 → 저장값', '0.99999999', pg_temp.val($v$SELECT fee_rate_kr::text FROM season WHERE seed = 400$v$));
CALL pg_temp.check_err('4 경계', 'fee_rate_kr = 0.999999995 (저장 1.00000000 → 위반)', $q$UPDATE season SET fee_rate_kr = 0.999999995 WHERE seed = 400$q$, '23514', 'ck_season_fee_rate_kr');
CALL pg_temp.check_ok('4 경계', 'fee_rate_us = 0.999999994 대입 (저장 0.99999999 → 통과)', $q$UPDATE season SET fee_rate_us = 0.999999994 WHERE seed = 400$q$);
CALL pg_temp.check_eq('4 경계', 'fee_rate_us 0.999999994 → 저장값', '0.99999999', pg_temp.val($v$SELECT fee_rate_us::text FROM season WHERE seed = 400$v$));
CALL pg_temp.check_err('4 경계', 'fee_rate_us = 0.999999995 (저장 1.00000000 → 위반)', $q$UPDATE season SET fee_rate_us = 0.999999995 WHERE seed = 400$q$, '23514', 'ck_season_fee_rate_us');
CALL pg_temp.check_ok('4 경계', 'fx_spread_rate = 0.999999994 대입 (저장 0.99999999 → 통과)', $q$UPDATE season SET fx_spread_rate = 0.999999994 WHERE seed = 400$q$);
CALL pg_temp.check_eq('4 경계', 'fx_spread_rate 0.999999994 → 저장값', '0.99999999', pg_temp.val($v$SELECT fx_spread_rate::text FROM season WHERE seed = 400$v$));
CALL pg_temp.check_err('4 경계', 'fx_spread_rate = 0.999999995 (저장 1.00000000 → 위반)', $q$UPDATE season SET fx_spread_rate = 0.999999995 WHERE seed = 400$q$, '23514', 'ck_season_fx_spread_rate');

-- 4.5 turn.drawdown NUMERIC(12,8), CHECK drawdown BETWEEN -1 AND 0
CALL pg_temp.check_ok('4 경계', 'drawdown = 0.000000004 대입 (저장 0.00000000 → 통과)', $q$UPDATE turn SET drawdown = 0.000000004 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$);
CALL pg_temp.check_eq('4 경계', 'drawdown 0.000000004 → 저장값', '0.00000000', pg_temp.val($v$SELECT drawdown::text FROM turn WHERE season_id = (SELECT id FROM season WHERE seed = 400)$v$));
CALL pg_temp.check_err('4 경계', 'drawdown = 0.000000005 (저장 0.00000001 → 위반)', $q$UPDATE turn SET drawdown = 0.000000005 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '23514', 'ck_turn_drawdown');
CALL pg_temp.check_ok('4 경계', 'drawdown = -1.000000004 대입 (저장 -1.00000000 → 통과)', $q$UPDATE turn SET drawdown = -1.000000004 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$);
CALL pg_temp.check_err('4 경계', 'drawdown = -1.000000005 (저장 -1.00000001 → 위반)', $q$UPDATE turn SET drawdown = -1.000000005 WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '23514', 'ck_turn_drawdown');

-- 4.6 season.status VARCHAR(11): 폭을 넘는 공백만 오류 없이 잘리고, 폭 안의 공백은 값의 일부다 (기본 비교는 뒤 공백을 구분)
CALL pg_temp.check_ok('4 경계', 'status = ''IN_PROGRESS '' (폭 11 초과 공백 1개 → 잘려 통과)', $q$UPDATE season SET status = 'IN_PROGRESS ' WHERE seed = 400$q$);
CALL pg_temp.check_eq('4 경계', 'status ''IN_PROGRESS '' → 저장값의 길이', '11', pg_temp.val($v$SELECT length(status)::text FROM season WHERE seed = 400$v$));
CALL pg_temp.check_err('4 경계', 'status = ''ABANDONING '' (폭 11 안의 뒤 공백 → CHECK 위반)', $q$UPDATE season SET status = 'ABANDONING ' WHERE seed = 400$q$, '23514', 'ck_season_status');

-- 4.7 SMALLINT와 INTEGER: 소수 입력은 경고 없이 반올림되고, 범위를 넘으면 22003이다
CALL pg_temp.check_ok('4 경계', 'start_defender = 29.5 대입 (SMALLINT 컬럼에 소수 — 저장 30, 합 100 유지)', $q$UPDATE season SET start_defender = 29.5 WHERE seed = 400$q$);
CALL pg_temp.check_eq('4 경계', 'start_defender 29.5 → 저장값 (0에서 먼 쪽 반올림, 경고 없음)', '30', pg_temp.val($v$SELECT start_defender::text FROM season WHERE seed = 400$v$));
CALL pg_temp.check_err('4 경계', 'path_length = 32768 (SMALLINT 범위 초과 → 22003)', $q$UPDATE season SET path_length = 32768 WHERE seed = 400$q$, '22003');
CALL pg_temp.check_err('4 경계', 'mismatch_count = 2147483648 (INTEGER 범위 초과 → 22003)', $q$UPDATE reconciliation_log SET mismatch_count = 2147483648 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '22003');
CALL pg_temp.check_err('4 경계', 'expected_value = 10^15 (NUMERIC(19,4) 정수 16자리 → 22003)', $q$UPDATE reconciliation_log SET expected_value = 1000000000000000 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '22003');

-- 4.8 JSONB: 숫자는 numeric으로 저장되어 자리가 보존된다 (1.10이 1.1이 되지 않는다, erd D27). 키 순서·공백은 정규화된다.
CALL pg_temp.check_ok('4 경계', 'template_vars = {"r": 1.10} 대입', $q$UPDATE turn_feed SET template_vars = '{"r": 1.10}' WHERE template_id = 'zz_b'$q$);
CALL pg_temp.check_eq('4 경계', 'template_vars->>''r'' → 1.10 (숫자 자리 보존)', '1.10', pg_temp.val($v$SELECT template_vars->>'r' FROM turn_feed WHERE template_id = 'zz_b'$v$));

-- 4.9 NUMERIC NaN: NaN은 자기와 같고 모든 수보다 크게 비교되어 `>= 0`·`= ROUND`·등식만으로는 막히지 않는다 (erd D32).
--     값 CHECK의 <> 'NaN'::numeric이 실제로 걸리는지 컬럼마다 한 건씩 본다. 무한대는 정밀도 지정 NUMERIC이 22003으로 막는다.
--     상한이 있는 BETWEEN·< 1·IN 목록 CHECK(요율 3개, risk_limit, drawdown)는 NaN에서 이미 거짓이라 고치지 않았다(변경 없음 확인).
--     시즌 B(seed 400)의 턴·가격 행과 3.9의 기준 대사 행을 UPDATE로 시험하며 실패하면 되돌아간다. 먼저 그 전제를 단언한다.
CALL pg_temp.check_eq('4 경계', 'NaN 사례 전제: 시즌 B의 턴 1행·가격 1행이 있고 금액이 모두 유한하다', '1/1',
    pg_temp.val($v$SELECT (SELECT count(*) FROM turn WHERE season_id = (SELECT id FROM season WHERE seed = 400)
        AND 'NaN'::numeric NOT IN (cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown))::text
        || '/' || (SELECT count(*) FROM turn_price WHERE season_id = (SELECT id FROM season WHERE seed = 400) AND price <> 'NaN'::numeric)::text$v$));
CALL pg_temp.check_eq('4 경계', 'NaN 사례 전제: 3.9 기준 대사 행(MISMATCH, 기대 100 · 실제 90 · 차이 -10)이 1행이다', '1',
    pg_temp.val($v$SELECT count(*)::text FROM reconciliation_log WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL
        AND status = 'MISMATCH' AND expected_value = 100 AND actual_value = 90 AND diff_value = -10$v$));
CALL pg_temp.check_err('4 경계', 'season.initial_cash = NaN (_pos는 NaN > 0이 참이라 통과 → 이름 순으로 _won)', $q$UPDATE season SET initial_cash = 'NaN' WHERE seed = 400$q$, '23514', 'ck_season_initial_cash_won');
CALL pg_temp.check_err('4 경계', 'season.fee_rate_kr = NaN (< 1이 이미 거짓 — 변경 없음 확인)', $q$UPDATE season SET fee_rate_kr = 'NaN' WHERE seed = 400$q$, '23514', 'ck_season_fee_rate_kr');
CALL pg_temp.check_err('4 경계', 'season.fee_rate_us = NaN (변경 없음 확인)', $q$UPDATE season SET fee_rate_us = 'NaN' WHERE seed = 400$q$, '23514', 'ck_season_fee_rate_us');
CALL pg_temp.check_err('4 경계', 'season.fx_spread_rate = NaN (변경 없음 확인)', $q$UPDATE season SET fx_spread_rate = 'NaN' WHERE seed = 400$q$, '23514', 'ck_season_fx_spread_rate');
CALL pg_temp.check_err('4 경계', 'season.risk_limit = NaN (IN 목록이 이미 거짓 — 변경 없음 확인)', $q$UPDATE season SET risk_limit = 'NaN' WHERE seed = 400$q$, '23514', 'ck_season_risk_limit');
--     턴 금액 7개: 한 컬럼만 NaN이면 amounts_nonneg(NaN >= 0 참)는 통과하고 이름 순으로 amounts_won이 먼저 보고한다. 컬럼마다 사례가 있어야 절 하나의 삭제를 잡는다.
DO $$
DECLARE c text;
BEGIN
    FOREACH c IN ARRAY ARRAY['cash_balance', 'holdings_value', 'total_value', 'goalkeeper_value', 'defender_value', 'midfielder_value', 'forward_value'] LOOP
        CALL pg_temp.check_err('4 경계', format('turn.%s = NaN (나머지는 유한)', c),
            format($f$UPDATE turn SET %1$s = 'NaN' WHERE season_id = (SELECT id FROM season WHERE seed = 400)$f$, c), '23514', 'ck_turn_amounts_won');
    END LOOP;
END $$;
CALL pg_temp.check_err('4 경계', 'turn 금액 7개 모두 NaN (고치기 전에는 total_value = holdings_value + cash_balance까지 모든 CHECK를 통과했다)',
    $q$UPDATE turn SET cash_balance = 'NaN', holdings_value = 'NaN', total_value = 'NaN', goalkeeper_value = 'NaN', defender_value = 'NaN', midfielder_value = 'NaN', forward_value = 'NaN'
       WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '23514', 'ck_turn_amounts_won');
CALL pg_temp.check_err('4 경계', 'turn.drawdown = NaN (BETWEEN이 이미 거짓 — 변경 없음 확인)', $q$UPDATE turn SET drawdown = 'NaN' WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '23514', 'ck_turn_drawdown');
CALL pg_temp.check_err('4 경계', 'turn_price.price = NaN (_pos는 통과 → 이름 순으로 _won)', $q$UPDATE turn_price SET price = 'NaN' WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '23514', 'ck_turn_price_price_won');
--     대사 기록: 차이에는 절을 두지 않는다. 기대·실제가 유한하면 diff = 유한 − 유한이 NaN 차이를 거짓으로 만든다.
CALL pg_temp.check_err('4 경계', 'reconciliation_log 기대·실제·차이 모두 NaN (NaN = NaN − NaN이 참이라 고치기 전에는 저장됐다)',
    $q$UPDATE reconciliation_log SET expected_value = 'NaN', actual_value = 'NaN', diff_value = 'NaN' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_values');
CALL pg_temp.check_err('4 경계', 'reconciliation_log 기대 NaN · 차이 NaN · 실제 90 (90 − NaN = NaN이라 등식이 참)',
    $q$UPDATE reconciliation_log SET expected_value = 'NaN', diff_value = 'NaN' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_values');
CALL pg_temp.check_err('4 경계', 'reconciliation_log 실제 NaN · 차이 NaN · 기대 100 (NaN − 100 = NaN이라 등식이 참)',
    $q$UPDATE reconciliation_log SET actual_value = 'NaN', diff_value = 'NaN' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_values');
CALL pg_temp.check_err('4 경계', 'reconciliation_log MISMATCH, 기대 100 · 실제 90 유한, 차이만 NaN (NaN = 90 − 100이 거짓 — 등식이 막는다)',
    $q$UPDATE reconciliation_log SET diff_value = 'NaN' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_values');
CALL pg_temp.check_err('4 경계', 'reconciliation_log MATCH인데 차이 NaN (유한 − 유한 ≠ NaN, 이름 순으로 match_diff — 변경 없음 확인)',
    $q$UPDATE reconciliation_log SET status = 'MATCH', mismatch_count = 0, classification = NULL, diff_value = 'NaN' WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND detail IS NOT NULL$q$, '23514', 'ck_reconciliation_log_match_diff');
CALL pg_temp.check_err('4 경계', 'turn_price.price = Infinity (정밀도 지정 NUMERIC이 거부 → 22003)', $q$UPDATE turn_price SET price = 'Infinity' WHERE season_id = (SELECT id FROM season WHERE seed = 400)$q$, '22003');

-- 5 부분·표현식 유일 인덱스 동작 (erd 6.3 member·lineup, D26) --------------------------
-- uq_member_email은 lower(email)의 표현식 유일 인덱스이고, uq_lineup_open은 end_turn이 NULL인 행만 담는 부분 유일 인덱스다.
-- 둘 다 제약이 아니라 인덱스라 pg_constraint가 아니라 pg_index에 있다 (1.4·1.8). 위반의 CONSTRAINT_NAME은 인덱스 이름이다.
CALL pg_temp.check_ok('5 인덱스', 'member: zz_b@example.com', $q$INSERT INTO member (email, name, password) VALUES ('zz_b@example.com', 'x', 'x')$q$);
CALL pg_temp.check_err('5 인덱스', 'member: 대소문자만 다른 ZZ_B@EXAMPLE.COM (lower(email) 유일)',
    $q$INSERT INTO member (email, name, password) VALUES ('ZZ_B@EXAMPLE.COM', 'x', 'x')$q$, '23505', 'uq_member_email');
CALL pg_temp.check_ok('5 인덱스', 'member: zz_c@example.com', $q$INSERT INTO member (email, name, password) VALUES ('zz_c@example.com', 'x', 'x')$q$);
-- 현재 동작 기록(erd 결정 변경 아님): lower()는 공백을 다루지 않아 뒤 공백이 든 이메일은 다른 값이다. 형식 검사는 MVP @Valid의 몫이다(erd 6.3).
CALL pg_temp.check_ok('5 인덱스', 'member: 뒤 공백이 든 ''zz_c@example.com ''가 통과한다 (현재 동작 기록)',
    $q$INSERT INTO member (email, name, password) VALUES ('zz_c@example.com ', 'x', 'x')$q$);
CALL pg_temp.check_err('5 인덱스', 'lineup ★ 같은 (시즌, 종목)의 열린 구간 둘 (S0 × ZZ0001 열린 구간이 이미 있다)',
    $q$INSERT INTO lineup (season_id, asset_id, declared_position, start_turn) VALUES ((SELECT id FROM season WHERE seed = 100), (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 'DEFENDER', 1)$q$, '23505', 'uq_lineup_open');
CALL pg_temp.check_ok('5 인덱스', 'lineup: 열린 구간을 턴 3에 닫는다 (end_turn = 3)',
    $q$UPDATE lineup SET end_turn = 3 WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001') AND end_turn IS NULL$q$);
CALL pg_temp.check_ok('5 인덱스', 'lineup: 닫은 뒤 같은 종목의 새 열린 구간이 턴 3에 시작한다 (반열림 [3, …), 맞닿음)',
    $q$INSERT INTO lineup (season_id, asset_id, declared_position, start_turn) VALUES ((SELECT id FROM season WHERE seed = 100), (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 'FORWARD_BREAKOUT', 3)$q$);
CALL pg_temp.check_err('5 인덱스', 'lineup: 닫힌 구간을 다시 연다 (end_turn = NULL) — 열린 구간이 이미 있다 (UPDATE 경로)',
    $q$UPDATE lineup SET end_turn = NULL WHERE season_id = (SELECT id FROM season WHERE seed = 100) AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001') AND start_turn = 0$q$, '23505', 'uq_lineup_open');
-- 겹치는 닫힌 구간은 DDL이 막지 않는다: 같은 종목의 [1,5)와 [3,7)가 통과한다. 겹침 검사는 대사 규칙 18(LINEUP_OVERLAP)의 몫이다 (D26의 EXCLUDE 대안은 MVP).
CALL pg_temp.check_ok('5 인덱스', 'lineup: 같은 종목의 겹치는 닫힌 구간 [1,5)와 [3,7)가 통과한다 (현재 동작 기록 — 대사 규칙 18이 잡는다)',
    $q$INSERT INTO lineup (season_id, asset_id, declared_position, start_turn, end_turn)
       VALUES ((SELECT id FROM season WHERE seed = 400), (SELECT id FROM asset WHERE ticker = 'ZZAAPL'), 'DEFENDER', 1, 5), ((SELECT id FROM season WHERE seed = 400), (SELECT id FROM asset WHERE ticker = 'ZZAAPL'), 'DEFENDER', 3, 7)$q$);
CALL pg_temp.check_ok('5 인덱스', 'lineup: 다른 시즌(B)에서 같은 종목 ZZ0001의 열린 구간이 통과한다',
    $q$INSERT INTO lineup (season_id, asset_id, declared_position, start_turn) VALUES ((SELECT id FROM season WHERE seed = 400), (SELECT id FROM asset WHERE ticker = 'ZZ0001'), 'MIDFIELDER', 0)$q$);

-- 6 음성 DDL (트랜잭션 안에서 실행하고 되돌린다 — PostgreSQL의 DDL은 ROLLBACK된다) ------------
CALL pg_temp.check_err('6 DDL', 'D1: 컬럼 CHECK가 다른 컬럼을 참조 — PostgreSQL은 막지 않는다 (그래서 CHECK 위치 통일은 erd 2절 규칙이 지킨다)',
    $q$ALTER TABLE member ADD COLUMN zz_x integer CHECK (zz_x > length(name))$q$, '00000');
CALL pg_temp.check_err('6 DDL', 'D2: 유일하지 않은 컬럼(season.member_id)을 참조하는 FK',
    $q$ALTER TABLE turn_price ADD CONSTRAINT zz_fk FOREIGN KEY (season_id) REFERENCES season (member_id)$q$, '42830');
CALL pg_temp.check_err('6 DDL', 'D3: 없는 부모 테이블을 참조하는 FK',
    $q$ALTER TABLE turn_price ADD CONSTRAINT zz_fk FOREIGN KEY (season_id) REFERENCES zz_no_such_table (id)$q$, '42P01');
CALL pg_temp.check_err('6 DDL', 'D4a: 자식이 있는 02 부모 테이블 DROP (CASCADE 없음) — 재실행 블록이 역순인 이유',
    $q$DROP TABLE season$q$, '2BP01');
-- D4b 규칙: "다음 파일이 있을 때 재실행이 멈추는 이유"는 자기 파일 안에 자식이 없는 앞 번호 테이블로 증명한다.
-- month_block은 01 안에도 자식이 4개 있어(asset_month_return·fx_month·month_event·asset_month_attention) 02가 없어도 2BP01이므로 증명이 못 된다.
-- 01 재실행 블록의 둘째 줄이 DROP TABLE month_event이고, month_event는 01 안에 자식이 없다. 02의 turn_feed만 가리킨다(fk_turn_feed_month_event).
CALL pg_temp.check_err('6 DDL', 'D4b: 02가 가리키는 01 테이블 month_event DROP — 02가 있을 때 01 재실행이 2BP01로 멈추는 이유',
    $q$DROP TABLE month_event$q$, '2BP01');
CALL pg_temp.check_err('6 DDL', 'D4c: 03 대역 테이블(turn_feed를 FK로 참조)이 있을 때 turn_feed DROP — 03이 있으면 02 재실행이 멈추는 이유',
    $q$CREATE TABLE zz_orders (id bigint PRIMARY KEY, turn_feed_id bigint REFERENCES turn_feed (id));
       DROP TABLE turn_feed$q$, '2BP01');
CALL pg_temp.check_err('6 DDL', 'D5: 같은 테이블에 같은 CHECK 이름 두 번',
    $q$ALTER TABLE season ADD CONSTRAINT ck_season_status CHECK (true)$q$, '42710');
CALL pg_temp.check_err('6 DDL', 'D6: 기존 유일 인덱스 이름과 같은 인덱스 이름 (인덱스는 스키마 안에서 이름이 유일)',
    $q$CREATE INDEX uq_lineup_open ON member (name)$q$, '42P07');
CALL pg_temp.check_err('6 DDL', 'D7a: 복합 FK (fc_id, season_id, turn_no) → formation_change (id, season_id, turn_no) 는 대상 UNIQUE가 있어 만들어진다 (P0-07)',
    $q$CREATE TABLE zz_fc_ref (id bigint PRIMARY KEY, fc_id bigint, season_id bigint, turn_no smallint,
           FOREIGN KEY (fc_id, season_id, turn_no) REFERENCES formation_change (id, season_id, turn_no))$q$, '00000');
CALL pg_temp.check_err('6 DDL', 'D7b: 복합 FK (fc_id, season_id) → formation_change (id, season_id) 는 컬럼 목록이 정확히 같은 UNIQUE가 없어 42830 (erd 7.3)',
    $q$CREATE TABLE zz_fc_ref (id bigint PRIMARY KEY, fc_id bigint, season_id bigint,
           FOREIGN KEY (fc_id, season_id) REFERENCES formation_change (id, season_id))$q$, '42830');
CALL pg_temp.check_err('6 DDL', 'D7c: 복합 FK (tf_id, season_id) → turn_feed (id, season_id) 는 대상 UNIQUE가 있어 만들어진다 (P0-07)',
    $q$CREATE TABLE zz_tf_ref (id bigint PRIMARY KEY, tf_id bigint, season_id bigint,
           FOREIGN KEY (tf_id, season_id) REFERENCES turn_feed (id, season_id))$q$, '00000');

-- 7 판정 ---------------------------------------------------------------------
-- 7.1 커버리지: 9개 테이블의 ck_ 60 + uq_ 제약 7 + uq_ 인덱스 2 + fk_ 14 = 83개 이름이 각각 1건 이상 자기 이름으로 보고되어야 한다.
--     (보고된 이름 = 3·4·5절 성공한 음성 사례 결과의 실제값 둘째 칸, SQLSTATE 23514·23505·23503인 것)
--     가려진 CHECK(다른 CHECK에 가려 단독으로 못 걸리는 것)나 PK에 가려진 UNIQUE가 있으면 여기서 빠진 이름으로 드러난다.
--     유일 인덱스(uq_member_email, uq_lineup_open)는 제약이 아니라 pg_index에서 찾는다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
WITH covered AS (
    SELECT DISTINCT split_part(actual, ' ', 2) AS name
    FROM zz_check_result
    WHERE ok AND section IN ('3 음성', '4 경계', '5 인덱스') AND split_part(actual, ' ', 1) IN ('23514', '23505', '23503')
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
SELECT '7 판정', '커버리지: 자기 이름으로 보고된 ck_·uq_·fk_ 이름 수', '83/83',
       (SELECT count(*) FROM need WHERE name IN (SELECT name FROM covered))::text || '/' || (SELECT count(*) FROM need)::text
       || COALESCE(' 빠짐: ' || (SELECT string_agg(name, ', ' ORDER BY name) FROM need WHERE name NOT IN (SELECT name FROM covered)), ''),
       (SELECT count(*) FROM need WHERE name IN (SELECT name FROM covered)) = 83 AND (SELECT count(*) FROM need) = 83;

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
        RAISE EXCEPTION 'P0-06 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 8 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 17개 테이블(01의 8 + 02의 9)의 행 0, public의 zz_ 객체 0, 이 파일 9개 테이블의 제약 90개(PK 9 + UQ 7 + FK 14 + CK 60)와
-- 인덱스 27개(PK 9 + UQ 7 + UQI 2 + idx_ 9)가 그대로다. 01 테이블의 제약·인덱스 개수는 세지 않는다(01 검증의 몫).
-- identity 값 소비만 되돌아오지 않는다 (db/README.md).
DO $$
DECLARE
    v_t    text;
    v_n    integer;
    v_rows integer := 0;
    v_zz   integer;
    v_con  integer;
    v_idx  integer;
BEGIN
    -- pg_temp 도우미는 ROLLBACK과 함께 사라졌으므로 여기서는 직접 센다.
    FOREACH v_t IN ARRAY ARRAY['member', 'season', 'season_path', 'turn', 'turn_price', 'lineup', 'formation_change', 'turn_feed', 'reconciliation_log',
                             'asset_group', 'asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention'] LOOP
        EXECUTE format('SELECT count(*) FROM public.%I', v_t) INTO v_n;
        v_rows := v_rows + v_n;
    END LOOP;
    SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
         + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%') INTO v_zz;
    SELECT count(*) INTO v_con FROM pg_constraint
    WHERE contype IN ('p', 'u', 'f', 'c') AND conrelid IN (SELECT to_regclass('public.' || t) FROM unnest(ARRAY['member', 'season', 'season_path', 'turn', 'turn_price',
          'lineup', 'formation_change', 'turn_feed', 'reconciliation_log']) AS t);
    SELECT count(*) INTO v_idx FROM pg_indexes
    WHERE schemaname = 'public' AND tablename IN ('member', 'season', 'season_path', 'turn', 'turn_price', 'lineup', 'formation_change', 'turn_feed', 'reconciliation_log');
    IF (v_rows, v_zz, v_con, v_idx) <> (0, 0, 90, 27) THEN
        PERFORM setval('pg_temp.zz_verdict', 1);
        RAISE EXCEPTION 'P0-06 흔적 확인 실패: 행 % (기대 0), zz_ 객체 % (기대 0), 제약 % (기대 90), 인덱스 % (기대 27)', v_rows, v_zz, v_con, v_idx;
    END IF;
    -- 단언을 모두 통과했고 1–7절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_06_check;
