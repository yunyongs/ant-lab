-- =====================================================================
-- my Fund Manager Phase 0 — second judged base date drill (P0-10 follow-up, #53)
-- Why: the sample has one base date and no real month after it, so the per-base-date H9 section of db/12_calc_position_check.sql and the base-date-aware
--   parts of db/14_stats_calendar_check.sql cannot be seen working. This drill adds a second base date whose window is in the early sample months
--   (default 2010-01-04, window 2007-01..2009-12), so that real months exist after its base-date month.
-- ★ Throwaway DB only. It inserts and deletes asset_position rows and commits. The guard stops on antlab, antlab_test and antlab_api, and
--   also on any database whose name does not contain "drill" (positive marker, so a differently named owner copy is never modified).
--   Make the copy of a database that already ran 01 → 10 → 11 → 12 → 13 (no other connection on the source while copying; see README):
--     psql -X -d postgres -c "CREATE DATABASE antlab_drill2 TEMPLATE antlab"
--   Run (one psql session, from the repository root):
--     psql -X -q -v ON_ERROR_STOP=1 -v drill=1 -d antlab_drill2 -f db/12_second_base_date_drill.sql
--     psql -X -q -v ON_ERROR_STOP=1 -d antlab_drill2 -f db/12_calc_position.sql                    -- fills the judgment of the new rows
--     psql -X -q -v ON_ERROR_STOP=1 -d antlab_drill2 -f db/12_calc_position_check.sql              -- expected last line PASS
--     psql -X -q -v ON_ERROR_STOP=1 -d antlab_drill2 -f db/14_stats_calendar_check.sql             -- stops at its section 0 (one base date) until #48; PASS only after #48
--   Optional: -v base2=2011-01-03 chooses another second base date (it must be a date with at least 36 earlier months and real months after its month).
-- What it does: guard → assertions (no row at the base date yet) → insert one asset_position row per asset of the existing pool with a synthetic base price
--   (1000.0000) and FX rate (1.0000 for KRW, 1300.0000 for USD) and a NULL judgment and market_cap_rank → read db/12_calc_position_def.sql → delete the
--   inserted rows that are not judgeable at this base date (the 12 check requires every row to be judged) → assert that both market proxies and at least
--   one other asset remain and that real months exist after the base-date month → COMMIT → print counts.
-- Output: counts and one PASS/FAIL column only. No price, return or position value is printed.
-- New syntax: psql \if / \set / \gset for the guard and variable defaults; a temp table to carry values across statements.
-- =====================================================================

-- 1 Guard
\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'P0-10 drill: ON_ERROR_STOP is off. Run: psql -X -q -v ON_ERROR_STOP=1 -v drill=1 -d <throwaway DB> -f db/12_second_base_date_drill.sql'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'P0-10 drill: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
\else
  \echo 'P0-10 drill: it cannot run without -v ON_ERROR_STOP=1'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-10 drill: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
\if :{?drill}
\else
  \echo 'P0-10 drill: -v drill=1 is missing. This file inserts and deletes rows and commits. Run it on a throwaway DB only, with -v drill=1'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-10 drill: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
SELECT (:'drill' = '1') AS dr_flag_ok \gset
\if :dr_flag_ok
\else
  \echo 'P0-10 drill: -v drill=1 is the only accepted value'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-10 drill: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
SELECT (current_database() IN ('antlab', 'antlab_test', 'antlab_api')) AS dr_is_keep \gset
\if :dr_is_keep
  \echo 'P0-10 drill: this is one of the owner databases (antlab, antlab_test, antlab_api). Run it on a copy (CREATE DATABASE antlab_drill2 TEMPLATE antlab)'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-10 drill: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
SELECT (current_database() NOT LIKE '%drill%') AS dr_no_marker \gset
\if :dr_no_marker
  \echo 'P0-10 drill: the database name must contain "drill" (positive marker). Run it on a copy such as antlab_drill2 (CREATE DATABASE antlab_drill2 TEMPLATE antlab)'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-10 drill: not run (see the message above)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
\if :{?base2}
\else
  \set base2 2010-01-04
\endif

SET client_min_messages TO warning;
DROP TABLE IF EXISTS pg_temp.dr_p;
DROP TABLE IF EXISTS pg_temp.dr_counts;
RESET client_min_messages;

-- Parameter and result tables live outside the transaction below, so the counts survive until the final SELECT.
CREATE TEMP TABLE dr_p AS SELECT :'base2'::date AS base2;
CREATE TEMP TABLE dr_counts (item text PRIMARY KEY, n bigint NOT NULL);

BEGIN;

-- 2 Assertions (nothing is written)
DO $$
DECLARE
    v_bad  text;
    v_n    bigint;
    v_bd   date := (SELECT base2 FROM dr_p);
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-10 drill: names do not resolve to public tables (current_schema=%, bad names: %)', current_schema(), COALESCE(v_bad, '-');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM asset_position WHERE system_position IS NOT NULL) THEN
        RAISE EXCEPTION 'P0-10 drill: no judged asset_position row. Run 10 and 12 on this copy first';
    END IF;
    SELECT count(*) INTO v_n FROM asset_position WHERE base_date = v_bd;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-10 drill: % asset_position rows already have the base date (use a fresh copy or another -v base2)', v_n;
    END IF;
END $$;

-- 3 Insert one row per asset of the existing pool (synthetic base price and FX rate, no judgment, no market cap rank)
INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, market_cap_rank)
SELECT a.id, (SELECT base2 FROM dr_p), 1000.0000, CASE a.currency WHEN 'USD' THEN 1300.0000 ELSE 1.0000 END, NULL
FROM asset a
WHERE a.id IN (SELECT DISTINCT asset_id FROM asset_position)
ORDER BY a.id;

INSERT INTO dr_counts SELECT 'inserted', count(*) FROM asset_position WHERE base_date = (SELECT base2 FROM dr_p);

-- 4 Definition (session temp views pos_*)
\ir 12_calc_position_def.sql

-- 5 Delete the inserted rows that are not judgeable at this base date. Rows of other base dates are never touched.
WITH del AS (
    DELETE FROM asset_position p
    USING pos_judge j
    WHERE j.asset_position_id = p.id AND p.base_date = (SELECT base2 FROM dr_p) AND NOT j.judgeable
    RETURNING p.id)
INSERT INTO dr_counts SELECT 'deleted', count(*) FROM del;

INSERT INTO dr_counts SELECT 'kept', count(*) FROM asset_position WHERE base_date = (SELECT base2 FROM dr_p);
INSERT INTO dr_counts
SELECT 'months_after_base_month', count(*) FROM month_block
WHERE month_start >= date_trunc('month', (SELECT base2 FROM dr_p)::timestamp)::date AND month_start < DATE '2090-01-01';

-- 6 Postconditions: both market proxies remain, at least one other asset remains, real months exist after the base-date month
DO $$
DECLARE
    v_bd date := (SELECT base2 FROM dr_p);
    v_n  bigint;
BEGIN
    SELECT count(*) INTO v_n
    FROM asset_position p JOIN asset a ON a.id = p.asset_id
    WHERE p.base_date = v_bd AND (a.market, a.ticker) IN (('KR', '069500'), ('US', 'SPY'));
    IF v_n <> 2 THEN
        RAISE EXCEPTION 'P0-10 drill: a market proxy is not judgeable at this base date (% of 2 kept). Choose another -v base2 (for example 2011-01-03)', v_n;
    END IF;
    IF (SELECT n FROM dr_counts WHERE item = 'kept') < 3 THEN
        RAISE EXCEPTION 'P0-10 drill: fewer than 3 rows kept at this base date. Choose another -v base2';
    END IF;
    IF (SELECT n FROM dr_counts WHERE item = 'months_after_base_month') = 0 THEN
        RAISE EXCEPTION 'P0-10 drill: no real month exists after the base-date month. Choose an earlier -v base2';
    END IF;
END $$;

COMMIT;

-- 7 Report: counts only
SELECT (SELECT n FROM dr_counts WHERE item = 'inserted')                AS rows_inserted,
       (SELECT n FROM dr_counts WHERE item = 'kept')                    AS rows_kept,
       (SELECT n FROM dr_counts WHERE item = 'deleted')                 AS rows_deleted,
       (SELECT n FROM dr_counts WHERE item = 'months_after_base_month') AS months_after_base_month,
       (SELECT count(DISTINCT base_date) FROM asset_position)           AS base_dates_now,
       'PASS'                                                           AS p0_10_drill;
