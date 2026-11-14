-- =====================================================================
-- my Fund Manager Phase 0 — DDL ① 검증 SQL (P0-05)
-- 대상: db/01_schema_history.sql이 만든 8개 테이블 (asset_group, asset, asset_position, month_block,
--       asset_month_return, fx_month, month_event, asset_month_attention)
-- 근거: docs/erd.md 5.3(엔티티 정의서), 5.4(제약과 인덱스, 개수 표), 5.5(검증 규칙 H1), 9절 P0-05
-- 실행: psql -X -q -v ON_ERROR_STOP=1 -d <검증용 DB> -f db/01_schema_history_check.sql
--       스키마 파일을 먼저 실행해 둔다. 검증용 DB(antlab_test)에서만 돌린다 (identity 값을 소비한다).
-- 판정: 마지막 줄 `p0_05_check`가 `PASS`이고 종료 코드가 0이면 통과다. `PASS`는 1–7절이 모두 통과하고
--       8절 흔적 확인까지 끝났을 때만 나온다 (zz_verdict: 1 = 미완료 또는 실패, 2 = 1–7절 통과·흔적 확인 대기, 0 = 최종 통과).
--       0절 전제가 하나라도 FAIL이면 0절 결과를 보여 준 뒤 픽스처를 넣기 전에 오류로 멈춘다.
--       그 밖에 하나라도 어긋나면 7절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다. 마지막 줄은 PASS가 아니라 FAIL이다.
-- 흔적: 파일 전체가 한 트랜잭션이고 7절 뒤에 ROLLBACK한다. 8개 테이블의 행, 임시 객체가 남지 않는다.
--       identity(번호표) 값 소비만 되돌아오지 않는다.
-- 절:   0 전제(FAIL이면 여기서 멈춤) → 1 구조 → 2 양성 → 3 음성 DML → 4 저장 정밀도 경계 → 5 검증 규칙 H1 예시 → 6 음성 DDL
--       → 7 판정 → ROLLBACK → 8 흔적 확인
-- 판정 함수(pg_temp 프로시저): check_err(실패해야 정상) · check_ok(성공해야 정상) · check_eq(값 비교) + 값 질의 도우미 val().
--       호출은 CALL pg_temp.check_err(...) 처럼 pg_temp. 접두가 필요하다.
-- 02·03 검증 파일이 복사하는 묶음 (일부만 복사하면 헛 PASS 길이 다시 열린다):
--       ① 맨 앞 CREATE TEMP SEQUENCE zz_verdict + pg_temp 존재 단언 + setval('pg_temp.zz_verdict', 1) (모든 참조는 pg_temp. 한정)   ② 결과 표 zz_check_result
--       ③ 기대 목록 표 3개(zz_expect_*)의 모양   ④ 프로시저 check_err·check_ok·check_eq + 함수 val()
--       ⑤ 0절 뒤 멈춤과 7.5 판정(→ 표식 2 또는 1, RAISE)   ⑥ 8절 흔적 확인(→ 표식 0)과 마지막 CASE
--       파일마다 바뀌는 것: 기대 목록 내용, 사례, 테이블 이름 목록, 기대 개수(71·21), 메시지의 P0-05.
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
    section  text    NOT NULL,   -- '0 전제' '1 구조' '2 양성' '3 음성' '4 경계' '5 H1' '6 DDL' '7 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값 (SQLSTATE + 제약 이름, 개수, 정의 ...)
    actual   text,               -- 실제값 (같은 형식)
    ok       boolean NOT NULL
);

-- 기대 목록: erd 5.3·5.4를 그대로 옮겼다 (정의는 $$ ... $$ 안에 erd 글자 그대로)
CREATE TEMP TABLE zz_expect_count (tbl text, tbl_comment text, pk int, uq int, fk int, ck int, idx int);       -- 8행
CREATE TEMP TABLE zz_expect_constraint (name text, kind text, tbl text, def text);                              -- 76행
CREATE TEMP TABLE zz_expect_column (tbl text, pos int, col text, typ text, nn boolean, dflt text, ident boolean, tag text); -- 81행

INSERT INTO zz_expect_count VALUES
    ('asset_group',           '종목 그룹',       1, 1, 0, 2,  0),
    ('asset',                 '종목',           1, 1, 1, 7,  1),
    ('asset_position',        '종목 기준일',     1, 1, 1, 11, 1),
    ('month_block',           '월 블록',        1, 1, 0, 12, 0),
    ('asset_month_return',    '종목 달 수익률',  1, 1, 2, 2,  1),
    ('fx_month',              '달 환율 변화',    1, 1, 1, 2,  0),
    ('month_event',           '달 사건 태그',    1, 1, 1, 7,  1),
    ('asset_month_attention', '종목 달 관심 지수', 1, 1, 2, 4,  1);

INSERT INTO zz_expect_constraint (name, kind, tbl, def) VALUES
    ('pk_asset_group', 'PK', 'asset_group', $$(id)$$),
    ('uq_asset_group_code', 'UQ', 'asset_group', $$(code)$$),
    ('ck_asset_group_code_format', 'CK', 'asset_group', $$code ~ '^[A-Z][A-Z0-9_]*$'$$),
    ('ck_asset_group_kind', 'CK', 'asset_group', $$kind IN ('SECTOR','ASSET_CLASS')$$),
    ('pk_asset', 'PK', 'asset', $$(id)$$),
    ('uq_asset_market_ticker', 'UQ', 'asset', $$(market, ticker)$$),
    ('fk_asset_asset_group', 'FK', 'asset', $$(asset_group_id) → asset_group.id (R1)$$),
    ('ck_asset_market', 'CK', 'asset', $$market IN ('KR','US')$$),
    ('ck_asset_ticker_upper', 'CK', 'asset', $$ticker = UPPER(ticker)$$),
    ('ck_asset_asset_type', 'CK', 'asset', $$asset_type IN ('STOCK','ETF')$$),
    ('ck_asset_currency', 'CK', 'asset', $$currency IN ('KRW','USD')$$),
    ('ck_asset_market_currency', 'CK', 'asset', $$(market = 'KR' AND currency = 'KRW') OR (market = 'US' AND currency = 'USD')$$),
    ('ck_asset_return_basis', 'CK', 'asset', $$return_basis IN ('PRICE','TOTAL')$$),
    ('ck_asset_proxy_etf', 'CK', 'asset', $$proxy_index_name IS NULL OR asset_type = 'ETF'$$),
    ('idx_asset_asset_group', 'IDX', 'asset', $$(asset_group_id)$$),
    ('pk_asset_position', 'PK', 'asset_position', $$(id)$$),
    ('uq_asset_position_asset_base_date', 'UQ', 'asset_position', $$(asset_id, base_date)$$),
    ('fk_asset_position_asset', 'FK', 'asset_position', $$(asset_id) → asset.id (R2)$$),
    ('ck_asset_position_base_price', 'CK', 'asset_position', $$base_price > 0 AND base_price <> 'NaN'::numeric$$),
    ('ck_asset_position_base_fx_rate', 'CK', 'asset_position', $$base_fx_rate > 0 AND base_fx_rate <> 'NaN'::numeric$$),
    ('ck_asset_position_market_cap_rank', 'CK', 'asset_position', $$market_cap_rank IS NULL OR market_cap_rank > 0$$),
    ('ck_asset_position_window', 'CK', 'asset_position', $$window_start <= window_end AND window_end < date_trunc('month', base_date::timestamp)::date$$),
    ('ck_asset_position_window_month_start', 'CK', 'asset_position', $$EXTRACT(DAY FROM window_start) = 1 AND EXTRACT(DAY FROM window_end) = 1$$),
    ('ck_asset_position_months_used', 'CK', 'asset_position', $$months_used > 0$$),
    ('ck_asset_position_participation_not_nan', 'CK', 'asset_position', $$up_participation <> 'NaN'::numeric AND down_participation <> 'NaN'::numeric$$),
    ('ck_asset_position_volatility', 'CK', 'asset_position', $$volatility >= 0 AND volatility <> 'NaN'::numeric$$),
    ('ck_asset_position_max_drawdown', 'CK', 'asset_position', $$max_drawdown BETWEEN -1 AND 0$$),
    ('ck_asset_position_system_position', 'CK', 'asset_position', $$system_position IN ('GOALKEEPER','DEFENDER','MIDFIELDER','FORWARD_TARGET','FORWARD_BREAKOUT','FORWARD_DEFENSIVE')$$),
    ('ck_asset_position_judgment_all_or_none', 'CK', 'asset_position', $$num_nulls(window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position) IN (0, 8)$$),
    ('idx_asset_position_base_date', 'IDX', 'asset_position', $$(base_date)$$),
    ('pk_month_block', 'PK', 'month_block', $$(id)$$),
    ('uq_month_block_month_start', 'UQ', 'month_block', $$(month_start)$$),
    ('ck_month_block_month_start', 'CK', 'month_block', $$EXTRACT(DAY FROM month_start) = 1$$),
    ('ck_month_block_rate_dir', 'CK', 'month_block', $$rate_dir IN ('UP','DOWN','NEUTRAL')$$),
    ('ck_month_block_growth_dir', 'CK', 'month_block', $$growth_dir IN ('UP','DOWN','NEUTRAL')$$),
    ('ck_month_block_fx_dir', 'CK', 'month_block', $$fx_dir IN ('UP','DOWN','NEUTRAL')$$),
    ('ck_month_block_sentiment_dir', 'CK', 'month_block', $$sentiment_dir IN ('UP','DOWN','NEUTRAL')$$),
    ('ck_month_block_rate_pair', 'CK', 'month_block', $$(rate_dir IS NULL) = (rate_strength IS NULL) AND (rate_strength IS NULL OR (rate_strength >= 0 AND rate_strength <> 'NaN'::numeric))$$),
    ('ck_month_block_growth_pair', 'CK', 'month_block', $$(growth_dir IS NULL) = (growth_strength IS NULL) AND (growth_strength IS NULL OR (growth_strength >= 0 AND growth_strength <> 'NaN'::numeric))$$),
    ('ck_month_block_fx_pair', 'CK', 'month_block', $$(fx_dir IS NULL) = (fx_strength IS NULL) AND (fx_strength IS NULL OR (fx_strength >= 0 AND fx_strength <> 'NaN'::numeric))$$),
    ('ck_month_block_sentiment_pair', 'CK', 'month_block', $$(sentiment_dir IS NULL) = (sentiment_strength IS NULL) AND (sentiment_strength IS NULL OR (sentiment_strength >= 0 AND sentiment_strength <> 'NaN'::numeric))$$),
    ('ck_month_block_prob_range', 'CK', 'month_block', $$prob_p1 BETWEEN 0 AND 1 AND prob_p2 BETWEEN 0 AND 1 AND prob_p3 BETWEEN 0 AND 1 AND prob_p4 BETWEEN 0 AND 1$$),
    ('ck_month_block_prob_all_or_none', 'CK', 'month_block', $$num_nulls(prob_p1, prob_p2, prob_p3, prob_p4) IN (0, 4)$$),
    ('ck_month_block_prob_sum', 'CK', 'month_block', $$prob_p1 IS NULL OR ABS(prob_p1 + prob_p2 + prob_p3 + prob_p4 - 1) <= 0.0002$$),
    ('pk_asset_month_return', 'PK', 'asset_month_return', $$(id)$$),
    ('uq_asset_month_return_asset_month', 'UQ', 'asset_month_return', $$(asset_id, month_block_id)$$),
    ('fk_asset_month_return_asset', 'FK', 'asset_month_return', $$(asset_id) → asset.id (R3)$$),
    ('fk_asset_month_return_month_block', 'FK', 'asset_month_return', $$(month_block_id) → month_block.id (R4)$$),
    ('ck_asset_month_return_local_return', 'CK', 'asset_month_return', $$local_return >= -1 AND local_return <> 'NaN'::numeric$$),
    ('ck_asset_month_return_return_kind', 'CK', 'asset_month_return', $$return_kind IN ('ACTUAL','PROXY_INDEX')$$),
    ('idx_asset_month_return_month_block', 'IDX', 'asset_month_return', $$(month_block_id)$$),
    ('pk_fx_month', 'PK', 'fx_month', $$(id)$$),
    ('uq_fx_month_month_block_currency', 'UQ', 'fx_month', $$(month_block_id, currency)$$),
    ('fk_fx_month_month_block', 'FK', 'fx_month', $$(month_block_id) → month_block.id (R5)$$),
    ('ck_fx_month_currency', 'CK', 'fx_month', $$currency IN ('USD')$$),
    ('ck_fx_month_fx_change', 'CK', 'fx_month', $$fx_change > -1 AND fx_change <> 'NaN'::numeric$$),
    ('pk_month_event', 'PK', 'month_event', $$(id)$$),
    ('uq_month_event_event_key', 'UQ', 'month_event', $$(event_key)$$),
    ('fk_month_event_month_block', 'FK', 'month_event', $$(month_block_id) → month_block.id (R6)$$),
    ('ck_month_event_category', 'CK', 'month_event', $$category IN ('GEOPOLITICS','TRADE','POLITICS','SUPPLY_SHOCK','FINANCIAL','POLICY','HEALTH')$$),
    ('ck_month_event_event_type_format', 'CK', 'month_event', $$event_type ~ '^[A-Z][A-Z0-9_]*$'$$),
    ('ck_month_event_phase', 'CK', 'month_event', $$phase IN ('START','ONGOING','END')$$),
    ('ck_month_event_outcome_format', 'CK', 'month_event', $$outcome IS NULL OR outcome ~ '^[A-Z][A-Z_]*$'$$),
    ('ck_month_event_direction', 'CK', 'month_event', $$direction IN ('UP','DOWN')$$),
    ('ck_month_event_strength', 'CK', 'month_event', $$strength BETWEEN 1 AND 3$$),
    ('ck_month_event_reaction_change', 'CK', 'month_event', $$reaction_change IS NULL OR (reaction_change >= -1 AND reaction_change <> 'NaN'::numeric)$$),
    ('idx_month_event_month_block', 'IDX', 'month_event', $$(month_block_id)$$),
    ('pk_asset_month_attention', 'PK', 'asset_month_attention', $$(id)$$),
    ('uq_asset_month_attention_asset_month', 'UQ', 'asset_month_attention', $$(asset_id, month_block_id)$$),
    ('fk_asset_month_attention_asset', 'FK', 'asset_month_attention', $$(asset_id) → asset.id (R7)$$),
    ('fk_asset_month_attention_month_block', 'FK', 'asset_month_attention', $$(month_block_id) → month_block.id (R8)$$),
    ('ck_asset_month_attention_window_months', 'CK', 'asset_month_attention', $$window_months BETWEEN 0 AND 12$$),
    ('ck_asset_month_attention_z_window', 'CK', 'asset_month_attention', $$attention_z IS NULL OR window_months >= 2$$),
    ('ck_asset_month_attention_index_needs_z', 'CK', 'asset_month_attention', $$attention_z IS NOT NULL OR attention_index IS NULL$$),
    ('ck_asset_month_attention_not_nan', 'CK', 'asset_month_attention', $$attention_z <> 'NaN'::numeric AND attention_index <> 'NaN'::numeric$$),
    ('idx_asset_month_attention_month_block', 'IDX', 'asset_month_attention', $$(month_block_id)$$);
INSERT INTO zz_expect_column (tbl, pos, col, typ, nn, dflt, ident, tag) VALUES
    ('asset_group', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('asset_group', 2, 'code', 'character varying(30)', true, NULL, false, '[내부]'),
    ('asset_group', 3, 'name', 'character varying(50)', true, NULL, false, '[노출]'),
    ('asset_group', 4, 'kind', 'character varying(11)', true, NULL, false, '[노출]'),
    ('asset_group', 5, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('asset', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('asset', 2, 'market', 'character varying(2)', true, NULL, false, '[노출]'),
    ('asset', 3, 'ticker', 'character varying(12)', true, NULL, false, '[노출]'),
    ('asset', 4, 'name', 'character varying(100)', true, NULL, false, '[노출]'),
    ('asset', 5, 'asset_type', 'character varying(5)', true, NULL, false, '[노출]'),
    ('asset', 6, 'currency', 'character varying(3)', true, NULL, false, '[내부]'),
    ('asset', 7, 'asset_group_id', 'bigint', true, NULL, false, '[노출]'),
    ('asset', 8, 'listed_on', 'date', true, NULL, false, '[복기 전용]'),
    ('asset', 9, 'return_basis', 'character varying(5)', true, NULL, false, '[노출]'),
    ('asset', 10, 'proxy_index_name', 'character varying(100)', false, NULL, false, '[노출]'),
    ('asset', 11, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('asset_position', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('asset_position', 2, 'asset_id', 'bigint', true, NULL, false, '[내부]'),
    ('asset_position', 3, 'base_date', 'date', true, NULL, false, '[내부]'),
    ('asset_position', 4, 'base_price', 'numeric(19,4)', true, NULL, false, '[노출]'),
    ('asset_position', 5, 'base_fx_rate', 'numeric(19,4)', true, NULL, false, '[내부]'),
    ('asset_position', 6, 'market_cap_rank', 'smallint', false, NULL, false, '[내부]'),
    ('asset_position', 7, 'window_start', 'date', false, NULL, false, '[내부]'),
    ('asset_position', 8, 'window_end', 'date', false, NULL, false, '[내부]'),
    ('asset_position', 9, 'months_used', 'smallint', false, NULL, false, '[내부]'),
    ('asset_position', 10, 'up_participation', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('asset_position', 11, 'down_participation', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('asset_position', 12, 'volatility', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('asset_position', 13, 'max_drawdown', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('asset_position', 14, 'system_position', 'character varying(17)', false, NULL, false, '[노출]'),
    ('asset_position', 15, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('month_block', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('month_block', 2, 'month_start', 'date', true, NULL, false, '[복기 전용]'),
    ('month_block', 3, 'rate_dir', 'character varying(7)', false, NULL, false, '[노출]'),
    ('month_block', 4, 'growth_dir', 'character varying(7)', false, NULL, false, '[노출]'),
    ('month_block', 5, 'fx_dir', 'character varying(7)', false, NULL, false, '[노출]'),
    ('month_block', 6, 'sentiment_dir', 'character varying(7)', false, NULL, false, '[노출]'),
    ('month_block', 7, 'rate_strength', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('month_block', 8, 'growth_strength', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('month_block', 9, 'fx_strength', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('month_block', 10, 'sentiment_strength', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('month_block', 11, 'prob_p1', 'numeric(5,4)', false, NULL, false, '[노출]'),
    ('month_block', 12, 'prob_p2', 'numeric(5,4)', false, NULL, false, '[노출]'),
    ('month_block', 13, 'prob_p3', 'numeric(5,4)', false, NULL, false, '[노출]'),
    ('month_block', 14, 'prob_p4', 'numeric(5,4)', false, NULL, false, '[노출]'),
    ('month_block', 15, 'event_summary', 'character varying(200)', false, NULL, false, '[복기 전용]'),
    ('month_block', 16, 'events_reviewed', 'boolean', true, 'false', false, '[내부]'),
    ('month_block', 17, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('asset_month_return', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('asset_month_return', 2, 'asset_id', 'bigint', true, NULL, false, '[내부]'),
    ('asset_month_return', 3, 'month_block_id', 'bigint', true, NULL, false, '[내부]'),
    ('asset_month_return', 4, 'local_return', 'numeric(12,8)', true, NULL, false, '[노출]'),
    ('asset_month_return', 5, 'return_kind', 'character varying(11)', true, '''ACTUAL''::character varying', false, '[노출]'),
    ('asset_month_return', 6, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('fx_month', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('fx_month', 2, 'month_block_id', 'bigint', true, NULL, false, '[내부]'),
    ('fx_month', 3, 'currency', 'character varying(3)', true, NULL, false, '[내부]'),
    ('fx_month', 4, 'fx_change', 'numeric(12,8)', true, NULL, false, '[노출]'),
    ('fx_month', 5, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('month_event', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('month_event', 2, 'event_key', 'character varying(40)', true, NULL, false, '[내부]'),
    ('month_event', 3, 'month_block_id', 'bigint', true, NULL, false, '[내부]'),
    ('month_event', 4, 'category', 'character varying(12)', true, NULL, false, '[노출]'),
    ('month_event', 5, 'event_type', 'character varying(30)', true, NULL, false, '[노출]'),
    ('month_event', 6, 'phase', 'character varying(7)', true, NULL, false, '[노출]'),
    ('month_event', 7, 'outcome', 'character varying(20)', false, NULL, false, '[노출]'),
    ('month_event', 8, 'direction', 'character varying(4)', false, NULL, false, '[노출]'),
    ('month_event', 9, 'strength', 'smallint', true, NULL, false, '[노출]'),
    ('month_event', 10, 'reaction_change', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('month_event', 11, 'title', 'character varying(100)', true, NULL, false, '[복기 전용]'),
    ('month_event', 12, 'source', 'character varying(500)', true, NULL, false, '[복기 전용]'),
    ('month_event', 13, 'hindsight', 'character varying(300)', false, NULL, false, '[복기 전용]'),
    ('month_event', 14, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]'),
    ('asset_month_attention', 1, 'id', 'bigint', true, NULL, true, '[내부]'),
    ('asset_month_attention', 2, 'asset_id', 'bigint', true, NULL, false, '[내부]'),
    ('asset_month_attention', 3, 'month_block_id', 'bigint', true, NULL, false, '[내부]'),
    ('asset_month_attention', 4, 'window_months', 'smallint', true, NULL, false, '[내부]'),
    ('asset_month_attention', 5, 'attention_z', 'numeric(12,8)', false, NULL, false, '[내부]'),
    ('asset_month_attention', 6, 'streak', 'smallint', true, NULL, false, '[노출]'),
    ('asset_month_attention', 7, 'attention_index', 'numeric(12,8)', false, NULL, false, '[노출]'),
    ('asset_month_attention', 8, 'created_at', 'timestamp with time zone', true, 'now()', false, '[내부]');
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

-- 0 전제 -------------------------------------------------------------------
-- 하나라도 FAIL이면 1절 이후 결과를 믿지 않는다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('8개 이름이 모두 public으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '8',
        (SELECT count(*)::text FROM zz_expect_count WHERE to_regclass(tbl) IS NOT NULL AND to_regclass(tbl) = to_regclass('public.' || tbl))),
    ('public에 8개 테이블이 있다', '8',
        (SELECT count(*)::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname IN (SELECT tbl FROM zz_expect_count))),
    ('public의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text)
) AS x(item, expected, actual);

-- 8개 테이블이 비어 있다 (비어 있지 않으면 중복 키 같은 우연한 충돌이 생긴다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', '8개 테이블의 행 수 합', '0', s.n::text, s.n = 0
FROM (SELECT (SELECT count(*) FROM asset_group) + (SELECT count(*) FROM asset)
           + (SELECT count(*) FROM asset_position) + (SELECT count(*) FROM month_block)
           + (SELECT count(*) FROM asset_month_return) + (SELECT count(*) FROM fx_month)
           + (SELECT count(*) FROM month_event) + (SELECT count(*) FROM asset_month_attention) AS n) AS s;

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
        RAISE EXCEPTION 'P0-05 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 전제를 맞춘 뒤 다시 실행한다)', v_fail;
    END IF;
END $$;

-- 1 구조 -------------------------------------------------------------------
-- 모든 카탈로그 조회는 이 8개 테이블만 본다. 종류는 contype IN ('p','u','f','c')로 고정한다.

-- 1.1 테이블별 개수 (erd 5.4 개수 표)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '개수 PK·UQ·FK·CK·idx_: ' || e.tbl,
       format('pk=%s uq=%s fk=%s ck=%s idx=%s', e.pk, e.uq, e.fk, e.ck, e.idx),
       format('pk=%s uq=%s fk=%s ck=%s idx=%s', a.pk, a.uq, a.fk, a.ck, a.idx),
       (e.pk, e.uq, e.fk, e.ck, e.idx) IS NOT DISTINCT FROM (a.pk, a.uq, a.fk, a.ck, a.idx)
FROM zz_expect_count e
CROSS JOIN LATERAL (
    SELECT (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.' || e.tbl) AND contype = 'p') AS pk,
           (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.' || e.tbl) AND contype = 'u') AS uq,
           (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.' || e.tbl) AND contype = 'f') AS fk,
           (SELECT count(*) FROM pg_constraint WHERE conrelid = to_regclass('public.' || e.tbl) AND contype = 'c') AS ck,
           (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename = e.tbl AND indexname LIKE 'idx\_%') AS idx
) a
ORDER BY e.tbl;

-- 1.2 인덱스 총 개수 (PK 8 + UQ 8 + idx_ 5 = 21)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '8개 테이블의 인덱스 총 개수 (PK + UQ + idx_)', '21', n::text, n = 21
FROM (SELECT count(*) AS n FROM pg_indexes WHERE schemaname = 'public' AND tablename IN (SELECT tbl FROM zz_expect_count)) s;

-- 1.3 테이블 코멘트 (논리명)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', '테이블 코멘트: ' || e.tbl, e.tbl_comment, obj_description(to_regclass('public.' || e.tbl), 'pg_class'),
       e.tbl_comment IS NOT DISTINCT FROM obj_description(to_regclass('public.' || e.tbl), 'pg_class')
FROM zz_expect_count e
ORDER BY e.tbl;

-- 1.4 제약·인덱스 76개의 이름·종류·테이블이 1:1 (빠진 것도 남는 것도 한 행씩 FAIL)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
WITH act AS (
    SELECT c.conname::text AS name,
           CASE c.contype WHEN 'p' THEN 'PK' WHEN 'u' THEN 'UQ' WHEN 'f' THEN 'FK' WHEN 'c' THEN 'CK' END AS kind,
           (SELECT relname::text FROM pg_class WHERE oid = c.conrelid) AS tbl
    FROM pg_constraint c
    WHERE c.contype IN ('p', 'u', 'f', 'c')
      AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)
    UNION ALL
    SELECT indexname::text, 'IDX', tablename::text
    FROM pg_indexes
    WHERE schemaname = 'public' AND tablename IN (SELECT tbl FROM zz_expect_count) AND indexname LIKE 'idx\_%'
)
SELECT '1 구조', '이름·종류·테이블: ' || COALESCE(e.name, a.name),
       COALESCE(e.kind || ' ' || e.tbl, '(없어야 함)'), COALESCE(a.kind || ' ' || a.tbl, '(없음)'),
       e.kind IS NOT DISTINCT FROM a.kind AND e.tbl IS NOT DISTINCT FROM a.tbl
FROM zz_expect_constraint e FULL JOIN act a ON a.name = e.name
ORDER BY COALESCE(e.name, a.name);

-- 1.5 CHECK 47개의 정의 — 왕복 정규화
--     erd 5.4의 식을 빈 임시 사본(LIKE: 컬럼 이름·타입·NOT NULL만 복사)에 CHECK로 붙여 PostgreSQL이 다시 파싱하게 하고,
--     실제 제약과 pg_get_constraintdef 출력끼리 비교한다. 괄호·캐스트·IN·BETWEEN 표기 차이에 흔들리지 않는다.
--     erd 식에 오타가 있으면 ALTER가 실패해 FAIL로 드러난다.
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

-- 1.7 FK 8개: 컬럼 → 대상 테이블.컬럼 (erd의 (R1) 꼬리표는 뗀다), 동작 RESTRICT·RESTRICT, 지연 없음, MATCH SIMPLE
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

-- 1.8 idx_ 5개의 정의
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'idx_ 정의: ' || e.name,
       format('CREATE INDEX %s ON public.%s USING btree %s', e.name, e.tbl, e.def), i.indexdef,
       format('CREATE INDEX %s ON public.%s USING btree %s', e.name, e.tbl, e.def) IS NOT DISTINCT FROM i.indexdef
FROM zz_expect_constraint e
LEFT JOIN pg_indexes i ON i.schemaname = 'public' AND i.indexname = e.name
WHERE e.kind = 'IDX'
ORDER BY e.name;

-- 1.9 FK 인덱스 짝: 모든 FK의 첫 컬럼이 같은 테이블 어떤 인덱스의 첫 컬럼이다 (erd 2절, 8/8)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 인덱스 짝: ' || c.conname, 'true',
       EXISTS (SELECT 1 FROM pg_index i
               WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indkey[0] = c.conkey[1])::text,
       EXISTS (SELECT 1 FROM pg_index i
               WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indkey[0] = c.conkey[1])
FROM pg_constraint c
WHERE c.contype = 'f' AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)
ORDER BY c.conname;

-- 1.10 컬럼 81개: 순서·이름·타입·NULL·기본값·identity(ALWAYS)·노출 태그 (erd 5.3)
--      노출 태그는 컬럼 코멘트 앞머리 `[노출]`/`[복기 전용]`/`[내부]`다. 형식이 다르면 태그가 NULL이 되어 FAIL이다.
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

-- 1.12 CHECK·FK가 모두 검증된 상태(NOT VALID 없음)이고 FK 내부 트리거가 모두 켜져 있다 (4개 × 8 = 32)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'NOT VALID 제약 수 (CHECK·FK)', '0', n::text, n = 0
FROM (SELECT count(*) AS n FROM pg_constraint
      WHERE contype IN ('c', 'f') AND NOT convalidated
        AND conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)) s;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 구조', 'FK 내부 트리거 (전체 / 켜져 있지 않은 것)', 'total=32 not_enabled=0',
       format('total=%s not_enabled=%s', s.total, s.off), (s.total, s.off) = (32, 0)
FROM (SELECT count(*) AS total, count(*) FILTER (WHERE t.tgenabled <> 'O') AS off
      FROM pg_trigger t JOIN pg_constraint c ON c.oid = t.tgconstraint
      WHERE c.contype = 'f' AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)) s;


-- 2 양성 (과잉 제약이 없다는 증거) ---------------------------------------------
-- 여기서 만든 행은 뒤 절의 픽스처다. 자연 키는 ZZ_ / ZZ 접두라 다른 데이터와 겹치지 않고, 끝의 ROLLBACK이 지운다.

CALL pg_temp.check_ok('2 양성', 'asset_group: ZZ_GROUP (SECTOR)',
    $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_GROUP', 'zz 업종', 'SECTOR')$q$);
CALL pg_temp.check_ok('2 양성', 'asset_group: ZZ_CLASS (ASSET_CLASS), 숫자·밑줄 포함 코드',
    $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_CLASS', 'zz 자산군', 'ASSET_CLASS'), ('ZZ_G1_X2', 'zz 숫자', 'SECTOR')$q$);

CALL pg_temp.check_ok('2 양성', 'asset: KR STOCK ZZ0001 KRW PRICE',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', 'ZZ0001', 'zz 국내 주식', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$);
CALL pg_temp.check_ok('2 양성', 'asset: KR ETF ZZ0002 + 대체 지수명 (ETF만 가능)',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name)
       VALUES ('KR', 'ZZ0002', 'zz 국내 ETF', 'ETF', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2010-05-04', 'PRICE', 'zz 지수')$q$);
CALL pg_temp.check_ok('2 양성', 'asset: US STOCK ZZAAPL USD TOTAL',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('US', 'ZZAAPL', 'zz 미국 주식', 'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '1980-12-12', 'TOTAL')$q$);
CALL pg_temp.check_ok('2 양성', 'asset: US ETF ZZSPY USD TOTAL (그룹 ZZ_CLASS)',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('US', 'ZZSPY', 'zz 미국 ETF', 'ETF', 'USD', (SELECT id FROM asset_group WHERE code = 'ZZ_CLASS'), '1993-01-29', 'TOTAL')$q$);
CALL pg_temp.check_ok('2 양성', 'asset: 숫자 코드 005930, 점이 든 코드 BRK.B',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', '005930', 'zz 숫자 코드', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '1975-06-11', 'PRICE'),
              ('US', 'BRK.B', 'zz 점 코드', 'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '1996-05-09', 'TOTAL')$q$);
-- 현재 동작 기록(erd 결정 변경 아님): 앞뒤 공백은 DB가 막지 않는다. 'ZZAAPL'과 'ZZAAPL '은 다른 값이라 UNIQUE를 통과한다.
-- 그래서 적재(P0-08)가 trim하고 공백 포함 값을 검사 쿼리로 0건 확인한다.
CALL pg_temp.check_ok('2 양성', 'asset: 뒤 공백이 든 ticker ''ZZAAPL ''가 통과한다 (현재 동작 기록)',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('US', 'ZZAAPL ', 'zz 공백', 'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '1980-12-12', 'TOTAL')$q$);
-- 지우기·경계 사례용 종목
CALL pg_temp.check_ok('2 양성', 'asset: 사례용 종목 ZZBND, ZZDEL1, ZZDEL2, ZZDEL3 (KR STOCK)',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       SELECT 'KR', t, 'zz ' || t, 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE'
       FROM unnest(ARRAY['ZZBND', 'ZZDEL1', 'ZZDEL2', 'ZZDEL3']) AS t$q$);

-- month_block
CALL pg_temp.check_ok('2 양성', 'month_block: 축·확률이 전부 NULL인 달 (Phase 0 표본 모양)',
    $q$INSERT INTO month_block (month_start) VALUES ('1990-01-01')$q$);
CALL pg_temp.check_eq('2 양성', 'month_block 기본값: events_reviewed = false', 'false',
    pg_temp.val($v$SELECT events_reviewed::text FROM month_block WHERE month_start = '1990-01-01'$v$));
CALL pg_temp.check_eq('2 양성', 'month_block 기본값: created_at이 채워진다', 'true',
    pg_temp.val($v$SELECT (created_at IS NOT NULL)::text FROM month_block WHERE month_start = '1990-01-01'$v$));
CALL pg_temp.check_ok('2 양성', 'month_block: 축 4개 + 강도 0 + 확률 합 1.0000',
    $q$INSERT INTO month_block (month_start, rate_dir, growth_dir, fx_dir, sentiment_dir,
                                rate_strength, growth_strength, fx_strength, sentiment_strength,
                                prob_p1, prob_p2, prob_p3, prob_p4, event_summary, events_reviewed)
       VALUES ('1990-02-01', 'UP', 'DOWN', 'NEUTRAL', 'UP', 0, 1.5, 0.25, 3.12345678, 0.1, 0.2, 0.3, 0.4, 'zz 요약', true)$q$);
CALL pg_temp.check_ok('2 양성', 'month_block: 확률 (0.3333, 0.3333, 0.3333, 0.0001) 합 1.0000',
    $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('1990-03-01', 0.3333, 0.3333, 0.3333, 0.0001)$q$);
CALL pg_temp.check_ok('2 양성', 'month_block: 확률 합 1.0002 (허용 경계)',
    $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('1990-04-01', 0.5001, 0.5001, 0, 0)$q$);
CALL pg_temp.check_ok('2 양성', 'month_block: 확률 합 0.9998 (허용 경계)',
    $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('1990-05-01', 0.4999, 0.4999, 0, 0)$q$);
CALL pg_temp.check_ok('2 양성', 'month_block: 지우기·경계 사례용 달 (1991-01 ~ 04, 1993-01 ~ 12)',
    $q$INSERT INTO month_block (month_start)
       SELECT d::date FROM generate_series('1991-01-01'::date, '1991-04-01'::date, '1 month') AS d
       UNION ALL
       SELECT d::date FROM generate_series('1993-01-01'::date, '1993-12-01'::date, '1 month') AS d$q$);

-- asset_position (기준일 2025-04-02: USD 종목 2개는 환율이 같고 KRW 종목은 1 — 5절 H1 예시의 일관된 픽스처)
CALL pg_temp.check_ok('2 양성', 'asset_position: 판정 8컬럼이 전부 NULL인 행 (P0-08 직후 모양), KRW 환율 1',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank)
       VALUES ((SELECT id FROM asset WHERE market = 'KR' AND ticker = 'ZZ0001'), '2025-04-02', 70000, 1, 5)$q$);
CALL pg_temp.check_ok('2 양성', 'asset_position: 판정 8컬럼을 전부 채운 행 (window_end = 기준일 전달 1일)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE market = 'US' AND ticker = 'ZZAAPL'), '2025-04-02', 200.5, 1450.1234, 3,
               '2022-04-01', '2025-03-01', 36, 1.1, 0.9, 0.05, -0.35, 'FORWARD_TARGET')$q$);
CALL pg_temp.check_ok('2 양성', 'asset_position: ETF market_cap_rank NULL, 판정 NULL (USD, 환율 ZZAAPL과 같음)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)
       VALUES ((SELECT id FROM asset WHERE market = 'US' AND ticker = 'ZZSPY'), '2025-04-02', 500.25, 1450.1234)$q$);
CALL pg_temp.check_ok('2 양성', 'asset_position: 전부 NULL인 행을 한 문장 UPDATE로 판정 8컬럼 모두 채움',
    $q$UPDATE asset_position
       SET window_start = '2022-04-01', window_end = '2025-03-01', months_used = 36,
           up_participation = 1.05, down_participation = 0.8, volatility = 0.04, max_drawdown = -0.2, system_position = 'MIDFIELDER'
       WHERE asset_id = (SELECT id FROM asset WHERE market = 'US' AND ticker = 'ZZSPY') AND base_date = '2025-04-02'$q$);
CALL pg_temp.check_eq('2 양성', 'asset_position: UPDATE 뒤 판정 8컬럼의 NULL 수', '0',
    pg_temp.val($v$SELECT num_nulls(window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position)::text
     FROM asset_position WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZSPY') AND base_date = '2025-04-02'$v$));
CALL pg_temp.check_ok('2 양성', 'asset_position: 기준일이 달의 1일 (2025-05-01), window_end = 2025-04-01',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0002'), '2025-05-01', 10000, 1, '2025-04-01', '2025-04-01', 1, 1, 1, 0, 0, 'GOALKEEPER')$q$);
-- 시간대와 무관하다 (::timestamp 캐스트가 IMMUTABLE 오버로드를 고르기 때문). max_drawdown 경계 0과 -1도 함께 본다.
SET LOCAL timezone = 'America/Santiago';
CALL pg_temp.check_ok('2 양성', 'asset_position: window CHECK, 시간대 America/Santiago에서도 통과 (max_drawdown 0)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0001'), '2026-01-02', 71000, 1, '2023-01-01', '2025-12-01', 36, 1, 1, 0.1, 0, 'DEFENDER')$q$);
SET LOCAL timezone = 'UTC';
CALL pg_temp.check_ok('2 양성', 'asset_position: window CHECK, 시간대 UTC에서도 통과 (max_drawdown -1)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0001'), '2027-01-04', 72000, 1, '2024-01-01', '2026-12-01', 36, 1, 1, 0.1, -1, 'FORWARD_DEFENSIVE')$q$);
RESET timezone;
CALL pg_temp.check_eq('2 양성', 'RESET 뒤 시간대가 Asia/Seoul로 돌아왔다', 'Asia/Seoul', current_setting('TimeZone'));

-- asset_month_return, fx_month
CALL pg_temp.check_ok('2 양성', 'asset_month_return: local_return = -1 (경계), return_kind 기본값',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0001'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), -1)$q$);
CALL pg_temp.check_eq('2 양성', 'asset_month_return 기본값: return_kind = ACTUAL', 'ACTUAL',
    pg_temp.val($v$SELECT return_kind FROM asset_month_return WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001')$v$));
CALL pg_temp.check_ok('2 양성', 'asset_month_return: PROXY_INDEX 행, 다른 종목·달',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0002'), (SELECT id FROM month_block WHERE month_start = '1990-02-01'), 0.0123, 'PROXY_INDEX'),
              ((SELECT id FROM asset WHERE ticker = 'ZZAAPL'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 0.5, 'ACTUAL')$q$);
CALL pg_temp.check_ok('2 양성', 'fx_month: USD 변화율',
    $q$INSERT INTO fx_month (month_block_id, currency, fx_change)
       VALUES ((SELECT id FROM month_block WHERE month_start = '1990-01-01'), 'USD', -0.5),
              ((SELECT id FROM month_block WHERE month_start = '1990-02-01'), 'USD', 0.01)$q$);

-- month_event
CALL pg_temp.check_ok('2 양성', 'month_event: 노출 컬럼 코드값이 유효한 행 (US_MIDTERM START MAJORITY_LOST UP, strength 2)',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, direction, strength, reaction_change, title, source)
       VALUES ('ZZ_EV1', (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 'POLITICS', 'US_MIDTERM', 'START', 'MAJORITY_LOST', 'UP', 2, 0.28, 'zz 사건', 'zz 출처')$q$);
CALL pg_temp.check_ok('2 양성', 'month_event: strength 1과 3 (경계), direction·outcome·reaction_change NULL, hindsight 채움',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source, hindsight)
       VALUES ('ZZ_EV2', (SELECT id FROM month_block WHERE month_start = '1990-02-01'), 'TRADE', 'TARIFF', 'ONGOING', 1, 'zz 사건 2', 'zz 출처', 'zz 사후'),
              ('ZZ_EV3', (SELECT id FROM month_block WHERE month_start = '1990-02-01'), 'HEALTH', 'PANDEMIC', 'END', 3, 'zz 사건 3', 'zz 출처', NULL)$q$);
CALL pg_temp.check_ok('2 양성', 'month_event: 뒤 공백이 든 event_key ''ZZ_EV1 ''가 통과한다 (현재 동작 기록)',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source)
       VALUES ('ZZ_EV1 ', (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 'FINANCIAL', 'BANK_FAILURE', 'START', 2, 'zz 공백', 'zz 출처')$q$);

-- asset_month_attention
CALL pg_temp.check_ok('2 양성', 'asset_month_attention: window_months 0 (z 없음), 12 (z 있음), 2 (z 있음, 경계)',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak, attention_index)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0001'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 0, NULL, 0, NULL),
              ((SELECT id FROM asset WHERE ticker = 'ZZ0001'), (SELECT id FROM month_block WHERE month_start = '1990-02-01'), 12, 1.5, 3, 0.7),
              ((SELECT id FROM asset WHERE ticker = 'ZZAAPL'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 2, 0.1, -2, NULL)$q$);


-- 3 음성 DML (실패해야 정상) -----------------------------------------------------
-- 사례마다 유효한 행에서 한 컬럼만 어긴다. 한 행이 CHECK를 여러 개 어기면 NOT NULL(23502)이 먼저, 그다음 CHECK를
-- 제약 이름의 알파벳 순으로 검사해 첫 위반 하나만 보고한다. 둘 이상이 걸리는 사례는 주석에 먼저 보고되는 이름을 적었다.
-- RESTRICT 사례는 자식 테이블이 하나뿐인 부모를 쓴다 (자식이 둘 이상이면 어느 FK가 먼저 보고될지 이름에 기대게 된다).

-- 3.1 asset_group
CALL pg_temp.check_err('3 음성', 'asset_group: code 소문자 ''semi''', $q$INSERT INTO asset_group (code, name, kind) VALUES ('semi', 'x', 'SECTOR')$q$, '23514', 'ck_asset_group_code_format');
CALL pg_temp.check_err('3 음성', 'asset_group: code 숫자로 시작 ''1SEMI''', $q$INSERT INTO asset_group (code, name, kind) VALUES ('1SEMI', 'x', 'SECTOR')$q$, '23514', 'ck_asset_group_code_format');
CALL pg_temp.check_err('3 음성', 'asset_group: code에 하이픈 ''S-EMI''', $q$INSERT INTO asset_group (code, name, kind) VALUES ('S-EMI', 'x', 'SECTOR')$q$, '23514', 'ck_asset_group_code_format');
CALL pg_temp.check_err('3 음성', 'asset_group: kind = MARKET', $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_NEW', 'x', 'MARKET')$q$, '23514', 'ck_asset_group_kind');
CALL pg_temp.check_err('3 음성', 'asset_group: kind 소문자 ''sector''', $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_NEW', 'x', 'sector')$q$, '23514', 'ck_asset_group_kind');
CALL pg_temp.check_err('3 음성', 'asset_group: 같은 code ZZ_GROUP', $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_GROUP', 'x', 'SECTOR')$q$, '23505', 'uq_asset_group_code');
CALL pg_temp.check_err('3 음성', 'asset_group: name NULL', $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_NEW', NULL, 'SECTOR')$q$, '23502', 'name');
CALL pg_temp.check_err('3 음성', 'asset_group: code NULL', $q$INSERT INTO asset_group (code, name, kind) VALUES (NULL, 'x', 'SECTOR')$q$, '23502', 'code');
CALL pg_temp.check_err('3 음성', 'asset_group: kind NULL', $q$INSERT INTO asset_group (code, name, kind) VALUES ('ZZ_NEW', 'x', NULL)$q$, '23502', 'kind');
CALL pg_temp.check_err('3 음성', 'asset_group: id를 직접 지정 (GENERATED ALWAYS)', $q$INSERT INTO asset_group (id, code, name, kind) VALUES (999999, 'ZZ_NEW', 'x', 'SECTOR')$q$, '428C9');

-- 3.2 asset (기준 행: KR STOCK KRW PRICE, 상장일 2000-01-03)
CALL pg_temp.check_err('3 음성', 'asset: market = JP (currency KRW) — ck_asset_market_currency도 어기지만 이름 순으로 ck_asset_market이 먼저',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('JP', 'ZZNEW', 'x', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$, '23514', 'ck_asset_market');
CALL pg_temp.check_err('3 음성', 'asset: ticker 소문자 ''aapl''',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('US', 'aapl', 'x', 'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'TOTAL')$q$, '23514', 'ck_asset_ticker_upper');
CALL pg_temp.check_err('3 음성', 'asset: asset_type = FUND',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', 'ZZNEW', 'x', 'FUND', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$, '23514', 'ck_asset_asset_type');
CALL pg_temp.check_err('3 음성', 'asset: KR + EUR — ck_asset_market_currency도 어기지만 이름 순으로 ck_asset_currency가 먼저',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', 'ZZNEW', 'x', 'STOCK', 'EUR', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$, '23514', 'ck_asset_currency');
CALL pg_temp.check_err('3 음성', 'asset: KR + USD',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', 'ZZNEW', 'x', 'STOCK', 'USD', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$, '23514', 'ck_asset_market_currency');
CALL pg_temp.check_err('3 음성', 'asset: US + KRW',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('US', 'ZZNEW', 'x', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'TOTAL')$q$, '23514', 'ck_asset_market_currency');
CALL pg_temp.check_err('3 음성', 'asset: return_basis = NET',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', 'ZZNEW', 'x', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'NET')$q$, '23514', 'ck_asset_return_basis');
CALL pg_temp.check_err('3 음성', 'asset: STOCK에 대체 지수명',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name)
       VALUES ('KR', 'ZZNEW', 'x', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE', 'x 지수')$q$, '23514', 'ck_asset_proxy_etf');
CALL pg_temp.check_err('3 음성', 'asset: 같은 (market, ticker) = (KR, ZZ0001)',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', 'ZZ0001', 'x', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$, '23505', 'uq_asset_market_ticker');
CALL pg_temp.check_err('3 음성', 'asset: 없는 그룹 999999',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', 'ZZNEW', 'x', 'STOCK', 'KRW', 999999, '2000-01-03', 'PRICE')$q$, '23503', 'fk_asset_asset_group');
CALL pg_temp.check_err('3 음성', 'asset: listed_on NULL',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', 'ZZNEW', 'x', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), NULL, 'PRICE')$q$, '23502', 'listed_on');
CALL pg_temp.check_err('3 음성', 'asset: ticker NULL',
    $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
       VALUES ('KR', NULL, 'x', 'STOCK', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$, '23502', 'ticker');

-- 3.3 asset_position (기준 행: ZZBND, 기준일 2030-01-02, 판정 8컬럼은 유효하게 채움 — window_end 2029-12-01)
CALL pg_temp.check_err('3 음성', 'asset_position: base_price = 0',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 0, 1)$q$, '23514', 'ck_asset_position_base_price');
CALL pg_temp.check_err('3 음성', 'asset_position: base_fx_rate = 0',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 100, 0)$q$, '23514', 'ck_asset_position_base_fx_rate');
CALL pg_temp.check_err('3 음성', 'asset_position: market_cap_rank = 0',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 100, 1, 0)$q$, '23514', 'ck_asset_position_market_cap_rank');
-- erd 9절 필수 사례: 기준일이 속한 달의 window_end (기준일 2025-04-02, window_end 2025-04-01)
CALL pg_temp.check_err('3 음성', 'asset_position: 기준일 2025-04-02인데 window_end = 2025-04-01 (기준일이 속한 달) [erd 9절 필수]',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-04-01', 36, 1, 1, 0.1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_window');
CALL pg_temp.check_err('3 음성', 'asset_position: 기준일 2025-04-01(달의 1일)인데 window_end = 2025-04-01',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-01', 100, 1, '2022-04-01', '2025-04-01', 36, 1, 1, 0.1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_window');
CALL pg_temp.check_err('3 음성', 'asset_position: window_start > window_end',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2025-03-01', '2025-02-01', 3, 1, 1, 0.1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_window');
CALL pg_temp.check_err('3 음성', 'asset_position: window_start가 달의 1일이 아님 (2022-01-15)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-01-15', '2025-03-01', 36, 1, 1, 0.1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_window_month_start');
CALL pg_temp.check_err('3 음성', 'asset_position: window_end가 달의 1일이 아님 (2025-03-15)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-03-15', 36, 1, 1, 0.1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_window_month_start');
CALL pg_temp.check_err('3 음성', 'asset_position: months_used = 0',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-03-01', 0, 1, 1, 0.1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_months_used');
CALL pg_temp.check_err('3 음성', 'asset_position: volatility = -0.01',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-03-01', 36, 1, 1, -0.01, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_volatility');
CALL pg_temp.check_err('3 음성', 'asset_position: max_drawdown = 0.1 (0 초과)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-03-01', 36, 1, 1, 0.1, 0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_max_drawdown');
CALL pg_temp.check_err('3 음성', 'asset_position: max_drawdown = -1.1 (-1 미만)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-03-01', 36, 1, 1, 0.1, -1.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_max_drawdown');
CALL pg_temp.check_err('3 음성', 'asset_position: system_position = STRIKER',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-03-01', 36, 1, 1, 0.1, -0.1, 'STRIKER')$q$, '23514', 'ck_asset_position_system_position');
CALL pg_temp.check_err('3 음성', 'asset_position: system_position 소문자 ''defender''',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-03-01', 36, 1, 1, 0.1, -0.1, 'defender')$q$, '23514', 'ck_asset_position_system_position');
CALL pg_temp.check_err('3 음성', 'asset_position: 판정 8컬럼 중 7개만 (volatility 없음)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                   up_participation, down_participation, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, '2022-04-01', '2025-03-01', 36, 1, 1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_judgment_all_or_none');
CALL pg_temp.check_err('3 음성', 'asset_position: 판정 8컬럼 중 1개만 (volatility만)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, volatility) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2025-04-02', 100, 1, 0.1)$q$, '23514', 'ck_asset_position_judgment_all_or_none');
CALL pg_temp.check_err('3 음성', 'asset_position: 전부 채운 행을 UPDATE로 일부만 NULL (volatility)',
    $q$UPDATE asset_position SET volatility = NULL WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZAAPL') AND base_date = '2025-04-02'$q$, '23514', 'ck_asset_position_judgment_all_or_none');
CALL pg_temp.check_err('3 음성', 'asset_position: 같은 (종목, 기준일) = (ZZ0001, 2025-04-02)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0001'), '2025-04-02', 100, 1)$q$, '23505', 'uq_asset_position_asset_base_date');
CALL pg_temp.check_err('3 음성', 'asset_position: 없는 종목 999999',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES (999999, '2030-01-02', 100, 1)$q$, '23503', 'fk_asset_position_asset');
CALL pg_temp.check_err('3 음성', 'asset_position: base_price NULL', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', NULL, 1)$q$, '23502', 'base_price');
CALL pg_temp.check_err('3 음성', 'asset_position: base_fx_rate NULL', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 100, NULL)$q$, '23502', 'base_fx_rate');
CALL pg_temp.check_err('3 음성', 'asset_position: base_date NULL', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), NULL, 100, 1)$q$, '23502', 'base_date');

-- 3.4 month_block
CALL pg_temp.check_err('3 음성', 'month_block: month_start가 1일이 아님 (2008-09-15)', $q$INSERT INTO month_block (month_start) VALUES ('2008-09-15')$q$, '23514', 'ck_month_block_month_start');
-- 축 4개 × (방향 값 오류, 방향만, 강도만, 강도 음수) — 방향 값이 틀리면서 강도를 같이 주어 ck_..._dir 하나만 어긴다
DO $$
DECLARE a text;
BEGIN
    FOREACH a IN ARRAY ARRAY['rate', 'growth', 'fx', 'sentiment'] LOOP
        CALL pg_temp.check_err('3 음성', format('month_block: %s_dir 소문자 ''up''', a),
            format($f$INSERT INTO month_block (month_start, %1$s_dir, %1$s_strength) VALUES ('1995-01-01', 'up', 1)$f$, a), '23514', format('ck_month_block_%s_dir', a));
        CALL pg_temp.check_err('3 음성', format('month_block: %s_dir = FLAT', a),
            format($f$INSERT INTO month_block (month_start, %1$s_dir, %1$s_strength) VALUES ('1995-01-01', 'FLAT', 1)$f$, a), '23514', format('ck_month_block_%s_dir', a));
        CALL pg_temp.check_err('3 음성', format('month_block: %s 방향만 있고 강도 없음', a),
            format($f$INSERT INTO month_block (month_start, %1$s_dir) VALUES ('1995-01-01', 'UP')$f$, a), '23514', format('ck_month_block_%s_pair', a));
        CALL pg_temp.check_err('3 음성', format('month_block: %s 강도만 있고 방향 없음', a),
            format($f$INSERT INTO month_block (month_start, %1$s_strength) VALUES ('1995-01-01', 1)$f$, a), '23514', format('ck_month_block_%s_pair', a));
        CALL pg_temp.check_err('3 음성', format('month_block: %s 강도 음수', a),
            format($f$INSERT INTO month_block (month_start, %1$s_dir, %1$s_strength) VALUES ('1995-01-01', 'UP', -0.5)$f$, a), '23514', format('ck_month_block_%s_pair', a));
    END LOOP;
END $$;
CALL pg_temp.check_err('3 음성', 'month_block: 확률 범위 (1.5, -0.5, 0, 0) — 합은 1이라 prob_range만 어긴다',
    $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('1995-01-01', 1.5, -0.5, 0, 0)$q$, '23514', 'ck_month_block_prob_range');
CALL pg_temp.check_err('3 음성', 'month_block: 확률 3개만 (0.5, 0.5, 0, NULL)',
    $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3) VALUES ('1995-01-01', 0.5, 0.5, 0)$q$, '23514', 'ck_month_block_prob_all_or_none');
CALL pg_temp.check_err('3 음성', 'month_block: 확률 1개만 (prob_p4)',
    $q$INSERT INTO month_block (month_start, prob_p4) VALUES ('1995-01-01', 0.5)$q$, '23514', 'ck_month_block_prob_all_or_none');
CALL pg_temp.check_err('3 음성', 'month_block: 확률 합 1.0003',
    $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('1995-01-01', 0.5, 0.5, 0.0003, 0)$q$, '23514', 'ck_month_block_prob_sum');
CALL pg_temp.check_err('3 음성', 'month_block: 확률 합 0.9997',
    $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('1995-01-01', 0.4997, 0.5, 0, 0)$q$, '23514', 'ck_month_block_prob_sum');
CALL pg_temp.check_err('3 음성', 'month_block: 확률 합 0.9',
    $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('1995-01-01', 0.9, 0, 0, 0)$q$, '23514', 'ck_month_block_prob_sum');
CALL pg_temp.check_err('3 음성', 'month_block: 같은 month_start 1990-01-01', $q$INSERT INTO month_block (month_start) VALUES ('1990-01-01')$q$, '23505', 'uq_month_block_month_start');
CALL pg_temp.check_err('3 음성', 'month_block: events_reviewed에 NULL 명시', $q$INSERT INTO month_block (month_start, events_reviewed) VALUES ('1995-01-01', NULL)$q$, '23502', 'events_reviewed');
CALL pg_temp.check_err('3 음성', 'month_block: month_start NULL', $q$INSERT INTO month_block (month_start) VALUES (NULL)$q$, '23502', 'month_start');

-- 3.5 asset_month_return (기준 행: ZZSPY × 1990-01-01, 아직 행이 없다)
CALL pg_temp.check_err('3 음성', 'asset_month_return: local_return = -1.5',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), -1.5)$q$, '23514', 'ck_asset_month_return_local_return');
CALL pg_temp.check_err('3 음성', 'asset_month_return: return_kind = SYNTH',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 0.1, 'SYNTH')$q$, '23514', 'ck_asset_month_return_return_kind');
CALL pg_temp.check_err('3 음성', 'asset_month_return: 같은 (종목, 달) = (ZZ0001, 1990-01)',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0001'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 0.1)$q$, '23505', 'uq_asset_month_return_asset_month');
CALL pg_temp.check_err('3 음성', 'asset_month_return: 없는 종목 999999',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return) VALUES (999999, (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 0.1)$q$, '23503', 'fk_asset_month_return_asset');
CALL pg_temp.check_err('3 음성', 'asset_month_return: 없는 달 999999',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), 999999, 0.1)$q$, '23503', 'fk_asset_month_return_month_block');
CALL pg_temp.check_err('3 음성', 'asset_month_return: local_return NULL',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), NULL)$q$, '23502', 'local_return');

-- 3.6 fx_month
CALL pg_temp.check_err('3 음성', 'fx_month: currency = EUR', $q$INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES ((SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'EUR', 0.01)$q$, '23514', 'ck_fx_month_currency');
CALL pg_temp.check_err('3 음성', 'fx_month: currency 소문자 ''usd''', $q$INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES ((SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'usd', 0.01)$q$, '23514', 'ck_fx_month_currency');
CALL pg_temp.check_err('3 음성', 'fx_month: fx_change = -1', $q$INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES ((SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'USD', -1)$q$, '23514', 'ck_fx_month_fx_change');
CALL pg_temp.check_err('3 음성', 'fx_month: 같은 (달, 통화) = (1990-01, USD)', $q$INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES ((SELECT id FROM month_block WHERE month_start = '1990-01-01'), 'USD', 0.01)$q$, '23505', 'uq_fx_month_month_block_currency');
CALL pg_temp.check_err('3 음성', 'fx_month: 없는 달 999999', $q$INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES (999999, 'USD', 0.01)$q$, '23503', 'fk_fx_month_month_block');
CALL pg_temp.check_err('3 음성', 'fx_month: fx_change NULL', $q$INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES ((SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'USD', NULL)$q$, '23502', 'fx_change');

-- 3.7 month_event (기준 행: TRADE TARIFF START, strength 2)
CALL pg_temp.check_err('3 음성', 'month_event: category = WAR',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'WAR', 'TARIFF', 'START', 2, 't', 's')$q$, '23514', 'ck_month_event_category');
CALL pg_temp.check_err('3 음성', 'month_event: event_type 소문자 ''us_midterm''',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'us_midterm', 'START', 2, 't', 's')$q$, '23514', 'ck_month_event_event_type_format');
CALL pg_temp.check_err('3 음성', 'month_event: phase = BEGIN',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'BEGIN', 2, 't', 's')$q$, '23514', 'ck_month_event_phase');
CALL pg_temp.check_err('3 음성', 'month_event: outcome 소문자 ''imposed''',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', 'imposed', 2, 't', 's')$q$, '23514', 'ck_month_event_outcome_format');
CALL pg_temp.check_err('3 음성', 'month_event: direction = FLAT',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, direction, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', 'FLAT', 2, 't', 's')$q$, '23514', 'ck_month_event_direction');
CALL pg_temp.check_err('3 음성', 'month_event: strength = 0',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', 0, 't', 's')$q$, '23514', 'ck_month_event_strength');
CALL pg_temp.check_err('3 음성', 'month_event: strength = 4',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', 4, 't', 's')$q$, '23514', 'ck_month_event_strength');
CALL pg_temp.check_err('3 음성', 'month_event: reaction_change = -2',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, reaction_change, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', 2, -2, 't', 's')$q$, '23514', 'ck_month_event_reaction_change');
CALL pg_temp.check_err('3 음성', 'month_event: 같은 event_key ZZ_EV1',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_EV1', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', 2, 't', 's')$q$, '23505', 'uq_month_event_event_key');
CALL pg_temp.check_err('3 음성', 'month_event: 없는 달 999999',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_NEW', 999999, 'TRADE', 'TARIFF', 'START', 2, 't', 's')$q$, '23503', 'fk_month_event_month_block');
CALL pg_temp.check_err('3 음성', 'month_event: title NULL',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', 2, NULL, 's')$q$, '23502', 'title');
CALL pg_temp.check_err('3 음성', 'month_event: strength NULL',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', NULL, 't', 's')$q$, '23502', 'strength');

-- 3.8 asset_month_attention (기준 행: ZZSPY × 1990-01-01, streak 0)
CALL pg_temp.check_err('3 음성', 'asset_month_attention: window_months = 13',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, streak) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 13, 0)$q$, '23514', 'ck_asset_month_attention_window_months');
CALL pg_temp.check_err('3 음성', 'asset_month_attention: window_months = -1',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, streak) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), -1, 0)$q$, '23514', 'ck_asset_month_attention_window_months');
CALL pg_temp.check_err('3 음성', 'asset_month_attention: z가 있는데 window_months = 1',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 1, 1.0, 0)$q$, '23514', 'ck_asset_month_attention_z_window');
CALL pg_temp.check_err('3 음성', 'asset_month_attention: z 없이 attention_index',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, streak, attention_index) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 5, 0, 0.5)$q$, '23514', 'ck_asset_month_attention_index_needs_z');
CALL pg_temp.check_err('3 음성', 'asset_month_attention: 같은 (종목, 달) = (ZZ0001, 1990-01)',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, streak) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZ0001'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 0, 0)$q$, '23505', 'uq_asset_month_attention_asset_month');
CALL pg_temp.check_err('3 음성', 'asset_month_attention: 없는 종목 999999',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, streak) VALUES (999999, (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 0, 0)$q$, '23503', 'fk_asset_month_attention_asset');
CALL pg_temp.check_err('3 음성', 'asset_month_attention: 없는 달 999999',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, streak) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), 999999, 0, 0)$q$, '23503', 'fk_asset_month_attention_month_block');
CALL pg_temp.check_err('3 음성', 'asset_month_attention: streak NULL',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, streak) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 0, NULL)$q$, '23502', 'streak');

-- 3.9 RESTRICT (ON DELETE / ON UPDATE). 부모마다 자식 테이블이 하나뿐인 픽스처를 만든다.
--     그룹: 자식은 asset뿐. 달 1991-01은 asset_month_return만, 1991-02는 fx_month만, 1991-03은 month_event만, 1991-04는 attention만.
--     종목 ZZDEL1은 asset_position만, ZZDEL2는 asset_month_return만, ZZDEL3은 attention만 자식으로 둔다.
CALL pg_temp.check_ok('3 음성', 'RESTRICT 픽스처: 달·종목마다 자식 테이블 하나',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), (SELECT id FROM month_block WHERE month_start = '1991-01-01'), 0.01),
              ((SELECT id FROM asset WHERE ticker = 'ZZDEL2'), (SELECT id FROM month_block WHERE month_start = '1993-12-01'), 0.01);
       INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES ((SELECT id FROM month_block WHERE month_start = '1991-02-01'), 'USD', 0.01);
       INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, title, source)
       VALUES ('ZZ_EVD', (SELECT id FROM month_block WHERE month_start = '1991-03-01'), 'POLICY', 'RATE_DECISION', 'START', 1, 't', 's');
       INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, streak)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), (SELECT id FROM month_block WHERE month_start = '1991-04-01'), 0, 0),
              ((SELECT id FROM asset WHERE ticker = 'ZZDEL3'), (SELECT id FROM month_block WHERE month_start = '1993-12-01'), 0, 0);
       INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZDEL1'), '2030-06-03', 100, 1)$q$);
CALL pg_temp.check_err('3 음성', 'RESTRICT: 자식 종목이 있는 그룹 삭제',
    $q$DELETE FROM asset_group WHERE code = 'ZZ_GROUP'$q$, '23503', 'fk_asset_asset_group');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 자식 종목이 있는 그룹의 id 변경 (ON UPDATE RESTRICT, SET id = DEFAULT)',
    $q$UPDATE asset_group SET id = DEFAULT WHERE code = 'ZZ_GROUP'$q$, '23503', 'fk_asset_asset_group');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 수익률 행이 있는 달(1991-01) 삭제',
    $q$DELETE FROM month_block WHERE month_start = '1991-01-01'$q$, '23503', 'fk_asset_month_return_month_block');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 환율 행이 있는 달(1991-02) 삭제',
    $q$DELETE FROM month_block WHERE month_start = '1991-02-01'$q$, '23503', 'fk_fx_month_month_block');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 사건 행이 있는 달(1991-03) 삭제',
    $q$DELETE FROM month_block WHERE month_start = '1991-03-01'$q$, '23503', 'fk_month_event_month_block');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 관심 지수 행이 있는 달(1991-04) 삭제',
    $q$DELETE FROM month_block WHERE month_start = '1991-04-01'$q$, '23503', 'fk_asset_month_attention_month_block');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 기준일 행만 있는 종목(ZZDEL1) 삭제',
    $q$DELETE FROM asset WHERE ticker = 'ZZDEL1'$q$, '23503', 'fk_asset_position_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 수익률 행만 있는 종목(ZZDEL2) 삭제',
    $q$DELETE FROM asset WHERE ticker = 'ZZDEL2'$q$, '23503', 'fk_asset_month_return_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 관심 지수 행만 있는 종목(ZZDEL3) 삭제',
    $q$DELETE FROM asset WHERE ticker = 'ZZDEL3'$q$, '23503', 'fk_asset_month_attention_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 기준일 행만 있는 종목(ZZDEL1)의 id 변경 (ON UPDATE RESTRICT)',
    $q$UPDATE asset SET id = DEFAULT WHERE ticker = 'ZZDEL1'$q$, '23503', 'fk_asset_position_asset');
CALL pg_temp.check_err('3 음성', 'RESTRICT: 환율 행이 있는 달(1991-02)의 id 변경 (ON UPDATE RESTRICT)',
    $q$UPDATE month_block SET id = DEFAULT WHERE month_start = '1991-02-01'$q$, '23503', 'fk_fx_month_month_block');


-- 4 저장 정밀도 경계 ------------------------------------------------------------
-- erd 5절 테이블에는 원 단위 CHECK가 없다(2절 금액). 대신 "CHECK는 반올림된 저장값을 본다"(D1)를 확인한다.
-- NaN은 자기와 같고 모든 수보다 크게 비교되어 하한·등식 CHECK만으로는 막히지 않는다(erd D32, 4.7).
-- NUMERIC(p,s)는 소수 자리가 넘치면 경고 없이 0에서 먼 쪽으로 반올림하고, 정수 자리가 넘치면 22003으로 거부한다.
-- 수익률 사례는 종목 ZZBND × 1993-01 ~ 11, 환율 사례는 1993-01 ~ 02, 기준일 사례는 ZZBND × 2031 이후 기준일을 쓴다.

-- 4.1 local_return NUMERIC(12,8): 소수 8자리, 정수 4자리
CALL pg_temp.check_ok('4 경계', 'local_return = 0.123456785 대입', $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), (SELECT id FROM month_block WHERE month_start = '1993-01-01'), 0.123456785)$q$);
CALL pg_temp.check_eq('4 경계', 'local_return 0.123456785 → 저장값 (0에서 먼 쪽 반올림)', '0.12345679',
    pg_temp.val($v$SELECT local_return::text FROM asset_month_return WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZBND') AND month_block_id = (SELECT id FROM month_block WHERE month_start = '1993-01-01')$v$));
CALL pg_temp.check_ok('4 경계', 'local_return = -1.000000004 대입 (저장 -1.00000000 → 통과)', $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), (SELECT id FROM month_block WHERE month_start = '1993-02-01'), -1.000000004)$q$);
CALL pg_temp.check_eq('4 경계', 'local_return -1.000000004 → 저장값', '-1.00000000',
    pg_temp.val($v$SELECT local_return::text FROM asset_month_return WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZBND') AND month_block_id = (SELECT id FROM month_block WHERE month_start = '1993-02-01')$v$));
CALL pg_temp.check_err('4 경계', 'local_return = -1.000000005 (저장 -1.00000001 → CHECK 위반)', $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), (SELECT id FROM month_block WHERE month_start = '1993-03-01'), -1.000000005)$q$, '23514', 'ck_asset_month_return_local_return');
CALL pg_temp.check_ok('4 경계', 'local_return = 9999.99999999 (정수 4자리 최댓값 → 통과)', $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), (SELECT id FROM month_block WHERE month_start = '1993-04-01'), 9999.99999999)$q$);
CALL pg_temp.check_err('4 경계', 'local_return = 9999.999999995 (반올림하면 10000 → 22003)', $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), (SELECT id FROM month_block WHERE month_start = '1993-05-01'), 9999.999999995)$q$, '22003');
CALL pg_temp.check_err('4 경계', 'local_return = 10000 (정수 자리 넘침 → 22003)', $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), (SELECT id FROM month_block WHERE month_start = '1993-05-01'), 10000)$q$, '22003');

-- 4.2 fx_change NUMERIC(12,8), CHECK fx_change > -1
CALL pg_temp.check_err('4 경계', 'fx_change = -0.999999995 (저장 -1.00000000 → CHECK 위반)', $q$INSERT INTO fx_month (month_block_id, currency, fx_change)
    VALUES ((SELECT id FROM month_block WHERE month_start = '1993-01-01'), 'USD', -0.999999995)$q$, '23514', 'ck_fx_month_fx_change');
CALL pg_temp.check_ok('4 경계', 'fx_change = -0.999999994 대입 (저장 -0.99999999 → 통과)', $q$INSERT INTO fx_month (month_block_id, currency, fx_change)
    VALUES ((SELECT id FROM month_block WHERE month_start = '1993-02-01'), 'USD', -0.999999994)$q$);
CALL pg_temp.check_eq('4 경계', 'fx_change -0.999999994 → 저장값', '-0.99999999',
    pg_temp.val($v$SELECT fx_change::text FROM fx_month WHERE month_block_id = (SELECT id FROM month_block WHERE month_start = '1993-02-01')$v$));

-- 4.3 확률 NUMERIC(5,4): 정수 1자리, 소수 4자리
CALL pg_temp.check_err('4 경계', 'prob_p1 = 10 (정수 자리 넘침 → 22003)', $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4)
    VALUES ('1996-01-01', 10, 0, 0, 0)$q$, '22003');
CALL pg_temp.check_ok('4 경계', '확률 0.33335 세 개 + 0 대입 (저장 0.3334 → 합 1.0002 통과)', $q$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4)
    VALUES ('1996-02-01', 0.33335, 0.33335, 0.33335, 0)$q$);
CALL pg_temp.check_eq('4 경계', 'prob_p1 0.33335 → 저장값', '0.3334',
    pg_temp.val($v$SELECT prob_p1::text FROM month_block WHERE month_start = '1996-02-01'$v$));

-- 4.4 base_price NUMERIC(19,4): 정수 15자리, 소수 4자리
CALL pg_temp.check_err('4 경계', 'base_price = 10^15 (정수 16자리 → 22003)', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2031-01-02', 1000000000000000, 1)$q$, '22003');
CALL pg_temp.check_ok('4 경계', 'base_price = 999999999999999.9999 (최댓값 → 통과)', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2031-01-02', 999999999999999.9999, 1)$q$);
CALL pg_temp.check_err('4 경계', 'base_price = 0.00004 (저장 0.0000 → CHECK 위반)', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2031-02-03', 0.00004, 1)$q$, '23514', 'ck_asset_position_base_price');
CALL pg_temp.check_ok('4 경계', 'base_price = 0.00005 대입 (저장 0.0001 → 통과)', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2031-03-03', 0.00005, 1)$q$);
CALL pg_temp.check_eq('4 경계', 'base_price 0.00005 → 저장값', '0.0001',
    pg_temp.val($v$SELECT base_price::text FROM asset_position WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZBND') AND base_date = '2031-03-03'$v$));

-- 4.5 max_drawdown NUMERIC(12,8), CHECK BETWEEN -1 AND 0 (판정 8컬럼은 유효하게 채운다. window_end = 기준일 전달 1일)
CALL pg_temp.check_ok('4 경계', 'max_drawdown = 0.000000004 대입 (저장 0.00000000 → 통과)', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate,
        window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2032-01-02', 100, 1, '2029-01-01', '2031-12-01', 36, 1, 1, 0.1, 0.000000004, 'MIDFIELDER')$q$);
CALL pg_temp.check_eq('4 경계', 'max_drawdown 0.000000004 → 저장값', '0.00000000',
    pg_temp.val($v$SELECT max_drawdown::text FROM asset_position WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZBND') AND base_date = '2032-01-02'$v$));
CALL pg_temp.check_err('4 경계', 'max_drawdown = 0.000000005 (저장 0.00000001 → CHECK 위반)', $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate,
        window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position)
    VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2033-01-03', 100, 1, '2030-01-01', '2032-12-01', 36, 1, 1, 0.1, 0.000000005, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_max_drawdown');

-- 4.6 VARCHAR(n)의 뒤 공백: 폭을 넘는 공백만 오류 없이 잘리고, 폭 안의 공백은 값의 일부다 (기본 비교는 뒤 공백을 구분)
CALL pg_temp.check_ok('4 경계', 'asset_type = ''STOCK '' (폭 5 초과 공백 1개 → 잘려 통과)', $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
    VALUES ('KR', 'ZZTRIM', 'x', 'STOCK ', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$);
CALL pg_temp.check_eq('4 경계', 'asset_type ''STOCK '' → 저장값의 길이', '5',
    pg_temp.val($v$SELECT length(asset_type)::text FROM asset WHERE ticker = 'ZZTRIM'$v$));
CALL pg_temp.check_err('4 경계', 'asset_type = ''ETF  '' (폭 5 안의 뒤 공백 2개 → CHECK 위반)', $q$INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
    VALUES ('KR', 'ZZTRIM2', 'x', 'ETF  ', 'KRW', (SELECT id FROM asset_group WHERE code = 'ZZ_GROUP'), '2000-01-03', 'PRICE')$q$, '23514', 'ck_asset_asset_type');

-- 4.7 NUMERIC NaN: NaN은 자기와 같고 모든 수보다 크게 비교되어 `>= 0`·`> 0`만으로는 막히지 않는다 (erd D32).
--     값 CHECK의 <> 'NaN'::numeric과 NaN 전용 CHECK 2개가 실제로 걸리는지 컬럼마다 한 건씩 본다. 무한대는 정밀도 지정 NUMERIC이 22003으로 막는다.
--     BETWEEN으로 상한이 있는 max_drawdown·확률은 NaN에서 이미 거짓이라 고치지 않았다(변경 없음 확인).
--     사례는 3절의 빈 기준 행(ZZBND × 2030-01-02, 월 1995-01·1990-03, ZZSPY × 1990-01)을 쓰며 실패하면 되돌아간다. 먼저 그 전제를 단언한다.
CALL pg_temp.check_eq('4 경계', 'NaN 사례 전제: 부모(종목 ZZBND·ZZSPY / 월 1990-01·1990-03)가 있다', '2/2',
    pg_temp.val($v$SELECT (SELECT count(*) FROM asset WHERE ticker IN ('ZZBND', 'ZZSPY'))::text
        || '/' || (SELECT count(*) FROM month_block WHERE month_start IN ('1990-01-01', '1990-03-01'))::text$v$));
CALL pg_temp.check_eq('4 경계', 'NaN 사례 전제: 사례 키(ZZBND × 2030-01-02, 월 1995-01, ZZSPY × 1990-01 수익률·관심, 1990-03 환율, ZZ_NEW)가 비어 있다', '0',
    pg_temp.val($v$SELECT ((SELECT count(*) FROM asset_position WHERE base_date = '2030-01-02')
        + (SELECT count(*) FROM month_block WHERE month_start = '1995-01-01')
        + (SELECT count(*) FROM asset_month_return WHERE month_block_id = (SELECT id FROM month_block WHERE month_start = '1990-01-01') AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZSPY'))
        + (SELECT count(*) FROM asset_month_attention WHERE month_block_id = (SELECT id FROM month_block WHERE month_start = '1990-01-01') AND asset_id = (SELECT id FROM asset WHERE ticker = 'ZZSPY'))
        + (SELECT count(*) FROM fx_month WHERE month_block_id = (SELECT id FROM month_block WHERE month_start = '1990-03-01'))
        + (SELECT count(*) FROM month_event WHERE event_key = 'ZZ_NEW'))::text$v$));
CALL pg_temp.check_err('4 경계', 'asset_position.base_price = NaN',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 'NaN', 1)$q$, '23514', 'ck_asset_position_base_price');
CALL pg_temp.check_err('4 경계', 'asset_position.base_fx_rate = NaN',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 100, 'NaN')$q$, '23514', 'ck_asset_position_base_fx_rate');
CALL pg_temp.check_err('4 경계', 'asset_position.up_participation = NaN (값 CHECK가 없어 전용 CHECK)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 100, 1, '2027-01-01', '2029-12-01', 36, 'NaN', 1, 0.1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_participation_not_nan');
CALL pg_temp.check_err('4 경계', 'asset_position.down_participation = NaN',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 100, 1, '2027-01-01', '2029-12-01', 36, 1, 'NaN', 0.1, -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_participation_not_nan');
CALL pg_temp.check_err('4 경계', 'asset_position.volatility = NaN',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 100, 1, '2027-01-01', '2029-12-01', 36, 1, 1, 'NaN', -0.1, 'MIDFIELDER')$q$, '23514', 'ck_asset_position_volatility');
CALL pg_temp.check_err('4 경계', 'asset_position.max_drawdown = NaN (BETWEEN이 이미 거짓 — 변경 없음 확인)',
    $q$INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position)
       VALUES ((SELECT id FROM asset WHERE ticker = 'ZZBND'), '2030-01-02', 100, 1, '2027-01-01', '2029-12-01', 36, 1, 1, 0.1, 'NaN', 'MIDFIELDER')$q$, '23514', 'ck_asset_position_max_drawdown');
DO $$
DECLARE a text;
BEGIN
    FOREACH a IN ARRAY ARRAY['rate', 'growth', 'fx', 'sentiment'] LOOP
        CALL pg_temp.check_err('4 경계', format('month_block.%s_strength = NaN (방향 UP)', a),
            format($f$INSERT INTO month_block (month_start, %1$s_dir, %1$s_strength) VALUES ('1995-01-01', 'UP', 'NaN')$f$, a), '23514', format('ck_month_block_%s_pair', a));
    END LOOP;
END $$;
--     확률 4개: 한 컬럼만 NaN(나머지 0.25). prob_sum도 어기지만 이름 순으로 prob_range가 먼저 보고된다.
DO $$
DECLARE i int;
BEGIN
    FOR i IN 1..4 LOOP
        CALL pg_temp.check_err('4 경계', format('month_block.prob_p%s = NaN (BETWEEN이 이미 거짓 — 변경 없음 확인)', i),
            format($f$INSERT INTO month_block (month_start, prob_p1, prob_p2, prob_p3, prob_p4) VALUES ('1995-01-01', %s, %s, %s, %s)$f$,
                CASE i WHEN 1 THEN '''NaN''' ELSE '0.25' END, CASE i WHEN 2 THEN '''NaN''' ELSE '0.25' END,
                CASE i WHEN 3 THEN '''NaN''' ELSE '0.25' END, CASE i WHEN 4 THEN '''NaN''' ELSE '0.25' END),
            '23514', 'ck_month_block_prob_range');
    END LOOP;
END $$;
CALL pg_temp.check_err('4 경계', 'asset_month_return.local_return = NaN',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 'NaN')$q$, '23514', 'ck_asset_month_return_local_return');
CALL pg_temp.check_err('4 경계', 'fx_month.fx_change = NaN',
    $q$INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES ((SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'USD', 'NaN')$q$, '23514', 'ck_fx_month_fx_change');
CALL pg_temp.check_err('4 경계', 'month_event.reaction_change = NaN',
    $q$INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, strength, reaction_change, title, source) VALUES ('ZZ_NEW', (SELECT id FROM month_block WHERE month_start = '1990-03-01'), 'TRADE', 'TARIFF', 'START', 2, 'NaN', 't', 's')$q$, '23514', 'ck_month_event_reaction_change');
--     창 12개월로 시험한다: 창 1이면 z_window도 어기지만 이름 순으로 not_nan이 먼저 보고된다.
CALL pg_temp.check_err('4 경계', 'asset_month_attention.attention_z = NaN (값 CHECK가 없어 전용 CHECK)',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 12, 'NaN', 0)$q$, '23514', 'ck_asset_month_attention_not_nan');
CALL pg_temp.check_err('4 경계', 'asset_month_attention.attention_index = NaN',
    $q$INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, attention_index, streak) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 12, 1, 'NaN', 0)$q$, '23514', 'ck_asset_month_attention_not_nan');
CALL pg_temp.check_err('4 경계', 'asset_month_return.local_return = Infinity (정밀도 지정 NUMERIC이 거부 → 22003)',
    $q$INSERT INTO asset_month_return (asset_id, month_block_id, local_return) VALUES ((SELECT id FROM asset WHERE ticker = 'ZZSPY'), (SELECT id FROM month_block WHERE month_start = '1990-01-01'), 'Infinity')$q$, '22003');

-- 5 검증 규칙 H1 예시 (erd 5.5, 9절) ----------------------------------------------
-- H1: 같은 base_date의 USD 종목 행은 base_fx_rate가 하나이고, KRW 종목은 1이다. CHECK는 다른 행을 못 보므로 쿼리로 센다.
-- 아래 쿼리가 위반을 한 행씩 돌려준다. P0-08 적재가 같은 쿼리를 완료 기준으로 쓴다 (0행이어야 정상).
--
--   SELECT p.base_date, a.currency
--   FROM asset_position p JOIN asset a ON a.id = p.asset_id
--   GROUP BY p.base_date, a.currency
--   HAVING (a.currency = 'USD' AND count(DISTINCT p.base_fx_rate) > 1)
--       OR (a.currency = 'KRW' AND (min(p.base_fx_rate) <> 1 OR max(p.base_fx_rate) <> 1));
--
-- 일관된 픽스처는 0행, 위반 픽스처는 서브트랜잭션 안에서 만들어 세고 되돌린다.
DO $$
DECLARE
    h1 constant text := $h$SELECT count(*) FROM (
        SELECT p.base_date, a.currency
        FROM asset_position p JOIN asset a ON a.id = p.asset_id
        GROUP BY p.base_date, a.currency
        HAVING (a.currency = 'USD' AND count(DISTINCT p.base_fx_rate) > 1)
            OR (a.currency = 'KRW' AND (min(p.base_fx_rate) <> 1 OR max(p.base_fx_rate) <> 1))) v$h$;
    n_ok  bigint;
    n_usd bigint;
    n_krw bigint;
BEGIN
    EXECUTE h1 INTO n_ok;
    BEGIN
        UPDATE asset_position SET base_fx_rate = 1451
        WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZSPY') AND base_date = '2025-04-02';
        EXECUTE h1 INTO n_usd;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    BEGIN
        UPDATE asset_position SET base_fx_rate = 2
        WHERE asset_id = (SELECT id FROM asset WHERE ticker = 'ZZ0001') AND base_date = '2025-04-02';
        EXECUTE h1 INTO n_krw;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok) VALUES
        ('5 H1', 'H1 위반 행 수: 일관된 픽스처 (USD 같은 환율, KRW 1)', '0', n_ok::text, n_ok = 0),
        ('5 H1', 'H1 위반 행 수: USD 두 종목의 환율이 다름', '1', n_usd::text, n_usd = 1),
        ('5 H1', 'H1 위반 행 수: KRW 종목의 환율이 2', '1', n_krw::text, n_krw = 1);
END $$;

-- 6 음성 DDL (트랜잭션 안에서 실행하고 되돌린다 — PostgreSQL의 DDL은 ROLLBACK된다) ------------
CALL pg_temp.check_err('6 DDL', 'D1: 컬럼 CHECK가 다른 컬럼을 참조 — PostgreSQL은 막지 않는다 (그래서 CHECK 위치 통일은 erd 2절 규칙이 지킨다)',
    $q$ALTER TABLE asset_group ADD COLUMN zz_x integer CHECK (zz_x > length(code))$q$, '00000');
CALL pg_temp.check_err('6 DDL', 'D2: 유일하지 않은 컬럼(asset.asset_group_id)을 참조하는 FK',
    $q$ALTER TABLE asset_month_return ADD CONSTRAINT zz_fk FOREIGN KEY (asset_id) REFERENCES asset (asset_group_id)$q$, '42830');
CALL pg_temp.check_err('6 DDL', 'D3: 없는 부모 테이블을 참조하는 FK',
    $q$ALTER TABLE asset_month_return ADD CONSTRAINT zz_fk FOREIGN KEY (asset_id) REFERENCES zz_no_such_table (id)$q$, '42P01');
CALL pg_temp.check_err('6 DDL', 'D4: 자식이 있는 부모 테이블 DROP (CASCADE 없음) — 재실행 블록이 역순·자기 테이블만인 이유',
    $q$DROP TABLE month_block$q$, '2BP01');
CALL pg_temp.check_err('6 DDL', 'D5: 같은 테이블에 같은 CHECK 이름 두 번',
    $q$ALTER TABLE asset ADD CONSTRAINT ck_asset_market CHECK (true)$q$, '42710');
CALL pg_temp.check_err('6 DDL', 'D6: 기존 UNIQUE 이름과 같은 인덱스 이름 (인덱스는 스키마 안에서 이름이 유일)',
    $q$CREATE INDEX uq_asset_market_ticker ON asset_group (code)$q$, '42P07');
CALL pg_temp.check_err('6 DDL', 'D6: 기존 idx_ 이름과 같은 이름의 UNIQUE 제약 (만들어지는 인덱스 이름이 겹침)',
    $q$ALTER TABLE fx_month ADD CONSTRAINT idx_asset_asset_group UNIQUE (id)$q$, '42P07');

-- 7 판정 ---------------------------------------------------------------------
-- 7.1 커버리지: 8개 테이블의 ck_ 47 + uq_ 8 + fk_ 8 = 63개 이름이 각각 1건 이상 자기 이름으로 보고되어야 한다.
--     (보고된 이름 = 3·4절 성공한 음성 사례 결과의 실제값 둘째 칸, SQLSTATE 23514·23505·23503인 것)
--     가려진 CHECK(다른 CHECK에 가려 단독으로 못 걸리는 것)가 있으면 여기서 빠진 이름으로 드러난다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
WITH covered AS (
    SELECT DISTINCT split_part(actual, ' ', 2) AS name
    FROM zz_check_result
    WHERE ok AND section IN ('3 음성', '4 경계') AND split_part(actual, ' ', 1) IN ('23514', '23505', '23503')
), need AS (
    SELECT c.conname::text AS name
    FROM pg_constraint c
    WHERE c.contype IN ('u', 'f', 'c') AND c.conrelid IN (SELECT to_regclass('public.' || tbl) FROM zz_expect_count)
)
SELECT '7 판정', '커버리지: 자기 이름으로 보고된 ck_·uq_·fk_ 이름 수', '63/63',
       (SELECT count(*) FROM need WHERE name IN (SELECT name FROM covered))::text || '/' || (SELECT count(*) FROM need)::text
       || COALESCE(' 빠짐: ' || (SELECT string_agg(name, ', ' ORDER BY name) FROM need WHERE name NOT IN (SELECT name FROM covered)), ''),
       (SELECT count(*) FROM need WHERE name IN (SELECT name FROM covered)) = 63 AND (SELECT count(*) FROM need) = 63;

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
        RAISE EXCEPTION 'P0-05 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 8 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 8개 테이블의 행 0, public의 zz_ 객체 0, 제약 71개(PK 8 + UQ 8 + FK 8 + CK 47)와 인덱스 21개(PK 8 + UQ 8 + idx_ 5)가 그대로다.
-- identity 값 소비만 되돌아오지 않는다 (db/README.md).
DO $$
DECLARE
    v_rows integer;
    v_zz   integer;
    v_con  integer;
    v_idx  integer;
BEGIN
    SELECT (SELECT count(*) FROM asset_group) + (SELECT count(*) FROM asset) + (SELECT count(*) FROM asset_position)
         + (SELECT count(*) FROM month_block) + (SELECT count(*) FROM asset_month_return) + (SELECT count(*) FROM fx_month)
         + (SELECT count(*) FROM month_event) + (SELECT count(*) FROM asset_month_attention) INTO v_rows;
    SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
         + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%') INTO v_zz;
    SELECT count(*) INTO v_con FROM pg_constraint
    WHERE contype IN ('p', 'u', 'f', 'c') AND conrelid IN (SELECT to_regclass('public.' || t) FROM unnest(ARRAY['asset_group', 'asset', 'asset_position', 'month_block',
          'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention']) AS t);
    SELECT count(*) INTO v_idx FROM pg_indexes
    WHERE schemaname = 'public' AND tablename IN ('asset_group', 'asset', 'asset_position', 'month_block',
          'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention');
    IF (v_rows, v_zz, v_con, v_idx) <> (0, 0, 71, 21) THEN
        PERFORM setval('pg_temp.zz_verdict', 1);
        RAISE EXCEPTION 'P0-05 흔적 확인 실패: 행 % (기대 0), zz_ 객체 % (기대 0), 제약 % (기대 71), 인덱스 % (기대 21)', v_rows, v_zz, v_con, v_idx;
    END IF;
    -- 단언을 모두 통과했고 1–7절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_05_check;
