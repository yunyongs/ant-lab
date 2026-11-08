-- =====================================================================
-- my Fund Manager Phase 0 — 시즌 시작 (P0-14): 연습 회원·시즌·손 경로 10턴·현금 계좌·개설 입금·0턴 가격
-- 근거: docs/erd.md 6.3(member·season·season_path·turn_price)·7.3(cash_account·cash_ledger)·S1·S2·S4·H1·H2·D14·D16·D23·D30·9절 P0-14,
--       docs/PLAN.md §4.1-1·§4.2·§4.6·§4.10·§4.11·§5.2·§10
-- 대상: db/01·02·03 스키마, db/10 표본 적재, db/12 판정 계산을 실행한 DB (PostgreSQL 17, DB timezone Asia/Seoul). antlab 에서 실행한다.
--       antlab_test 에는 넣지 않는다 (01–03 검증이 빈 테이블을 요구한다). 11·13 은 필요 없다.
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/20_season_start.sql
--       -v ON_ERROR_STOP=1 을 빼면 첫 오류 뒤 남은 문장이 25P02 로 실패하고 마지막 COMMIT 은 ROLLBACK 이 되어 행은 남지 않지만 종료 코드가 0일 수 있다.
-- 멱등: 연습 회원(이메일)이 이미 있으면 아무것도 넣지 않는다. 그 시즌의 바뀌지 않는 부분(설정·경로·개설 입금·0턴 가격)이 이 파일과 다르면
--       조용히 두지 않고 오류로 멈춘다 (값 정정은 하지 않는다). 전부 되돌아간다. 바뀌는 부분(status·current_turn_no·current_*·잔액·뒤 원장·1턴 이후 가격)은
--       비교하지 않으므로 팀 구성(P0-15) 이후에 다시 실행해도 통과한다. 설정을 바꾸려면 antlab 을 (b′)로 다시 만들고 10 → 11 → 12 → 13 → 20 순서로 다시 돌린다.
-- 쓰는 곳: member · season · season_path · cash_account · cash_ledger · turn_price 의 INSERT 만. 아무것도 지우거나 고치지 않는다.
--       판정(asset_position 의 8컬럼)은 읽기만 하고 시즌 테이블에 복사하지 않는다.
-- 판정 전제(erd S2·H2): 기준일 풀에 판정이 비어 있는 종목이 하나라도 있으면 12 계산을 먼저 돌리라는 메시지로 멈춘다 (아무것도 넣지 않음).
-- 경로: 아래 ss_path 의 10행은 손으로 적은 리터럴이다 (PLAN §10 "SQL 단계는 손으로 만든 경로 1개"). 이 파일은 경로의 실제 달을 담고 있다 —
--       MVP 시즌의 경로는 저장소에 들어가지 않는다. 경로를 바꾸면 engine_version 을 올린다 (같은 시드 · 같은 엔진 버전 = 같은 경로).
-- 보고: 건수와 판정만 낸다. 경로의 달·블록 번호·시즌 번호·가격은 출력하지 않는다.
-- 구조: BEGIN → 설정 한 곳 → 이름 해석·전제 단언(H2 포함) → 상태 판정 → INSERT 6개 → 차이 검사 → 사후 단언(S1·S4) → 보고 → COMMIT
-- 새 문법: 임시 표에 설정을 한 번만 적고 여러 INSERT … SELECT 가 읽기, 데이터 변경 CTE(WITH … INSERT … RETURNING)로 건수 세기,
--       CREATE TEMP VIEW 로 이 시즌 찾기, ROUND(numeric, 0) 한 번(PLAN §5.2), 트랜잭션 하나로 6개 테이블을 묶어 한 곳이 실패하면 전부 되돌리기.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

BEGIN;

-- [설정 한 곳] 시즌 설정은 여기에만 적는다. 검증 파일(20_season_start_check.sql)은 문서에서 기대값을 따로 다시 적어 대조한다.
CREATE TEMP TABLE ss_setting (
    email              text          NOT NULL,   -- 연습 회원 (RFC 2606 예약 TLD — 공개 저장소에 실제 주소를 쓰지 않는다)
    member_name        text          NOT NULL,
    password           text          NOT NULL,   -- '!' = 로그인 불가 표식 (평문도 해시도 아니라 어떤 입력과도 맞지 않는다). MVP 인증 전까지 쓰지 않는다
    status             text          NOT NULL,
    current_turn_no    smallint      NOT NULL,   -- 0 = 시즌 시작·팀 구성 (D23)
    path_length        smallint      NOT NULL,
    seed               bigint        NOT NULL,   -- Phase 0 은 경로를 만들지 않는다 (손 경로). P0-18 피드 난수의 기준값
    engine_version     text          NOT NULL,
    data_snapshot_date date          NOT NULL,   -- 표본을 만든 날 (db/sample/README.md 상태 줄). 적재한 날(created_at)에서 유도하지 않는다
    base_date          date          NOT NULL,   -- 이 시즌이 쓰는 asset_position 스냅샷의 기준일
    initial_cash       numeric(19,4) NOT NULL,
    fee_rate_kr        numeric(12,8) NOT NULL,
    fee_rate_us        numeric(12,8) NOT NULL,
    fx_spread_rate     numeric(12,8) NOT NULL,
    risk_limit         numeric(12,8) NOT NULL,
    goalkeeper         smallint      NOT NULL,   -- 시작 포메이션. 현재 포메이션도 같은 값으로 시작한다 (시작 = 현재)
    defender           smallint      NOT NULL,
    midfielder         smallint      NOT NULL,
    forward            smallint      NOT NULL
) ON COMMIT DROP;
INSERT INTO ss_setting VALUES (
    'phase0@example.invalid', '연습 감독', '!',
    'IN_PROGRESS', 0, 10,
    14, 'p0-sql', DATE '2026-11-07', DATE '2026-01-02',
    30000000, 0.00015, 0.0025, 0.0040, -0.20,
    10, 30, 30, 30   -- 골키퍼 · 수비 · 미드필더 · 공격 (PLAN §4.10 "균형", 합 100)
);

-- [손 경로] 턴 번호와 실제 달. 블록 안은 실제 순서대로 이어지고, 블록 사이는 이어지지 않는다. 한 달 = 한 턴.
--           고른 규칙: 턴 k 의 달 = ((k − 1) % 12) + 1 월 (달력 정렬, S4) · 2007-01-01 이상 기준일 해 1월 1일 미만 (S1) ·
--           블록 3개(길이 4·3·3, 연대순 아님) · 관심 지수가 있는 달만. 수익률·가격을 보지 않고 골랐다.
CREATE TEMP TABLE ss_path (
    turn_no     smallint NOT NULL,
    month_start date     NOT NULL
) ON COMMIT DROP;
INSERT INTO ss_path VALUES
    (1,  DATE '2011-01-01'), (2,  DATE '2011-02-01'), (3,  DATE '2011-03-01'), (4,  DATE '2011-04-01'),
    (5,  DATE '2024-05-01'), (6,  DATE '2024-06-01'), (7,  DATE '2024-07-01'),
    (8,  DATE '2009-08-01'), (9,  DATE '2009-09-01'), (10, DATE '2009-10-01');

-- [이름 해석·전제 단언] 9개 이름이 public 으로 해석되고, 10 적재와 12 판정이 끝나 있고, 경로의 달이 모두 있을 때만 계속한다
DO $$
DECLARE
    v_bad  text;
    v_n    bigint;
    v_base date := (SELECT base_date FROM ss_setting);
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['member', 'season', 'season_path', 'cash_account', 'cash_ledger', 'turn_price', 'asset', 'asset_position', 'month_block']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-14 시즌 시작: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01·02·03 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;

    -- 기준일 풀 (erd S2): 그 기준일의 asset_position 행이 있어야 한다
    SELECT count(*) INTO v_n FROM asset_position WHERE base_date = v_base;
    IF v_n = 0 THEN
        RAISE EXCEPTION 'P0-14 시즌 시작: 기준일 풀(asset_position)이 비어 있다. db/10_load_sample.sql 을 먼저 실행한다 (10 적재를 먼저)';
    END IF;

    -- H1: 같은 기준일의 USD 종목은 환율이 하나, KRW 종목은 1 — 0턴 원화 환산이 이 전제에 기댄다
    SELECT count(*) INTO v_n
    FROM asset_position p JOIN asset a ON a.id = p.asset_id
    WHERE p.base_date = v_base AND a.currency = 'KRW' AND p.base_fx_rate <> 1;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-14 시즌 시작: 원화 종목의 base_fx_rate 가 1 이 아닌 행이 % 건 있다 (erd H1)', v_n;
    END IF;
    SELECT count(DISTINCT p.base_fx_rate) INTO v_n
    FROM asset_position p JOIN asset a ON a.id = p.asset_id
    WHERE p.base_date = v_base AND a.currency = 'USD';
    IF v_n > 1 THEN
        RAISE EXCEPTION 'P0-14 시즌 시작: 같은 기준일의 달러 종목 환율이 % 가지다 (erd H1: 하나여야 한다)', v_n;
    END IF;

    -- H2·S2: 기준일 풀의 모든 종목이 판정되어 있어야 한다. 시즌 시작은 판정 값을 어디에도 복사하지 않고 asset_position 이 정본이다.
    --        판정 8컬럼은 전부-또는-없음 CHECK 가 묶으므로 system_position 만 보면 된다.
    SELECT count(*) INTO v_n FROM asset_position WHERE base_date = v_base AND system_position IS NULL;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-14 시즌 시작: 기준일 풀의 판정이 비어 있다 (% 건). db/12_calc_position.sql 을 먼저 실행한다 (12 계산을 먼저). 아무것도 넣지 않았다', v_n;
    END IF;
    SELECT count(*) INTO v_n
    FROM asset_position
    WHERE base_date = v_base AND window_end IS DISTINCT FROM (date_trunc('month', v_base::timestamp) - INTERVAL '1 month')::date;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-14 시즌 시작: 판정 창의 끝이 기준일 전달 1일이 아닌 행이 % 건 있다 (erd S2). 12 계산을 다시 확인한다', v_n;
    END IF;

    -- 경로의 달: 각각 month_block 에 정확히 1행
    SELECT count(*) INTO v_n
    FROM ss_path p
    WHERE (SELECT count(*) FROM month_block mb WHERE mb.month_start = p.month_start) <> 1;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-14 시즌 시작: 경로의 달 중 month_block 에 정확히 1행이 아닌 것이 % 개 있다. db/10_load_sample.sql 을 확인한다', v_n;
    END IF;
END $$;

-- 이 시즌 찾기: 시즌 번호(identity)를 파일에 적지 않고 연습 회원의 이메일로 찾는다 (번호는 롤백되지 않아 DB 마다 다를 수 있다)
CREATE TEMP VIEW ss_cur AS
SELECT se.*
FROM season se
JOIN member m      ON m.id = se.member_id
JOIN ss_setting st ON lower(m.email) = lower(st.email);

-- 상태 판정: 연습 회원이 이미 있으면 "이미 있음" — 아래 INSERT 는 모두 건너뛴다 (표마다 따로 거르지 않는다.
--           망가진 시즌의 빠진 조각을 조용히 메우지 않고 차이 검사가 멈추게 하기 위해)
CREATE TEMP TABLE ss_state ON COMMIT DROP AS
SELECT EXISTS (SELECT 1 FROM member m JOIN ss_setting st ON lower(m.email) = lower(st.email)) AS existed;

CREATE TEMP TABLE ss_err (msg text) ON COMMIT DROP;
CREATE TEMP TABLE ss_report (item text PRIMARY KEY, n bigint NOT NULL) ON COMMIT DROP;

-- 1 회원
WITH ins AS (
    INSERT INTO member (email, name, password)
    SELECT st.email, st.member_name, st.password
    FROM ss_setting st
    WHERE NOT (SELECT existed FROM ss_state)
    RETURNING 1)
INSERT INTO ss_report SELECT 'member', count(*) FROM ins;

-- 2 시즌: D30 8컬럼(seed · engine_version · data_snapshot_date · base_date · initial_cash · 요율 3)은 DB 기본값이 없고 NOT NULL 이라
--         하나라도 빼면 23502 로 실패한다. 포메이션 CHECK 6개와 위험 선언 CHECK 가 여기서 걸린다.
WITH ins AS (
    INSERT INTO season (member_id, status, path_length, current_turn_no,
                        seed, engine_version, data_snapshot_date, base_date,
                        initial_cash, fee_rate_kr, fee_rate_us, fx_spread_rate, risk_limit,
                        start_goalkeeper, start_defender, start_midfielder, start_forward,
                        current_goalkeeper, current_defender, current_midfielder, current_forward)
    SELECT m.id, st.status, st.path_length, st.current_turn_no,
           st.seed, st.engine_version, st.data_snapshot_date, st.base_date,
           st.initial_cash, st.fee_rate_kr, st.fee_rate_us, st.fx_spread_rate, st.risk_limit,
           st.goalkeeper, st.defender, st.midfielder, st.forward,
           st.goalkeeper, st.defender, st.midfielder, st.forward
    FROM ss_setting st
    JOIN member m ON lower(m.email) = lower(st.email)
    WHERE NOT (SELECT existed FROM ss_state)
    RETURNING 1)
INSERT INTO ss_report SELECT 'season', count(*) FROM ins;

-- 3 시즌 경로: 10턴을 시즌 시작에 전부 저장한다 (month_block_id 는 복기 전용 — 출력하지 않는다)
WITH ins AS (
    INSERT INTO season_path (season_id, turn_no, month_block_id)
    SELECT c.id, p.turn_no, mb.id
    FROM ss_cur c
    CROSS JOIN ss_path p
    JOIN month_block mb ON mb.month_start = p.month_start
    WHERE NOT (SELECT existed FROM ss_state)
    ORDER BY p.turn_no
    RETURNING 1)
INSERT INTO ss_report SELECT 'season_path', count(*) FROM ins;

-- 4 현금 계좌: 잔액 = 초기 자금, 예약금 0. 개설 입금과 함께 잔액 = Σ 원장 (대사 규칙 2)이 처음부터 성립한다.
--   잠금(season → cash_account FOR UPDATE)은 직전 balance_after 를 읽는 트랜잭션의 규칙이다. 개설 입금은 직전 행이 없고
--   방금 만든 시즌이 커밋 전이라 다른 세션에 보이지 않으므로 필요 없다. P0-15 부터는 지킨다.
WITH ins AS (
    INSERT INTO cash_account (season_id, balance, reserved)
    SELECT c.id, c.initial_cash, 0
    FROM ss_cur c
    WHERE NOT (SELECT existed FROM ss_state)
    RETURNING 1)
INSERT INTO ss_report SELECT 'cash_account', count(*) FROM ins;

-- 5 개설 입금 원장: DEPOSIT · 금액 = 거래 후 잔액 = 초기 자금 · 주문 없음
WITH ins AS (
    INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
    SELECT c.id, 'DEPOSIT', c.initial_cash, c.initial_cash, NULL
    FROM ss_cur c
    WHERE NOT (SELECT existed FROM ss_state)
    RETURNING 1)
INSERT INTO ss_report SELECT 'cash_ledger', count(*) FROM ins;

-- 6 0턴 가격: 기준일 풀 전체. price = ROUND(base_price × base_fx_rate, 0) — 두 NUMERIC(19,4) 의 곱은 소수 8자리 정확값이고
--   (중간 반올림 없음) ROUND 는 0에서 먼 쪽 반올림이라 양수에서 Java HALF_UP 과 같다. 반올림은 이 한 곳에서 한 번 (PLAN §5.2).
--   원화 종목은 base_fx_rate = 1 이라 원화 그대로. 1턴 이후 가격은 미리 계산하지 않는다 (사후 정보 — 턴 진행 P0-17 이 만든다).
WITH ins AS (
    INSERT INTO turn_price (season_id, turn_no, asset_id, price)
    SELECT c.id, 0, ap.asset_id, ROUND(ap.base_price * ap.base_fx_rate, 0)
    FROM ss_cur c
    JOIN asset_position ap ON ap.base_date = c.base_date
    WHERE NOT (SELECT existed FROM ss_state)
    ORDER BY ap.asset_id
    RETURNING 1)
INSERT INTO ss_report SELECT 'turn_price', count(*) FROM ins;

-- [차이 검사] 방금 만들었든 이미 있었든 같은 쿼리로 대조한다. 하나라도 다르면 멈추고 전부 되돌린다.
--   비교하지 않는 것: status · current_turn_no · current_* · version · updated_at (턴 진행·전술 변경이 정당하게 바꾼다),
--   password (인증이 바꾼다), 개설 입금 뒤의 원장 행·잔액·예약금 (팀 구성이 정당하게 더한다), 1턴 이후 turn_price.
-- 회원 · 시즌 수
INSERT INTO ss_err
SELECT '연습 회원이 없다 (이메일 조회 0건)'
WHERE NOT EXISTS (SELECT 1 FROM member m JOIN ss_setting st ON lower(m.email) = lower(st.email))
UNION ALL
SELECT '연습 회원의 이름이 설정과 다르다'
FROM member m JOIN ss_setting st ON lower(m.email) = lower(st.email)
WHERE m.name IS DISTINCT FROM st.member_name
UNION ALL
SELECT '연습 시즌이 정확히 1개가 아니다: ' || (SELECT count(*) FROM ss_cur) || ' 개'
WHERE (SELECT count(*) FROM ss_cur) <> 1;

-- 시즌 설정: 바뀌지 않는 컬럼마다 하나씩 (어느 컬럼이 다른지 이름으로 알린다. 값은 설정값이라 낼 수 있지만 컬럼 이름만 낸다)
INSERT INTO ss_err
SELECT 'season.' || d.col || ' 이(가) 설정과 다르다'
FROM ss_cur c
CROSS JOIN ss_setting st
CROSS JOIN LATERAL (VALUES
    ('path_length',        c.path_length        IS DISTINCT FROM st.path_length),
    ('seed',               c.seed               IS DISTINCT FROM st.seed),
    ('engine_version',     c.engine_version     IS DISTINCT FROM st.engine_version),
    ('data_snapshot_date', c.data_snapshot_date IS DISTINCT FROM st.data_snapshot_date),
    ('base_date',          c.base_date          IS DISTINCT FROM st.base_date),
    ('initial_cash',       c.initial_cash       IS DISTINCT FROM st.initial_cash),
    ('fee_rate_kr',        c.fee_rate_kr        IS DISTINCT FROM st.fee_rate_kr),
    ('fee_rate_us',        c.fee_rate_us        IS DISTINCT FROM st.fee_rate_us),
    ('fx_spread_rate',     c.fx_spread_rate     IS DISTINCT FROM st.fx_spread_rate),
    ('risk_limit',         c.risk_limit         IS DISTINCT FROM st.risk_limit),
    ('start_goalkeeper',   c.start_goalkeeper   IS DISTINCT FROM st.goalkeeper),
    ('start_defender',     c.start_defender     IS DISTINCT FROM st.defender),
    ('start_midfielder',   c.start_midfielder   IS DISTINCT FROM st.midfielder),
    ('start_forward',      c.start_forward      IS DISTINCT FROM st.forward)
) AS d(col, differs)
WHERE d.differs;

-- 경로: (turn_no, month_start) 집합이 ss_path 와 양방향으로 같다
INSERT INTO ss_err
SELECT 'season_path 가 경로 리터럴과 다르다: 파일에만 있는 턴 ' || count(*) FILTER (WHERE side = 'file') || ' 개, DB 에만 있는 턴 ' || count(*) FILTER (WHERE side = 'db') || ' 개'
FROM (
    (SELECT 'file' AS side, p.turn_no, p.month_start FROM ss_path p
     EXCEPT
     SELECT 'file', sp.turn_no, mb.month_start FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id)
    UNION ALL
    (SELECT 'db', sp.turn_no, mb.month_start FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id
     EXCEPT
     SELECT 'db', p.turn_no, p.month_start FROM ss_path p)
) diff
HAVING count(*) > 0;

-- 계좌 · 개설 입금: 계좌 행이 있고, 가장 먼저 쓴 원장 행(최소 id)이 DEPOSIT · 금액 = 거래 후 잔액 = 초기 자금 · 주문 없음
INSERT INTO ss_err
SELECT 'cash_account 행이 없다'
FROM ss_cur c WHERE NOT EXISTS (SELECT 1 FROM cash_account a WHERE a.season_id = c.id)
UNION ALL
SELECT 'cash_ledger 에 개설 입금(첫 행)이 없다 또는 DEPOSIT · 초기 자금과 다르다'
FROM ss_cur c
WHERE NOT EXISTS (
    SELECT 1 FROM cash_ledger l
    WHERE l.season_id = c.id
      AND l.id = (SELECT min(l2.id) FROM cash_ledger l2 WHERE l2.season_id = c.id)
      AND l.type = 'DEPOSIT' AND l.amount = c.initial_cash AND l.balance_after = c.initial_cash AND l.ref_order_id IS NULL);

-- 0턴 가격: 기준일 풀에서 지금 다시 계산한 (종목, 가격) 집합과 양방향으로 같다
INSERT INTO ss_err
SELECT '0턴 가격이 기준일 풀의 계산값과 다르다: 계산에만 있는 행 ' || count(*) FILTER (WHERE side = 'calc') || ' 개, DB 에만 있는 행 ' || count(*) FILTER (WHERE side = 'db') || ' 개'
FROM (
    (SELECT 'calc' AS side, ap.asset_id, ROUND(ap.base_price * ap.base_fx_rate, 0) AS price
     FROM ss_cur c JOIN asset_position ap ON ap.base_date = c.base_date
     EXCEPT
     SELECT 'calc', tp.asset_id, tp.price FROM ss_cur c JOIN turn_price tp ON tp.season_id = c.id AND tp.turn_no = 0)
    UNION ALL
    (SELECT 'db', tp.asset_id, tp.price FROM ss_cur c JOIN turn_price tp ON tp.season_id = c.id AND tp.turn_no = 0
     EXCEPT
     SELECT 'db', ap.asset_id, ROUND(ap.base_price * ap.base_fx_rate, 0)
     FROM ss_cur c JOIN asset_position ap ON ap.base_date = c.base_date)
) diff
HAVING count(*) > 0;

DO $$
DECLARE
    v_n   bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ss_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-14 시즌 시작: 이미 있는 시즌이 이 파일과 어긋난다 (처음 % 건). 값 정정은 하지 않는다. 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- [사후 단언] 이번 실행이 끝난 DB 기준 (erd S1·S4 와 시작 직후에도 언제나 참인 것)
--   S1: 행 수 = path_length, turn_no 1..path_length 빈틈 없음, 모든 달이 2007-01-01 이상 · 기준일 해 1월 1일 미만
--   S4: 턴 k 의 달 = ((k − 1) % 12) + 1 월
--   블록 수 ≥ 2 (역사 한 구간을 그대로 재생하지 않는다), 0턴 가격은 있고 current_turn_no 를 넘는 가격은 없다 (S3)
INSERT INTO ss_err
SELECT 'S1: 경로 행 수 ' || count(*) || ' (path_length ' || max(c.path_length) || ')'
FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id
GROUP BY c.id HAVING count(*) <> max(c.path_length)
UNION ALL
SELECT 'S1: turn_no 가 1..path_length 의 빈틈 없는 열이 아니다'
FROM ss_cur c
WHERE (SELECT count(DISTINCT sp.turn_no) FROM season_path sp WHERE sp.season_id = c.id AND sp.turn_no BETWEEN 1 AND c.path_length) <> c.path_length
UNION ALL
SELECT 'S1: 재생 범위(2007-01-01 이상, 기준일 해 1월 1일 미만)를 벗어난 턴 ' || count(*) || ' 개'
FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id
WHERE mb.month_start < DATE '2007-01-01' OR mb.month_start >= date_trunc('year', c.base_date::timestamp)::date
GROUP BY c.id HAVING count(*) > 0
UNION ALL
SELECT 'S4: 달력 정렬을 어긴 턴 ' || count(*) || ' 개'
FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id
WHERE EXTRACT(MONTH FROM mb.month_start)::int <> ((sp.turn_no - 1) % 12) + 1
GROUP BY c.id HAVING count(*) > 0
UNION ALL
SELECT '블록 수가 ' || count(*) FILTER (WHERE brk) + 1 || ' 개다 (2 개 이상이어야 한다)'
FROM (
    SELECT sp.turn_no,
           (mb.month_start <> (LAG(mb.month_start) OVER (ORDER BY sp.turn_no) + INTERVAL '1 month')::date) AS brk
    FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id
) t
HAVING count(*) FILTER (WHERE brk) + 1 < 2
UNION ALL
SELECT 'S3: 0턴 가격이 없거나 current_turn_no 를 넘는 가격이 있다'
FROM ss_cur c
WHERE NOT EXISTS (SELECT 1 FROM turn_price tp WHERE tp.season_id = c.id AND tp.turn_no = 0)
   OR EXISTS (SELECT 1 FROM turn_price tp WHERE tp.season_id = c.id AND tp.turn_no > c.current_turn_no);

DO $$
DECLARE
    v_n   bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ss_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-14 시즌 시작: 시작 뒤 단언 실패 (처음 % 건). 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- 보고 1: 상태와 이번에 넣은 행 수 (이미 있던 시즌이면 모두 0)
SELECT CASE WHEN (SELECT existed FROM ss_state) THEN '이미 같은 시즌 (새 행 없음)' ELSE '새로 만듦' END AS state,
       (SELECT n FROM ss_report WHERE item = 'member')       AS member_inserted,
       (SELECT n FROM ss_report WHERE item = 'season')       AS season_inserted,
       (SELECT n FROM ss_report WHERE item = 'season_path')  AS path_inserted,
       (SELECT n FROM ss_report WHERE item = 'cash_account') AS account_inserted,
       (SELECT n FROM ss_report WHERE item = 'cash_ledger')  AS ledger_inserted,
       (SELECT n FROM ss_report WHERE item = 'turn_price')   AS turn_price_inserted;

-- 보고 2: 풀 크기 · 경로 턴 수 · 블록 수 · 달력 위반 수 (달·가격은 내지 않는다)
SELECT (SELECT count(*) FROM asset_position ap JOIN ss_cur c ON ap.base_date = c.base_date)                    AS pool_size,
       (SELECT count(*) FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id)                              AS path_turns,
       (SELECT count(*) FILTER (WHERE brk) + 1
        FROM (SELECT mb.month_start <> (LAG(mb.month_start) OVER (ORDER BY sp.turn_no) + INTERVAL '1 month')::date AS brk
              FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id) t) AS blocks,
       (SELECT count(*) FROM ss_cur c JOIN season_path sp ON sp.season_id = c.id JOIN month_block mb ON mb.id = sp.month_block_id
        WHERE EXTRACT(MONTH FROM mb.month_start)::int <> ((sp.turn_no - 1) % 12) + 1)                          AS calendar_violations;

COMMIT;
