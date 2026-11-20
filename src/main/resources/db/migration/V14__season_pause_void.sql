-- AG-02 A5 (owner decision O-2, 2026-11-20): two more season states for agent seasons.
-- PAUSED: the runner stopped the season at its current turn; paused_at is the start of the 7-day window after which the
--   pause is treated as an abandon (applied lazily by the service, no scheduler). Resume returns to IN_PROGRESS.
-- VOID: the season cannot continue or be trusted (model gone, engine or runner defect); void_reason and void_note are
--   the required defect record. A void season is never scored: ratings and score rows read FINISHED seasons only.
-- Rows are untouched; the new columns are NULL for every existing season.
ALTER TABLE season DROP CONSTRAINT ck_season_status;
ALTER TABLE season ADD CONSTRAINT ck_season_status
    CHECK (status IN ('IN_PROGRESS','ABANDONING','FINISHED','HALTED','PAUSED','VOID'));

ALTER TABLE season ADD COLUMN paused_at TIMESTAMPTZ NULL;
ALTER TABLE season ADD COLUMN void_reason VARCHAR(20) NULL;
ALTER TABLE season ADD COLUMN void_note VARCHAR(500) NULL;

ALTER TABLE season ADD CONSTRAINT ck_season_paused_at
    CHECK ((status = 'PAUSED') = (paused_at IS NOT NULL));
ALTER TABLE season ADD CONSTRAINT ck_season_void_reason
    CHECK (void_reason IN ('MODEL_GONE','ENGINE_DEFECT','RUNNER_DEFECT'));
ALTER TABLE season ADD CONSTRAINT ck_season_void_status
    CHECK ((status = 'VOID') = (void_reason IS NOT NULL));
ALTER TABLE season ADD CONSTRAINT ck_season_void_note
    CHECK ((void_reason IS NULL) = (void_note IS NULL) AND (void_note IS NULL OR btrim(void_note) <> ''));

COMMENT ON COLUMN season.paused_at IS '[내부] 일시 정지 시작 시각';
COMMENT ON COLUMN season.void_reason IS '[내부] 무효 사유';
COMMENT ON COLUMN season.void_note IS '[내부] 무효 결함 메모';

-- A pause older than 7 days is an abandon even when nobody touches the season again (O-2): the two views that group or
-- list seasons by status show it as ABANDONING, the same outcome the service gives on its next action. Same columns,
-- same order and types as V13; only the status expression changes.
CREATE OR REPLACE VIEW obs.agent_season WITH (security_barrier = true) AS
SELECT s.id AS season_id,
       c.member_id AS agent_id,
       c.persona_key,
       c.persona_version,
       c.model_route,
       c.model_requested,
       CAST(CASE WHEN s.status = 'PAUSED' AND s.paused_at + INTERVAL '7 days' <= now()
                 THEN 'ABANDONING' ELSE s.status END AS VARCHAR(11)) AS status,
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

CREATE OR REPLACE VIEW obs.season_summary WITH (security_barrier = true) AS
SELECT m.kind,
       CAST(CASE WHEN s.status = 'PAUSED' AND s.paused_at + INTERVAL '7 days' <= now()
                 THEN 'ABANDONING' ELSE s.status END AS VARCHAR(11)) AS status,
       s.path_length,
       count(*) AS season_count
FROM public.season s
JOIN public.member m ON m.id = s.member_id
GROUP BY m.kind, 2, s.path_length;
