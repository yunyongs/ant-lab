-- AG-05 PR B: agent knowledge accumulation. Two append-only tables hold what an agent wrote about a finished season
-- (agent_reflection, accepted or discarded by the leak filter) and each released version of its operating note
-- (agent_memory). A season pins one version at its start (season_agent_config.memory_version); older versions stay
-- readable because comparisons need them. Only an accepted reflection can make a version (erd D29 pattern).

-- one reflection outcome per season; body only when accepted, the fixed discard codes only when discarded
CREATE TABLE agent_reflection (
    season_id      BIGINT      NOT NULL,
    member_id      BIGINT      NOT NULL,
    outcome        VARCHAR(9)  NOT NULL,
    body           JSONB,
    discard_codes  JSONB,
    filter_version VARCHAR(32) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_agent_reflection PRIMARY KEY (season_id),
    CONSTRAINT uq_agent_reflection_source UNIQUE (season_id, member_id, outcome),
    CONSTRAINT fk_agent_reflection_season FOREIGN KEY (season_id, member_id) REFERENCES season (id, member_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_agent_reflection_agent FOREIGN KEY (member_id) REFERENCES agent_profile (member_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_agent_reflection_outcome CHECK (outcome IN ('ACCEPTED','DISCARDED')),
    CONSTRAINT ck_agent_reflection_body CHECK ((outcome = 'ACCEPTED') = (body IS NOT NULL)),
    CONSTRAINT ck_agent_reflection_body_object CHECK (body IS NULL OR jsonb_typeof(body) = 'object'),
    CONSTRAINT ck_agent_reflection_discard_codes CHECK ((outcome = 'DISCARDED') = (discard_codes IS NOT NULL)),
    CONSTRAINT ck_agent_reflection_discard_codes_array CHECK (discard_codes IS NULL OR jsonb_typeof(discard_codes) = 'array'),
    CONSTRAINT ck_agent_reflection_filter_version CHECK (filter_version <> '')
);
CREATE INDEX idx_agent_reflection_member ON agent_reflection (member_id);
COMMENT ON TABLE agent_reflection IS '에이전트 시즌 복기';
COMMENT ON COLUMN agent_reflection.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN agent_reflection.member_id IS '[내부] 에이전트 회원번호';
COMMENT ON COLUMN agent_reflection.outcome IS '[내부] 결과(채택·폐기)';
COMMENT ON COLUMN agent_reflection.body IS '[노출] 걸러 낸 복기 본문(season-reflection/1)';
COMMENT ON COLUMN agent_reflection.discard_codes IS '[내부] 폐기 사유 코드 목록';
COMMENT ON COLUMN agent_reflection.filter_version IS '[내부] 누출 필터 버전';
COMMENT ON COLUMN agent_reflection.created_at IS '[내부] 기록일시';

-- one row per memory version of an agent; version n+1 is written only when n exists (service rule)
CREATE TABLE agent_memory (
    member_id        BIGINT      NOT NULL,
    version          INTEGER     NOT NULL,
    source_season_id BIGINT      NOT NULL,
    source_outcome   VARCHAR(9)  NOT NULL DEFAULT 'ACCEPTED',
    playbook         JSONB       NOT NULL,
    playbook_carried BOOLEAN     NOT NULL,
    filter_version   VARCHAR(32) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_agent_memory PRIMARY KEY (member_id, version),
    CONSTRAINT uq_agent_memory_source UNIQUE (source_season_id),
    CONSTRAINT fk_agent_memory_agent FOREIGN KEY (member_id) REFERENCES agent_profile (member_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_agent_memory_source FOREIGN KEY (source_season_id, member_id, source_outcome)
        REFERENCES agent_reflection (season_id, member_id, outcome) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_agent_memory_version CHECK (version >= 1),
    CONSTRAINT ck_agent_memory_source_outcome CHECK (source_outcome = 'ACCEPTED'),
    CONSTRAINT ck_agent_memory_playbook_object CHECK (jsonb_typeof(playbook) = 'object'),
    CONSTRAINT ck_agent_memory_filter_version CHECK (filter_version <> '')
);
COMMENT ON TABLE agent_memory IS '에이전트 메모리 버전';
COMMENT ON COLUMN agent_memory.member_id IS '[내부] 에이전트 회원번호';
COMMENT ON COLUMN agent_memory.version IS '[노출] 메모리 버전';
COMMENT ON COLUMN agent_memory.source_season_id IS '[내부] 출처 시즌번호';
COMMENT ON COLUMN agent_memory.source_outcome IS '[내부] 출처 복기 결과(채택 고정)';
COMMENT ON COLUMN agent_memory.playbook IS '[노출] 걸러 낸 운용 노트(operating-note/1)';
COMMENT ON COLUMN agent_memory.playbook_carried IS '[내부] 이전 운용 노트를 그대로 이어 받았는지';
COMMENT ON COLUMN agent_memory.filter_version IS '[내부] 누출 필터 버전';
COMMENT ON COLUMN agent_memory.created_at IS '[내부] 기록일시';

-- a season can pin only a version the agent has; NULL (no memory) is not checked
ALTER TABLE season_agent_config ADD CONSTRAINT fk_season_agent_config_memory
    FOREIGN KEY (member_id, memory_version) REFERENCES agent_memory (member_id, version)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- memory calls (the reflection and the playbook rewrite) are recorded like turn calls
ALTER TABLE agent_call DROP CONSTRAINT ck_agent_call_call_kind;
ALTER TABLE agent_call ADD CONSTRAINT ck_agent_call_call_kind CHECK (call_kind IN ('TURN_DECISION','REFLECTION','PLAYBOOK'));

-- a sibling league plays the same seed as its group root (the seed is copied server-side and never leaves it)
ALTER TABLE league ADD COLUMN seed_league_id BIGINT;
ALTER TABLE league ADD CONSTRAINT fk_league_seed_league FOREIGN KEY (seed_league_id) REFERENCES league (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE league ADD CONSTRAINT ck_league_seed_league_not_self CHECK (seed_league_id IS NULL OR seed_league_id <> id);
CREATE INDEX idx_league_seed_league ON league (seed_league_id);
COMMENT ON COLUMN league.seed_league_id IS '[내부] 같은 시드 묶음의 뿌리 리그번호';
