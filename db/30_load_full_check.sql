-- =====================================================================
-- my Fund Manager MVP-06 (06-B1) — checks for the full history that db/30_load_full.sql loaded
-- Rules checked here (docs/erd.md 5.5): H1, H3, H4, H7 (per-cell lineage rule), plus row counts, the month calendar,
--   A2 (a proxy month after the first ACTUAL month) and R2 (proxy_index_name is set exactly for assets with a proxy month).
-- Checked by their own scripts: H2 db/12_calc_position_check.sql, H5 and H6 db/11_load_events_check.sql, H8 db/13_calc_attention_check.sql,
--   H9 db/12_calc_position_check.sql. Not checkable in the database: that the fee/12 deduction was applied only to ETF proxy months
--   (no fee is stored); that is a build rule, tested on the build fixture (tools/fetch/tests/test_build_full.py).
-- Reads from one directory (psql variable indir, default db/full): lineage.csv (read into a temp table, it is not a DB table)
--   and proxy_series.csv (the series names a lineage row may use).
-- Run from the repository root, after the load:
--   psql -X -q -A -v ON_ERROR_STOP=1 -d <db> -f db/30_load_full_check.sql
-- Expected-size variables (defaults are the full MVP history; tests use a small fixture):
--   first_month 1996-01-01, exp_months 360, exp_assets 180, stock_actual_from 2007-01-01 (PLAN 4.7: stocks are ACTUAL from here).
--   stock_actual_from is the stock rule, not the replay start (B13).
-- Result: every violating row is printed as `code|detail` (at most 20 per code), then the script fails (exit code 3).
--   Zero rows and a last line `PASS` mean the check passed. The script refuses to run without -v ON_ERROR_STOP=1. The script writes nothing: all work is in temp tables and ends with ROLLBACK.
-- =====================================================================

-- Refuse to run without ON_ERROR_STOP: a client-side \copy error (missing file) would not abort the transaction.
\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'load_full_check: ON_ERROR_STOP is off. Run: psql -X -v ON_ERROR_STOP=1 -d <db> -f db/30_load_full_check.sql'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'load_full_check: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
\else
  \echo 'load_full_check: cannot run without -v ON_ERROR_STOP=1'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'load_full_check: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif

\if :{?indir}
\else
  \set indir db/full
\endif
\if :{?first_month}
\else
  \set first_month 1996-01-01
\endif
\if :{?exp_months}
\else
  \set exp_months 360
\endif
\if :{?exp_assets}
\else
  \set exp_assets 180
\endif
\if :{?stock_actual_from}
\else
  \set stock_actual_from 2007-01-01
\endif

BEGIN;

CREATE TEMP TABLE stg_lineage (market text, ticker text, month_start text, return_kind text, series_id text) ON COMMIT DROP;
CREATE TEMP TABLE stg_series (series_id text, source text, source_code text, currency text, basis text, observation text,
                              first_month text, last_month text, formula text, terms_url text, terms_checked_on text) ON COMMIT DROP;

\set f :indir '/lineage.csv'
\set cmd '\\copy stg_lineage FROM ' :'f' ' WITH (FORMAT csv, HEADER MATCH)'
:cmd
\set f :indir '/proxy_series.csv'
\set cmd '\\copy stg_series FROM ' :'f' ' WITH (FORMAT csv, HEADER MATCH)'
:cmd

CREATE TEMP TABLE ck (code text NOT NULL, detail text NOT NULL) ON COMMIT DROP;

-- Row counts and the month calendar -------------------------------------------------
INSERT INTO ck
SELECT 'CNT_MONTH', 'month_block rows ' || n || ', expected ' || :'exp_months'
FROM (SELECT count(*) AS n FROM month_block) c WHERE n <> :'exp_months'::int
UNION ALL
SELECT 'CNT_ASSET', 'asset rows ' || n || ', expected ' || :'exp_assets'
FROM (SELECT count(*) AS n FROM asset) c WHERE n <> :'exp_assets'::int
UNION ALL
SELECT 'CNT_RETURN', 'asset_month_return rows ' || n || ', expected ' || (:'exp_assets'::int * :'exp_months'::int)
FROM (SELECT count(*) AS n FROM asset_month_return) c WHERE n <> :'exp_assets'::int * :'exp_months'::int
UNION ALL
SELECT 'CNT_FX', 'fx_month rows ' || n || ', expected ' || :'exp_months'
FROM (SELECT count(*) AS n FROM fx_month) c WHERE n <> :'exp_months'::int
UNION ALL
SELECT 'CNT_POS', 'asset_position rows ' || n || ', expected ' || :'exp_assets'
FROM (SELECT count(*) AS n FROM asset_position) c WHERE n <> :'exp_assets'::int
UNION ALL
SELECT 'CAL_MISSING', 'month ' || d::date || ' is not in month_block'
FROM generate_series(:'first_month'::date, (:'first_month'::date + make_interval(months => :'exp_months'::int - 1))::date, interval '1 month') d
WHERE NOT EXISTS (SELECT 1 FROM month_block m WHERE m.month_start = d::date)
UNION ALL
SELECT 'CAL_EXTRA', 'month_block ' || m.month_start || ' is outside the expected range'
FROM month_block m
WHERE m.month_start < :'first_month'::date
   OR m.month_start > (:'first_month'::date + make_interval(months => :'exp_months'::int - 1))::date;

-- Formats and basics -----------------------------------------------------------------
INSERT INTO ck
SELECT 'FMT_TICKER', 'ticker ' || quote_literal(a.ticker) || ' (' || a.market || ')'
FROM asset a
WHERE a.ticker <> upper(a.ticker) OR a.ticker ~ '\s' OR a.ticker <> btrim(a.ticker) OR (a.market = 'KR' AND a.ticker !~ '^[0-9A-Z]{6}$')
UNION ALL
SELECT 'ETF_RANK', 'ETF ' || a.market || ':' || a.ticker || ' has market_cap_rank ' || p.market_cap_rank
FROM asset_position p JOIN asset a ON a.id = p.asset_id
WHERE a.asset_type = 'ETF' AND p.market_cap_rank IS NOT NULL
UNION ALL
SELECT 'POS_BASE_DATE', count(DISTINCT base_date) || ' distinct base dates, expected 1' FROM asset_position HAVING count(DISTINCT base_date) > 1
UNION ALL
SELECT 'POS_MISSING', 'asset ' || a.market || ':' || a.ticker || ' has no asset_position row'
FROM asset a WHERE NOT EXISTS (SELECT 1 FROM asset_position p WHERE p.asset_id = a.id);

-- H1: one USD rate per base date, KRW is 1
INSERT INTO ck
SELECT 'H1', 'base_date ' || v.base_date || ' currency ' || v.currency
FROM (SELECT p.base_date, a.currency
      FROM asset_position p JOIN asset a ON a.id = p.asset_id
      GROUP BY p.base_date, a.currency
      HAVING (a.currency = 'USD' AND count(DISTINCT p.base_fx_rate) > 1)
          OR (a.currency = 'KRW' AND (min(p.base_fx_rate) <> 1 OR max(p.base_fx_rate) <> 1))) v;

-- H3: no empty (asset, month) cell. H4: one USD row per month.
INSERT INTO ck
SELECT 'H3', 'no return for ' || a.market || ':' || a.ticker || ' ' || m.month_start
FROM asset a CROSS JOIN month_block m
WHERE NOT EXISTS (SELECT 1 FROM asset_month_return r WHERE r.asset_id = a.id AND r.month_block_id = m.id)
UNION ALL
SELECT 'H4', 'month ' || m.month_start || ' has ' || (SELECT count(*) FROM fx_month f WHERE f.month_block_id = m.id AND f.currency = 'USD') || ' USD rows'
FROM month_block m
WHERE (SELECT count(*) FROM fx_month f WHERE f.month_block_id = m.id AND f.currency = 'USD') <> 1;

-- H7: per-cell lineage rule ------------------------------------------------------------
CREATE TEMP TABLE cell ON COMMIT DROP AS
SELECT a.market, a.ticker, a.asset_type, a.return_basis, a.listed_on, a.id AS asset_id, m.month_start, r.return_kind
FROM asset_month_return r JOIN asset a ON a.id = r.asset_id JOIN month_block m ON m.id = r.month_block_id;

INSERT INTO ck
-- every cell has exactly one lineage row, and every lineage row belongs to a cell
SELECT 'H7_LINEAGE_MISSING', 'no lineage row for ' || c.market || ':' || c.ticker || ' ' || c.month_start
FROM cell c
WHERE NOT EXISTS (SELECT 1 FROM stg_lineage l WHERE l.market = c.market AND l.ticker = c.ticker AND l.month_start = to_char(c.month_start, 'YYYY-MM-DD'))
UNION ALL
SELECT 'H7_LINEAGE_DUP', 'duplicate lineage rows for ' || l.market || ':' || l.ticker || ' ' || l.month_start
FROM stg_lineage l GROUP BY l.market, l.ticker, l.month_start HAVING count(*) > 1
UNION ALL
SELECT 'H7_LINEAGE_ORPHAN', 'lineage row without a cell: ' || l.market || ':' || l.ticker || ' ' || l.month_start
FROM stg_lineage l
WHERE NOT EXISTS (SELECT 1 FROM cell c WHERE c.market = l.market AND c.ticker = l.ticker AND to_char(c.month_start, 'YYYY-MM-DD') = l.month_start)
UNION ALL
-- the stored kind equals the lineage kind
SELECT 'H7_KIND', 'return_kind ' || c.return_kind || ' but lineage says ' || l.return_kind || ' for ' || c.market || ':' || c.ticker || ' ' || c.month_start
FROM cell c JOIN stg_lineage l ON l.market = c.market AND l.ticker = c.ticker AND l.month_start = to_char(c.month_start, 'YYYY-MM-DD')
WHERE c.return_kind IS DISTINCT FROM l.return_kind
UNION ALL
-- a PROXY_INDEX cell names a proxy series of proxy_series.csv
SELECT 'H7_PROXY_SERIES', 'PROXY_INDEX cell ' || c.market || ':' || c.ticker || ' ' || c.month_start || ' has series ' || COALESCE(quote_literal(l.series_id), 'NULL')
FROM cell c JOIN stg_lineage l ON l.market = c.market AND l.ticker = c.ticker AND l.month_start = to_char(c.month_start, 'YYYY-MM-DD')
WHERE c.return_kind = 'PROXY_INDEX'
  AND (l.series_id IS NULL OR NOT EXISTS (SELECT 1 FROM stg_series s WHERE s.series_id = l.series_id))
UNION ALL
-- an ACTUAL cell names no series
SELECT 'H7_ACTUAL_SERIES', 'ACTUAL cell ' || c.market || ':' || c.ticker || ' ' || c.month_start || ' has series ' || quote_literal(l.series_id)
FROM cell c JOIN stg_lineage l ON l.market = c.market AND l.ticker = c.ticker AND l.month_start = to_char(c.month_start, 'YYYY-MM-DD')
WHERE c.return_kind = 'ACTUAL' AND l.series_id IS NOT NULL
UNION ALL
-- stocks are ACTUAL from stock_actual_from on (PLAN 4.7)
SELECT 'H7_STOCK', 'stock ' || c.market || ':' || c.ticker || ' ' || c.month_start || ' is ' || c.return_kind
FROM cell c
WHERE c.asset_type = 'STOCK' AND c.month_start >= :'stock_actual_from'::date AND c.return_kind <> 'ACTUAL'
UNION ALL
-- an ETF has no actual return in or before its listing month
SELECT 'H7_ETF_LISTING', 'ETF ' || c.market || ':' || c.ticker || ' ' || c.month_start || ' is ACTUAL, listed ' || c.listed_on
FROM cell c
WHERE c.asset_type = 'ETF' AND c.month_start <= date_trunc('month', c.listed_on)::date AND c.return_kind = 'ACTUAL'
UNION ALL
-- return basis: KR price, US total
SELECT 'H7_BASIS', 'asset ' || a.market || ':' || a.ticker || ' has basis ' || a.return_basis
FROM asset a WHERE (a.market = 'KR' AND a.return_basis <> 'PRICE') OR (a.market = 'US' AND a.return_basis <> 'TOTAL');

-- A2: a PROXY_INDEX month after the asset's first ACTUAL month is reported (interior gap, listing gap)
INSERT INTO ck
SELECT 'A2', 'proxy month ' || c.month_start || ' of ' || c.market || ':' || c.ticker || ' is after its first ACTUAL month ' || f.first_actual
FROM cell c
JOIN (SELECT asset_id, min(month_start) AS first_actual FROM cell WHERE return_kind = 'ACTUAL' GROUP BY asset_id) f ON f.asset_id = c.asset_id
WHERE c.return_kind = 'PROXY_INDEX' AND c.month_start > f.first_actual;

-- R2: proxy_index_name is set exactly for assets that have at least one PROXY_INDEX month
INSERT INTO ck
SELECT 'R2_NAME_MISSING', 'asset ' || a.market || ':' || a.ticker || ' has proxy months but no proxy_index_name'
FROM asset a
WHERE a.proxy_index_name IS NULL AND EXISTS (SELECT 1 FROM cell c WHERE c.asset_id = a.id AND c.return_kind = 'PROXY_INDEX')
UNION ALL
SELECT 'R2_NAME_EXTRA', 'asset ' || a.market || ':' || a.ticker || ' has proxy_index_name but no proxy month'
FROM asset a
WHERE a.proxy_index_name IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cell c WHERE c.asset_id = a.id AND c.return_kind = 'PROXY_INDEX');

-- Result -------------------------------------------------------------------------------
SELECT code, detail
FROM (SELECT code, detail, row_number() OVER (PARTITION BY code ORDER BY detail) AS rn FROM ck) t
WHERE rn <= 20
ORDER BY code, detail;

SELECT count(*) = 0 AS ck_ok FROM ck \gset

DO $$
DECLARE
    v_n bigint;
BEGIN
    SELECT count(*) INTO v_n FROM ck;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'load_full_check: % violating rows (the rows above show at most 20 per code)', v_n;
    END IF;
END $$;

ROLLBACK;

\if :ck_ok
  SELECT 'PASS' AS load_full_check;
\else
  SELECT 'FAIL' AS load_full_check;
\endif
