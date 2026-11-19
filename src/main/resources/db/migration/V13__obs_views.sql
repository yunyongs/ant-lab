-- OBS-01: read-only observation views for Grafana. Schema obs holds views only; no table, no function.
-- The views are the only relations the role grafana_reader may read (besides two public macro tables, see
-- db/roles/grafana_reader.sql). They run with the rights of their owner, so the role never needs a right on the base
-- tables. They carry no prompt or reply text, no free-text error detail, no member credentials and nothing that dates a
-- season path (season_path, month_block, base_date, data_snapshot_date, seed). Money and scores stay NUMERIC.
-- Turn-scoped views show only turns up to season.current_turn_no (a snapshot k exists once the season has reached turn k;
-- turns after it never show, even if a row were written early); score rows show only for FINISHED seasons.
-- security_barrier keeps a predicate typed by the reader from running on base-table rows that a view filters out.

-- A role without CREATE on the database cannot create the schema; db/roles/antlab_owner_app.sql creates it.
DO $obs$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'obs') THEN
        BEGIN
            CREATE SCHEMA obs;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE EXCEPTION 'schema obs does not exist and % cannot create it; run db/roles/antlab_owner_app.sql as a superuser first', current_user;
        END;
    END IF;
END
$obs$;

CREATE VIEW obs.job_run WITH (security_barrier = true) AS
SELECT j.id,
       j.job_name,
       j.run_trigger,
       j.status,
       j.started_at,
       j.finished_at,
       round(extract(epoch FROM (j.finished_at - j.started_at))::numeric, 3) AS duration_s,
       j.request_count,
       j.inserted_count,
       j.revised_count,
       j.unchanged_count,
       j.failed_target_count,
       j.error_code
FROM public.job_run j;

CREATE VIEW obs.series_freshness WITH (security_barrier = true) AS
SELECT s.source,
       s.code AS series_key,
       s.frequency,
       max(o.ref_date) AS last_observation_date,
       count(DISTINCT o.ref_date) AS observation_count,
       max(o.observed_at) AS last_collected_at
FROM public.series s
LEFT JOIN public.series_observation o ON o.series_id = s.id
GROUP BY s.id, s.source, s.code, s.frequency;

CREATE VIEW obs.price_freshness WITH (security_barrier = true) AS
SELECT a.market,
       p.source,
       max(p.trade_date) AS last_price_date,
       count(DISTINCT p.asset_id) AS asset_count
FROM public.asset_daily_price p
JOIN public.asset a ON a.id = p.asset_id
GROUP BY a.market, p.source;

CREATE VIEW obs.agent WITH (security_barrier = true) AS
SELECT ap.member_id AS agent_id,
       ap.persona_key,
       ap.model_route,
       ap.model_id,
       ap.status,
       ap.created_at
FROM public.agent_profile ap;

CREATE VIEW obs.agent_season WITH (security_barrier = true) AS
SELECT s.id AS season_id,
       c.member_id AS agent_id,
       c.persona_key,
       c.persona_version,
       c.model_route,
       c.model_requested,
       s.status,
       s.path_length,
       s.current_turn_no,
       c.budget_calls,
       c.budget_usd,
       c.memory_version,
       c.runner_version,
       c.prompt_template_version,
       s.created_at,
       s.updated_at
FROM public.season s
JOIN public.season_agent_config c ON c.season_id = s.id;

CREATE VIEW obs.agent_call WITH (security_barrier = true) AS
SELECT ac.id,
       ac.season_id,
       ac.turn_no,
       ac.attempt_no,
       ac.call_kind,
       ac.status,
       ac.http_status,
       ac.model_requested,
       ac.model_actual,
       ac.provider,
       ac.tokens_in,
       ac.tokens_out,
       ac.cost_usd,
       ac.latency_ms,
       ac.is_final,
       ac.outcome,
       CASE WHEN jsonb_typeof(ac.rejects) = 'array' THEN jsonb_array_length(ac.rejects) ELSE 0 END AS reject_count,
       ac.created_at
FROM public.agent_call ac
JOIN public.season s ON s.id = ac.season_id AND ac.turn_no <= s.current_turn_no;

-- One row per reject, the code only. A code outside the fixed list (DecisionRejectCode) shows as OTHER, so reply text
-- can never reach a panel. A new code goes into a new migration with CREATE OR REPLACE VIEW obs.agent_call_reject (same
-- columns, so the reader grant survives); this file is never edited after it is applied.
CREATE VIEW obs.agent_call_reject WITH (security_barrier = true) AS
SELECT ac.season_id,
       ac.turn_no,
       CASE WHEN jsonb_typeof(r.item -> 'code') = 'string'
                 AND (r.item ->> 'code') IN ('SCHEMA_INVALID', 'TURN_ZERO_FORMATION', 'FORMATION_UNCHANGED', 'FORMATION_INVALID', 'ASSET_NOT_IN_POOL', 'QUANTITY_INVALID', 'INSUFFICIENT_HOLDING', 'INSUFFICIENT_CASH', 'SINGLE_ASSET_LIMIT', 'POSITION_REQUIRED', 'POSITION_CONFLICT', 'POSITION_ON_SELL', 'SOURCE_NOT_VISIBLE', 'SOURCE_NOT_ALLOWED', 'THESES_NOT_SUPPORTED')
            THEN r.item ->> 'code' ELSE 'OTHER' END AS reject_code
FROM public.agent_call ac
JOIN public.season s ON s.id = ac.season_id AND ac.turn_no <= s.current_turn_no
CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(ac.rejects) = 'array' THEN ac.rejects ELSE '[]'::jsonb END)
     AS r(item);

CREATE VIEW obs.agent_order WITH (security_barrier = true) AS
SELECT o.season_id,
       o.turn_no,
       o.side,
       o.type,
       o.status,
       o.source,
       o.created_at
FROM public.orders o
JOIN public.season_agent_config c ON c.season_id = o.season_id
JOIN public.season s ON s.id = o.season_id AND o.turn_no <= s.current_turn_no;

CREATE VIEW obs.agent_turn WITH (security_barrier = true) AS
SELECT t.season_id,
       t.turn_no,
       t.total_value,
       t.drawdown,
       t.created_at
FROM public.turn t
JOIN public.season_agent_config c ON c.season_id = t.season_id
JOIN public.season s ON s.id = t.season_id AND t.turn_no <= s.current_turn_no;

CREATE VIEW obs.agent_season_score WITH (security_barrier = true) AS
SELECT sc.season_id,
       c.member_id AS agent_id,
       c.persona_key,
       c.model_requested,
       sc.score_version,
       sc.score,
       sc.result_score,
       sc.process_score,
       sc.rating_exclusion,
       sc.created_at
FROM public.season_score sc
JOIN public.season_agent_config c ON c.season_id = sc.season_id
JOIN public.season s ON s.id = sc.season_id AND s.status = 'FINISHED';

CREATE VIEW obs.season_summary WITH (security_barrier = true) AS
SELECT m.kind,
       s.status,
       s.path_length,
       count(*) AS season_count
FROM public.season s
JOIN public.member m ON m.id = s.member_id
GROUP BY m.kind, s.status, s.path_length;
