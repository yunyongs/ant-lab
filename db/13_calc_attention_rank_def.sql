-- =====================================================================
-- my Fund Manager Phase 0 — attention rank lookup R (P0-11, shared by P0-12 and P0-18): session temp view attn_rank
-- Basis: docs/erd.md 5.3 (asset_month_attention, "rank (lookup, not stored)"), D33, D34
-- Not a standalone script. Read it with \ir from db/13_calc_attention_check.sql (6), db/14_stats_calendar_def.sql
--       and, later, the feed generator. One definition, so a change cannot drift between consumers.
-- Creates one session temp view. No transaction statements, no table writes. Computed on read, not stored.
-- Columns: base_date, month_block_id, month_start, asset_id, market, attention_index, attention_rank, n_ranked, is_top_q
--       One row per (base date pool, month, asset) with a non-NULL attention_index; NULL rows are not ranked.
-- Coupling: the 13 and 14 checks mutate exact text in this file (RANK() OVER, WHERE a.attention_index IS NOT NULL,
--       0.10::numeric AS top_q, CEIL(k.top_q * t.n_ranked), JOIN asset s ON s.id = p.asset_id,
--       ORDER BY a.attention_index DESC) AS attention_rank). If you change one, update those mutations, or the check
--       stops with an error instead of FAIL.
-- Rules:
--   R1 rank = RANK() within (base_date, month), index descending; equal indexes share a rank.
--   R2 n_ranked = number of ranked rows in that (base_date, month).
--   R3 is_top_q = attention_rank <= CEIL(q * n_ranked); boundary ties are all in.
--   R4 Reads only the month's own stored attention_index plus four tables: asset, asset_position, asset_month_attention, month_block.
--       A row never depends on a later month, and no user function is called. The 14 check enforces both.
--       Callers add their own filters (for example, the stats board drops the base date's year).
-- The constant q (top share) lives only in CTE k below. Changing it changes erd 5.3/D33 and every consumer.
-- Display order (market, ticker) is not a column here; consumers that need it order by asset.market, asset.ticker (byte order).
-- Only numeric, integer, text and boolean types. No float conversion.
-- psql (command line) is the reference. \ir is a psql meta-command; in VS Code run it from the integrated terminal.
-- =====================================================================

DO $$ BEGIN
    IF to_regclass('pg_temp.attn_rank') IS NOT NULL THEN DROP VIEW pg_temp.attn_rank; END IF;
END $$;

CREATE TEMP VIEW attn_rank AS
WITH k AS (
    SELECT 0.10::numeric AS top_q    -- top share (erd 5.3, draft 10%)
)
SELECT t.base_date, t.month_block_id, t.month_start, t.asset_id, t.market, t.attention_index,
       t.attention_rank::integer AS attention_rank, t.n_ranked::integer AS n_ranked,
       t.attention_rank <= CEIL(k.top_q * t.n_ranked) AS is_top_q
FROM (SELECT p.base_date, a.month_block_id, mb.month_start, a.asset_id, s.market::text AS market, a.attention_index,
             RANK() OVER (PARTITION BY p.base_date, a.month_block_id ORDER BY a.attention_index DESC) AS attention_rank,
             COUNT(*) OVER (PARTITION BY p.base_date, a.month_block_id) AS n_ranked
      FROM asset_position p
      JOIN asset s ON s.id = p.asset_id
      JOIN asset_month_attention a ON a.asset_id = p.asset_id
      JOIN month_block mb ON mb.id = a.month_block_id
      WHERE a.attention_index IS NOT NULL) t
CROSS JOIN k;
