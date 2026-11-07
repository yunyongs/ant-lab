-- =====================================================================
-- my Fund Manager Phase 0 — 표본 적재 (P0-08): db/sample/*.csv 6개 → 역사 테이블 6개
-- 근거: docs/erd.md D12(대리 키 + 자연 키, CSV에 id 없음), D20, 9절 P0-08, docs/SPRING_JPA.md 7절
-- 대상: db/01_schema_history.sql 을 실행한 DB (PostgreSQL 17, DB timezone Asia/Seoul)
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/10_load_sample.sql
--       \copy 의 파일 경로가 psql 을 실행한 디렉터리 기준이라 반드시 저장소 루트에서 실행한다.
-- 멱등: 같은 CSV로 다시 실행하면 새 행이 없고 기존 행(id, created_at 포함)은 그대로다.
--       이미 있는 행의 값이 CSV와 다르면 조용히 두지 않고 오류로 멈춘다 (값 정정은 하지 않는다). 전부 되돌아간다.
-- 안 하는 일: 아무것도 지우지 않는다. 01 스키마 파일을 다시 실행하지 않는다 (데이터가 지워진다).
--       asset_position 의 판정 8컬럼(P0-10)과 month_block 의 나머지 컬럼(P0-09·MVP)은 넣지 않는다 (NULL, events_reviewed 기본값).
-- 입력 거부: 빈 문자열(""), 자연 키의 앞뒤·안쪽 공백, 형식 위반(소수 자릿수·날짜·코드값), 파일 안 자연 키 중복,
--       다른 CSV에 없는 참조는 DB에 닿기 전에 모두 거부한다. 고치지 않고 거부한다 (조용한 수정보다 실패가 낫다).
-- 구조: BEGIN → 이름 해석 확인 → TEMP 스테이징 6개(모든 열 text) → \copy → 입력 검사 → INSERT … SELECT(자연 키 조인)
--       → 차이 검사 → 행 수 보고 → COMMIT
-- 새 문법: \copy … HEADER MATCH(머리글 이름 확인), INSERT … WHERE NOT EXISTS … ON CONFLICT DO NOTHING,
--       WITH x AS (INSERT … RETURNING …) (삽입 행 수 세기), IS DISTINCT FROM(NULL 안전 비교).
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- [무시 CSV 확인] 시세·환율에서 만든 CSV 중 적재가 읽는 3개는 git 이 무시한다(보수적 적용; hand_check.csv 는 검증용). 하나라도 없으면 DB 에 닿기 전에 분명한 메시지로 멈춘다.
-- \! 은 셸의 test 만 부른다(파일 이름만 쓴다). SHELL_ERROR 는 마지막 셸 명령이 실패했을 때 true (psql 16 이상).
\! test -f db/sample/asset_month_return.csv -a -f db/sample/asset_position.csv -a -f db/sample/fx_month.csv
\if :SHELL_ERROR
  \echo 'db/sample/ 의 무시 CSV(asset_month_return.csv, asset_position.csv, fx_month.csv) 중 없는 것이 있다 — db/sample/README.md 의 "표본 만들기"를 먼저 하고, 저장소 루트에서 실행한다 (hand_check.csv 는 검증용)'
  DO $$ BEGIN RAISE EXCEPTION 'P0-08 적재: 무시 CSV(asset_month_return.csv, asset_position.csv, fx_month.csv)가 없다. 표본 만들기를 먼저'; END $$;
\endif

BEGIN;

-- [이름 해석 확인] 6개 이름이 public 으로 해석될 때만 계속한다 (01 스키마 파일의 블록과 같은 방식)
DO $$
DECLARE
    v_bad text;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['asset_group', 'asset', 'month_block', 'asset_position', 'asset_month_return', 'fx_month']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-08 적재: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
END $$;

-- 스테이징 (CSV 머리글과 같은 이름·순서. 모든 열 text — 형식은 아래 입력 검사가 판정한다) -----------------
CREATE TEMP TABLE stg_asset_group (code text, name text, kind text) ON COMMIT DROP;
CREATE TEMP TABLE stg_asset (market text, ticker text, name text, asset_type text, currency text,
                             group_code text, listed_on text, return_basis text, proxy_index_name text) ON COMMIT DROP;
CREATE TEMP TABLE stg_month_block (month_start text) ON COMMIT DROP;
CREATE TEMP TABLE stg_asset_position (market text, ticker text, base_date text, base_price text,
                                      base_fx_rate text, market_cap_rank text) ON COMMIT DROP;
CREATE TEMP TABLE stg_asset_month_return (market text, ticker text, month_start text, local_return text,
                                          return_kind text) ON COMMIT DROP;
CREATE TEMP TABLE stg_fx_month (month_start text, currency text, fx_change text) ON COMMIT DROP;

\copy stg_asset_group FROM 'db/sample/asset_group.csv' WITH (FORMAT csv, HEADER MATCH)
\copy stg_asset FROM 'db/sample/asset.csv' WITH (FORMAT csv, HEADER MATCH)
\copy stg_month_block FROM 'db/sample/month_block.csv' WITH (FORMAT csv, HEADER MATCH)
\copy stg_asset_position FROM 'db/sample/asset_position.csv' WITH (FORMAT csv, HEADER MATCH)
\copy stg_asset_month_return FROM 'db/sample/asset_month_return.csv' WITH (FORMAT csv, HEADER MATCH)
\copy stg_fx_month FROM 'db/sample/fx_month.csv' WITH (FORMAT csv, HEADER MATCH)

-- [입력 검사] 하나라도 있으면 전부 되돌린다 ---------------------------------------------------------
CREATE TEMP TABLE ld_err (msg text) ON COMMIT DROP;

-- 빈 문자열 (CSV 의 따옴표 친 "" 만 여기 걸린다. 따옴표 없는 빈 칸은 NULL)
INSERT INTO ld_err
SELECT 'asset_group 빈 문자열: ' || row_to_json(t)::text FROM stg_asset_group t WHERE t.code = '' OR t.name = '' OR t.kind = ''
UNION ALL SELECT 'asset 빈 문자열: ' || row_to_json(t)::text FROM stg_asset t
    WHERE t.market = '' OR t.ticker = '' OR t.name = '' OR t.asset_type = '' OR t.currency = '' OR t.group_code = ''
       OR t.listed_on = '' OR t.return_basis = '' OR t.proxy_index_name = ''
UNION ALL SELECT 'month_block 빈 문자열: ' || row_to_json(t)::text FROM stg_month_block t WHERE t.month_start = ''
UNION ALL SELECT 'asset_position 빈 문자열: ' || row_to_json(t)::text FROM stg_asset_position t
    WHERE t.market = '' OR t.ticker = '' OR t.base_date = '' OR t.base_price = '' OR t.base_fx_rate = '' OR t.market_cap_rank = ''
UNION ALL SELECT 'asset_month_return 빈 문자열: ' || row_to_json(t)::text FROM stg_asset_month_return t
    WHERE t.market = '' OR t.ticker = '' OR t.month_start = '' OR t.local_return = '' OR t.return_kind = ''
UNION ALL SELECT 'fx_month 빈 문자열: ' || row_to_json(t)::text FROM stg_fx_month t
    WHERE t.month_start = '' OR t.currency = '' OR t.fx_change = '';

-- NOT NULL 열의 빈 칸(NULL). 허용되는 빈 칸은 asset.proxy_index_name, asset_position.market_cap_rank 뿐이다
INSERT INTO ld_err
SELECT 'asset_group 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_asset_group t WHERE num_nulls(t.code, t.name, t.kind) > 0
UNION ALL SELECT 'asset 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_asset t
    WHERE num_nulls(t.market, t.ticker, t.name, t.asset_type, t.currency, t.group_code, t.listed_on, t.return_basis) > 0
UNION ALL SELECT 'month_block 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_month_block t WHERE t.month_start IS NULL
UNION ALL SELECT 'asset_position 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_asset_position t
    WHERE num_nulls(t.market, t.ticker, t.base_date, t.base_price, t.base_fx_rate) > 0
UNION ALL SELECT 'asset_month_return 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_asset_month_return t
    WHERE num_nulls(t.market, t.ticker, t.month_start, t.local_return, t.return_kind) > 0
UNION ALL SELECT 'fx_month 필수 열이 빈 칸: ' || row_to_json(t)::text FROM stg_fx_month t WHERE num_nulls(t.month_start, t.currency, t.fx_change) > 0;

-- 자연 키의 앞뒤·안쪽 공백 (고치지 않고 거부)
INSERT INTO ld_err
SELECT 'asset_group.code 공백: [' || code || ']' FROM stg_asset_group WHERE code ~ '\s'
UNION ALL SELECT 'asset 자연 키 공백: [' || market || '][' || ticker || ']' FROM stg_asset WHERE market ~ '\s' OR ticker ~ '\s'
UNION ALL SELECT 'asset.group_code 공백: [' || group_code || ']' FROM stg_asset WHERE group_code ~ '\s'
UNION ALL SELECT 'asset_position 자연 키 공백: [' || market || '][' || ticker || ']' FROM stg_asset_position WHERE market ~ '\s' OR ticker ~ '\s'
UNION ALL SELECT 'asset_month_return 자연 키 공백: [' || market || '][' || ticker || ']' FROM stg_asset_month_return WHERE market ~ '\s' OR ticker ~ '\s';

-- 형식 (소수 자릿수, 날짜, 코드값, 대문자)
INSERT INTO ld_err
SELECT 'asset_group 형식: ' || row_to_json(t)::text FROM stg_asset_group t
    WHERE t.code !~ '^[A-Z][A-Z0-9_]*$' OR t.kind NOT IN ('SECTOR', 'ASSET_CLASS')
UNION ALL SELECT 'asset 형식: ' || row_to_json(t)::text FROM stg_asset t
    WHERE t.market NOT IN ('KR', 'US') OR t.ticker <> upper(t.ticker) OR t.asset_type NOT IN ('STOCK', 'ETF')
       OR t.currency NOT IN ('KRW', 'USD') OR t.return_basis NOT IN ('PRICE', 'TOTAL')
       OR t.listed_on !~ '^\d{4}-\d{2}-\d{2}$' OR (t.market = 'KR' AND t.ticker !~ '^\d{6}$')
UNION ALL SELECT 'month_block 형식(날짜·매월 1일): ' || t.month_start FROM stg_month_block t WHERE t.month_start !~ '^\d{4}-\d{2}-01$'
UNION ALL SELECT 'asset_position 형식(소수 정확히 4자리·날짜·순위 정수): ' || row_to_json(t)::text FROM stg_asset_position t
    WHERE t.base_date !~ '^\d{4}-\d{2}-\d{2}$' OR t.base_price !~ '^\d+\.\d{4}$' OR t.base_fx_rate !~ '^\d+\.\d{4}$'
       OR t.market_cap_rank !~ '^[1-9]\d*$'
UNION ALL SELECT 'asset_month_return 형식(소수 정확히 8자리): ' || row_to_json(t)::text FROM stg_asset_month_return t
    WHERE t.month_start !~ '^\d{4}-\d{2}-01$' OR t.local_return !~ '^-?\d+\.\d{8}$' OR t.return_kind NOT IN ('ACTUAL', 'PROXY_INDEX')
UNION ALL SELECT 'fx_month 형식(소수 정확히 8자리): ' || row_to_json(t)::text FROM stg_fx_month t
    WHERE t.month_start !~ '^\d{4}-\d{2}-01$' OR t.currency <> 'USD' OR t.fx_change !~ '^-?\d+\.\d{8}$';

-- 위 형식 검사를 통과하지 못한 값은 날짜·숫자 변환에서 오류가 나므로, 여기서 먼저 멈춘다
DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-08 적재: 입력 검사 실패 (처음 % 건)\n%', v_n, v_msg;
    END IF;
END $$;

-- 자연 키 중복, 참조 누락
INSERT INTO ld_err
SELECT 'asset_group 키 중복: ' || code FROM stg_asset_group GROUP BY code HAVING count(*) > 1
UNION ALL SELECT 'asset 키 중복: ' || market || ':' || ticker FROM stg_asset GROUP BY market, ticker HAVING count(*) > 1
UNION ALL SELECT 'month_block 키 중복: ' || month_start FROM stg_month_block GROUP BY month_start HAVING count(*) > 1
UNION ALL SELECT 'asset_position 키 중복: ' || market || ':' || ticker || ':' || base_date FROM stg_asset_position GROUP BY market, ticker, base_date HAVING count(*) > 1
UNION ALL SELECT 'asset_month_return 키 중복: ' || market || ':' || ticker || ':' || month_start FROM stg_asset_month_return GROUP BY market, ticker, month_start HAVING count(*) > 1
UNION ALL SELECT 'fx_month 키 중복: ' || month_start || ':' || currency FROM stg_fx_month GROUP BY month_start, currency HAVING count(*) > 1;

INSERT INTO ld_err
SELECT 'asset.group_code 가 asset_group.csv 에 없다: ' || a.group_code FROM stg_asset a
    WHERE NOT EXISTS (SELECT 1 FROM stg_asset_group g WHERE g.code = a.group_code)
UNION ALL SELECT 'asset_position 의 종목이 asset.csv 에 없다: ' || p.market || ':' || p.ticker FROM stg_asset_position p
    WHERE NOT EXISTS (SELECT 1 FROM stg_asset a WHERE a.market = p.market AND a.ticker = p.ticker)
UNION ALL SELECT 'asset_month_return 의 종목이 asset.csv 에 없다: ' || r.market || ':' || r.ticker FROM stg_asset_month_return r
    WHERE NOT EXISTS (SELECT 1 FROM stg_asset a WHERE a.market = r.market AND a.ticker = r.ticker)
UNION ALL SELECT 'asset_month_return 의 달이 month_block.csv 에 없다: ' || r.month_start FROM stg_asset_month_return r
    WHERE NOT EXISTS (SELECT 1 FROM stg_month_block m WHERE m.month_start = r.month_start)
UNION ALL SELECT 'fx_month 의 달이 month_block.csv 에 없다: ' || f.month_start FROM stg_fx_month f
    WHERE NOT EXISTS (SELECT 1 FROM stg_month_block m WHERE m.month_start = f.month_start);

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-08 적재: 입력 검사 실패 (처음 % 건)\n%', v_n, v_msg;
    END IF;
END $$;

-- [삽입] FK 순서. WHERE NOT EXISTS 로 이미 있는 행을 먼저 거르고, ON CONFLICT DO NOTHING 은 안전망이다
--        (ON CONFLICT 만 쓰면 충돌 행도 identity 번호를 소비한다)
CREATE TEMP TABLE ld_report (seq serial, tbl text, csv_rows bigint, new_rows bigint) ON COMMIT DROP;

WITH ins AS (
    INSERT INTO asset_group (code, name, kind)
    SELECT s.code, s.name, s.kind FROM stg_asset_group s
    WHERE NOT EXISTS (SELECT 1 FROM asset_group g WHERE g.code = s.code)
    ORDER BY s.code COLLATE "C"
    ON CONFLICT DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'asset_group', (SELECT count(*) FROM stg_asset_group), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name)
    SELECT s.market, s.ticker, s.name, s.asset_type, s.currency, g.id, s.listed_on::date, s.return_basis, s.proxy_index_name
    FROM stg_asset s JOIN asset_group g ON g.code = s.group_code
    WHERE NOT EXISTS (SELECT 1 FROM asset a WHERE a.market = s.market AND a.ticker = s.ticker)
    ORDER BY s.market COLLATE "C", s.ticker COLLATE "C"
    ON CONFLICT DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'asset', (SELECT count(*) FROM stg_asset), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO month_block (month_start)
    SELECT s.month_start::date FROM stg_month_block s
    WHERE NOT EXISTS (SELECT 1 FROM month_block m WHERE m.month_start = s.month_start::date)
    ORDER BY s.month_start
    ON CONFLICT DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'month_block', (SELECT count(*) FROM stg_month_block), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank)
    SELECT a.id, s.base_date::date, s.base_price::numeric, s.base_fx_rate::numeric, s.market_cap_rank::smallint
    FROM stg_asset_position s JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
    WHERE NOT EXISTS (SELECT 1 FROM asset_position p WHERE p.asset_id = a.id AND p.base_date = s.base_date::date)
    ORDER BY s.market COLLATE "C", s.ticker COLLATE "C", s.base_date
    ON CONFLICT DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'asset_position', (SELECT count(*) FROM stg_asset_position), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
    SELECT a.id, m.id, s.local_return::numeric, s.return_kind
    FROM stg_asset_month_return s
    JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
    JOIN month_block m ON m.month_start = s.month_start::date
    WHERE NOT EXISTS (SELECT 1 FROM asset_month_return r WHERE r.asset_id = a.id AND r.month_block_id = m.id)
    ORDER BY s.market COLLATE "C", s.ticker COLLATE "C", s.month_start
    ON CONFLICT DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'asset_month_return', (SELECT count(*) FROM stg_asset_month_return), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO fx_month (month_block_id, currency, fx_change)
    SELECT m.id, s.currency, s.fx_change::numeric
    FROM stg_fx_month s JOIN month_block m ON m.month_start = s.month_start::date
    WHERE NOT EXISTS (SELECT 1 FROM fx_month f WHERE f.month_block_id = m.id AND f.currency = s.currency)
    ORDER BY s.month_start, s.currency
    ON CONFLICT DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'fx_month', (SELECT count(*) FROM stg_fx_month), (SELECT count(*) FROM ins);

-- [차이 검사] 이미 있던 행이 CSV 와 값이 다르면 조용히 옛 값을 두지 않고 멈춘다 (NULL 안전 비교)
--             P0-09·P0-10 이 채우는 컬럼(month_block 의 나머지, asset_position 의 판정 8컬럼)은 비교하지 않는다
INSERT INTO ld_err
SELECT 'asset_group 값이 CSV 와 다르다: ' || s.code
FROM stg_asset_group s JOIN asset_group g ON g.code = s.code
WHERE (g.name, g.kind) IS DISTINCT FROM (s.name, s.kind)
UNION ALL
SELECT 'asset 값이 CSV 와 다르다: ' || s.market || ':' || s.ticker
FROM stg_asset s JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
LEFT JOIN asset_group g ON g.id = a.asset_group_id
WHERE (a.name, a.asset_type, a.currency, g.code, a.listed_on, a.return_basis, a.proxy_index_name)
      IS DISTINCT FROM (s.name, s.asset_type, s.currency, s.group_code, s.listed_on::date, s.return_basis, s.proxy_index_name)
UNION ALL
SELECT 'asset_position 값이 CSV 와 다르다: ' || s.market || ':' || s.ticker || ':' || s.base_date
FROM stg_asset_position s JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
JOIN asset_position p ON p.asset_id = a.id AND p.base_date = s.base_date::date
WHERE (p.base_price, p.base_fx_rate, p.market_cap_rank)
      IS DISTINCT FROM (s.base_price::numeric, s.base_fx_rate::numeric, s.market_cap_rank::smallint)
UNION ALL
SELECT 'asset_month_return 값이 CSV 와 다르다: ' || s.market || ':' || s.ticker || ':' || s.month_start
FROM stg_asset_month_return s JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
JOIN month_block m ON m.month_start = s.month_start::date
JOIN asset_month_return r ON r.asset_id = a.id AND r.month_block_id = m.id
WHERE (r.local_return, r.return_kind) IS DISTINCT FROM (s.local_return::numeric, s.return_kind)
UNION ALL
SELECT 'fx_month 값이 CSV 와 다르다: ' || s.month_start || ':' || s.currency
FROM stg_fx_month s JOIN month_block m ON m.month_start = s.month_start::date
JOIN fx_month f ON f.month_block_id = m.id AND f.currency = s.currency
WHERE f.fx_change IS DISTINCT FROM s.fx_change::numeric;

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-08 적재: CSV 와 DB 가 어긋난다 (처음 % 건). 값 정정은 하지 않는다. 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- 행 수 보고: csv_rows = CSV 행 수, new_rows = 이번에 새로 넣은 행 수 (다시 실행하면 0)
SELECT tbl, csv_rows, new_rows FROM ld_report ORDER BY seq;

COMMIT;
