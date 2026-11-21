-- =====================================================================
-- my Fund Manager MVP-06 A -- full-history build: raw cache -> db/full/*.csv (git-ignored outputs)
-- Basis: docs/PLAN.md 4.2, 4.6, 4.7, 5.2, 7; docs/erd.md 5 (month_block, fx_month, asset_position, asset_month_return);
--        db/full/README.md "Proxy map" and "Build". Generalises db/sample/build_sample.sql (same output columns).
-- Run (repository root, a throwaway database, psql only):
--   psql -X -q -v ON_ERROR_STOP=1 -d <scratch db> -f db/full/build_full.sql
-- Variables (all optional):
--   indir=data/raw/mvp-04   raw cache (norm/daily.csv, aux/{fred,ecos,wb}/, yf/)
--   mapdir=db/full          tracked inputs: asset.csv, fetch_targets.csv, proxy_series.csv, proxy_map.csv, pool_proposal.csv
--   outdir=db/full          output directory; refuses any path under db/sample (paths may not contain spaces: psql \o and \i do not unquote)
--   workdir=:outdir         where the two generated load scripts (.build_full_load_*.psql) are written
--   partial=1               list every gap (cells and files) and write nothing; exit status 0 (default 0: any gap stops the build)
--   yf_window=19951201_20260102   date part of the yfinance file names (tools/fetch/fetch_aux.py)
--   first_month=1996-01-01, last_month=2025-12-01, base_date=2026-01-02   history range and base date (tests use a short range)
-- Outputs: month_block.csv, fx_month.csv, asset_position.csv, asset_month_return.csv, lineage.csv.
--   Nothing is written unless every check passes. Permanent tables are never touched (ROLLBACK at the end).
-- Rules:
--   One source per cell: the canonical source of (asset, return month) comes from fetch_targets.csv
--   (KR: KIS; US: yfinance up to yf_canonical_to, KIS after). A return is ACTUAL when that source has the month-end
--   close of the month and of the month before; otherwise the lowest-priority ladder series whose declared span covers the
--   month fills it (PROXY_INDEX). The chosen series must have a value for the month; a hole stops the build.
--   Proxy return = series return, times (1 + fx_change) once when to_krw = Y, minus annual_fee / 12 when fee_applied = Y.
--   Ratios are carried unrounded and rounded once to 8 decimals at write. No float anywhere.
-- Hard stops: a cell with neither actual nor proxy value; a chosen series without a value; a pending fee on a proxy
--   month; a stock month from 2007-01 that is not actual; duplicate rows; an input file that is missing, empty or malformed.
-- Syntax new here: \o + \i to generate load commands (\copy does not expand variables), COPY ... TO STDOUT through \o,
--   savepoint + :ERROR, CREATE FUNCTION pg_temp.*, regexp_match, DISTINCT ON.
-- =====================================================================

\set ON_ERROR_STOP on
\if :{?indir}
\else
  \set indir data/raw/mvp-04
\endif
\if :{?mapdir}
\else
  \set mapdir db/full
\endif
\if :{?outdir}
\else
  \set outdir db/full
\endif
\if :{?workdir}
\else
  \set workdir :outdir
\endif
\if :{?partial}
\else
  \set partial 0
\endif
\if :{?yf_window}
\else
  \set yf_window 19951201_20260102
\endif
\if :{?first_month}
\else
  \set first_month 1996-01-01
\endif
\if :{?last_month}
\else
  \set last_month 2025-12-01
\endif
\if :{?base_date}
\else
  \set base_date 2026-01-02
\endif
-- paths are compared after collapsing repeated slashes and /./ and ignoring case (macOS is case-insensitive)
SELECT (:'partial' = '1') AS is_partial,
       (lower(regexp_replace(regexp_replace(:'outdir', '/+', '/', 'g'), '(/\.)+(/|$)', '/', 'g')) ~ '(^|/)db/sample(/|$)'
        OR lower(regexp_replace(regexp_replace(:'workdir', '/+', '/', 'g'), '(/\.)+(/|$)', '/', 'g')) ~ '(^|/)db/sample(/|$)') AS out_in_sample,
       set_config('build_full.dirs', :'outdir' || ' ' || :'workdir', false) AS dirs \gset
\if :out_in_sample
  DO $$ BEGIN RAISE EXCEPTION 'build_full: refusing to write into db/sample (outdir workdir = %)', current_setting('build_full.dirs'); END $$;
\endif
-- fail now, not after the checks, when the output directory is missing or not writable (leaves an empty probe file)
\set probe :outdir '/.build_full_probe'
\o :probe
\o
\set load_maps :workdir '/.build_full_load_maps.psql'
\set load_data :workdir '/.build_full_load_data.psql'

BEGIN;

-- Fixed settings -----------------------------------------------------------------
CREATE TEMP TABLE p_month AS
SELECT d::date AS m FROM generate_series(:'first_month'::timestamp, :'last_month'::timestamp, interval '1 month') d;
CREATE TEMP TABLE p_base (base_date date);
INSERT INTO p_base VALUES (:'base_date'::date);

CREATE TEMP TABLE gap (kind text NOT NULL, market text, ticker text, m date, detail text);
CREATE TEMP TABLE load_log (label text, n bigint);
CREATE TEMP TABLE load_error (label text);

-- Staging tables (text only, checked below) ---------------------------------------
CREATE TEMP TABLE stg_asset (market text, ticker text, name text, asset_type text, currency text,
                             group_code text, listed_on text, return_basis text, proxy_index_name text);
CREATE TEMP TABLE stg_target (market text, ticker text, kis_excd text, kis_basis text, kis_from text, yf_symbol text,
                              yf_canonical_to text, order_no text);
CREATE TEMP TABLE stg_series (series_id text, source text, source_code text, currency text, basis text, observation text,
                              first_month text, last_month text, formula text, terms_url text, terms_checked_on text);
CREATE TEMP TABLE stg_map (market text, ticker text, priority text, series_id text, to_krw text, fee_applied text,
                           annual_fee text, fee_source text);
CREATE TEMP TABLE stg_pool (market text, ticker text, name text, asset_type text, group_code text, listed_on text,
                            listed_check text, return_basis text, rule text, rule_rank text, mcap_rank_public text,
                            mcap_asof text, source_url text, kis_excd text, yf_symbol text, kis_first_month text,
                            yf_first_month text, has_2006_12 text, status text, owner_decision text, note text);
CREATE TEMP TABLE stg_daily (market text, ticker text, trade_date text, close text, close_price text, close_raw text, source text);
CREATE TEMP TABLE stg_fx (observation_date text, "DEXKOUS" text);
CREATE TEMP TABLE stg_obs (series_id text, k text, v1 text, v2 text);

-- One load block: copy under a savepoint so that (in partial mode) a server-side error is recorded and the transaction survives.
-- A missing file is a client-side error (ERROR stays false): the empty table shows up in load_log.
CREATE FUNCTION pg_temp.load_block(tbl text, path text, label text) RETURNS text LANGUAGE sql AS $f$
SELECT format(E'SAVEPOINT sp;\n\\copy %s FROM %L WITH (FORMAT csv, HEADER MATCH)\n\\if :ERROR\nROLLBACK TO sp;\nINSERT INTO load_error VALUES (%L);\n\\endif\nRELEASE sp;\nINSERT INTO load_log SELECT %L, count(*) FROM %s;',
              tbl, path, label, label, tbl)
$f$;

-- 1. Tracked inputs (always fatal when unreadable) ---------------------------------
\pset format unaligned
\pset tuples_only on
\o :load_maps
SELECT format(E'\\copy %s FROM %L WITH (FORMAT csv, HEADER MATCH)', v.t, :'mapdir' || '/' || v.f)
FROM (VALUES ('stg_asset', 'asset.csv'), ('stg_target', 'fetch_targets.csv'), ('stg_series', 'proxy_series.csv'),
             ('stg_map', 'proxy_map.csv'), ('stg_pool', 'pool_proposal.csv')) v(t, f);
\o
\pset tuples_only off
\pset format aligned
\i :load_maps

DO $$
DECLARE
    v_bad bigint;
BEGIN
    IF (SELECT count(*) FROM stg_asset) = 0 THEN
        RAISE EXCEPTION 'build_full: asset.csv has no rows';
    END IF;
    SELECT count(*) INTO v_bad FROM stg_asset
    WHERE num_nulls(market, ticker, name, asset_type, currency, return_basis) > 0
       OR market NOT IN ('KR', 'US') OR asset_type NOT IN ('STOCK', 'ETF') OR currency NOT IN ('KRW', 'USD')
       OR (market = 'KR') <> (currency = 'KRW') OR return_basis NOT IN ('PRICE', 'TOTAL')
       OR ticker !~ '^[A-Za-z0-9.]+$';
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: asset.csv has % malformed rows', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM (SELECT market, ticker FROM stg_asset GROUP BY 1, 2 HAVING count(*) > 1) d;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: duplicate (market, ticker) in asset.csv: % keys', v_bad; END IF;

    SELECT count(*) INTO v_bad FROM stg_target
    WHERE num_nulls(market, ticker, kis_basis) > 0 OR kis_basis NOT IN ('ADJUSTED', 'RAW')
       OR (yf_canonical_to IS NOT NULL AND yf_canonical_to !~ '^\d{4}-(0[1-9]|1[0-2])$')
       OR (yf_canonical_to IS NOT NULL AND market = 'KR');
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: fetch_targets.csv has % malformed rows (yf_canonical_to is YYYY-MM and US only)', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM (
        (SELECT market, ticker FROM stg_asset EXCEPT SELECT market, ticker FROM stg_target)
        UNION ALL (SELECT market, ticker FROM stg_target EXCEPT SELECT market, ticker FROM stg_asset)
        UNION ALL (SELECT market, ticker FROM stg_target GROUP BY 1, 2 HAVING count(*) > 1)) d;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: asset.csv and fetch_targets.csv name different assets (% keys)', v_bad; END IF;

    SELECT count(*) INTO v_bad FROM stg_series
    WHERE num_nulls(series_id, source, currency, basis, observation, first_month, last_month) > 0
       OR source NOT IN ('YF', 'FRED', 'ECOS', 'WB', 'ASSET', 'DERIVED') OR currency NOT IN ('KRW', 'USD')
       OR basis NOT IN ('PRICE', 'TOTAL', 'RATE') OR observation NOT IN ('MONTH_END', 'MONTH_AVG')
       OR first_month !~ '^\d{4}-(0[1-9]|1[0-2])$' OR last_month !~ '^\d{4}-(0[1-9]|1[0-2])$' OR first_month > last_month
       OR (source = 'DERIVED') <> (formula IS NOT NULL) OR (source <> 'DERIVED' AND source_code IS NULL);
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: proxy_series.csv has % malformed rows', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM (SELECT series_id FROM stg_series GROUP BY 1 HAVING count(*) > 1) d;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: duplicate series_id in proxy_series.csv'; END IF;

    SELECT count(*) INTO v_bad FROM stg_map
    WHERE num_nulls(market, ticker, priority, series_id, to_krw, fee_applied) > 0 OR priority !~ '^\d+$'
       OR to_krw NOT IN ('Y', 'N') OR fee_applied NOT IN ('Y', 'N')
       OR (annual_fee IS NOT NULL AND (annual_fee !~ '^\d+(\.\d+)?$' OR annual_fee::numeric >= 0.05 OR annual_fee::numeric <= 0));
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: proxy_map.csv has % malformed rows', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM (SELECT market, ticker, priority FROM stg_map GROUP BY 1, 2, 3 HAVING count(*) > 1) d;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: duplicate (market, ticker, priority) in proxy_map.csv'; END IF;
    SELECT count(*) INTO v_bad FROM stg_map m
    WHERE NOT EXISTS (SELECT 1 FROM stg_asset a WHERE a.market = m.market AND a.ticker = m.ticker)
       OR NOT EXISTS (SELECT 1 FROM stg_series s WHERE s.series_id = m.series_id AND s.basis <> 'RATE');
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: proxy_map.csv names % unknown assets or series (a RATE series may not be named)', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM stg_map m JOIN stg_asset a USING (market, ticker)
    WHERE m.fee_applied <> CASE a.asset_type WHEN 'ETF' THEN 'Y' ELSE 'N' END;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: fee_applied is Y exactly for ETF rows (% violations)', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM stg_map m JOIN stg_asset a USING (market, ticker) JOIN stg_series s USING (series_id)
    WHERE m.to_krw <> CASE WHEN a.currency = 'KRW' AND s.currency = 'USD' AND a.name !~ '\(H\)' THEN 'Y' ELSE 'N' END;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: to_krw disagrees with the currencies of the asset and the series (% rows)', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM stg_map m JOIN stg_asset a USING (market, ticker) JOIN stg_series s USING (series_id)
    WHERE a.currency = 'USD' AND s.currency = 'KRW';
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: % map rows give a USD asset a won series', v_bad; END IF;
END $$;

CREATE TEMP TABLE a_asset AS
SELECT a.market, a.ticker, a.asset_type, a.currency, a.return_basis, a.name,
       CASE WHEN t.yf_canonical_to IS NULL THEN NULL ELSE (t.yf_canonical_to || '-01')::date END AS yf_to
FROM stg_asset a JOIN stg_target t USING (market, ticker);

-- 2. Raw cache: generate the load commands for files whose names come from the data ------------
CREATE TEMP TABLE load_plan (seq int PRIMARY KEY, script text NOT NULL);
INSERT INTO load_plan
SELECT 1, pg_temp.load_block('stg_daily', :'indir' || '/norm/daily.csv', 'norm/daily.csv')
UNION ALL
SELECT 2, pg_temp.load_block('stg_fx', :'indir' || '/aux/fred/DEXKOUS.csv', 'aux/fred/DEXKOUS.csv');

INSERT INTO load_plan
SELECT 100 + row_number() OVER (ORDER BY s.series_id),
       CASE s.source
         WHEN 'YF' THEN format('CREATE TEMP TABLE o_%1$s (trade_date text, close text, adj_close text);', s.series_id) || E'\n'
              || pg_temp.load_block('o_' || s.series_id, :'indir' || '/yf/yf_px_' || s.source_code || '_' || :'yf_window' || '.csv', s.series_id)
              || format(E'\nINSERT INTO stg_obs SELECT %L, trade_date, close, adj_close FROM o_%s;', s.series_id, s.series_id)
         WHEN 'FRED' THEN format('CREATE TEMP TABLE o_%1$s (observation_date text, %2$I text);', s.series_id, s.source_code) || E'\n'
              || pg_temp.load_block('o_' || s.series_id, :'indir' || '/aux/fred/' || s.source_code || '.csv', s.series_id)
              || format(E'\nINSERT INTO stg_obs SELECT %L, observation_date, %I, NULL FROM o_%s WHERE %I IS NOT NULL AND %I <> ''.'';', s.series_id, s.source_code, s.series_id, s.source_code, s.source_code)
         WHEN 'ECOS' THEN format('CREATE TEMP TABLE o_%1$s (time text, value text);', s.series_id) || E'\n'
              || pg_temp.load_block('o_' || s.series_id,
                                    :'indir' || '/aux/ecos/' || replace(s.source_code, '/', '_') || '_' || CASE s.observation WHEN 'MONTH_AVG' THEN 'M' ELSE 'D' END || '.csv',
                                    s.series_id)
              || format(E'\nINSERT INTO stg_obs SELECT %L, time, value, NULL FROM o_%s;', s.series_id, s.series_id)
       END
FROM stg_series s WHERE s.source IN ('YF', 'FRED', 'ECOS');

-- The World Bank workbook is flattened to aux/wb/monthly_prices.csv (series,month,value) by tools/fetch/aux_norm.py.
CREATE TEMP TABLE o_wb (series text, month text, value text);
INSERT INTO load_plan
SELECT 999, pg_temp.load_block('o_wb', :'indir' || '/aux/wb/monthly_prices.csv', 'aux/wb/monthly_prices.csv')
WHERE EXISTS (SELECT 1 FROM stg_series WHERE source = 'WB');
INSERT INTO load_plan
SELECT 1000 + row_number() OVER (ORDER BY s.series_id), format(E'INSERT INTO stg_obs SELECT %L, month, value, NULL FROM o_wb WHERE series = %L;', s.series_id, s.source_code)
FROM stg_series s WHERE s.source = 'WB';

\pset format unaligned
\pset tuples_only on
\o :load_data
SELECT script FROM load_plan ORDER BY seq, script;
\o
\pset tuples_only off
\pset format aligned

-- Partial mode keeps going past a missing file; the empty table is reported as a gap below.
\if :is_partial
  \set ON_ERROR_STOP off
\endif
\i :load_data
\set ON_ERROR_STOP on

INSERT INTO gap (kind, detail) SELECT 'LOAD_ERROR', label FROM load_error;
INSERT INTO gap (kind, detail) SELECT 'FILE_EMPTY_OR_MISSING', label FROM load_log WHERE n = 0
                                  AND label NOT IN (SELECT label FROM load_error);

-- 3. Format and duplicate checks on the raw rows ---------------------------------------------
DO $$
DECLARE
    v_bad bigint;
    v_list text;
BEGIN
    SELECT count(*) INTO v_bad FROM stg_daily
    WHERE num_nulls(market, ticker, trade_date, close, source) > 0
       OR market NOT IN ('KR', 'US') OR ticker !~ '^[A-Za-z0-9.]+$'
       OR trade_date !~ '^\d{4}-\d{2}-\d{2}$' OR close !~ '^\d+(\.\d+)?$' OR close::numeric <= 0
       OR (close_price IS NOT NULL AND (close_price !~ '^\d+(\.\d+)?$' OR close_price::numeric <= 0))
       OR (close_raw IS NOT NULL AND (close_raw !~ '^\d+(\.\d+)?$' OR close_raw::numeric <= 0))
       OR source NOT IN ('KIS', 'YF');
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: norm/daily.csv has % malformed rows (blank, non-numeric, non-positive or unknown source)', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM (SELECT 1 FROM stg_daily GROUP BY market, upper(ticker), trade_date, source HAVING count(*) > 1) d;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: norm/daily.csv repeats a (market, ticker, trade_date, source) % times', v_bad; END IF;
    SELECT string_agg(DISTINCT d.market || ':' || d.ticker, ', ') INTO v_list FROM stg_daily d
    WHERE NOT EXISTS (SELECT 1 FROM stg_asset a WHERE a.market = d.market AND upper(a.ticker) = upper(d.ticker));
    IF v_list IS NOT NULL THEN RAISE EXCEPTION 'build_full: norm/daily.csv holds tickers that are not in asset.csv: %', v_list; END IF;

    SELECT count(*) INTO v_bad FROM stg_fx
    WHERE observation_date IS NULL OR observation_date !~ '^\d{4}-\d{2}-\d{2}$'
       OR ("DEXKOUS" IS NOT NULL AND "DEXKOUS" <> '.' AND "DEXKOUS" !~ '^\d+(\.\d+)?$');
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: DEXKOUS file has % malformed rows', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM (SELECT observation_date FROM stg_fx GROUP BY 1 HAVING count(*) > 1) d;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: DEXKOUS file repeats a date % times', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM stg_fx WHERE "DEXKOUS" ~ '^\d' AND "DEXKOUS"::numeric <= 0;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: DEXKOUS has % non-positive observations', v_bad; END IF;

    -- series observations: the key shape follows the source/cycle, the value is a number, no key twice
    SELECT count(*) INTO v_bad FROM stg_obs o JOIN stg_series s USING (series_id)
    WHERE o.k IS NULL OR o.v1 IS NULL
       OR (s.source = 'YF' AND (o.k !~ '^\d{4}-\d{2}-\d{2}$' OR o.v1 !~ '^\d+(\.\d+)?$' OR o.v2 !~ '^\d+(\.\d+)?$' OR o.v1::numeric <= 0 OR o.v2::numeric <= 0))
       OR (s.source = 'FRED' AND (o.k !~ '^\d{4}-\d{2}-\d{2}$' OR (o.v1 <> '.' AND o.v1 !~ '^-?\d+(\.\d+)?$')))
       OR (s.source = 'ECOS' AND (o.k !~ CASE s.observation WHEN 'MONTH_AVG' THEN '^\d{6}$' ELSE '^\d{8}$' END OR o.v1 !~ '^-?\d+(\.\d+)?$'))
       OR (s.source = 'WB' AND (o.k !~ '^\d{4}-\d{2}$' OR o.v1 !~ '^\d+(\.\d+)?$'));
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: series files have % malformed rows', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM (SELECT 1 FROM stg_obs GROUP BY series_id, k HAVING count(*) > 1) d;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: a series file repeats a date or month % times', v_bad; END IF;
    SELECT count(*) INTO v_bad FROM stg_obs o JOIN stg_series s USING (series_id)
    WHERE s.basis IN ('PRICE', 'TOTAL') AND o.v1 ~ '^-?\d' AND o.v1::numeric <= 0;
    IF v_bad > 0 THEN RAISE EXCEPTION 'build_full: a price or total-return series has % non-positive levels', v_bad; END IF;
END $$;

-- 4. FX (FRED DEXKOUS, last observation of the month) -----------------------------------------
CREATE TEMP TABLE fx_obs AS
SELECT observation_date::date AS d, "DEXKOUS"::numeric AS x FROM stg_fx WHERE "DEXKOUS" IS NOT NULL AND "DEXKOUS" <> '.';
CREATE TEMP TABLE fx_me AS
SELECT DISTINCT ON (date_trunc('month', d)) date_trunc('month', d)::date AS m, d, x FROM fx_obs
ORDER BY date_trunc('month', d), d DESC;
CREATE TEMP TABLE fx_calc AS
SELECT p.m, c.x / pv.x - 1 AS fx_change
FROM p_month p JOIN fx_me c ON c.m = p.m JOIN fx_me pv ON pv.m = (p.m - interval '1 month')::date;
INSERT INTO gap (kind, m, detail) SELECT 'FX_MONTH', p.m, 'no DEXKOUS month-end for this month or the month before'
FROM p_month p WHERE NOT EXISTS (SELECT 1 FROM fx_calc f WHERE f.m = p.m);

-- 5. Actual returns ----------------------------------------------------------------------------
CREATE TEMP TABLE px_me AS
SELECT DISTINCT ON (market, upper(ticker), source, date_trunc('month', trade_date::date))
       market, upper(ticker) AS ticker, source, date_trunc('month', trade_date::date)::date AS m,
       trade_date::date AS trade_date, close::numeric AS close
FROM stg_daily
ORDER BY market, upper(ticker), source, date_trunc('month', trade_date::date), trade_date::date DESC;

CREATE TEMP TABLE cell AS
SELECT a.market, a.ticker, a.asset_type, a.currency, p.m,
       CASE WHEN a.market = 'US' AND a.yf_to IS NOT NULL AND p.m <= a.yf_to THEN 'YF' ELSE 'KIS' END AS src
FROM a_asset a CROSS JOIN p_month p;

CREATE TEMP TABLE actual AS
SELECT c.market, c.ticker, c.m, cur.close / prev.close - 1 AS r
FROM cell c
JOIN px_me cur  ON cur.market  = c.market AND cur.ticker  = upper(c.ticker) AND cur.source  = c.src AND cur.m  = c.m
JOIN px_me prev ON prev.market = c.market AND prev.ticker = upper(c.ticker) AND prev.source = c.src AND prev.m = (c.m - interval '1 month')::date;

-- 6. Series: levels, returns, derived formulas ---------------------------------------------------
CREATE TEMP TABLE s_lv (series_id text, m date, level numeric, PRIMARY KEY (series_id, m));
-- yfinance: month-end row; adjusted close for TOTAL, close for PRICE
INSERT INTO s_lv
SELECT DISTINCT ON (o.series_id, date_trunc('month', o.k::date)) o.series_id, date_trunc('month', o.k::date)::date,
       CASE s.basis WHEN 'TOTAL' THEN o.v2::numeric ELSE o.v1::numeric END
FROM stg_obs o JOIN stg_series s USING (series_id) WHERE s.source = 'YF'
ORDER BY o.series_id, date_trunc('month', o.k::date), o.k DESC;
-- FRED: last observation with a value in the month
INSERT INTO s_lv
SELECT DISTINCT ON (o.series_id, date_trunc('month', o.k::date)) o.series_id, date_trunc('month', o.k::date)::date, o.v1::numeric
FROM stg_obs o JOIN stg_series s USING (series_id) WHERE s.source = 'FRED' AND o.v1 <> '.'
ORDER BY o.series_id, date_trunc('month', o.k::date), o.k DESC;
-- ECOS: monthly cycle gives the month, daily cycle the last day with a value
INSERT INTO s_lv
SELECT o.series_id, to_date(o.k || '01', 'YYYYMMDD'), o.v1::numeric
FROM stg_obs o JOIN stg_series s USING (series_id) WHERE s.source = 'ECOS' AND s.observation = 'MONTH_AVG';
INSERT INTO s_lv
SELECT DISTINCT ON (o.series_id, date_trunc('month', to_date(o.k, 'YYYYMMDD'))) o.series_id,
       date_trunc('month', to_date(o.k, 'YYYYMMDD'))::date, o.v1::numeric
FROM stg_obs o JOIN stg_series s USING (series_id) WHERE s.source = 'ECOS' AND s.observation = 'MONTH_END'
ORDER BY o.series_id, date_trunc('month', to_date(o.k, 'YYYYMMDD')), o.k DESC;
-- World Bank: monthly averages
INSERT INTO s_lv
SELECT o.series_id, (o.k || '-01')::date, o.v1::numeric FROM stg_obs o JOIN stg_series s USING (series_id) WHERE s.source = 'WB';

CREATE TEMP TABLE s_ret (series_id text, m date, r numeric NOT NULL, PRIMARY KEY (series_id, m));
CREATE TEMP TABLE s_done (series_id text PRIMARY KEY);

-- level series (PRICE / TOTAL): ratio of consecutive months
INSERT INTO s_ret
SELECT c.series_id, c.m, c.level / p.level - 1
FROM s_lv c JOIN stg_series s ON s.series_id = c.series_id AND s.basis IN ('PRICE', 'TOTAL')
JOIN s_lv p ON p.series_id = c.series_id AND p.m = (c.m - interval '1 month')::date
WHERE c.m IN (SELECT m FROM p_month);
-- pool assets' own actual returns
INSERT INTO s_ret
SELECT s.series_id, x.m, x.r
FROM stg_series s JOIN actual x ON x.market = split_part(s.source_code, ':', 1) AND x.ticker = split_part(s.source_code, ':', 2)
WHERE s.source = 'ASSET';
INSERT INTO s_done SELECT series_id FROM stg_series WHERE source IN ('YF', 'FRED', 'ECOS', 'WB', 'ASSET') AND basis <> 'RATE';

-- DERIVED formulas: same definitions as tools/fetch/derived.py (the parity test compares them cell by cell)
CREATE FUNCTION pg_temp.par_bond_return(y_prev numeric, y_now numeric, yrs int) RETURNS numeric LANGUAGE plpgsql IMMUTABLE AS $f$
DECLARE
    n int := 2 * yrs;
    j numeric;
    c numeric;
    disc numeric;
    ann numeric;
BEGIN
    IF yrs < 1 OR y_prev <= -1 OR y_now <= -1 THEN
        RAISE EXCEPTION 'PAR_BOND_TR: maturity must be at least 1 year and yields above -100 %%';
    END IF;
    j := y_now / 2;
    c := y_prev / 2;
    disc := power(1 + j, -n);
    IF j = 0 THEN ann := n; ELSE ann := (1 - disc) / j; END IF;
    RETURN y_prev / 12 + (c * ann + disc - 1);
END $f$;

DO $$
DECLARE
    s record;
    f text[];
    args text[];
    ready boolean;
    progress boolean := true;
    pending int;
BEGIN
    WHILE progress LOOP
        progress := false;
        FOR s IN SELECT * FROM stg_series WHERE source = 'DERIVED' AND series_id NOT IN (SELECT series_id FROM s_done) ORDER BY series_id LOOP
            f := regexp_match(s.formula, '^([A-Z0-9_]+)\(([A-Za-z0-9_,]*)\)$');
            IF f IS NULL THEN RAISE EXCEPTION 'build_full: series % has an unreadable formula', s.series_id; END IF;
            args := string_to_array(f[2], ',');
            IF f[1] IN ('PAR_BOND_TR', 'CASH_ACCRUAL') THEN
                IF NOT EXISTS (SELECT 1 FROM stg_series r WHERE r.series_id = args[1] AND r.basis = 'RATE') THEN
                    RAISE EXCEPTION 'build_full: % reads a RATE series, not %', f[1], args[1];
                END IF;
                ready := true;
            ELSIF f[1] IN ('MIX_50_50', 'MIX_50_50_FX', 'MIX_W') THEN
                IF NOT EXISTS (SELECT 1 FROM stg_series r WHERE r.series_id = args[1] AND r.basis <> 'RATE')
                   OR NOT EXISTS (SELECT 1 FROM stg_series r WHERE r.series_id = args[2] AND r.basis <> 'RATE') THEN
                    RAISE EXCEPTION 'build_full: % of series % names an unknown or RATE input', f[1], s.series_id;
                END IF;
                ready := EXISTS (SELECT 1 FROM s_done WHERE series_id = args[1]) AND EXISTS (SELECT 1 FROM s_done WHERE series_id = args[2]);
            ELSE
                RAISE EXCEPTION 'build_full: series % uses the unknown formula %', s.series_id, f[1];
            END IF;
            IF NOT ready THEN CONTINUE; END IF;

            IF f[1] = 'PAR_BOND_TR' THEN
                INSERT INTO s_ret
                SELECT s.series_id, c.m, pg_temp.par_bond_return(p.level / 100, c.level / 100, args[2]::int)
                FROM s_lv c JOIN s_lv p ON p.series_id = c.series_id AND p.m = (c.m - interval '1 month')::date
                WHERE c.series_id = args[1] AND c.m IN (SELECT m FROM p_month);
            ELSIF f[1] = 'CASH_ACCRUAL' THEN
                INSERT INTO s_ret
                SELECT s.series_id, c.m, (p.level / 100) / 12
                FROM s_lv c JOIN s_lv p ON p.series_id = c.series_id AND p.m = (c.m - interval '1 month')::date
                WHERE c.series_id = args[1] AND c.m IN (SELECT m FROM p_month);
            ELSIF f[1] = 'MIX_50_50' THEN
                INSERT INTO s_ret
                SELECT s.series_id, a.m, 0.5 * a.r + 0.5 * b.r
                FROM s_ret a JOIN s_ret b ON b.m = a.m AND b.series_id = args[2] WHERE a.series_id = args[1];
            ELSIF f[1] = 'MIX_W' THEN
                IF NOT (args[3] ~ '^\d+$' AND args[3]::int BETWEEN 1 AND 99) THEN
                    RAISE EXCEPTION 'build_full: MIX_W of % needs a whole percent weight 1-99', s.series_id;
                END IF;
                INSERT INTO s_ret
                SELECT s.series_id, a.m, (args[3]::numeric / 100) * a.r + (1 - args[3]::numeric / 100) * b.r
                FROM s_ret a JOIN s_ret b ON b.m = a.m AND b.series_id = args[2] WHERE a.series_id = args[1];
            ELSE  -- MIX_50_50_FX: only the USD leg is converted to won
                INSERT INTO s_ret
                SELECT s.series_id, a.m, 0.5 * ((1 + a.r) * (1 + x.fx_change) - 1) + 0.5 * b.r
                FROM s_ret a JOIN s_ret b ON b.m = a.m AND b.series_id = args[2] JOIN fx_calc x ON x.m = a.m
                WHERE a.series_id = args[1];
            END IF;
            INSERT INTO s_done VALUES (s.series_id);
            progress := true;
        END LOOP;
    END LOOP;
    SELECT count(*) INTO pending FROM stg_series WHERE source = 'DERIVED' AND series_id NOT IN (SELECT series_id FROM s_done);
    IF pending > 0 THEN RAISE EXCEPTION 'build_full: % derived series could not be resolved (cycle or missing input)', pending; END IF;
END $$;

-- 7. Fill the cells without an actual return ---------------------------------------------------------
CREATE TEMP TABLE ladder AS
SELECT m.market, m.ticker, m.priority::int AS priority, m.series_id, m.to_krw = 'Y' AS to_krw, m.fee_applied = 'Y' AS fee_applied,
       m.annual_fee::numeric AS annual_fee,
       (s.first_month || '-01')::date AS s_first, (s.last_month || '-01')::date AS s_last
FROM stg_map m JOIN stg_series s USING (series_id);

CREATE TEMP TABLE fill AS
SELECT DISTINCT ON (c.market, c.ticker, c.m) c.market, c.ticker, c.m, l.series_id, l.to_krw, l.fee_applied, l.annual_fee
FROM cell c
JOIN ladder l ON l.market = c.market AND l.ticker = c.ticker AND c.m BETWEEN l.s_first AND l.s_last
WHERE NOT EXISTS (SELECT 1 FROM actual x WHERE x.market = c.market AND x.ticker = c.ticker AND x.m = c.m)
ORDER BY c.market, c.ticker, c.m, l.priority;

INSERT INTO gap (kind, market, ticker, m, detail)
SELECT 'NO_ACTUAL_NO_SERIES', c.market, c.ticker, c.m, 'no actual return and no ladder series covers the month'
FROM cell c
WHERE NOT EXISTS (SELECT 1 FROM actual x WHERE x.market = c.market AND x.ticker = c.ticker AND x.m = c.m)
  AND NOT EXISTS (SELECT 1 FROM fill f WHERE f.market = c.market AND f.ticker = c.ticker AND f.m = c.m);

INSERT INTO gap (kind, market, ticker, m, detail)
SELECT 'SERIES_VALUE_MISSING', f.market, f.ticker, f.m, f.series_id
FROM fill f WHERE NOT EXISTS (SELECT 1 FROM s_ret r WHERE r.series_id = f.series_id AND r.m = f.m);

INSERT INTO gap (kind, market, ticker, m, detail)
SELECT 'FEE_PENDING', f.market, f.ticker, f.m, f.series_id
FROM fill f WHERE f.fee_applied AND f.annual_fee IS NULL;

INSERT INTO gap (kind, market, ticker, m, detail)
SELECT 'FX_MISSING_FOR_PROXY', f.market, f.ticker, f.m, f.series_id
FROM fill f WHERE f.to_krw AND NOT EXISTS (SELECT 1 FROM fx_calc x WHERE x.m = f.m);

CREATE TEMP TABLE proxy_ret AS
SELECT f.market, f.ticker, f.m, f.series_id,
       (CASE WHEN f.to_krw THEN (1 + r.r) * (1 + x.fx_change) - 1 ELSE r.r END)
       - CASE WHEN f.fee_applied THEN f.annual_fee / 12 ELSE 0 END AS r
FROM fill f
JOIN s_ret r ON r.series_id = f.series_id AND r.m = f.m
LEFT JOIN fx_calc x ON x.m = f.m
WHERE (NOT f.to_krw OR x.m IS NOT NULL) AND (NOT f.fee_applied OR f.annual_fee IS NOT NULL);

-- a stock month from 2007-01 must be actual (PLAN 4.7, erd H7), even where a series could fill it
INSERT INTO gap (kind, market, ticker, m, detail)
SELECT 'STOCK_NOT_ACTUAL_FROM_2007', c.market, c.ticker, c.m, 'a stock has no actual return in the main period'
FROM cell c
WHERE c.asset_type = 'STOCK' AND c.m >= '2007-01-01'
  AND NOT EXISTS (SELECT 1 FROM actual x WHERE x.market = c.market AND x.ticker = c.ticker AND x.m = c.m);

CREATE TEMP TABLE ret_final AS
SELECT x.market, x.ticker, x.m, x.r, 'ACTUAL'::text AS return_kind, NULL::text AS series_id FROM actual x
UNION ALL
SELECT p.market, p.ticker, p.m, p.r, 'PROXY_INDEX', p.series_id FROM proxy_ret p;

INSERT INTO gap (kind, market, ticker, m, detail)
SELECT 'RETURN_BELOW_MINUS_100_PERCENT', market, ticker, m, return_kind FROM ret_final WHERE r < -1;

-- 8. Base-date price, FX and rank --------------------------------------------------------------------
CREATE TEMP TABLE base_fx AS
SELECT b.base_date, o.d AS obs_date, o.x FROM p_base b
JOIN LATERAL (SELECT d, x FROM fx_obs WHERE d <= b.base_date ORDER BY d DESC LIMIT 1) o ON true;

CREATE TEMP TABLE pos_calc AS
SELECT a.market, a.ticker, b.base_date,
       (SELECT d.close_raw::numeric FROM stg_daily d
         WHERE d.market = a.market AND upper(d.ticker) = upper(a.ticker) AND d.trade_date = b.base_date::text AND d.close_raw IS NOT NULL
         ORDER BY d.source LIMIT 1) AS base_price,
       CASE a.market WHEN 'KR' THEN 1 ELSE f.x END AS base_fx_rate,
       CASE WHEN a.asset_type = 'STOCK' THEN pl.mcap_rank_public END AS market_cap_rank_text
FROM a_asset a CROSS JOIN p_base b
LEFT JOIN base_fx f ON f.base_date = b.base_date
LEFT JOIN stg_pool pl ON pl.market = a.market AND pl.ticker = a.ticker AND pl.status = 'SELECTED';

INSERT INTO gap (kind, market, ticker, detail)
SELECT 'BASE_PRICE_OR_FX_MISSING', market, ticker, 'close_raw on the base date or a DEXKOUS observation is missing' FROM pos_calc
WHERE base_price IS NULL OR base_fx_rate IS NULL;
INSERT INTO gap (kind, market, ticker, detail)
SELECT 'MARKET_CAP_RANK_MISSING', p.market, p.ticker, 'a stock needs mcap_rank_public in pool_proposal.csv' FROM pos_calc p
JOIN a_asset a USING (market, ticker) WHERE a.asset_type = 'STOCK' AND (p.market_cap_rank_text IS NULL OR p.market_cap_rank_text !~ '^\d+$');

-- 9. Verdict -----------------------------------------------------------------------------------------
DO $$
DECLARE
    v_n bigint;
    v_assets bigint;
    v_months bigint;
    v_dups bigint;
BEGIN
    SELECT count(*) INTO v_assets FROM a_asset;
    SELECT count(*) INTO v_months FROM p_month;
    SELECT count(*) - count(DISTINCT (market, ticker, m)) INTO v_dups FROM ret_final;
    IF v_dups > 0 THEN RAISE EXCEPTION 'build_full: % duplicate (asset, month) rows', v_dups; END IF;
    IF (SELECT count(*) FROM ret_final) < v_assets * v_months AND NOT EXISTS (SELECT 1 FROM gap) THEN
        RAISE EXCEPTION 'build_full: internal error, % of % cells have a value but no gap was recorded', (SELECT count(*) FROM ret_final), v_assets * v_months;
    END IF;
    IF (SELECT count(*) FROM ret_final) > v_assets * v_months THEN
        RAISE EXCEPTION 'build_full: more rows than cells';
    END IF;
END $$;

\if :is_partial
  \echo 'build_full: partial mode - gaps by kind (nothing is written)'
  SELECT kind, count(*) AS rows, count(DISTINCT market || ':' || ticker) AS assets, min(m) AS first_month, max(m) AS last_month,
         (array_agg(DISTINCT coalesce(market || ':' || ticker, detail)))[1:5] AS examples
  FROM gap GROUP BY kind ORDER BY kind;
  SELECT 'cells' AS what, (SELECT count(*) FROM a_asset) * (SELECT count(*) FROM p_month) AS total,
         (SELECT count(*) FROM actual) AS actual, (SELECT count(*) FROM proxy_ret) AS proxy_filled,
         (SELECT count(DISTINCT (market, ticker, m)) FROM gap WHERE market IS NOT NULL AND m IS NOT NULL) AS cells_with_a_gap;
  ROLLBACK;
  \quit
\endif

DO $$
DECLARE
    v_msg text;
BEGIN
    IF EXISTS (SELECT 1 FROM gap) THEN
        SELECT string_agg(format('%s: %s rows, %s assets, e.g. %s', kind, n, assets, ex), E'\n' ORDER BY kind) INTO v_msg
        FROM (SELECT kind, count(*) AS n, count(DISTINCT market || ':' || ticker) AS assets,
                     (array_agg(coalesce(market || ':' || ticker || ' ' || to_char(m, 'YYYY-MM'), detail) ORDER BY market, ticker, m))[1] AS ex
              FROM gap GROUP BY kind) s;
        RAISE EXCEPTION E'build_full: the build stops, nothing was written. Use -v partial=1 to list every gap.\n%', v_msg;
    END IF;
END $$;

-- 10. Everything passed: write the five files ---------------------------------------------------------
\set f_month_block :outdir '/month_block.csv'
\set f_fx_month :outdir '/fx_month.csv'
\set f_position :outdir '/asset_position.csv'
\set f_return :outdir '/asset_month_return.csv'
\set f_lineage :outdir '/lineage.csv'

\o :f_month_block
COPY (SELECT to_char(m, 'YYYY-MM-DD') AS month_start FROM p_month ORDER BY m) TO STDOUT WITH (FORMAT csv, HEADER);
\o
\o :f_fx_month
COPY (SELECT to_char(m, 'YYYY-MM-DD') AS month_start, 'USD' AS currency, ROUND(fx_change, 8)::text AS fx_change FROM fx_calc ORDER BY m) TO STDOUT WITH (FORMAT csv, HEADER);
\o
\o :f_position
COPY (SELECT market, ticker, to_char(base_date, 'YYYY-MM-DD') AS base_date, ROUND(base_price, 4)::text AS base_price,
             ROUND(base_fx_rate, 4)::text AS base_fx_rate, market_cap_rank_text AS market_cap_rank
      FROM pos_calc ORDER BY market COLLATE "C", ticker COLLATE "C") TO STDOUT WITH (FORMAT csv, HEADER);
\o
\o :f_return
COPY (SELECT market, ticker, to_char(m, 'YYYY-MM-DD') AS month_start, ROUND(r, 8)::text AS local_return, return_kind
      FROM ret_final ORDER BY market COLLATE "C", ticker COLLATE "C", m) TO STDOUT WITH (FORMAT csv, HEADER);
\o
\o :f_lineage
COPY (SELECT market, ticker, to_char(m, 'YYYY-MM-DD') AS month_start, return_kind, series_id
      FROM ret_final ORDER BY market COLLATE "C", ticker COLLATE "C", m) TO STDOUT WITH (FORMAT csv, HEADER);
\o

ROLLBACK;
