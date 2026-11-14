-- =====================================================================
-- my Fund Manager Phase 0 — 표본 적재 검증 SQL (P0-08)
-- 대상: db/10_load_sample.sql 이 채운 6개 테이블 (asset_group, asset, month_block, asset_position, asset_month_return, fx_month)
-- 근거: docs/erd.md 5.3(정의서), 5.5(검증 규칙 H1·H3·H4·H7), 9절 P0-08, db/01_schema_history_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/10_load_sample_check.sql
--       적재(db/10_load_sample.sql) 뒤에 실행한다. INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아
--       실습 DB(antlab)에서도 돌릴 수 있다 (01_schema_history_check.sql 은 검증용 DB 전용 — 차이).
-- 판정: 마지막 줄 `p0_08_check` 가 `PASS` 이고 종료 코드가 0이면 통과다.
--       0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고, 그 밖에 하나라도 어긋나면 7절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 7절 뒤에 ROLLBACK 한다. 8절이 행 수·identity 마지막 값이 실행 전과 같은지 확인한다.
-- 절:   0 전제(FAIL 이면 여기서 멈춤) → 1 행 수 → 2 빈 칸·형식(H3) → 3 H1 → 4 H4 → 5 H7 → 6 손 대조 → 7 판정 → ROLLBACK → 8 흔적 확인
-- P0-05 판정 계약에서 복사한 것: ① pg_temp.zz_verdict ② 결과 표 zz_check_result ④ check_err·check_ok·check_eq + val()
--       ⑤ 0절 뒤 멈춤과 7.5 판정(표식 2 또는 1) ⑥ 8절 흔적 확인(표식 0)과 마지막 CASE.
--       ③ 기대 목록 표(제약·컬럼 대조용)는 쓰지 않는다. 구조는 01 검증이 맡는다.
--       갱신 규칙: val() 은 정확히 한 행, 기대값을 NULL 로 쓰지 않는다, NULL 안전 비교(IS NOT DISTINCT FROM), 사례 하나에 조건 하나.
-- 단언하지 않는 것: asset_position 의 판정 8컬럼, month_block 의 나머지 컬럼과 events_reviewed.
--       P0-09·P0-10 이 채운 뒤에도 이 검증이 PASS 여야 하기 때문이다 (소유 컬럼 원칙). 정보 행으로만 보여 준다.
-- 음성 사례: 서브트랜잭션 안에서 DELETE/UPDATE 로 위반을 1건 만들어 검사 쿼리가 잡는지 센 뒤 되돌린다. INSERT 는 쓰지 않는다.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다. psql 메타 명령은 6절의 hand_check.csv 읽기(\! test, \if, \copy)에만 쓴다.
--       6절 기대값은 git 이 무시하는 db/sample/hand_check.csv 에서 읽는다 (가격에서 나온 값이라 추적하지 않는다). 저장소 루트에서 실행한다.
-- =====================================================================

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 1–7절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

-- 흔적 확인용 스냅샷: 6개 테이블의 행 수와 identity 마지막 값. 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['asset_group', 'asset', 'month_block', 'asset_position', 'asset_month_return', 'fx_month'] LOOP
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
    section  text    NOT NULL,   -- '0 전제' '1 행 수' '2 빈 칸·형식' '3 H1' '4 H4' '5 H7' '6 손 대조'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

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
-- 하나라도 FAIL 이면 1절 이후 결과를 믿지 않는다. 테이블이 비어 있어도 되는지는 1절(행 수)이 판정한다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('6개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '6',
        (SELECT count(*)::text FROM unnest(ARRAY['asset_group', 'asset', 'month_block', 'asset_position', 'asset_month_return', 'fx_month']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text)
) AS x(item, expected, actual);

-- 정보 행: 현재 DB 이름, 이 검증과 무관한 테이블의 행 수 (세지 않는다. FAIL 아님)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('0 전제', '현재 DB 이름 (정보)', '(정보)', current_database(), true);

-- 0절 결과를 먼저 보여 주고, FAIL 이 있으면 멈춘다 (표식은 1로 남아 ON_ERROR_STOP 없이도 마지막 줄이 FAIL 이다).
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
        RAISE EXCEPTION 'P0-08 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 01 스키마와 10 적재를 먼저 실행했는지 확인한다)', v_fail;
    END IF;
END $$;

-- 검사 쿼리 목록: 위반 개수를 돌려주는 쿼리. 양성(0건)과 음성(위반 1건을 만든 뒤 1건)에 같은 쿼리를 쓴다.
CREATE TEMP TABLE zz_q (code text PRIMARY KEY, section text NOT NULL, item text NOT NULL, sql text NOT NULL);
INSERT INTO zz_q (code, section, item, sql) VALUES
    ('h3', '2 빈 칸·형식', 'H3 (종목 × 달) 수익률 누락',
     $q$SELECT count(*) FROM asset a CROSS JOIN month_block m
        WHERE NOT EXISTS (SELECT 1 FROM asset_month_return r WHERE r.asset_id = a.id AND r.month_block_id = m.id)$q$),
    ('ws', '2 빈 칸·형식', 'asset.ticker 의 앞뒤·안쪽 공백 (F6·R15)',
     $q$SELECT count(*) FROM asset WHERE ticker <> btrim(ticker) OR ticker ~ '\s'$q$),
    ('gws', '2 빈 칸·형식', 'asset_group.code 의 앞뒤·안쪽 공백',
     $q$SELECT count(*) FROM asset_group WHERE code <> btrim(code) OR code ~ '\s'$q$),
    ('fmt', '2 빈 칸·형식', 'ticker 가 대문자이고 KR 은 숫자 6자리 (앞자리 0 보존)',
     $q$SELECT count(*) FROM asset WHERE ticker <> upper(ticker) OR (market = 'KR' AND ticker !~ '^[0-9]{6}$')$q$),
    ('etfrank', '2 빈 칸·형식', 'ETF 의 market_cap_rank 는 빈 칸 (erd D15)',
     $q$SELECT count(*) FROM asset_position p JOIN asset a ON a.id = p.asset_id
        WHERE a.asset_type = 'ETF' AND p.market_cap_rank IS NOT NULL$q$),
    ('h1', '3 H1', 'H1 위반 (같은 기준일 USD 환율 하나, KRW 는 1) — P0-05 예시 쿼리',
     $q$SELECT count(*) FROM (
        SELECT p.base_date, a.currency
        FROM asset_position p JOIN asset a ON a.id = p.asset_id
        GROUP BY p.base_date, a.currency
        HAVING (a.currency = 'USD' AND count(DISTINCT p.base_fx_rate) > 1)
            OR (a.currency = 'KRW' AND (min(p.base_fx_rate) <> 1 OR max(p.base_fx_rate) <> 1))) v$q$),
    ('h4', '4 H4', 'H4 달마다 USD 환율 행이 정확히 1개인지 어긋난 달',
     $q$SELECT count(*) FROM month_block m
        WHERE (SELECT count(*) FROM fx_month f WHERE f.month_block_id = m.id AND f.currency = 'USD') <> 1$q$),
    ('h7a', '5 H7', 'H7 ETF 는 상장월까지 PROXY_INDEX · 다음 달부터 ACTUAL, 주식은 전부 ACTUAL',
     $q$SELECT count(*) FROM asset_month_return r
        JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id
        WHERE (a.asset_type = 'ETF' AND ((m.month_start <= date_trunc('month', a.listed_on)::date) <> (r.return_kind = 'PROXY_INDEX')))
           OR (a.asset_type = 'STOCK' AND r.return_kind <> 'ACTUAL')$q$),
    ('h7b', '5 H7', 'H7 수익률 기준: KR → PRICE, US → TOTAL',
     $q$SELECT count(*) FROM asset
        WHERE (market = 'KR' AND return_basis <> 'PRICE') OR (market = 'US' AND return_basis <> 'TOTAL')$q$),
    ('h7c', '5 H7', 'H7 보조: PROXY_INDEX 행이 있는 종목은 proxy_index_name 이 있다',
     $q$SELECT count(*) FROM asset a WHERE a.proxy_index_name IS NULL
        AND EXISTS (SELECT 1 FROM asset_month_return r WHERE r.asset_id = a.id AND r.return_kind = 'PROXY_INDEX')$q$),
    ('s1', '1 행 수', 'S1 재생 범위: 모든 월 블록이 2007-01-01 이후, 2026-01-01 앞',
     $q$SELECT count(*) FROM month_block WHERE month_start < DATE '2007-01-01' OR month_start >= DATE '2026-01-01'$q$);

-- 1 행 수 ------------------------------------------------------------------
-- 6개 테이블의 행 수는 db/sample/*.csv 와 같아야 한다 (표본: 종목 20 · 월 블록 100). 다른 기능이 채우는 컬럼은 단언하지 않는다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '1 행 수', x.item, x.expected, pg_temp.val(x.sql), x.expected IS NOT DISTINCT FROM pg_temp.val(x.sql)
FROM (VALUES
    ('asset_group 행 수', '12', 'SELECT count(*) FROM asset_group'),
    ('asset 행 수', '20', 'SELECT count(*) FROM asset'),
    ('asset_position 행 수 (기준일 1개 × 종목 20)', '20', 'SELECT count(*) FROM asset_position'),
    ('month_block 행 수 (64 + 36)', '100', 'SELECT count(*) FROM month_block'),
    ('asset_month_return 행 수 (20 × 100)', '2000', 'SELECT count(*) FROM asset_month_return'),
    ('fx_month 행 수', '100', 'SELECT count(*) FROM fx_month'),
    ('종목 구성: KR STOCK', '6', $$SELECT count(*) FROM asset WHERE market = 'KR' AND asset_type = 'STOCK'$$),
    ('종목 구성: KR ETF', '4', $$SELECT count(*) FROM asset WHERE market = 'KR' AND asset_type = 'ETF'$$),
    ('종목 구성: US STOCK', '6', $$SELECT count(*) FROM asset WHERE market = 'US' AND asset_type = 'STOCK'$$),
    ('종목 구성: US ETF', '4', $$SELECT count(*) FROM asset WHERE market = 'US' AND asset_type = 'ETF'$$),
    ('시장 지수 대용 2종 (069500, SPY) 존재', '2', $$SELECT count(*) FROM asset WHERE (market, ticker) IN (('KR', '069500'), ('US', 'SPY'))$$),
    ('PROXY_INDEX 행 수 (2 종목 × 64개월)', '128', $$SELECT count(*) FROM asset_month_return WHERE return_kind = 'PROXY_INDEX'$$),
    ('ACTUAL 행 수', '1872', $$SELECT count(*) FROM asset_month_return WHERE return_kind = 'ACTUAL'$$),
    ('월 블록 구간 ① 2007-01 ~ 2012-04', '64', $$SELECT count(*) FROM month_block WHERE month_start BETWEEN DATE '2007-01-01' AND DATE '2012-04-01'$$),
    ('월 블록 구간 ② 2023-01 ~ 2025-12', '36', $$SELECT count(*) FROM month_block WHERE month_start BETWEEN DATE '2023-01-01' AND DATE '2025-12-01'$$),
    ('기준일 종류 수', '1', 'SELECT count(DISTINCT base_date) FROM asset_position'),
    ('기준일', '2026-01-02', 'SELECT min(base_date)::text FROM asset_position')
) AS x(item, expected, sql);

-- 정보 행 (FAIL 아님): 순위가 채워진 주식 수 (시가총액 순위 — 상장주식수 미수집이라 일부가 빈 칸일 수 있다: erd §9 P0-08), 판정 8컬럼이 채워진 종목 수 (P0-10 이 채운다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('1 행 수', 'market_cap_rank 가 채워진 주식 수 (정보, 최대 12)', '(정보)',
        pg_temp.val($$SELECT count(*) FROM asset_position p JOIN asset a ON a.id = p.asset_id WHERE a.asset_type = 'STOCK' AND p.market_cap_rank IS NOT NULL$$), true),
       ('1 행 수', '판정 8컬럼이 채워진 asset_position 행 수 (정보, 단언하지 않음)', '(정보)',
        pg_temp.val('SELECT count(*) FROM asset_position WHERE system_position IS NOT NULL'), true);

-- 양성: 위 쿼리 모두 위반 0건 ----------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT q.section, q.item || ' — 위반 0건', '0', pg_temp.val(q.sql), pg_temp.val(q.sql) IS NOT DISTINCT FROM '0'
FROM zz_q q
ORDER BY q.code;

-- 음성: 일부러 위반 1건을 만들면 검사 쿼리가 1건으로 잡는지 (서브트랜잭션 안의 DELETE/UPDATE, 끝에 되돌린다) ----------
CREATE TEMP TABLE zz_neg (n serial, q text NOT NULL, label text NOT NULL, mutate text NOT NULL);
INSERT INTO zz_neg (q, label, mutate) VALUES
    ('h3', 'H3: 수익률 행 1개를 지우면', $m$DELETE FROM asset_month_return WHERE id = (SELECT min(id) FROM asset_month_return)$m$),
    ('ws', 'ticker 뒤에 공백 하나를 붙이면', $m$UPDATE asset SET ticker = 'AAPL ' WHERE market = 'US' AND ticker = 'AAPL'$m$),
    ('ws', 'ticker 안에 공백 하나를 넣으면', $m$UPDATE asset SET ticker = 'BRK B' WHERE market = 'US' AND ticker = 'KO'$m$),
    ('fmt', 'KR ticker 의 앞자리 0 이 빠지면', $m$UPDATE asset SET ticker = '5930' WHERE market = 'KR' AND ticker = '005930'$m$),
    ('etfrank', 'ETF 에 순위를 넣으면', $m$UPDATE asset_position SET market_cap_rank = 1 WHERE asset_id = (SELECT id FROM asset WHERE market = 'US' AND ticker = 'SPY')$m$),
    ('h1', 'H1: 같은 기준일 USD 종목의 환율이 다르면', $m$UPDATE asset_position SET base_fx_rate = base_fx_rate + 1 WHERE asset_id = (SELECT id FROM asset WHERE market = 'US' AND ticker = 'AAPL')$m$),
    ('h1', 'H1: KRW 종목의 환율이 1이 아니면', $m$UPDATE asset_position SET base_fx_rate = 2 WHERE asset_id = (SELECT id FROM asset WHERE market = 'KR' AND ticker = '005930')$m$),
    ('h4', 'H4: 어느 달의 USD 행을 지우면', $m$DELETE FROM fx_month WHERE id = (SELECT min(id) FROM fx_month)$m$),
    ('h7a', 'H7: 주식 행이 PROXY_INDEX 이면', $m$UPDATE asset_month_return SET return_kind = 'PROXY_INDEX' WHERE id = (SELECT r.id FROM asset_month_return r JOIN asset a ON a.id = r.asset_id WHERE a.asset_type = 'STOCK' ORDER BY r.id LIMIT 1)$m$),
    ('h7a', 'H7: 대체 구간의 ETF 행이 ACTUAL 이면', $m$UPDATE asset_month_return SET return_kind = 'ACTUAL' WHERE id = (SELECT r.id FROM asset_month_return r WHERE r.return_kind = 'PROXY_INDEX' ORDER BY r.id LIMIT 1)$m$),
    ('h7b', 'H7: 국내 종목의 수익률 기준이 TOTAL 이면', $m$UPDATE asset SET return_basis = 'TOTAL' WHERE market = 'KR' AND ticker = '005930'$m$),
    ('h7c', 'H7 보조: 대체 수익률 ETF 의 proxy_index_name 이 비면', $m$UPDATE asset SET proxy_index_name = NULL WHERE market = 'KR' AND ticker = '360750'$m$);
-- asset_group.code 는 CHECK(^[A-Z][A-Z0-9_]*$)가 공백을 막아 음성 사례를 만들 수 없다. gws 는 양성만 본다.

DO $$
DECLARE
    r record;
    v_sql text;
    v_n   text;
BEGIN
    FOR r IN SELECT n.q, n.label, n.mutate, q.section FROM zz_neg n JOIN zz_q q ON q.code = n.q ORDER BY n.n LOOP
        SELECT sql INTO v_sql FROM zz_q WHERE code = r.q;
        v_n := NULL;
        BEGIN
            EXECUTE r.mutate;
            EXECUTE v_sql INTO v_n;
            RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
        EXCEPTION WHEN OTHERS THEN
            IF SQLERRM <> 'zz_undo' THEN
                v_n := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
            END IF;
        END;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES (r.section, '음성 [' || r.q || '] ' || r.label || ' 위반 1건', '1', v_n, v_n IS NOT DISTINCT FROM '1');
    END LOOP;
END $$;

-- 6 손 대조 ----------------------------------------------------------------
-- 손 대조 5칸 + 기준일 2칸(SPY 기준일 가격·환율)의 기대값은 가격에서 나온 값이라 git 이 무시하는 db/sample/hand_check.csv(code,expected)에서 읽는다.
-- 소유자가 원본 일별 종가에서 손으로 계산한 문자열(소수 8자리, 기준일 가격·환율은 4자리)을 넣는다.
-- 파일이 없거나 칸이 모자라면 기대값이 '<hand_check.csv 없음>' 이라 FAIL 이다 (헛 PASS 방지).
CREATE TEMP TABLE zz_hand (code text, expected text);
\! test -f db/sample/hand_check.csv
\if :SHELL_ERROR
  \echo 'db/sample/hand_check.csv 가 없다 — 6절 손 대조는 FAIL 이 된다 (README 의 손 대조 안내를 본다)'
\else
  \copy zz_hand FROM 'db/sample/hand_check.csv' WITH (FORMAT csv, HEADER MATCH)
\endif

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 손 대조', x.item, h.expected, pg_temp.val(x.sql), h.expected IS NOT DISTINCT FROM pg_temp.val(x.sql)
FROM (VALUES
    ('1', '① 005930 2008-10 local_return (KR PRICE, 수정주가, 급락 달)',
     $$SELECT r.local_return::text FROM asset_month_return r JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id WHERE a.market = 'KR' AND a.ticker = '005930' AND m.month_start = DATE '2008-10-01'$$),
    ('2', '② XOM 2011-08 local_return (US TOTAL, 배당락 달 — 수정 종가를 썼다는 증거)',
     $$SELECT r.local_return::text FROM asset_month_return r JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id WHERE a.market = 'US' AND a.ticker = 'XOM' AND m.month_start = DATE '2011-08-01'$$),
    ('3', '③ 360750 2008-10 local_return (PROXY_INDEX: SPY 가격 × 환율 − 보수/12)',
     $$SELECT r.local_return::text FROM asset_month_return r JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id WHERE a.market = 'KR' AND a.ticker = '360750' AND m.month_start = DATE '2008-10-01'$$),
    ('4', '④ fx_month 2008-10 fx_change (월말 관측값 규칙: 월말 환율 / 직전 월말 환율 − 1)',
     $$SELECT f.fx_change::text FROM fx_month f JOIN month_block m ON m.id = f.month_block_id WHERE f.currency = 'USD' AND m.month_start = DATE '2008-10-01'$$),
    ('5', '⑤ 069500 2023-01 local_return (구간이 끊긴 뒤 첫 달 — 직전 월말은 2022-12)',
     $$SELECT r.local_return::text FROM asset_month_return r JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id WHERE a.market = 'KR' AND a.ticker = '069500' AND m.month_start = DATE '2023-01-01'$$),
    ('base_SPY', '덤 SPY base_price (2026-01-02 실제 종가, 수정 종가 아님)',
     $$SELECT p.base_price::text FROM asset_position p JOIN asset a ON a.id = p.asset_id WHERE a.market = 'US' AND a.ticker = 'SPY'$$),
    ('base_SPY_fx', '덤 SPY base_fx_rate (2026-01-02 DEXKOUS 관측값, 소수 4자리)',
     $$SELECT p.base_fx_rate::text FROM asset_position p JOIN asset a ON a.id = p.asset_id WHERE a.market = 'US' AND a.ticker = 'SPY'$$)
) AS x(code, item, sql)
CROSS JOIN LATERAL (SELECT COALESCE((SELECT string_agg(expected, '|' ORDER BY expected) FROM zz_hand WHERE code = x.code),
                                    '<hand_check.csv 없음>') AS expected) h;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('6 손 대조', 'hand_check.csv 행 수 (손 대조 5 + 기준일 2, 코드 중복 없음)', '7',
        pg_temp.val($$SELECT count(*) FROM zz_hand$$) || CASE WHEN (SELECT count(DISTINCT code) FROM zz_hand) = (SELECT count(*) FROM zz_hand) THEN '' ELSE ' (코드 중복)' END,
        pg_temp.val($$SELECT count(*) FROM zz_hand$$) = '7' AND (SELECT count(DISTINCT code) FROM zz_hand) = 7);

-- 7 판정 ---------------------------------------------------------------------
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

-- 7.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3).
--     판정 표식은 트랜잭션이 되돌아가도 남는다. 통과해도 2(흔적 확인 대기)까지만 올린다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-08 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 8 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 6개 테이블의 행 수와 identity 마지막 값이 실행 전과 같고, public 에 zz_ 객체가 없다. 검증은 INSERT 를 하지 않으므로 번호표를 소비하지 않는다.
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
        RAISE EXCEPTION E'P0-08 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    -- 단언을 모두 통과했고 1–7절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_08_check;
