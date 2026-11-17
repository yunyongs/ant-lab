-- Agent fund managers: member kind, agent profile, per-season agent config, append-only call record.
-- The agent-only rule is enforced by composite foreign keys (erd D29 pattern), not by service code.

-- member kind: agents have no e-mail and no password; humans have both
ALTER TABLE member ADD COLUMN kind VARCHAR(5) NOT NULL DEFAULT 'HUMAN';
ALTER TABLE member ALTER COLUMN email DROP NOT NULL;
ALTER TABLE member ALTER COLUMN password DROP NOT NULL;
ALTER TABLE member ADD CONSTRAINT ck_member_kind CHECK (kind IN ('HUMAN','AGENT'));
ALTER TABLE member ADD CONSTRAINT ck_member_kind_credentials CHECK (
    (kind = 'HUMAN' AND email IS NOT NULL AND password IS NOT NULL)
    OR (kind = 'AGENT' AND email IS NULL AND password IS NULL));
ALTER TABLE member ADD CONSTRAINT ck_member_kind_role CHECK (kind = 'HUMAN' OR role = 'USER');
ALTER TABLE member ADD CONSTRAINT uq_member_id_kind UNIQUE (id, kind);
COMMENT ON COLUMN member.kind IS '[내부] 회원 종류';

-- target of the composite foreign key of season_agent_config
ALTER TABLE season ADD CONSTRAINT uq_season_id_member UNIQUE (id, member_id);

-- agent profile: one row per agent member; the agent is the (owner, persona, model) combination
CREATE TABLE agent_profile (
    member_id        BIGINT       NOT NULL,
    member_kind      VARCHAR(5)   NOT NULL DEFAULT 'AGENT',
    owner_member_id  BIGINT       NOT NULL,
    owner_kind       VARCHAR(5)   NOT NULL DEFAULT 'HUMAN',
    persona_key      VARCHAR(64)  NOT NULL,
    model_route      VARCHAR(10)  NOT NULL,
    model_id         VARCHAR(200) NOT NULL,
    status           VARCHAR(7)   NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_agent_profile PRIMARY KEY (member_id),
    CONSTRAINT uq_agent_profile_identity UNIQUE (owner_member_id, persona_key, model_route, model_id),
    CONSTRAINT fk_agent_profile_member FOREIGN KEY (member_id, member_kind) REFERENCES member (id, kind)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_agent_profile_owner FOREIGN KEY (owner_member_id, owner_kind) REFERENCES member (id, kind)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_agent_profile_member_agent_only CHECK (member_kind = 'AGENT'),
    CONSTRAINT ck_agent_profile_owner_human_only CHECK (owner_kind = 'HUMAN'),
    CONSTRAINT ck_agent_profile_persona_key CHECK (persona_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_agent_profile_model_route CHECK (model_route IN ('OPENROUTER','CLAUDE_CLI','CODEX_CLI','OLLAMA')),
    CONSTRAINT ck_agent_profile_model_id CHECK (model_id <> '' AND model_id = btrim(model_id)),
    CONSTRAINT ck_agent_profile_status CHECK (status IN ('ACTIVE','RETIRED'))
);
COMMENT ON TABLE agent_profile IS '에이전트 프로필';
COMMENT ON COLUMN agent_profile.member_id IS '[내부] 에이전트 회원번호';
COMMENT ON COLUMN agent_profile.member_kind IS '[내부] 에이전트 회원 종류(AGENT 고정)';
COMMENT ON COLUMN agent_profile.owner_member_id IS '[내부] 소유자 회원번호';
COMMENT ON COLUMN agent_profile.owner_kind IS '[내부] 소유자 회원 종류(HUMAN 고정)';
COMMENT ON COLUMN agent_profile.persona_key IS '[내부] 페르소나 키';
COMMENT ON COLUMN agent_profile.model_route IS '[내부] 모델 경로';
COMMENT ON COLUMN agent_profile.model_id IS '[내부] 모델 id';
COMMENT ON COLUMN agent_profile.status IS '[내부] 상태';
COMMENT ON COLUMN agent_profile.created_at IS '[내부] 등록일시';
COMMENT ON COLUMN agent_profile.updated_at IS '[내부] 수정일시';

-- season agent config: written once when an agent season starts
CREATE TABLE season_agent_config (
    season_id               BIGINT        NOT NULL,
    member_id               BIGINT        NOT NULL,
    persona_key             VARCHAR(64)   NOT NULL,
    persona_version         INTEGER       NOT NULL,
    persona_sha256          VARCHAR(64)   NOT NULL,
    model_route             VARCHAR(10)   NOT NULL,
    model_requested         VARCHAR(200)  NOT NULL,
    temperature             NUMERIC(4,3)  NOT NULL,
    model_seed              BIGINT,
    max_tokens              INTEGER       NOT NULL,
    turn_timeout_ms         INTEGER       NOT NULL,
    prompt_template_version VARCHAR(32)   NOT NULL,
    runner_version          VARCHAR(32)   NOT NULL,
    memory_version          INTEGER,
    budget_calls            INTEGER       NOT NULL,
    budget_usd              NUMERIC(19,8) NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_season_agent_config PRIMARY KEY (season_id),
    CONSTRAINT fk_season_agent_config_season FOREIGN KEY (season_id, member_id) REFERENCES season (id, member_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_season_agent_config_agent FOREIGN KEY (member_id) REFERENCES agent_profile (member_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_season_agent_config_persona_key CHECK (persona_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_season_agent_config_persona_version CHECK (persona_version >= 1),
    CONSTRAINT ck_season_agent_config_persona_sha256 CHECK (persona_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_season_agent_config_model_route CHECK (model_route IN ('OPENROUTER','CLAUDE_CLI','CODEX_CLI','OLLAMA')),
    CONSTRAINT ck_season_agent_config_model_requested CHECK (model_requested <> '' AND model_requested = btrim(model_requested)),
    CONSTRAINT ck_season_agent_config_temperature CHECK (temperature >= 0 AND temperature <= 2),
    CONSTRAINT ck_season_agent_config_max_tokens CHECK (max_tokens > 0),
    CONSTRAINT ck_season_agent_config_turn_timeout CHECK (turn_timeout_ms > 0),
    CONSTRAINT ck_season_agent_config_prompt_version CHECK (prompt_template_version <> ''),
    CONSTRAINT ck_season_agent_config_runner_version CHECK (runner_version <> ''),
    CONSTRAINT ck_season_agent_config_memory_version CHECK (memory_version IS NULL OR memory_version >= 1),
    CONSTRAINT ck_season_agent_config_budget_calls CHECK (budget_calls > 0),
    CONSTRAINT ck_season_agent_config_budget_usd CHECK (budget_usd >= 0 AND budget_usd <> 'NaN'::numeric)
);
COMMENT ON TABLE season_agent_config IS '시즌 에이전트 설정';
COMMENT ON COLUMN season_agent_config.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN season_agent_config.member_id IS '[내부] 에이전트 회원번호';
COMMENT ON COLUMN season_agent_config.persona_key IS '[내부] 페르소나 키';
COMMENT ON COLUMN season_agent_config.persona_version IS '[내부] 페르소나 버전';
COMMENT ON COLUMN season_agent_config.persona_sha256 IS '[내부] 페르소나 해시';
COMMENT ON COLUMN season_agent_config.model_route IS '[내부] 모델 경로';
COMMENT ON COLUMN season_agent_config.model_requested IS '[내부] 요청한 모델';
COMMENT ON COLUMN season_agent_config.temperature IS '[내부] 온도';
COMMENT ON COLUMN season_agent_config.model_seed IS '[내부] 모델 시드';
COMMENT ON COLUMN season_agent_config.max_tokens IS '[내부] 최대 토큰';
COMMENT ON COLUMN season_agent_config.turn_timeout_ms IS '[내부] 턴 제한시간(ms)';
COMMENT ON COLUMN season_agent_config.prompt_template_version IS '[내부] 프롬프트 템플릿 버전';
COMMENT ON COLUMN season_agent_config.runner_version IS '[내부] 러너 버전';
COMMENT ON COLUMN season_agent_config.memory_version IS '[내부] 메모리 버전';
COMMENT ON COLUMN season_agent_config.budget_calls IS '[내부] 호출 예산';
COMMENT ON COLUMN season_agent_config.budget_usd IS '[내부] 비용 예산(USD)';
COMMENT ON COLUMN season_agent_config.created_at IS '[내부] 기록일시';

-- agent call: append-only record of every model call of an agent season (the material for replay)
CREATE TABLE agent_call (
    id               BIGINT         GENERATED ALWAYS AS IDENTITY,
    season_id        BIGINT         NOT NULL,
    turn_no          SMALLINT       NOT NULL,
    attempt_no       SMALLINT       NOT NULL,
    call_kind        VARCHAR(13)    NOT NULL,
    ledger_call_id   UUID,
    status           VARCHAR(14)    NOT NULL,
    http_status      SMALLINT,
    model_requested  VARCHAR(200)   NOT NULL,
    model_actual     VARCHAR(200),
    provider         VARCHAR(100),
    request_text     VARCHAR(262144) NOT NULL,
    response_text    VARCHAR(262144),
    parsed           JSONB,
    tokens_in        INTEGER,
    tokens_out       INTEGER,
    cost_usd         NUMERIC(19,8),
    latency_ms       INTEGER        NOT NULL,
    is_final         BOOLEAN        NOT NULL DEFAULT FALSE,
    outcome          VARCHAR(11),
    rejects          JSONB,
    request_sha256   VARCHAR(64),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_agent_call PRIMARY KEY (id),
    CONSTRAINT uq_agent_call_attempt UNIQUE (season_id, turn_no, attempt_no, call_kind),
    CONSTRAINT uq_agent_call_ledger UNIQUE (ledger_call_id),
    CONSTRAINT fk_agent_call_config FOREIGN KEY (season_id) REFERENCES season_agent_config (season_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_agent_call_turn_no CHECK (turn_no >= 0),
    CONSTRAINT ck_agent_call_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT ck_agent_call_call_kind CHECK (call_kind IN ('TURN_DECISION')),
    CONSTRAINT ck_agent_call_status CHECK (status IN ('OK','UPSTREAM','TIMEOUT','LIMITED','POLICY_REFUSED','ERROR','INVALID')),
    CONSTRAINT ck_agent_call_http_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_agent_call_text_size CHECK (
        octet_length(request_text) <= 262144 AND (response_text IS NULL OR octet_length(response_text) <= 262144)),
    CONSTRAINT ck_agent_call_tokens_in CHECK (tokens_in IS NULL OR tokens_in >= 0),
    CONSTRAINT ck_agent_call_tokens_out CHECK (tokens_out IS NULL OR tokens_out >= 0),
    CONSTRAINT ck_agent_call_cost_usd CHECK (cost_usd IS NULL OR (cost_usd >= 0 AND cost_usd <> 'NaN'::numeric)),
    CONSTRAINT ck_agent_call_latency_ms CHECK (latency_ms >= 0),
    CONSTRAINT ck_agent_call_outcome CHECK (outcome IN ('DECISION','NO_RESPONSE','INVALID')),
    CONSTRAINT ck_agent_call_request_sha256 CHECK (request_sha256 IS NULL OR request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_call_final_outcome CHECK (is_final = (outcome IS NOT NULL)),
    CONSTRAINT ck_agent_call_final_hash CHECK (is_final = (request_sha256 IS NOT NULL)),
    CONSTRAINT ck_agent_call_final_rejects CHECK (rejects IS NULL OR is_final)
);
CREATE UNIQUE INDEX uq_agent_call_final_per_turn ON agent_call (season_id, turn_no, call_kind) WHERE is_final;
COMMENT ON TABLE agent_call IS '에이전트 호출';
COMMENT ON COLUMN agent_call.id IS '[내부] 호출번호';
COMMENT ON COLUMN agent_call.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN agent_call.turn_no IS '[내부] 턴 번호';
COMMENT ON COLUMN agent_call.attempt_no IS '[내부] 시도 번호';
COMMENT ON COLUMN agent_call.call_kind IS '[내부] 호출 종류';
COMMENT ON COLUMN agent_call.ledger_call_id IS '[내부] 호출 장부 번호';
COMMENT ON COLUMN agent_call.status IS '[내부] 호출 상태';
COMMENT ON COLUMN agent_call.http_status IS '[내부] HTTP 상태';
COMMENT ON COLUMN agent_call.model_requested IS '[내부] 요청한 모델';
COMMENT ON COLUMN agent_call.model_actual IS '[내부] 실제 모델';
COMMENT ON COLUMN agent_call.provider IS '[내부] 제공자';
COMMENT ON COLUMN agent_call.request_text IS '[내부] 요청 원문';
COMMENT ON COLUMN agent_call.response_text IS '[내부] 응답 원문';
COMMENT ON COLUMN agent_call.parsed IS '[내부] 파싱한 결정';
COMMENT ON COLUMN agent_call.tokens_in IS '[내부] 입력 토큰';
COMMENT ON COLUMN agent_call.tokens_out IS '[내부] 출력 토큰';
COMMENT ON COLUMN agent_call.cost_usd IS '[내부] 비용(USD)';
COMMENT ON COLUMN agent_call.latency_ms IS '[내부] 지연(ms)';
COMMENT ON COLUMN agent_call.is_final IS '[내부] 턴의 최종 결정 여부';
COMMENT ON COLUMN agent_call.outcome IS '[내부] 결정 결과';
COMMENT ON COLUMN agent_call.rejects IS '[내부] 거부 목록';
COMMENT ON COLUMN agent_call.request_sha256 IS '[내부] 요청 해시';
COMMENT ON COLUMN agent_call.created_at IS '[내부] 기록일시';
