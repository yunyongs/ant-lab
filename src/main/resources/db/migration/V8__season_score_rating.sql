-- Season score and manager rating: one immutable score row per finished season, one immutable rating row per counted season.
-- Both tables are append-only. A formula change is a new score_version for new rows; old rows are never rewritten.

CREATE TABLE season_score (
    season_id               BIGINT        NOT NULL,
    member_id               BIGINT        NOT NULL,
    score_version           VARCHAR(20)   NOT NULL,
    score                   NUMERIC(5,2),
    result_score            NUMERIC(5,2),
    process_score           NUMERIC(5,2)  NOT NULL,
    sharpe                  NUMERIC(14,8) NOT NULL,
    bench_sharpe            NUMERIC(14,8) NOT NULL,
    sharpe_diff             NUMERIC(14,8) NOT NULL,
    sharpe_diff_percentile  NUMERIC(9,8),
    luck_percentile         NUMERIC(9,8),
    risk_score              NUMERIC(5,2),
    discipline_score        NUMERIC(5,2),
    diversification_score   NUMERIC(5,2),
    turnover_score          NUMERIC(5,2),
    consistency_score       NUMERIC(5,2),
    formation_score         NUMERIC(5,2),
    role_score              NUMERIC(5,2),
    noise_score             NUMERIC(5,2),
    nudge_score             NUMERIC(5,2),
    process_items           SMALLINT      NOT NULL,
    rating_exclusion        VARCHAR(11),
    inputs                  JSONB         NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_season_score PRIMARY KEY (season_id),
    CONSTRAINT fk_season_score_season FOREIGN KEY (season_id, member_id) REFERENCES season (id, member_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_season_score_version CHECK (score_version <> ''),
    CONSTRAINT ck_season_score_score CHECK (score IS NULL OR (score >= 0 AND score <= 100 AND score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_result CHECK (
        result_score IS NULL OR (result_score >= 0 AND result_score <= 100 AND result_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_process CHECK (
        process_score >= 0 AND process_score <= 100 AND process_score <> 'NaN'::numeric),
    CONSTRAINT ck_season_score_score_pair CHECK ((score IS NULL) = (result_score IS NULL)),
    CONSTRAINT ck_season_score_sharpe CHECK (
        sharpe <> 'NaN'::numeric AND bench_sharpe <> 'NaN'::numeric AND sharpe_diff <> 'NaN'::numeric),
    CONSTRAINT ck_season_score_diff_percentile CHECK (
        sharpe_diff_percentile IS NULL
        OR (sharpe_diff_percentile >= 0 AND sharpe_diff_percentile <= 1 AND sharpe_diff_percentile <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_luck_percentile CHECK (
        luck_percentile IS NULL
        OR (luck_percentile >= 0 AND luck_percentile <= 1 AND luck_percentile <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_percentile_pair CHECK (
        (sharpe_diff_percentile IS NULL) = (luck_percentile IS NULL)
        AND (sharpe_diff_percentile IS NULL) = (result_score IS NULL)),
    CONSTRAINT ck_season_score_risk CHECK (
        risk_score IS NULL OR (risk_score >= 0 AND risk_score <= 100 AND risk_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_discipline CHECK (
        discipline_score IS NULL OR (discipline_score >= 0 AND discipline_score <= 100 AND discipline_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_diversification CHECK (
        diversification_score IS NULL
        OR (diversification_score >= 0 AND diversification_score <= 100 AND diversification_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_turnover CHECK (
        turnover_score IS NULL OR (turnover_score >= 0 AND turnover_score <= 100 AND turnover_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_consistency CHECK (
        consistency_score IS NULL
        OR (consistency_score >= 0 AND consistency_score <= 100 AND consistency_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_formation CHECK (
        formation_score IS NULL OR (formation_score >= 0 AND formation_score <= 100 AND formation_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_role CHECK (
        role_score IS NULL OR (role_score >= 0 AND role_score <= 100 AND role_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_noise CHECK (
        noise_score IS NULL OR (noise_score >= 0 AND noise_score <= 100 AND noise_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_nudge CHECK (
        nudge_score IS NULL OR (nudge_score >= 0 AND nudge_score <= 100 AND nudge_score <> 'NaN'::numeric)),
    CONSTRAINT ck_season_score_process_items CHECK (
        process_items BETWEEN 1 AND 9
        AND process_items = num_nonnulls(risk_score, discipline_score, diversification_score, turnover_score,
            consistency_score, formation_score, role_score, noise_score, nudge_score)),
    CONSTRAINT ck_season_score_rating_exclusion CHECK (rating_exclusion IN ('NOT_STARTED','NO_SCORE')),
    CONSTRAINT ck_season_score_no_score CHECK (score IS NOT NULL OR rating_exclusion IS NOT NULL),
    CONSTRAINT ck_season_score_no_score_null CHECK (rating_exclusion IS DISTINCT FROM 'NO_SCORE' OR score IS NULL),
    CONSTRAINT ck_season_score_inputs CHECK (jsonb_typeof(inputs) = 'object')
);
CREATE INDEX idx_season_score_member ON season_score (member_id);
COMMENT ON TABLE season_score IS '시즌 점수';
COMMENT ON COLUMN season_score.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN season_score.member_id IS '[내부] 회원번호';
COMMENT ON COLUMN season_score.score_version IS '[노출] 점수 공식 버전';
COMMENT ON COLUMN season_score.score IS '[노출] 시즌 점수';
COMMENT ON COLUMN season_score.result_score IS '[노출] 결과 점수';
COMMENT ON COLUMN season_score.process_score IS '[노출] 과정 점수';
COMMENT ON COLUMN season_score.sharpe IS '[노출] 샤프(월 단위)';
COMMENT ON COLUMN season_score.bench_sharpe IS '[노출] 기준선 샤프(월 단위)';
COMMENT ON COLUMN season_score.sharpe_diff IS '[노출] 샤프 차이';
COMMENT ON COLUMN season_score.sharpe_diff_percentile IS '[노출] 샤프 차이 경로 백분위';
COMMENT ON COLUMN season_score.luck_percentile IS '[노출] 운 백분위';
COMMENT ON COLUMN season_score.risk_score IS '[노출] 위험 관리 점수';
COMMENT ON COLUMN season_score.discipline_score IS '[노출] 매매 규율 점수';
COMMENT ON COLUMN season_score.diversification_score IS '[노출] 분산 점수';
COMMENT ON COLUMN season_score.turnover_score IS '[노출] 회전율 점수';
COMMENT ON COLUMN season_score.consistency_score IS '[노출] 일관성 점수';
COMMENT ON COLUMN season_score.formation_score IS '[노출] 포메이션 준수 점수';
COMMENT ON COLUMN season_score.role_score IS '[노출] 역할 수행 점수';
COMMENT ON COLUMN season_score.noise_score IS '[노출] 소음 반응 점수';
COMMENT ON COLUMN season_score.nudge_score IS '[노출] 넛지 의존 점수';
COMMENT ON COLUMN season_score.process_items IS '[노출] 값이 있는 과정 항목 수';
COMMENT ON COLUMN season_score.rating_exclusion IS '[노출] 레이팅 제외 사유';
COMMENT ON COLUMN season_score.inputs IS '[내부] 계산에 쓴 입력';
COMMENT ON COLUMN season_score.created_at IS '[내부] 기록일시';

CREATE TABLE rating_history (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY,
    member_id     BIGINT        NOT NULL,
    pillar        VARCHAR(10)   NOT NULL,
    seq_no        INTEGER       NOT NULL,
    season_id     BIGINT        NOT NULL,
    rating        NUMERIC(5,2)  NOT NULL,
    seasons_used  SMALLINT      NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_rating_history PRIMARY KEY (id),
    CONSTRAINT uq_rating_history_member_pillar_seq UNIQUE (member_id, pillar, seq_no),
    CONSTRAINT uq_rating_history_season UNIQUE (season_id),
    CONSTRAINT fk_rating_history_member FOREIGN KEY (member_id) REFERENCES member (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_rating_history_score FOREIGN KEY (season_id) REFERENCES season_score (season_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_rating_history_pillar CHECK (pillar IN ('MANAGER')),
    CONSTRAINT ck_rating_history_seq_no CHECK (seq_no >= 1),
    CONSTRAINT ck_rating_history_rating CHECK (rating >= 0 AND rating <= 100 AND rating <> 'NaN'::numeric),
    CONSTRAINT ck_rating_history_used CHECK (seasons_used BETWEEN 1 AND 5 AND seasons_used = LEAST(seq_no, 5))
);
COMMENT ON TABLE rating_history IS '레이팅 이력';
COMMENT ON COLUMN rating_history.id IS '[내부] 번호';
COMMENT ON COLUMN rating_history.member_id IS '[내부] 회원번호';
COMMENT ON COLUMN rating_history.pillar IS '[노출] 레이팅 기둥';
COMMENT ON COLUMN rating_history.seq_no IS '[노출] 집계 시즌 순번';
COMMENT ON COLUMN rating_history.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN rating_history.rating IS '[노출] 레이팅';
COMMENT ON COLUMN rating_history.seasons_used IS '[노출] 평균에 쓴 시즌 수';
COMMENT ON COLUMN rating_history.created_at IS '[내부] 기록일시';
