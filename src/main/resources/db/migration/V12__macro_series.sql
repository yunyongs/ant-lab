-- Macro series (M1-07): the series catalog and its observations as vintages (append-only).
-- A later fetch that returns a different value adds a row with the next vintage_no; earlier rows are never changed.
-- realtime_start is the ALFRED vintage date (US revisable series), available_on the lag-rule date (ECOS series, M1-08);
-- a row with neither is dated by the engine's publication rule (the pre-vintage rule, erd 10.6).

ALTER TABLE job_run DROP CONSTRAINT ck_job_run_job_name;
ALTER TABLE job_run ADD CONSTRAINT ck_job_run_job_name CHECK (job_name IN ('KIS_DAILY_PRICE','FRED_SERIES','ECOS_SERIES'));

CREATE TABLE series (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY,
    code             VARCHAR(20)  NOT NULL,
    source           VARCHAR(4)   NOT NULL,
    source_series_id VARCHAR(30)  NOT NULL,
    unit             VARCHAR(20)  NOT NULL,
    frequency        VARCHAR(7)   NOT NULL,
    release_lag_days SMALLINT     NOT NULL,
    revisable        BOOLEAN      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_series PRIMARY KEY (id),
    CONSTRAINT uq_series_code UNIQUE (code),
    CONSTRAINT uq_series_source_series_id UNIQUE (source, source_series_id),
    CONSTRAINT ck_series_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_series_source CHECK (source IN ('FRED','ECOS')),
    CONSTRAINT ck_series_source_series_id_format CHECK (source_series_id ~ '^[A-Za-z0-9/_]+$'),
    CONSTRAINT ck_series_unit_format CHECK (unit ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_series_frequency CHECK (frequency IN ('DAILY','WEEKLY','MONTHLY')),
    CONSTRAINT ck_series_release_lag_days CHECK (release_lag_days BETWEEN 0 AND 60)
);
COMMENT ON TABLE series IS '거시 시계열 목록';
COMMENT ON COLUMN series.id IS '[내부] 시계열번호';
COMMENT ON COLUMN series.code IS '[내부] 시계열 코드';
COMMENT ON COLUMN series.source IS '[내부] 출처';
COMMENT ON COLUMN series.source_series_id IS '[내부] 출처의 시계열 번호';
COMMENT ON COLUMN series.unit IS '[내부] 단위';
COMMENT ON COLUMN series.frequency IS '[내부] 주기';
COMMENT ON COLUMN series.release_lag_days IS '[내부] 발표 시차(일)';
COMMENT ON COLUMN series.revisable IS '[내부] 수정 여부';
COMMENT ON COLUMN series.created_at IS '[내부] 생성일시';

CREATE TABLE series_observation (
    id             BIGINT         GENERATED ALWAYS AS IDENTITY,
    series_id      BIGINT         NOT NULL,
    ref_date       DATE           NOT NULL,
    value          NUMERIC(24,8)  NOT NULL,
    realtime_start DATE,
    available_on   DATE,
    observed_at    TIMESTAMPTZ    NOT NULL,
    vintage_no     SMALLINT       NOT NULL,
    job_run_id     BIGINT         NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_series_observation PRIMARY KEY (id),
    CONSTRAINT uq_series_observation_vintage UNIQUE (series_id, ref_date, vintage_no),
    CONSTRAINT fk_series_observation_series FOREIGN KEY (series_id) REFERENCES series (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_series_observation_job_run FOREIGN KEY (job_run_id) REFERENCES job_run (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_series_observation_value CHECK (value <> 'NaN'::numeric),
    CONSTRAINT ck_series_observation_vintage_no CHECK (vintage_no >= 1),
    CONSTRAINT ck_series_observation_one_date CHECK (realtime_start IS NULL OR available_on IS NULL),
    CONSTRAINT ck_series_observation_realtime_start CHECK (realtime_start IS NULL OR realtime_start >= ref_date),
    CONSTRAINT ck_series_observation_available_on CHECK (available_on IS NULL OR available_on >= ref_date)
);
CREATE UNIQUE INDEX uq_series_observation_realtime ON series_observation (series_id, ref_date, realtime_start)
    WHERE realtime_start IS NOT NULL;
CREATE INDEX idx_series_observation_job_run ON series_observation (job_run_id);
COMMENT ON TABLE series_observation IS '거시 시계열 관측값(시점 기준)';
COMMENT ON COLUMN series_observation.id IS '[내부] 관측번호';
COMMENT ON COLUMN series_observation.series_id IS '[내부] 시계열번호';
COMMENT ON COLUMN series_observation.ref_date IS '[복기 전용] 기준일';
COMMENT ON COLUMN series_observation.value IS '[복기 전용] 값';
COMMENT ON COLUMN series_observation.realtime_start IS '[복기 전용] 빈티지 시작일(ALFRED)';
COMMENT ON COLUMN series_observation.available_on IS '[복기 전용] 사용 가능일(발표 시차 규칙)';
COMMENT ON COLUMN series_observation.observed_at IS '[내부] 관측일시';
COMMENT ON COLUMN series_observation.vintage_no IS '[내부] 빈티지 번호';
COMMENT ON COLUMN series_observation.job_run_id IS '[내부] 실행번호';
COMMENT ON COLUMN series_observation.created_at IS '[내부] 생성일시';
