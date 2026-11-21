-- =====================================================================
-- my Fund Manager MVP-06 (06-B1) — full-history load: the build outputs of db/full/build_full.sql -> six history tables
-- Reads from one directory (psql variable indir, default db/full):
--   tracked pool files   asset_group.csv, asset.csv
--   git-ignored outputs  month_block.csv, fx_month.csv, asset_position.csv, asset_month_return.csv
--   lineage.csv is not loaded: it is not a table (plan D-7). db/30_load_full_check.sql reads it into a temp table.
-- Target: a database migrated by Flyway (PostgreSQL 17, timezone Asia/Seoul).
-- Run from the repository root (\copy paths are relative to the psql working directory):
--   psql -X -v ON_ERROR_STOP=1 -d <db> -f db/30_load_full.sql
--   psql -X -v ON_ERROR_STOP=1 -v indir=/some/dir -d <db> -f db/30_load_full.sql
-- Re-runnable: a second run with the same files inserts 0 rows. Existing rows keep their id and created_at.
-- Refusal rule (nothing is deleted or updated, the whole run rolls back):
--   1. a row that is already in the database and has the same natural key as a file row but a different value
--      (this is what a database that holds the Phase 0 sample looks like: its proxy names and its US 2008-01..2012-04 returns differ);
--   2. an asset or a month_block row in the database that no file names (a foreign pool or calendar);
--   3. the database already holds rows in any of the six tables and this run would insert any row (only an empty database or a
--      complete earlier load is accepted; a top-up of a partial history is refused, attention is insert-only, D-14).
--   Load the full history into a freshly migrated database (plan D-14, Q8).
-- Not loaded: the position judgement columns of asset_position (db/12), the regime and event columns of month_block.
-- Identity numbers: INSERT .. ON CONFLICT DO NOTHING uses up one identity number per skipped row, so a re-run leaves gaps in ids.
--   Nothing reads ids as a count; every join is by natural key.
-- Flow: BEGIN -> name check -> TEMP staging (all text) -> \copy -> input checks -> diff check against existing rows
--       -> INSERT .. ON CONFLICT (natural key) DO NOTHING -> report -> COMMIT
-- =====================================================================

-- Refuse to run without ON_ERROR_STOP: a client-side \copy error (missing file) would not abort the transaction.
\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'load_full: ON_ERROR_STOP is off. Run: psql -X -v ON_ERROR_STOP=1 -d <db> -f db/30_load_full.sql'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'load_full: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
\else
  \echo 'load_full: cannot run without -v ON_ERROR_STOP=1'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'load_full: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif

\if :{?indir}
\else
  \set indir db/full
\endif

BEGIN;

-- The six names must resolve to public tables.
DO $$
DECLARE
    v_bad text;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['asset_group', 'asset', 'month_block', 'asset_position', 'asset_month_return', 'fx_month']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'load_full: names do not resolve to public tables (current_schema=%, bad: %). Run the Flyway migrations first, check search_path',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
END $$;

CREATE TEMP TABLE stg_asset_group (code text, name text, kind text) ON COMMIT DROP;
CREATE TEMP TABLE stg_asset (market text, ticker text, name text, asset_type text, currency text,
                             group_code text, listed_on text, return_basis text, proxy_index_name text) ON COMMIT DROP;
CREATE TEMP TABLE stg_month_block (month_start text) ON COMMIT DROP;
CREATE TEMP TABLE stg_asset_position (market text, ticker text, base_date text, base_price text,
                                      base_fx_rate text, market_cap_rank text) ON COMMIT DROP;
CREATE TEMP TABLE stg_asset_month_return (market text, ticker text, month_start text, local_return text,
                                          return_kind text) ON COMMIT DROP;
CREATE TEMP TABLE stg_fx_month (month_start text, currency text, fx_change text) ON COMMIT DROP;

-- \copy does not expand variables, so the command text is built with \set and run as :cmd.
\set f :indir '/asset_group.csv'
\set cmd '\\copy stg_asset_group FROM ' :'f' ' WITH (FORMAT csv, HEADER MATCH)'
:cmd
\set f :indir '/asset.csv'
\set cmd '\\copy stg_asset FROM ' :'f' ' WITH (FORMAT csv, HEADER MATCH)'
:cmd
\set f :indir '/month_block.csv'
\set cmd '\\copy stg_month_block FROM ' :'f' ' WITH (FORMAT csv, HEADER MATCH)'
:cmd
\set f :indir '/asset_position.csv'
\set cmd '\\copy stg_asset_position FROM ' :'f' ' WITH (FORMAT csv, HEADER MATCH)'
:cmd
\set f :indir '/asset_month_return.csv'
\set cmd '\\copy stg_asset_month_return FROM ' :'f' ' WITH (FORMAT csv, HEADER MATCH)'
:cmd
\set f :indir '/fx_month.csv'
\set cmd '\\copy stg_fx_month FROM ' :'f' ' WITH (FORMAT csv, HEADER MATCH)'
:cmd

-- Input checks: any hit stops the load before the database is touched. Nothing is repaired.
CREATE TEMP TABLE ld_err (msg text) ON COMMIT DROP;

-- A file with a header and no rows would load nothing and look like a success.
INSERT INTO ld_err
SELECT f || '.csv has no data rows' FROM (VALUES ('asset_group', (SELECT count(*) FROM stg_asset_group)), ('asset', (SELECT count(*) FROM stg_asset)),
    ('month_block', (SELECT count(*) FROM stg_month_block)), ('asset_position', (SELECT count(*) FROM stg_asset_position)),
    ('asset_month_return', (SELECT count(*) FROM stg_asset_month_return)), ('fx_month', (SELECT count(*) FROM stg_fx_month))) v(f, n) WHERE n = 0;

-- Quoted empty strings ("" in the CSV). An unquoted empty cell is NULL.
INSERT INTO ld_err
SELECT 'asset_group empty string: ' || row_to_json(t)::text FROM stg_asset_group t WHERE t.code = '' OR t.name = '' OR t.kind = ''
UNION ALL SELECT 'asset empty string: ' || row_to_json(t)::text FROM stg_asset t
    WHERE t.market = '' OR t.ticker = '' OR t.name = '' OR t.asset_type = '' OR t.currency = '' OR t.group_code = ''
       OR t.listed_on = '' OR t.return_basis = '' OR t.proxy_index_name = ''
UNION ALL SELECT 'month_block empty string: ' || row_to_json(t)::text FROM stg_month_block t WHERE t.month_start = ''
UNION ALL SELECT 'asset_position empty string: ' || row_to_json(t)::text FROM stg_asset_position t
    WHERE t.market = '' OR t.ticker = '' OR t.base_date = '' OR t.base_price = '' OR t.base_fx_rate = '' OR t.market_cap_rank = ''
UNION ALL SELECT 'asset_month_return empty string: ' || row_to_json(t)::text FROM stg_asset_month_return t
    WHERE t.market = '' OR t.ticker = '' OR t.month_start = '' OR t.local_return = '' OR t.return_kind = ''
UNION ALL SELECT 'fx_month empty string: ' || row_to_json(t)::text FROM stg_fx_month t
    WHERE t.month_start = '' OR t.currency = '' OR t.fx_change = '';

-- Blank cells in NOT NULL columns. Allowed blanks: asset.proxy_index_name, asset_position.market_cap_rank.
INSERT INTO ld_err
SELECT 'asset_group blank required cell: ' || row_to_json(t)::text FROM stg_asset_group t WHERE num_nulls(t.code, t.name, t.kind) > 0
UNION ALL SELECT 'asset blank required cell: ' || row_to_json(t)::text FROM stg_asset t
    WHERE num_nulls(t.market, t.ticker, t.name, t.asset_type, t.currency, t.group_code, t.listed_on, t.return_basis) > 0
UNION ALL SELECT 'month_block blank required cell: ' || row_to_json(t)::text FROM stg_month_block t WHERE t.month_start IS NULL
UNION ALL SELECT 'asset_position blank required cell: ' || row_to_json(t)::text FROM stg_asset_position t
    WHERE num_nulls(t.market, t.ticker, t.base_date, t.base_price, t.base_fx_rate) > 0
UNION ALL SELECT 'asset_month_return blank required cell: ' || row_to_json(t)::text FROM stg_asset_month_return t
    WHERE num_nulls(t.market, t.ticker, t.month_start, t.local_return, t.return_kind) > 0
UNION ALL SELECT 'fx_month blank required cell: ' || row_to_json(t)::text FROM stg_fx_month t WHERE num_nulls(t.month_start, t.currency, t.fx_change) > 0;

-- Whitespace inside natural keys (rejected, not trimmed).
INSERT INTO ld_err
SELECT 'asset_group.code whitespace: [' || code || ']' FROM stg_asset_group WHERE code ~ '\s'
UNION ALL SELECT 'asset key whitespace: [' || market || '][' || ticker || ']' FROM stg_asset WHERE market ~ '\s' OR ticker ~ '\s'
UNION ALL SELECT 'asset.group_code whitespace: [' || group_code || ']' FROM stg_asset WHERE group_code ~ '\s'
UNION ALL SELECT 'asset_position key whitespace: [' || market || '][' || ticker || ']' FROM stg_asset_position WHERE market ~ '\s' OR ticker ~ '\s'
UNION ALL SELECT 'asset_month_return key whitespace: [' || market || '][' || ticker || ']' FROM stg_asset_month_return WHERE market ~ '\s' OR ticker ~ '\s';

-- Formats: decimals, dates, code values, upper case.
INSERT INTO ld_err
SELECT 'asset_group format: ' || row_to_json(t)::text FROM stg_asset_group t
    WHERE t.code !~ '^[A-Z][A-Z0-9_]*$' OR t.kind NOT IN ('SECTOR', 'ASSET_CLASS')
UNION ALL SELECT 'asset format: ' || row_to_json(t)::text FROM stg_asset t
    WHERE t.market NOT IN ('KR', 'US') OR t.ticker <> upper(t.ticker) OR t.asset_type NOT IN ('STOCK', 'ETF')
       OR t.currency NOT IN ('KRW', 'USD') OR t.return_basis NOT IN ('PRICE', 'TOTAL')
       OR t.listed_on !~ '^\d{4}-\d{2}-\d{2}$' OR (t.market = 'KR' AND t.ticker !~ '^[0-9A-Z]{6}$')
UNION ALL SELECT 'month_block format (first of month): ' || t.month_start FROM stg_month_block t WHERE t.month_start !~ '^\d{4}-\d{2}-01$'
UNION ALL SELECT 'asset_position format (4 decimals, date, integer rank): ' || row_to_json(t)::text FROM stg_asset_position t
    WHERE t.base_date !~ '^\d{4}-\d{2}-\d{2}$' OR t.base_price !~ '^\d+\.\d{4}$' OR t.base_fx_rate !~ '^\d+\.\d{4}$'
       OR t.market_cap_rank !~ '^[1-9]\d*$'
UNION ALL SELECT 'asset_month_return format (8 decimals): ' || row_to_json(t)::text FROM stg_asset_month_return t
    WHERE t.month_start !~ '^\d{4}-\d{2}-01$' OR t.local_return !~ '^-?\d+\.\d{8}$' OR t.return_kind NOT IN ('ACTUAL', 'PROXY_INDEX')
UNION ALL SELECT 'fx_month format (8 decimals): ' || row_to_json(t)::text FROM stg_fx_month t
    WHERE t.month_start !~ '^\d{4}-\d{2}-01$' OR t.currency <> 'USD' OR t.fx_change !~ '^-?\d+\.\d{8}$';

-- Values that failed the format checks would fail the casts below, so stop here first.
DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*) INTO v_n FROM ld_err;
    SELECT string_agg(left(msg, 160), E'
' ORDER BY msg) INTO v_msg FROM (SELECT msg FROM ld_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'load_full: input check failed (% rows, first 20 shown)\n%', v_n, v_msg;
    END IF;
END $$;

-- Duplicate natural keys and dangling references inside the files.
INSERT INTO ld_err
SELECT 'asset_group duplicate key: ' || code FROM stg_asset_group GROUP BY code HAVING count(*) > 1
UNION ALL SELECT 'asset duplicate key: ' || market || ':' || ticker FROM stg_asset GROUP BY market, ticker HAVING count(*) > 1
UNION ALL SELECT 'month_block duplicate key: ' || month_start FROM stg_month_block GROUP BY month_start HAVING count(*) > 1
UNION ALL SELECT 'asset_position duplicate key: ' || market || ':' || ticker || ':' || base_date FROM stg_asset_position GROUP BY market, ticker, base_date HAVING count(*) > 1
UNION ALL SELECT 'asset_month_return duplicate key: ' || market || ':' || ticker || ':' || month_start FROM stg_asset_month_return GROUP BY market, ticker, month_start HAVING count(*) > 1
UNION ALL SELECT 'fx_month duplicate key: ' || month_start || ':' || currency FROM stg_fx_month GROUP BY month_start, currency HAVING count(*) > 1;

INSERT INTO ld_err
SELECT 'asset.group_code is not in asset_group.csv: ' || a.group_code FROM stg_asset a
    WHERE NOT EXISTS (SELECT 1 FROM stg_asset_group g WHERE g.code = a.group_code)
UNION ALL SELECT 'asset_position asset is not in asset.csv: ' || p.market || ':' || p.ticker FROM stg_asset_position p
    WHERE NOT EXISTS (SELECT 1 FROM stg_asset a WHERE a.market = p.market AND a.ticker = p.ticker)
UNION ALL SELECT 'asset_month_return asset is not in asset.csv: ' || r.market || ':' || r.ticker FROM stg_asset_month_return r
    WHERE NOT EXISTS (SELECT 1 FROM stg_asset a WHERE a.market = r.market AND a.ticker = r.ticker)
UNION ALL SELECT 'asset_month_return month is not in month_block.csv: ' || r.month_start FROM stg_asset_month_return r
    WHERE NOT EXISTS (SELECT 1 FROM stg_month_block m WHERE m.month_start = r.month_start)
UNION ALL SELECT 'fx_month month is not in month_block.csv: ' || f.month_start FROM stg_fx_month f
    WHERE NOT EXISTS (SELECT 1 FROM stg_month_block m WHERE m.month_start = f.month_start);

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*) INTO v_n FROM ld_err;
    SELECT string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_msg FROM (SELECT msg FROM ld_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'load_full: input check failed (% rows, first 20 shown)\n%', v_n, v_msg;
    END IF;
END $$;

-- Refusal rule 1: an existing row with the same natural key but another value. The columns other loads own are not compared.
INSERT INTO ld_err
SELECT 'asset_group differs from the file: ' || s.code
FROM stg_asset_group s JOIN asset_group g ON g.code = s.code
WHERE (g.name, g.kind) IS DISTINCT FROM (s.name, s.kind)
UNION ALL
SELECT 'asset differs from the file: ' || s.market || ':' || s.ticker
FROM stg_asset s JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
LEFT JOIN asset_group g ON g.id = a.asset_group_id
WHERE (a.name, a.asset_type, a.currency, g.code, a.listed_on, a.return_basis, a.proxy_index_name)
      IS DISTINCT FROM (s.name, s.asset_type, s.currency, s.group_code, s.listed_on::date, s.return_basis, s.proxy_index_name)
UNION ALL
SELECT 'asset_position differs from the file: ' || s.market || ':' || s.ticker || ':' || s.base_date
FROM stg_asset_position s JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
JOIN asset_position p ON p.asset_id = a.id AND p.base_date = s.base_date::date
WHERE (p.base_price, p.base_fx_rate, p.market_cap_rank)
      IS DISTINCT FROM (s.base_price::numeric, s.base_fx_rate::numeric, s.market_cap_rank::smallint)
UNION ALL
SELECT 'asset_month_return differs from the file: ' || s.market || ':' || s.ticker || ':' || s.month_start
FROM stg_asset_month_return s JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
JOIN month_block m ON m.month_start = s.month_start::date
JOIN asset_month_return r ON r.asset_id = a.id AND r.month_block_id = m.id
WHERE (r.local_return, r.return_kind) IS DISTINCT FROM (s.local_return::numeric, s.return_kind)
UNION ALL
SELECT 'fx_month differs from the file: ' || s.month_start || ':' || s.currency
FROM stg_fx_month s JOIN month_block m ON m.month_start = s.month_start::date
JOIN fx_month f ON f.month_block_id = m.id AND f.currency = s.currency
WHERE f.fx_change IS DISTINCT FROM s.fx_change::numeric;

-- Refusal rule 2: assets and months in the database that no file names.
INSERT INTO ld_err
SELECT 'database asset not in asset.csv: ' || a.market || ':' || a.ticker FROM asset a
WHERE NOT EXISTS (SELECT 1 FROM stg_asset s WHERE s.market = a.market AND s.ticker = a.ticker)
UNION ALL
SELECT 'database month_block not in month_block.csv: ' || m.month_start FROM month_block m
WHERE NOT EXISTS (SELECT 1 FROM stg_month_block s WHERE s.month_start::date = m.month_start);

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*) INTO v_n FROM ld_err;
    SELECT string_agg(left(msg, 160), E'
' ORDER BY msg) INTO v_msg FROM (SELECT msg FROM ld_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'load_full: refused (% rows, first 20 shown). No value is corrected; everything is rolled back. Load into a freshly migrated database\n%', v_n, v_msg;
    END IF;
END $$;

-- Insert in FK order, in natural-key order (so ids follow the keys).
CREATE TEMP TABLE ld_pre ON COMMIT DROP AS
SELECT (SELECT count(*) FROM asset_group) + (SELECT count(*) FROM asset) + (SELECT count(*) FROM month_block)
     + (SELECT count(*) FROM asset_position) + (SELECT count(*) FROM asset_month_return) + (SELECT count(*) FROM fx_month) AS n;
CREATE TEMP TABLE ld_report (seq serial, tbl text, csv_rows bigint, new_rows bigint) ON COMMIT DROP;

WITH ins AS (
    INSERT INTO asset_group (code, name, kind)
    SELECT s.code, s.name, s.kind FROM stg_asset_group s
    ORDER BY s.code COLLATE "C"
    ON CONFLICT (code) DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'asset_group', (SELECT count(*) FROM stg_asset_group), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis, proxy_index_name)
    SELECT s.market, s.ticker, s.name, s.asset_type, s.currency, g.id, s.listed_on::date, s.return_basis, s.proxy_index_name
    FROM stg_asset s JOIN asset_group g ON g.code = s.group_code
    ORDER BY s.market COLLATE "C", s.ticker COLLATE "C"
    ON CONFLICT (market, ticker) DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'asset', (SELECT count(*) FROM stg_asset), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO month_block (month_start)
    SELECT s.month_start::date FROM stg_month_block s
    ORDER BY s.month_start
    ON CONFLICT (month_start) DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'month_block', (SELECT count(*) FROM stg_month_block), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank)
    SELECT a.id, s.base_date::date, s.base_price::numeric, s.base_fx_rate::numeric, s.market_cap_rank::smallint
    FROM stg_asset_position s JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
    ORDER BY s.market COLLATE "C", s.ticker COLLATE "C", s.base_date
    ON CONFLICT (asset_id, base_date) DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'asset_position', (SELECT count(*) FROM stg_asset_position), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
    SELECT a.id, m.id, s.local_return::numeric, s.return_kind
    FROM stg_asset_month_return s
    JOIN asset a ON a.market = s.market AND a.ticker = s.ticker
    JOIN month_block m ON m.month_start = s.month_start::date
    ORDER BY s.market COLLATE "C", s.ticker COLLATE "C", s.month_start
    ON CONFLICT (asset_id, month_block_id) DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'asset_month_return', (SELECT count(*) FROM stg_asset_month_return), (SELECT count(*) FROM ins);

WITH ins AS (
    INSERT INTO fx_month (month_block_id, currency, fx_change)
    SELECT m.id, s.currency, s.fx_change::numeric
    FROM stg_fx_month s JOIN month_block m ON m.month_start = s.month_start::date
    ORDER BY s.month_start, s.currency
    ON CONFLICT (month_block_id, currency) DO NOTHING
    RETURNING 1)
INSERT INTO ld_report (tbl, csv_rows, new_rows)
SELECT 'fx_month', (SELECT count(*) FROM stg_fx_month), (SELECT count(*) FROM ins);

-- Refusal rule 3: a non-empty database is accepted only when this run inserted nothing (rolled back otherwise).
DO $$
BEGIN
    IF (SELECT n FROM ld_pre) > 0 AND (SELECT sum(new_rows) FROM ld_report) > 0 THEN
        RAISE EXCEPTION 'load_full: refused. The database already holds history rows and this run would add % more (partial earlier load). Load into an empty, freshly migrated database',
            (SELECT sum(new_rows) FROM ld_report);
    END IF;
END $$;

-- csv_rows = rows in the file, new_rows = rows inserted by this run (0 on a re-run).
SELECT tbl, csv_rows, new_rows FROM ld_report ORDER BY seq;

COMMIT;
