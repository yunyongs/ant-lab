-- AG-06 PR G1: the composite manager score (PLAN 6.9). manager_score_formula holds the weights as versioned data (a new
-- row only through a migration; the newest adopted_at is the current formula). season_manager_parts holds, per finished
-- season and reference version, the return, risk-adjusted and risk parts the engine ranked against 200 random bots, or
-- the reason the season has none. S and L are computed when read, from the parts and the current weights (erd 17).
-- Both tables are append-only.

CREATE TABLE manager_score_formula (
    version          VARCHAR(20)   NOT NULL,
    w_return         NUMERIC(3,2)  NOT NULL,
    w_risk_adjusted  NUMERIC(3,2)  NOT NULL,
    w_risk           NUMERIC(3,2)  NOT NULL,
    w_process        NUMERIC(3,2)  NOT NULL,
    status           VARCHAR(11)   NOT NULL,
    basis            VARCHAR(200)  NOT NULL,
    adopted_at       TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_manager_score_formula PRIMARY KEY (version),
    CONSTRAINT uq_manager_score_formula_adopted UNIQUE (adopted_at),
    CONSTRAINT ck_manager_score_formula_version CHECK (version ~ '^s[0-9]+-v[1-9][0-9]*$'),
    CONSTRAINT ck_manager_score_formula_weights CHECK (
        w_return BETWEEN 0 AND 1 AND w_return <> 'NaN'::numeric
        AND w_risk_adjusted BETWEEN 0 AND 1 AND w_risk_adjusted <> 'NaN'::numeric
        AND w_risk BETWEEN 0 AND 1 AND w_risk <> 'NaN'::numeric
        AND w_process BETWEEN 0 AND 1 AND w_process <> 'NaN'::numeric),
    CONSTRAINT ck_manager_score_formula_sum CHECK (w_return + w_risk_adjusted + w_risk + w_process = 1),
    CONSTRAINT ck_manager_score_formula_status CHECK (status IN ('PROVISIONAL','FINAL')),
    CONSTRAINT ck_manager_score_formula_basis CHECK (basis <> '' AND basis = btrim(basis))
);
COMMENT ON TABLE manager_score_formula IS '종합 매니저 점수 공식';
COMMENT ON COLUMN manager_score_formula.version IS '[노출] 공식 판(예 s69-v1)';
COMMENT ON COLUMN manager_score_formula.w_return IS '[노출] 수익 부품 가중치';
COMMENT ON COLUMN manager_score_formula.w_risk_adjusted IS '[노출] 위험 조정 부품 가중치';
COMMENT ON COLUMN manager_score_formula.w_risk IS '[노출] 위험 부품 가중치';
COMMENT ON COLUMN manager_score_formula.w_process IS '[노출] 과정 부품 가중치';
COMMENT ON COLUMN manager_score_formula.status IS '[노출] 공식 상태(잠정·확정)';
COMMENT ON COLUMN manager_score_formula.basis IS '[내부] 근거';
COMMENT ON COLUMN manager_score_formula.adopted_at IS '[내부] 채택 시각(가장 늦은 행이 현재 공식)';
COMMENT ON COLUMN manager_score_formula.created_at IS '[내부] 기록일시';

INSERT INTO manager_score_formula (version, w_return, w_risk_adjusted, w_risk, w_process, status, basis, adopted_at)
VALUES ('s69-v1', 0.30, 0.30, 0.15, 0.25, 'PROVISIONAL', 'PLAN 6.9 draft weights; provisional until M3-08/M3-09',
        TIMESTAMPTZ '2026-11-24 00:00:00+09');

CREATE TABLE season_manager_parts (
    season_id              BIGINT         NOT NULL,
    member_id              BIGINT         NOT NULL,
    reference_version      VARCHAR(20)    NOT NULL,
    exclusion              VARCHAR(18),
    reference_key          CHAR(64),
    reference_size         SMALLINT,
    bot_version            VARCHAR(20),
    engine_version         VARCHAR(20),
    excess_cagr            NUMERIC(20,12),
    sharpe_diff            NUMERIC(20,12),
    sortino_diff           NUMERIC(20,12),
    calmar_diff            NUMERIC(20,12),
    mdd                    NUMERIC(20,12),
    bench_mdd              NUMERIC(20,12),
    pct_excess_cagr        NUMERIC(11,8),
    pct_sharpe_diff        NUMERIC(11,8),
    pct_sortino_diff       NUMERIC(11,8),
    pct_calmar_diff        NUMERIC(11,8),
    pct_risk_item          NUMERIC(11,8),
    pct_mdd                NUMERIC(11,8),
    part_return            NUMERIC(11,8),
    part_risk_adjusted     NUMERIC(11,8),
    part_risk              NUMERIC(11,8),
    part_process           NUMERIC(5,2)   NOT NULL,
    process_score_version  VARCHAR(20)    NOT NULL,
    final_multiple         NUMERIC(20,8)  NOT NULL,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_season_manager_parts PRIMARY KEY (season_id, reference_version),
    CONSTRAINT fk_season_manager_parts_score FOREIGN KEY (season_id) REFERENCES season_score (season_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_season_manager_parts_season FOREIGN KEY (season_id, member_id) REFERENCES season (id, member_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_season_manager_parts_reference_version CHECK (reference_version ~ '^ref[0-9]+-v[1-9][0-9]*$'),
    CONSTRAINT ck_season_manager_parts_exclusion CHECK (exclusion IN ('NOT_STARTED','REFERENCE_MISMATCH','NO_REFERENCE')),
    CONSTRAINT ck_season_manager_parts_counted CHECK (
        exclusion IS NOT NULL
        OR num_nonnulls(reference_key, reference_size, bot_version, engine_version, excess_cagr, sharpe_diff, sortino_diff,
            calmar_diff, mdd, bench_mdd, pct_excess_cagr, pct_sharpe_diff, pct_sortino_diff, pct_calmar_diff, pct_mdd,
            part_return, part_risk_adjusted, part_risk) = 18),
    CONSTRAINT ck_season_manager_parts_excluded CHECK (
        exclusion IS NULL
        OR num_nonnulls(reference_key, reference_size, bot_version, engine_version, excess_cagr, sharpe_diff, sortino_diff,
            calmar_diff, mdd, bench_mdd, pct_excess_cagr, pct_sharpe_diff, pct_sortino_diff, pct_calmar_diff, pct_risk_item,
            pct_mdd, part_return, part_risk_adjusted, part_risk) = 0),
    CONSTRAINT ck_season_manager_parts_reference_key CHECK (reference_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_season_manager_parts_reference_size CHECK (reference_size > 0),
    CONSTRAINT ck_season_manager_parts_bot_version CHECK (bot_version ~ '^[a-z]+/[0-9]{1,4}$'),
    CONSTRAINT ck_season_manager_parts_engine_version CHECK (engine_version <> '' AND engine_version = btrim(engine_version)),
    CONSTRAINT ck_season_manager_parts_components CHECK (
        excess_cagr <> 'NaN'::numeric AND sharpe_diff <> 'NaN'::numeric AND sortino_diff <> 'NaN'::numeric
        AND calmar_diff <> 'NaN'::numeric),
    CONSTRAINT ck_season_manager_parts_mdd CHECK (mdd BETWEEN -1 AND 0 AND bench_mdd BETWEEN -1 AND 0),
    CONSTRAINT ck_season_manager_parts_percentiles CHECK (
        pct_excess_cagr BETWEEN 0 AND 100 AND pct_excess_cagr <> 'NaN'::numeric
        AND pct_sharpe_diff BETWEEN 0 AND 100 AND pct_sharpe_diff <> 'NaN'::numeric
        AND pct_sortino_diff BETWEEN 0 AND 100 AND pct_sortino_diff <> 'NaN'::numeric
        AND pct_calmar_diff BETWEEN 0 AND 100 AND pct_calmar_diff <> 'NaN'::numeric
        AND pct_mdd BETWEEN 0 AND 100 AND pct_mdd <> 'NaN'::numeric),
    CONSTRAINT ck_season_manager_parts_risk_item CHECK (
        pct_risk_item BETWEEN 0 AND 100 AND pct_risk_item <> 'NaN'::numeric),
    CONSTRAINT ck_season_manager_parts_parts CHECK (
        part_return BETWEEN 0 AND 100 AND part_return <> 'NaN'::numeric
        AND part_risk_adjusted BETWEEN 0 AND 100 AND part_risk_adjusted <> 'NaN'::numeric
        AND part_risk BETWEEN 0 AND 100 AND part_risk <> 'NaN'::numeric),
    CONSTRAINT ck_season_manager_parts_process CHECK (
        part_process BETWEEN 0 AND 100 AND part_process <> 'NaN'::numeric),
    CONSTRAINT ck_season_manager_parts_process_version CHECK (process_score_version <> ''),
    CONSTRAINT ck_season_manager_parts_final_multiple CHECK (final_multiple >= 0 AND final_multiple <> 'NaN'::numeric)
);
CREATE INDEX idx_season_manager_parts_member ON season_manager_parts (member_id, reference_version);
COMMENT ON TABLE season_manager_parts IS '시즌 매니저 점수 부품';
COMMENT ON COLUMN season_manager_parts.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN season_manager_parts.member_id IS '[내부] 회원번호';
COMMENT ON COLUMN season_manager_parts.reference_version IS '[노출] 기준 집합 판(예 ref69-v1)';
COMMENT ON COLUMN season_manager_parts.exclusion IS '[노출] 제외 사유';
COMMENT ON COLUMN season_manager_parts.reference_key IS '[내부] 기준 집합 키(시즌 식별 정보의 SHA-256)';
COMMENT ON COLUMN season_manager_parts.reference_size IS '[내부] 기준 봇 수';
COMMENT ON COLUMN season_manager_parts.bot_version IS '[내부] 기준 봇 판';
COMMENT ON COLUMN season_manager_parts.engine_version IS '[내부] 엔진 판';
COMMENT ON COLUMN season_manager_parts.excess_cagr IS '[노출] 초과 CAGR';
COMMENT ON COLUMN season_manager_parts.sharpe_diff IS '[노출] 샤프 차이';
COMMENT ON COLUMN season_manager_parts.sortino_diff IS '[노출] 소르티노 차이';
COMMENT ON COLUMN season_manager_parts.calmar_diff IS '[노출] 칼마 차이';
COMMENT ON COLUMN season_manager_parts.mdd IS '[노출] 최대 낙폭';
COMMENT ON COLUMN season_manager_parts.bench_mdd IS '[노출] 기준선 최대 낙폭';
COMMENT ON COLUMN season_manager_parts.pct_excess_cagr IS '[노출] 초과 CAGR 백분위';
COMMENT ON COLUMN season_manager_parts.pct_sharpe_diff IS '[노출] 샤프 차이 백분위';
COMMENT ON COLUMN season_manager_parts.pct_sortino_diff IS '[노출] 소르티노 차이 백분위';
COMMENT ON COLUMN season_manager_parts.pct_calmar_diff IS '[노출] 칼마 차이 백분위';
COMMENT ON COLUMN season_manager_parts.pct_risk_item IS '[노출] 위험 항목 백분위';
COMMENT ON COLUMN season_manager_parts.pct_mdd IS '[노출] 최대 낙폭 백분위';
COMMENT ON COLUMN season_manager_parts.part_return IS '[노출] 수익 부품';
COMMENT ON COLUMN season_manager_parts.part_risk_adjusted IS '[노출] 위험 조정 부품';
COMMENT ON COLUMN season_manager_parts.part_risk IS '[노출] 위험 부품';
COMMENT ON COLUMN season_manager_parts.part_process IS '[노출] 과정 부품(시즌 점수의 과정 점수)';
COMMENT ON COLUMN season_manager_parts.process_score_version IS '[노출] 과정 점수 공식 버전';
COMMENT ON COLUMN season_manager_parts.final_multiple IS '[노출] 최종 평가 배수(표시 전용)';
COMMENT ON COLUMN season_manager_parts.created_at IS '[내부] 기록일시';
