-- Projection: at most two immutable rows per season, START (the team after the turn-0 team building, at turn-0 prices)
-- and END (the final team of a finished season). A row holds ratios only; won values are computed when it is read.
-- The table is append-only: a rule or engine change is a new projection_version for new rows, never a rewrite.

CREATE TABLE projection (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY,
    season_id          BIGINT        NOT NULL,
    phase              VARCHAR(5)    NOT NULL,
    turn_no            SMALLINT      NOT NULL,
    total_value        NUMERIC(19,4) NOT NULL,
    horizon_turns      SMALLINT      NOT NULL,
    path_count         INTEGER       NOT NULL,
    request            JSONB         NOT NULL,
    result             JSONB         NOT NULL,
    projection_version VARCHAR(20)   NOT NULL,
    engine_version     VARCHAR(20)   NOT NULL,
    history_digest     VARCHAR(64)   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_projection PRIMARY KEY (id),
    CONSTRAINT uq_projection_season_phase UNIQUE (season_id, phase),
    CONSTRAINT fk_projection_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_projection_phase CHECK (phase IN ('START','END')),
    CONSTRAINT ck_projection_turn_no CHECK (turn_no >= 0),
    CONSTRAINT ck_projection_total_value CHECK (total_value > 0 AND total_value = ROUND(total_value, 0)
        AND total_value <> 'NaN'::numeric),
    CONSTRAINT ck_projection_horizon_turns CHECK (horizon_turns IN (60, 120)),
    CONSTRAINT ck_projection_path_count CHECK (path_count BETWEEN 100 AND 5000),
    CONSTRAINT ck_projection_request CHECK (jsonb_typeof(request) = 'object'),
    CONSTRAINT ck_projection_result CHECK (jsonb_typeof(result) = 'array'),
    CONSTRAINT ck_projection_versions CHECK (projection_version <> '' AND engine_version <> ''),
    CONSTRAINT ck_projection_history_digest CHECK (history_digest ~ '^[0-9a-f]{64}$')
);
COMMENT ON TABLE projection IS '예상 분포';
COMMENT ON COLUMN projection.id IS '[내부] 번호';
COMMENT ON COLUMN projection.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN projection.phase IS '[노출] 시점(시작·끝)';
COMMENT ON COLUMN projection.turn_no IS '[노출] 기준 턴 번호';
COMMENT ON COLUMN projection.total_value IS '[노출] 기준 총 평가액';
COMMENT ON COLUMN projection.horizon_turns IS '[노출] 내다본 턴 수';
COMMENT ON COLUMN projection.path_count IS '[노출] 경로 수';
COMMENT ON COLUMN projection.request IS '[내부] 엔진에 보낸 포트폴리오';
COMMENT ON COLUMN projection.result IS '[노출] 엔진이 돌려준 비율(배수·낙폭·확률)';
COMMENT ON COLUMN projection.projection_version IS '[내부] 예상 분포 버전';
COMMENT ON COLUMN projection.engine_version IS '[내부] 엔진 버전';
COMMENT ON COLUMN projection.history_digest IS '[내부] 이력 요약';
COMMENT ON COLUMN projection.created_at IS '[내부] 기록일시';
