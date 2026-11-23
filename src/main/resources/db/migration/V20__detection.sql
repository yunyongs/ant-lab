-- AG-06 PR G3: the model detection test results (PLAN §4.16, report only). One detection_test row is one window of one
-- imported detection-summary/1 file; its five metrics are detection_metric rows. Per-month model answers stay in the
-- producer's git-ignored files and are never stored (erd 16). Both tables are append-only.

CREATE TABLE detection_test (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY,
    model_id       VARCHAR(200) NOT NULL,
    test_version   VARCHAR(32)  NOT NULL,
    sample_layer   VARCHAR(16)  NOT NULL,
    window_months  SMALLINT     NOT NULL,
    units          INTEGER      NOT NULL,
    source_sha256  CHAR(64)     NOT NULL,
    run_at         TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_detection_test PRIMARY KEY (id),
    CONSTRAINT uq_detection_test_source UNIQUE (model_id, test_version, sample_layer, window_months, source_sha256),
    CONSTRAINT ck_detection_test_model_id CHECK (model_id <> '' AND model_id = btrim(model_id)),
    CONSTRAINT ck_detection_test_test_version CHECK (test_version ~ '^[a-z][a-z0-9-]*/[1-9][0-9]*$'),
    CONSTRAINT ck_detection_test_sample_layer CHECK (sample_layer ~ '^[A-Z0-9][A-Z0-9-]*$'),
    CONSTRAINT ck_detection_test_window_months CHECK (window_months IN (1, 3, 6)),
    CONSTRAINT ck_detection_test_units CHECK (units > 0),
    CONSTRAINT ck_detection_test_source_sha256 CHECK (source_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_detection_test_source ON detection_test (source_sha256);
COMMENT ON TABLE detection_test IS '탐지 시험(모델 × 창)';
COMMENT ON COLUMN detection_test.id IS '[내부] 탐지 시험번호';
COMMENT ON COLUMN detection_test.model_id IS '[내부] 모델 id(에이전트 모델 id와 맞춰 읽는다)';
COMMENT ON COLUMN detection_test.test_version IS '[노출] 시험 판(예 detection-month/1)';
COMMENT ON COLUMN detection_test.sample_layer IS '[노출] 표본 층(예 P0-08)';
COMMENT ON COLUMN detection_test.window_months IS '[노출] 보여 준 달 수(1·3·6)';
COMMENT ON COLUMN detection_test.units IS '[노출] 시험 단위 수';
COMMENT ON COLUMN detection_test.source_sha256 IS '[내부] 원본 결과 파일 해시';
COMMENT ON COLUMN detection_test.run_at IS '[노출] 시험 실행 시각';
COMMENT ON COLUMN detection_test.created_at IS '[내부] 기록일시';

CREATE TABLE detection_metric (
    test_id    BIGINT       NOT NULL,
    metric     VARCHAR(16)  NOT NULL,
    hits       INTEGER      NOT NULL,
    units      INTEGER      NOT NULL,
    base_rate  NUMERIC(9,8) NOT NULL,
    CONSTRAINT pk_detection_metric PRIMARY KEY (test_id, metric),
    CONSTRAINT fk_detection_metric_test FOREIGN KEY (test_id) REFERENCES detection_test (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_detection_metric_metric CHECK (metric IN ('YM_EXACT','YM_WITHIN_3','YEAR','KR_NEXT','US_NEXT')),
    CONSTRAINT ck_detection_metric_units CHECK (units > 0),
    CONSTRAINT ck_detection_metric_hits CHECK (hits >= 0 AND hits <= units),
    CONSTRAINT ck_detection_metric_base_rate CHECK (base_rate >= 0 AND base_rate <= 1 AND base_rate <> 'NaN'::numeric)
);
COMMENT ON TABLE detection_metric IS '탐지 시험 지표';
COMMENT ON COLUMN detection_metric.test_id IS '[내부] 탐지 시험번호';
COMMENT ON COLUMN detection_metric.metric IS '[노출] 지표(연월 정확·3개월 안·연도·한국 다음 달·미국 다음 달)';
COMMENT ON COLUMN detection_metric.hits IS '[노출] 맞힌 수';
COMMENT ON COLUMN detection_metric.units IS '[노출] 채점한 단위 수';
COMMENT ON COLUMN detection_metric.base_rate IS '[노출] 우연 기준 적중률';
