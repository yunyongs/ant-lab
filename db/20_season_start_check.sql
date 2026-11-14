-- =====================================================================
-- my Fund Manager Phase 0 — 시즌 시작 검증 SQL (P0-14)
-- 대상: db/20_season_start.sql 이 연 연습 시즌 (member · season · season_path · cash_account · cash_ledger · turn_price 0턴)
-- 근거: docs/erd.md 6.3·6.5(S1–S4)·7.3·7.5(대사 규칙 1·2·3·9·12·15)·5.5(H1·H2)·D16·D30, docs/PLAN.md §4.1-1·§4.2·§4.6·§4.10·§5.2,
--       db/14_stats_calendar_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/20_season_start_check.sql
--       20 을 실행한 DB 에서 한다. INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아 실습 DB(antlab)에서도 돌릴 수 있다.
-- 판정: 마지막 줄 `p0_14_check` 가 `PASS` 이고 종료 코드가 0이면 통과다.
--       0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고, 그 밖에 하나라도 어긋나면 8절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 8절 뒤에 ROLLBACK 한다. 9절이 행 수·identity 마지막 값이 실행 전과 같은지 확인한다.
-- 절:   0 전제(FAIL 이면 여기서 멈춤) → 1 설정 → 2 제약 → 3 경로 → 4 계좌 → 5 가격 → 6 손 계산 → 7 정보 → 8 판정 → ROLLBACK → 9 흔적
--       음성 사례(규칙을 일부러 어긴 뒤 잡히는지)는 사례가 속한 규칙의 절에 "음성 [규칙]" 행으로 쌓인다.
-- 규칙 코드 20개 (규칙마다 양성 쿼리 1개 + 음성 사례 1개 이상, 8절이 커버리지 N/N 을 확인한다):
--       MEMBER_LOOKUP SETTING_VALUES NO_DEFAULT_D30 / FORMATION_SUM GOALKEEPER_MIN RISK_LIST ATOMIC_ROLLBACK /
--       PATH_COMPLETE PATH_RANGE CALENDAR BLOCKS / ACCOUNT_OPENING CASH_SUM LEDGER_CHAIN RESERVED_ZERO FORMATION_CURRENT /
--       POOL_PRECOND TURN0_PRICE POOL_COMPLETE NO_FUTURE_PRICE
--       모두 "시즌을 연 뒤 언제나 참"인 규칙이다 — P0-15 이후가 진행된 DB 에서도 PASS 해야 전체 순서를 다시 돌릴 수 있다.
--       시작 직후에만 참인 것(잔액 = 초기 자금, 원장 1행, current_turn_no = 0)은 규칙에 넣지 않고 7절 정보 행으로만 낸다.
-- 독립 계산: 0턴 가격은 ROUND 에 기대지 않고 정수 산술(div)로 다시 계산한다. 블록 수는 LAG 대신 "달 − 턴 개월"의 서로 다른 값 수로 센다.
--       기대값(설정·요율·포메이션·위험 목록)은 20 파일이 아니라 문서(PLAN §4.1-1·§4.6·§4.10)에서 다시 옮겨 적었다.
-- 출력: 건수와 판정만. 경로의 달·가격은 내지 않는다 (6절 손 계산은 합성 값이라 시세가 아니다).
-- P0-08·P0-09·P0-11·P0-12 판정 계약에서 복사한 것: ① pg_temp.zz_verdict ② 결과 표 zz_check_result ④ check_eq
--       ⑤ 0절 뒤 멈춤과 판정(표식 2 또는 1) ⑥ 9절 흔적 확인(표식 0)과 마지막 CASE.
-- 새 문법: 서브트랜잭션(PL/pgSQL 예외 블록)으로 "바꿔 보고 되돌리기", GET STACKED DIAGNOSTICS 로 제약 이름 읽기,
--       프로시저(CALL), EXECUTE 로 만든 SQL, div() 정수 나눗셈, LATERAL (VALUES …) 로 열마다 한 줄씩 비교.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 1–8절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

-- 흔적 확인용 스냅샷: 읽거나 일부러 바꿔 보는 표들의 행 수와 identity 마지막 값. 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    c text;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['member', 'season', 'season_path', 'cash_account', 'cash_ledger', 'turn_price', 'asset_position', 'month_block'] LOOP
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
CREATE TEMP TABLE zz_before AS SELECT pg_temp.zz_snap() AS snap;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '0 전제' '1 설정' '2 제약' '3 경로' '4 계좌' '5 가격' '6 손 계산' '7 정보' '8 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

-- 연습 시즌 찾기: 20 파일의 ss_cur 와 같은 뜻이지만 이메일을 문서 W4 에서 다시 옮겨 적었다
CREATE TEMP VIEW zz_cur AS
SELECT s.* FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');

-- 판정 프로시저 ------------------------------------------------------------

-- 값 비교. NULL끼리는 같다고 본다.
CREATE PROCEDURE pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text)
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

-- 규칙 코드의 절을 찾는다
CREATE FUNCTION pg_temp.sec(p_code text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE v text;
BEGIN
    SELECT section INTO v FROM zz_q WHERE code = p_code;
    RETURN v;
END $$;

-- 음성 사례(위반 개수형): 바꾼 뒤 규칙 쿼리를 돌려 위반이 p_min 건 이상 나와야 한다. 서브트랜잭션이라 바꾼 것은 되돌아간다.
CREATE PROCEDURE pg_temp.neg_viol(p_code text, p_label text, p_mutate text, p_min bigint)
LANGUAGE plpgsql AS $$
DECLARE
    v_sql text := (SELECT sql FROM zz_q WHERE code = p_code);
    v_n   text;
BEGIN
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

-- 음성 사례(오류형): DDL 이 막아야 한다. SQLSTATE 와 제약 이름(NOT NULL 은 컬럼 이름)까지 대조한다.
CREATE PROCEDURE pg_temp.neg_err(p_code text, p_label text, p_sql text, p_state text, p_name text)
LANGUAGE plpgsql AS $$
DECLARE
    v_con text;
    v_col text;
    v_act text;
BEGIN
    BEGIN
        EXECUTE p_sql;
        v_act := '오류 없음';
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            GET STACKED DIAGNOSTICS v_con = CONSTRAINT_NAME, v_col = COLUMN_NAME;
            v_act := SQLSTATE || ' ' || COALESCE(NULLIF(v_con, ''), NULLIF(v_col, ''), '-');
        END IF;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (pg_temp.sec(p_code), '음성 [' || p_code || '] ' || p_label, p_state || ' ' || p_name, v_act, v_act = p_state || ' ' || p_name);
END $$;

-- 0 전제 -------------------------------------------------------------------
-- 하나라도 FAIL 이면 1절 이후 결과를 믿지 않는다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('9개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '9',
        (SELECT count(*)::text FROM unnest(ARRAY['member', 'season', 'season_path', 'cash_account', 'cash_ledger', 'turn_price', 'asset', 'asset_position', 'month_block']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('10 적재가 끝나 있다 (asset_position · month_block 이 비어 있지 않다)', 'true',
        (SELECT ((SELECT count(*) FROM asset_position) > 0 AND (SELECT count(*) FROM month_block) > 0)::text)),
    ('12 판정이 끝나 있다 (이 시즌 기준일 풀에서 판정이 비어 있는 행 0건 — 비어 있으면 db/12_calc_position.sql 을 먼저)', '0',
        (SELECT count(*)::text FROM asset_position p JOIN zz_cur c ON c.base_date = p.base_date WHERE p.system_position IS NULL)),
    ('연습 시즌이 정확히 1개 있다 (db/20_season_start.sql 을 먼저 실행한다)', '1', (SELECT count(*)::text FROM zz_cur))
) AS x(item, expected, actual);

-- 정보 행: 현재 DB 이름 (FAIL 아님)
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
        RAISE EXCEPTION 'P0-14 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 01·02·03 스키마와 10 적재, 12 판정, 20 시즌 시작을 먼저 실행했는지 확인한다)', v_fail;
    END IF;
END $$;

-- 검사 쿼리 목록: 위반 개수를 돌려주는 쿼리. 양성(0건)과 음성(위반을 만든 뒤 기대한 개수)에 같은 쿼리를 쓴다.
CREATE TEMP TABLE zz_q (code text PRIMARY KEY, section text NOT NULL, item text NOT NULL, sql text NOT NULL);
INSERT INTO zz_q (code, section, item, sql) VALUES
    ('MEMBER_LOOKUP', '1 설정', '연습 회원 1명 · 그 회원의 시즌 1개에서 벗어난 수 (이메일로 찾는다)',
     $q$SELECT abs((SELECT count(*) FROM member WHERE lower(email) = lower('phase0@example.invalid')) - 1)
             + abs((SELECT count(*) FROM zz_cur) - 1)$q$),
    ('SETTING_VALUES', '1 설정', '시즌 설정이 문서의 기대값(초기 자금 · 요율 3 · 위험 · 길이 · 시작 포메이션 · D30 나머지)과 다른 컬럼 수, 회원 이름이 다르면 1 더함',
     $q$SELECT count(*) + (SELECT count(*) FROM member WHERE lower(email) = lower('phase0@example.invalid') AND name <> '연습 감독')
        FROM zz_cur c CROSS JOIN LATERAL (VALUES
            ('initial_cash',       c.initial_cash       IS NOT DISTINCT FROM 30000000::numeric),
            ('fee_rate_kr',        c.fee_rate_kr        IS NOT DISTINCT FROM 0.00015::numeric),
            ('fee_rate_us',        c.fee_rate_us        IS NOT DISTINCT FROM 0.0025::numeric),
            ('fx_spread_rate',     c.fx_spread_rate     IS NOT DISTINCT FROM 0.0040::numeric),
            ('risk_limit',         c.risk_limit         IS NOT DISTINCT FROM -0.20::numeric),
            ('path_length',        c.path_length        IS NOT DISTINCT FROM 10),
            ('start_goalkeeper',   c.start_goalkeeper   IS NOT DISTINCT FROM 10),
            ('start_defender',     c.start_defender     IS NOT DISTINCT FROM 30),
            ('start_midfielder',   c.start_midfielder   IS NOT DISTINCT FROM 30),
            ('start_forward',      c.start_forward      IS NOT DISTINCT FROM 30),
            ('seed',               c.seed               IS NOT DISTINCT FROM 14),
            ('engine_version',     c.engine_version     IS NOT DISTINCT FROM 'p0-sql'),
            ('data_snapshot_date', c.data_snapshot_date IS NOT DISTINCT FROM DATE '2026-11-07'),
            ('base_date',          c.base_date          IS NOT DISTINCT FROM DATE '2026-01-02')
        ) AS d(col, ok) WHERE NOT d.ok$q$),
    ('NO_DEFAULT_D30', '1 설정', 'D30 8컬럼(seed · engine_version · data_snapshot_date · base_date · initial_cash · 요율 3) 중 NOT NULL 이 아니거나 DB 기본값이 있는 컬럼 수',
     $q$SELECT 8 - count(*) FROM pg_attribute a
        WHERE a.attrelid = 'public.season'::regclass
          AND a.attname IN ('seed', 'engine_version', 'data_snapshot_date', 'base_date', 'initial_cash', 'fee_rate_kr', 'fee_rate_us', 'fx_spread_rate')
          AND a.attnotnull AND NOT a.atthasdef AND a.attidentity = ''
          AND NOT EXISTS (SELECT 1 FROM pg_attrdef d WHERE d.adrelid = a.attrelid AND d.adnum = a.attnum)$q$),
    ('FORMATION_SUM', '2 제약', '시작 · 현재 포메이션 합이 100 이 아닌 시즌 수',
     $q$SELECT count(*) FROM zz_cur
        WHERE start_goalkeeper + start_defender + start_midfielder + start_forward <> 100
           OR current_goalkeeper + current_defender + current_midfielder + current_forward <> 100$q$),
    ('GOALKEEPER_MIN', '2 제약', '시작 · 현재 골키퍼가 1 미만인 시즌 수 (PLAN §4.10 골키퍼 최소 1)',
     $q$SELECT count(*) FROM zz_cur WHERE start_goalkeeper < 1 OR current_goalkeeper < 1$q$),
    ('RISK_LIST', '2 제약', '위험 선언이 −10/−20/−30/−40% 목록 밖인 시즌 수 (PLAN §4.1-1)',
     $q$SELECT count(*) FROM zz_cur WHERE risk_limit NOT IN (-0.10, -0.20, -0.30, -0.40)$q$),
    ('ATOMIC_ROLLBACK', '2 제약', '연습 회원 이름이 설정값이 아닌 수 (앞 문장이 되돌아가지 않고 남았는지 — 음성 사례가 서브트랜잭션으로 시험한다)',
     $q$SELECT count(*) FROM member WHERE lower(email) = lower('phase0@example.invalid') AND name <> '연습 감독'$q$),
    ('PATH_COMPLETE', '3 경로', 'S1 앞부분: 경로 행 수가 path_length 와 다른 수 + 1..path_length 에서 빠진 턴 수',
     $q$SELECT abs((SELECT count(*) FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id) - (SELECT path_length FROM zz_cur))
             + (SELECT count(*) FROM generate_series(1, (SELECT path_length::int FROM zz_cur)) g(n)
                WHERE NOT EXISTS (SELECT 1 FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no = g.n))$q$),
    ('PATH_RANGE', '3 경로', 'S1 뒷부분: 2007-01-01 이상 · 기준일이 속한 해 1월 1일 미만을 벗어난 턴 수 (PLAN §4.2 "작년 말까지")',
     $q$SELECT count(*) FROM zz_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id
        WHERE mb.month_start < DATE '2007-01-01'
           OR mb.month_start >= make_date(EXTRACT(YEAR FROM c.base_date)::int, 1, 1)$q$),
    ('CALENDAR', '3 경로', 'S4: 턴 k 의 실제 달의 월 ≠ ((k − 1) % 12) + 1 인 턴 수 (PLAN §4.11 달력 정렬)',
     $q$SELECT count(*) FROM zz_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id
        WHERE EXTRACT(MONTH FROM mb.month_start)::int <> ((sp.turn_no - 1) % 12) + 1$q$),
    ('BLOCKS', '3 경로', '블록 수가 2 미만이면 1 (역사 한 구간을 그대로 재생하지 않는다). 블록 = "달 − 턴 개월"이 같은 연속 구간',
     $q$SELECT CASE WHEN count(DISTINCT mb.month_start - (sp.turn_no * INTERVAL '1 month')) < 2 THEN 1 ELSE 0 END
        FROM zz_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id$q$),
    ('ACCOUNT_OPENING', '4 계좌', '대사 규칙 1: 계좌 없음 · 원장 없음 · 첫 원장(최소 id)이 DEPOSIT · 금액 = 거래 후 잔액 = 초기 자금 · 주문 없음에서 벗어난 시즌 수',
     $q$SELECT count(*) FROM zz_cur c
        WHERE NOT EXISTS (SELECT 1 FROM cash_account a WHERE a.season_id = c.id)
           OR NOT EXISTS (SELECT 1 FROM (SELECT * FROM cash_ledger l WHERE l.season_id = c.id ORDER BY l.id LIMIT 1) f
                          WHERE f.type = 'DEPOSIT' AND f.amount = c.initial_cash AND f.balance_after = c.initial_cash AND f.ref_order_id IS NULL)$q$),
    ('CASH_SUM', '4 계좌', '대사 규칙 2: Σ 원장 amount ≠ 잔액인 시즌 수',
     $q$SELECT count(*) FROM zz_cur c JOIN cash_account a ON a.season_id = c.id
        WHERE a.balance <> (SELECT COALESCE(sum(l.amount), 0) FROM cash_ledger l WHERE l.season_id = c.id)$q$),
    ('LEDGER_CHAIN', '4 계좌', '대사 규칙 3: id 순 연쇄(balance_after = 앞 balance_after + amount, 첫 행은 0 에서)가 끊긴 행 수 + 최신 balance_after ≠ 잔액인 시즌 수',
     $q$SELECT (SELECT count(*) FROM (
                    SELECT l.balance_after, l.amount, COALESCE(lag(l.balance_after) OVER (PARTITION BY l.season_id ORDER BY l.id), 0) AS prev
                    FROM cash_ledger l WHERE l.season_id IN (SELECT id FROM zz_cur)) t
                WHERE t.balance_after <> t.prev + t.amount)
             + (SELECT count(*) FROM zz_cur c JOIN cash_account a ON a.season_id = c.id
                WHERE a.balance <> (SELECT l.balance_after FROM cash_ledger l WHERE l.season_id = c.id ORDER BY l.id DESC LIMIT 1))$q$),
    ('RESERVED_ZERO', '4 계좌', '대사 규칙 9: 예약금이 0 이 아닌 시즌 수 (지정가가 없어 주문 유무와 관계없이 0)',
     $q$SELECT count(*) FROM zz_cur c JOIN cash_account a ON a.season_id = c.id
        WHERE a.reserved <> 0$q$),
    ('FORMATION_CURRENT', '4 계좌', '대사 규칙 15 일반형: 현재 포메이션 ≠ (변경이 없으면 시작, 있으면 마지막 변경의 이후 값)인 시즌 수',
     $q$SELECT count(*) FROM zz_cur c
        LEFT JOIN LATERAL (SELECT * FROM formation_change fc WHERE fc.season_id = c.id ORDER BY fc.turn_no DESC, fc.id DESC LIMIT 1) f ON true
        WHERE (c.current_goalkeeper, c.current_defender, c.current_midfielder, c.current_forward)
              IS DISTINCT FROM
              (COALESCE(f.after_goalkeeper, c.start_goalkeeper), COALESCE(f.after_defender, c.start_defender),
               COALESCE(f.after_midfielder, c.start_midfielder), COALESCE(f.after_forward, c.start_forward))$q$),
    ('POOL_PRECOND', '5 가격', 'S2 · H1 · H2: 기준일 풀이 비었으면 1 + 달러 환율이 둘 이상이면 1 + 원화 환율 ≠ 1 인 행 + 판정이 비었거나 판정 창 끝이 기준일 전달 1일이 아닌 행',
     $q$SELECT (SELECT CASE WHEN count(*) = 0 THEN 1 ELSE 0 END FROM asset_position p JOIN zz_cur c ON p.base_date = c.base_date)
             + (SELECT CASE WHEN count(DISTINCT p.base_fx_rate) > 1 THEN 1 ELSE 0 END
                FROM asset_position p JOIN asset a ON a.id = p.asset_id JOIN zz_cur c ON p.base_date = c.base_date WHERE a.currency = 'USD')
             + (SELECT count(*) FROM asset_position p JOIN asset a ON a.id = p.asset_id JOIN zz_cur c ON p.base_date = c.base_date
                WHERE a.currency = 'KRW' AND p.base_fx_rate <> 1)
             + (SELECT count(*) FROM asset_position p JOIN zz_cur c ON p.base_date = c.base_date
                WHERE p.system_position IS NULL
                   OR p.window_end IS DISTINCT FROM make_date(EXTRACT(YEAR FROM (c.base_date - INTERVAL '1 month'))::int, EXTRACT(MONTH FROM (c.base_date - INTERVAL '1 month'))::int, 1))$q$),
    ('TURN0_PRICE', '5 가격', '0턴 가격 ≠ 정수 산술 독립 계산 div(기준가 × 10⁴ × 환율 × 10⁴ × 2 + 10⁸, 2 × 10⁸)인 행 수 (ROUND 에 기대지 않는다. 양수의 0에서 먼 쪽 반올림)',
     $q$SELECT count(*) FROM (
            SELECT ap.asset_id, div((ap.base_price * 10000) * (ap.base_fx_rate * 10000) * 2 + 100000000, 200000000) AS want
            FROM zz_cur c JOIN asset_position ap ON ap.base_date = c.base_date) w
        FULL JOIN (SELECT tp.asset_id, tp.price FROM turn_price tp JOIN zz_cur c ON tp.season_id = c.id AND tp.turn_no = 0) t ON t.asset_id = w.asset_id
        WHERE t.price IS DISTINCT FROM w.want$q$),
    ('POOL_COMPLETE', '5 가격', '대사 규칙 12 (0턴): 0턴 가격 종목 집합 ≠ 기준일 풀 종목 집합인 종목 수 (양방향 — 풀에 없는 종목 가격도 없다)',
     $q$SELECT count(*) FROM (SELECT ap.asset_id FROM asset_position ap JOIN zz_cur c ON ap.base_date = c.base_date) p
        FULL JOIN (SELECT tp.asset_id FROM turn_price tp JOIN zz_cur c ON tp.season_id = c.id AND tp.turn_no = 0) t ON t.asset_id = p.asset_id
        WHERE p.asset_id IS NULL OR t.asset_id IS NULL$q$),
    ('NO_FUTURE_PRICE', '5 가격', 'S3: turn_no 가 current_turn_no 보다 큰 가격 행 수 (미래 가격을 미리 두지 않는다)',
     $q$SELECT count(*) FROM turn_price tp JOIN zz_cur c ON c.id = tp.season_id WHERE tp.turn_no > c.current_turn_no$q$);

-- 양성: 모든 규칙의 위반이 0건
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT q.section, '[' || q.code || '] ' || q.item, '0', pg_temp.cnt(q.sql)::text, pg_temp.cnt(q.sql) = 0
FROM zz_q q ORDER BY q.code;

-- 음성 사례 --------------------------------------------------------------------
-- 규칙을 일부러 어겨 보고 잡히는지 본다. UPDATE · DELETE · DDL 만 쓰고 INSERT 는 쓰지 않는다 (번호표를 쓰지 않는다).
-- 음성 [오류형]은 DDL(CHECK · NOT NULL)이 막는지 SQLSTATE 와 제약 이름까지 대조한다. 한 사례에 조건 하나만 어긴다 (P0-05 R12).

-- 1 설정
CALL pg_temp.neg_viol('MEMBER_LOOKUP', '연습 회원의 이메일을 바꾸면 (이메일로 찾을 수 없다)',
    $m$UPDATE member SET email = 'zz-other@example.invalid' WHERE lower(email) = lower('phase0@example.invalid')$m$, 1);
CALL pg_temp.neg_viol('SETTING_VALUES', '환전 비용을 옛 잠정값 0.00100000 으로 바꾸면',
    $m$UPDATE season SET fx_spread_rate = 0.001 WHERE id IN (SELECT id FROM zz_cur)$m$, 1);
CALL pg_temp.neg_viol('SETTING_VALUES', '초기 자금을 바꾸면',
    $m$UPDATE season SET initial_cash = 20000000 WHERE id IN (SELECT id FROM zz_cur)$m$, 1);
CALL pg_temp.neg_viol('NO_DEFAULT_D30', 'seed 에 DB 기본값이 붙으면 (카탈로그가 잡는다)',
    $m$ALTER TABLE season ALTER COLUMN seed SET DEFAULT 0$m$, 1);
CALL pg_temp.neg_viol('NO_DEFAULT_D30', 'base_date 의 NOT NULL 이 풀리면',
    $m$ALTER TABLE season ALTER COLUMN base_date DROP NOT NULL$m$, 1);
CALL pg_temp.neg_err('NO_DEFAULT_D30', 'fee_rate_kr 를 NULL 로 바꾸면 (컬럼 이름)',
    $m$UPDATE season SET fee_rate_kr = NULL WHERE id IN (SELECT id FROM zz_cur)$m$, '23502', 'fee_rate_kr');

-- 2 제약
CALL pg_temp.neg_err('FORMATION_SUM', '시작 공격을 1 줄이면 (시작 합 99)',
    $m$UPDATE season SET start_forward = start_forward - 1 WHERE id IN (SELECT id FROM zz_cur)$m$, '23514', 'ck_season_start_formation_sum');
CALL pg_temp.neg_err('FORMATION_SUM', '현재 포메이션을 10·30·30·29 로 두면 (현재 합 99, 범위·골키퍼는 지킴 — 현재 값과 무관)',
    $m$UPDATE season SET current_goalkeeper = 10, current_defender = 30, current_midfielder = 30, current_forward = 29 WHERE id IN (SELECT id FROM zz_cur)$m$, '23514', 'ck_season_current_formation_sum');
CALL pg_temp.neg_err('GOALKEEPER_MIN', '시작 골키퍼를 0 으로 하고 수비에 더하면 (합 100 · 범위 안 — 골키퍼 최소만 어긴다)',
    $m$UPDATE season SET start_goalkeeper = 0, start_defender = start_defender + 10 WHERE id IN (SELECT id FROM zz_cur)$m$, '23514', 'ck_season_start_goalkeeper_min');
CALL pg_temp.neg_err('GOALKEEPER_MIN', '현재 골키퍼 전부를 수비로 옮기면 (합 100 · 범위 안 — 골키퍼 최소만 어긴다)',
    $m$UPDATE season SET current_defender = current_defender + current_goalkeeper, current_goalkeeper = 0 WHERE id IN (SELECT id FROM zz_cur)$m$, '23514', 'ck_season_current_goalkeeper_min');
CALL pg_temp.neg_err('RISK_LIST', '위험 선언을 목록 밖 −15% 로 바꾸면',
    $m$UPDATE season SET risk_limit = -0.15 WHERE id IN (SELECT id FROM zz_cur)$m$, '23514', 'ck_season_risk_limit');

-- ATOMIC_ROLLBACK: 한 서브트랜잭션에서 회원 이름을 바꾼 뒤 포메이션 합 99 로 CHECK 를 어긴다. 예외를 잡은 뒤 이름이 원래 값이면 앞 문장도 되돌아간 것이다.
DO $$
DECLARE
    v_caught boolean := false;
    v_name   text;
BEGIN
    BEGIN
        UPDATE member SET name = 'zz변경' WHERE lower(email) = lower('phase0@example.invalid');
        UPDATE season SET current_forward = current_forward - 1 WHERE id IN (SELECT id FROM zz_cur);
    EXCEPTION WHEN check_violation THEN
        v_caught := true;
    END;
    SELECT name INTO v_name FROM member WHERE lower(email) = lower('phase0@example.invalid');
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('2 제약', '음성 [ATOMIC_ROLLBACK] 이름 변경 뒤 포메이션 합 99 가 CHECK 에 걸려도 앞 문장(이름 변경)까지 되돌아간다',
            'CHECK 걸림, 이름 그대로', CASE WHEN v_caught THEN 'CHECK 걸림' ELSE 'CHECK 안 걸림' END || ', ' || CASE WHEN v_name = '연습 감독' THEN '이름 그대로' ELSE '이름 바뀜' END,
            v_caught AND v_name = '연습 감독');
END $$;

-- 3 경로
CALL pg_temp.neg_viol('PATH_COMPLETE', '경로 5턴 행을 지우면',
    $m$DELETE FROM season_path WHERE season_id IN (SELECT id FROM zz_cur) AND turn_no = 5$m$, 1);
CALL pg_temp.neg_viol('PATH_RANGE', '1턴이 쓰는 월 블록의 달을 기준일 해 1월(재생 범위 밖)로 바꾸면',
    $m$UPDATE month_block SET month_start = DATE '2026-01-01'
       WHERE id = (SELECT sp.month_block_id FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id WHERE sp.turn_no = 1)$m$, 1);
CALL pg_temp.neg_viol('CALENDAR', '5턴(5월)을 6월 블록으로 바꾸면',
    $m$UPDATE season_path SET month_block_id = (SELECT id FROM month_block WHERE month_start = DATE '2011-06-01')
       WHERE season_id IN (SELECT id FROM zz_cur) AND turn_no = 5$m$, 1);
CALL pg_temp.neg_viol('BLOCKS', '10턴 모두를 한 해의 연속 10개월(1월 시작)로 바꾸면 (블록 1개)',
    $m$UPDATE season_path sp SET month_block_id = (SELECT id FROM month_block WHERE month_start = (DATE '2011-01-01' + ((sp.turn_no - 1) * INTERVAL '1 month'))::date)
       WHERE season_id IN (SELECT id FROM zz_cur)$m$, 1);

-- 4 계좌
CALL pg_temp.neg_viol('ACCOUNT_OPENING', '초기 자금만 +1 하면 (원장 · 잔액은 그대로라 규칙 2 는 통과하고 규칙 1 만 잡힌다)',
    $m$UPDATE season SET initial_cash = initial_cash + 1 WHERE id IN (SELECT id FROM zz_cur)$m$, 1);
CALL pg_temp.neg_viol('ACCOUNT_OPENING', '첫 원장을 지우면',
    $m$DELETE FROM cash_ledger WHERE id = (SELECT min(l.id) FROM cash_ledger l JOIN zz_cur c ON c.id = l.season_id)$m$, 1);
CALL pg_temp.neg_viol('CASH_SUM', '잔액을 1 늘리면 (잔액이 0 이어도 CHECK 를 건드리지 않는다)',
    $m$UPDATE cash_account SET balance = balance + 1 WHERE season_id IN (SELECT id FROM zz_cur)$m$, 1);
CALL pg_temp.neg_viol('LEDGER_CHAIN', '첫 원장의 거래 후 잔액을 1 줄이면',
    $m$UPDATE cash_ledger SET balance_after = balance_after - 1 WHERE id = (SELECT min(l.id) FROM cash_ledger l JOIN zz_cur c ON c.id = l.season_id)$m$, 1);
CALL pg_temp.neg_viol('RESERVED_ZERO', '예약금을 1 로 하면 (잔액이 0 이어도 CHECK 를 건드리지 않게 잔액도 +1)',
    $m$UPDATE cash_account SET balance = balance + 1, reserved = 1 WHERE season_id IN (SELECT id FROM zz_cur)$m$, 1);
CALL pg_temp.neg_viol('FORMATION_CURRENT', '변경 이력 없이 현재 포메이션을 다른 유효 포메이션으로 바꾸면 (합 100 · 범위 · 골키퍼 지킴, 현재 값과 무관)',
    $m$UPDATE season s SET (current_goalkeeper, current_defender, current_midfielder, current_forward) =
           (SELECT v.g, v.d, v.m, v.f FROM (VALUES (10, 30, 30, 30), (25, 25, 25, 25)) v(g, d, m, f)
            WHERE (v.g, v.d, v.m, v.f) IS DISTINCT FROM (s.current_goalkeeper, s.current_defender, s.current_midfielder, s.current_forward)
            ORDER BY v.g LIMIT 1)
       WHERE s.id IN (SELECT id FROM zz_cur)$m$, 1);

-- 5 가격
CALL pg_temp.neg_viol('POOL_PRECOND', '달러 종목 하나의 기준일 환율만 바꾸면 (H1: 환율이 둘이 된다)',
    $m$UPDATE asset_position SET base_fx_rate = base_fx_rate + 1
       WHERE id = (SELECT min(p.id) FROM asset_position p JOIN asset a ON a.id = p.asset_id JOIN zz_cur c ON p.base_date = c.base_date WHERE a.currency = 'USD')$m$, 1);
CALL pg_temp.neg_viol('POOL_PRECOND', '한 종목의 판정 8컬럼을 모두 NULL 로 바꾸면 (전부-또는-없음 CHECK 는 통과, H2 가 잡는다)',
    $m$UPDATE asset_position SET window_start = NULL, window_end = NULL, months_used = NULL, up_participation = NULL,
                                 down_participation = NULL, volatility = NULL, max_drawdown = NULL, system_position = NULL
       WHERE id = (SELECT min(p.id) FROM asset_position p JOIN zz_cur c ON p.base_date = c.base_date)$m$, 1);
CALL pg_temp.neg_viol('POOL_PRECOND', '판정 창 끝을 한 달 앞당기면 (창 CHECK 는 통과하고 판정 8컬럼은 그대로 — H2 의 "기준일 전달 1일"이 잡는다)',
    $m$UPDATE asset_position SET window_end = (window_end - INTERVAL '1 month')::date
       WHERE id = (SELECT min(p.id) FROM asset_position p JOIN zz_cur c ON p.base_date = c.base_date)$m$, 1);
CALL pg_temp.neg_viol('POOL_PRECOND', '원화 종목 하나의 기준일 환율을 2 로 바꾸면 (H1: 원화는 1)',
    $m$UPDATE asset_position SET base_fx_rate = 2
       WHERE id = (SELECT min(p.id) FROM asset_position p JOIN asset a ON a.id = p.asset_id JOIN zz_cur c ON p.base_date = c.base_date WHERE a.currency = 'KRW')$m$, 1);
CALL pg_temp.neg_viol('TURN0_PRICE', '달러 종목 하나의 0턴 가격을 +1 하면',
    $m$UPDATE turn_price SET price = price + 1
       WHERE id = (SELECT min(tp.id) FROM turn_price tp JOIN zz_cur c ON tp.season_id = c.id AND tp.turn_no = 0 JOIN asset a ON a.id = tp.asset_id WHERE a.currency = 'USD')$m$, 1);
DO $$
DECLARE
    v_usd bigint := (SELECT count(*) FROM asset_position p JOIN asset a ON a.id = p.asset_id JOIN zz_cur c ON p.base_date = c.base_date WHERE a.currency = 'USD');
BEGIN
    CALL pg_temp.neg_viol('TURN0_PRICE', '변이 "환산 누락": 달러 종목의 0턴 가격을 환율 곱 없이 반올림만 한 값으로 바꾸면 — 달러 행 전부가 잡혀야 한다',
        $m$UPDATE turn_price tp SET price = ROUND(ap.base_price, 0)
           FROM zz_cur c, asset_position ap, asset a
           WHERE tp.season_id = c.id AND tp.turn_no = 0 AND ap.asset_id = tp.asset_id AND ap.base_date = c.base_date AND a.id = tp.asset_id AND a.currency = 'USD'$m$,
        v_usd);
END $$;
CALL pg_temp.neg_viol('POOL_COMPLETE', '0턴 가격 한 행을 지우면',
    $m$DELETE FROM turn_price WHERE id = (SELECT min(tp.id) FROM turn_price tp JOIN zz_cur c ON tp.season_id = c.id AND tp.turn_no = 0)$m$, 1);
CALL pg_temp.neg_viol('NO_FUTURE_PRICE', '0턴 가격 한 행의 턴을 current_turn_no + 1 로 바꾸면',
    $m$UPDATE turn_price SET turn_no = (SELECT current_turn_no + 1 FROM zz_cur)
       WHERE id = (SELECT min(tp.id) FROM turn_price tp JOIN zz_cur c ON tp.season_id = c.id AND tp.turn_no = 0)$m$, 1);

-- 6 손 계산 -------------------------------------------------------------------
-- 합성 값(시세 아님)으로 0턴 공식 두 가지(ROUND · 정수 산술)가 손으로 계산한 값과 같은지, 그리고 틀린 공식(환산 누락 · 두 번 반올림)이 다른 값을 내는지 본다.
-- 기대값은 손 계산이다: ① 5 × 600.5 = 3002.5 → 0에서 먼 쪽 3003 (짝수 쪽이면 3002)
--                        ② 1.4999 × 1000 = 1499.9 → 1500 (기준가를 먼저 반올림하면 1 × 1000 = 1000)
--                        ③ 원화 70000.4999 × 1 → 70000, 70000.5 × 1 → 70001 (환율 1 이면 원화 그대로 한 번 반올림)
CREATE TEMP TABLE zz_hand (n int PRIMARY KEY, label text NOT NULL, bp numeric(19,4) NOT NULL, fx numeric(19,4) NOT NULL, want numeric NOT NULL);
INSERT INTO zz_hand VALUES
    (1, '곱이 정확히 ·5 (홀수 쪽): 5 × 600.5 = 3002.5', 5.0000, 600.5000, 3003),
    (2, '기준가를 먼저 반올림하면 달라지는 값: 1.4999 × 1000 = 1499.9', 1.4999, 1000.0000, 1500),
    (3, '원화 .4999 (환율 1)', 70000.4999, 1.0000, 70000),
    (4, '원화 .5 (환율 1)', 70000.5000, 1.0000, 70001);

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 손 계산', '손 사례 ' || h.n || ' (' || h.label || '): 20 파일의 식 ROUND(기준가 × 환율, 0)', h.want::text, ROUND(h.bp * h.fx, 0)::text, ROUND(h.bp * h.fx, 0) = h.want
FROM zz_hand h ORDER BY h.n;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 손 계산', '손 사례 ' || h.n || ': 검증의 정수 산술 독립 계산도 같다', h.want::text,
       div((h.bp * 10000) * (h.fx * 10000) * 2 + 100000000, 200000000)::text,
       div((h.bp * 10000) * (h.fx * 10000) * 2 + 100000000, 200000000) = h.want
FROM zz_hand h ORDER BY h.n;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 손 계산', '변이 "두 번 반올림"(기준가 먼저 반올림)은 손 사례 2 에서 기대값과 달라야 한다 (식이 실제로 시험된다)', '≠ 1500',
       ROUND(ROUND(h.bp, 0) * h.fx, 0)::text, ROUND(ROUND(h.bp, 0) * h.fx, 0) <> h.want
FROM zz_hand h WHERE h.n = 2;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 손 계산', '변이 "환산 누락"(환율 곱 없음)은 환율이 1 이 아닌 손 사례 1 · 2 에서 기대값과 달라야 한다', '2건 다름',
       count(*) FILTER (WHERE ROUND(h.bp, 0) <> h.want)::text || '건 다름', count(*) FILTER (WHERE ROUND(h.bp, 0) <> h.want) = 2
FROM zz_hand h WHERE h.n IN (1, 2);
-- 실제 데이터에서도 환산 누락 식은 달러 행 전부와 달라야 한다 (환율이 1 이 아닌 풀이어야 시험이 의미 있다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 손 계산', '실제 0턴 가격에서 환산 누락 식(기준가만 반올림)과 같은 달러 행 수 (환산이 실제로 들어갔다)', '0',
       count(*)::text, count(*) = 0
FROM turn_price tp JOIN zz_cur c ON tp.season_id = c.id AND tp.turn_no = 0
JOIN asset a ON a.id = tp.asset_id JOIN asset_position ap ON ap.asset_id = tp.asset_id AND ap.base_date = c.base_date
WHERE a.currency = 'USD' AND tp.price = ROUND(ap.base_price, 0);
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '6 손 계산', '달러 종목이 풀에 있고 환율이 1 이 아니다 (위 시험이 헛돌지 않는다)', 'true',
       (count(*) > 0)::text, count(*) > 0
FROM asset_position p JOIN asset a ON a.id = p.asset_id JOIN zz_cur c ON p.base_date = c.base_date
WHERE a.currency = 'USD' AND p.base_fx_rate <> 1;

-- 7 정보 (판정에 들어가지 않는다) --------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '7 정보', '시작 직후다 (원장 1행 · current_turn_no 0 — 팀 구성 이후에는 false, 규칙에는 넣지 않는다) (정보)', '(정보)',
       ((SELECT count(*) FROM cash_ledger l JOIN zz_cur c ON c.id = l.season_id) = 1 AND (SELECT current_turn_no FROM zz_cur) = 0)::text, true;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '7 정보', '경로 10달 중 관심 지수(13 계산)가 풀에 하나 이상 있는 달 수 (13 을 돌리지 않았으면 0) (정보)', '(정보)',
       (SELECT count(*) FROM zz_cur c JOIN season_path sp ON sp.season_id = c.id
        WHERE EXISTS (SELECT 1 FROM asset_month_attention a WHERE a.month_block_id = sp.month_block_id AND a.attention_index IS NOT NULL))::text, true;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '7 정보', '0턴 가격 행 수 · 기준일 풀 크기 (정보)', '(정보)',
       (SELECT count(*) FROM turn_price tp JOIN zz_cur c ON tp.season_id = c.id AND tp.turn_no = 0)::text || ' · '
       || (SELECT count(*) FROM asset_position p JOIN zz_cur c ON p.base_date = c.base_date)::text, true;

-- 8 판정 ---------------------------------------------------------------------
-- 8.1 규칙 커버리지: 음성 사례가 통과한 규칙 코드 수 / 규칙 코드 수 (20/20 이어야 한다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '8 판정', '규칙 커버리지 (음성 사례가 통과한 규칙 코드 / 전체 규칙 코드)',
       (SELECT count(*) FROM zz_q) || '/' || (SELECT count(*) FROM zz_q),
       cov.n || '/' || (SELECT count(*) FROM zz_q),
       cov.n = (SELECT count(*) FROM zz_q)
FROM (SELECT count(*) AS n
      FROM zz_q q
      WHERE EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || q.code || ']%' AND r.ok)) cov;

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

-- 8.3b 커버리지 행 (PASS 출력에서도 N/N 이 보이게)
SELECT item, expected, actual FROM zz_check_result WHERE section = '8 판정' ORDER BY seq;

-- 8.4 판정 행
SELECT CASE WHEN count(*) > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result;

-- 8.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3).
--     판정 표식은 트랜잭션이 되돌아가도 남는다. 통과해도 2(흔적 확인 대기)까지만 올린다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-14 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 9 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
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
        RAISE EXCEPTION E'P0-14 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    -- 단언을 모두 통과했고 1–8절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_14_check;
