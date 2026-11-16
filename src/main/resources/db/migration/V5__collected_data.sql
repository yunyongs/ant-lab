-- Collected data: the collector run log and raw daily closes stored as vintages (append-only observations).
-- A later fetch that returns a different close adds a row with the next vintage_no; earlier rows are never changed.

CREATE TABLE job_run (
    id                  BIGINT       GENERATED ALWAYS AS IDENTITY,
    job_name            VARCHAR(20)  NOT NULL,
    run_trigger         VARCHAR(9)   NOT NULL,
    status              VARCHAR(9)   NOT NULL,
    started_at          TIMESTAMPTZ  NOT NULL,
    finished_at         TIMESTAMPTZ,
    request_count       INTEGER,
    inserted_count      INTEGER,
    revised_count       INTEGER,
    unchanged_count     INTEGER,
    failed_target_count SMALLINT,
    error_code          VARCHAR(40),
    error_detail        VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_job_run PRIMARY KEY (id),
    CONSTRAINT ck_job_run_job_name CHECK (job_name IN ('KIS_DAILY_PRICE')),
    CONSTRAINT ck_job_run_run_trigger CHECK (run_trigger IN ('MANUAL','SCHEDULED')),
    CONSTRAINT ck_job_run_status CHECK (status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED')),
    CONSTRAINT ck_job_run_finished CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at >= started_at)),
    CONSTRAINT ck_job_run_counts CHECK (
        (status = 'RUNNING'
            AND request_count IS NULL AND inserted_count IS NULL AND revised_count IS NULL
            AND unchanged_count IS NULL AND failed_target_count IS NULL)
        OR (status <> 'RUNNING'
            AND request_count IS NOT NULL AND request_count >= 0
            AND inserted_count IS NOT NULL AND inserted_count >= 0
            AND revised_count IS NOT NULL AND revised_count >= 0
            AND unchanged_count IS NOT NULL AND unchanged_count >= 0
            AND failed_target_count IS NOT NULL AND failed_target_count >= 0)),
    CONSTRAINT ck_job_run_revised_le_inserted CHECK (revised_count <= inserted_count),
    CONSTRAINT ck_job_run_error CHECK ((status IN ('PARTIAL','FAILED')) = (error_code IS NOT NULL)),
    CONSTRAINT ck_job_run_error_code_format CHECK (error_code ~ '^[A-Z][A-Z0-9_]*$')
);
CREATE UNIQUE INDEX uq_job_run_running ON job_run (job_name) WHERE status = 'RUNNING';
CREATE INDEX idx_job_run_job_name_started_at ON job_run (job_name, started_at);
COMMENT ON TABLE job_run IS '수집 실행 기록';
COMMENT ON COLUMN job_run.id IS '[내부] 실행번호';
COMMENT ON COLUMN job_run.job_name IS '[내부] 잡 이름';
COMMENT ON COLUMN job_run.run_trigger IS '[내부] 실행 방식';
COMMENT ON COLUMN job_run.status IS '[내부] 상태';
COMMENT ON COLUMN job_run.started_at IS '[내부] 시작일시';
COMMENT ON COLUMN job_run.finished_at IS '[내부] 종료일시';
COMMENT ON COLUMN job_run.request_count IS '[내부] 요청 수';
COMMENT ON COLUMN job_run.inserted_count IS '[내부] 추가 행 수';
COMMENT ON COLUMN job_run.revised_count IS '[내부] 정정 행 수';
COMMENT ON COLUMN job_run.unchanged_count IS '[내부] 변동 없음 수';
COMMENT ON COLUMN job_run.failed_target_count IS '[내부] 실패 대상 수';
COMMENT ON COLUMN job_run.error_code IS '[내부] 오류 코드';
COMMENT ON COLUMN job_run.error_detail IS '[내부] 오류 설명(가림 처리된 문장)';
COMMENT ON COLUMN job_run.created_at IS '[내부] 생성일시';
COMMENT ON COLUMN job_run.updated_at IS '[내부] 수정일시';

CREATE TABLE asset_daily_price (
    id            BIGINT         GENERATED ALWAYS AS IDENTITY,
    asset_id      BIGINT         NOT NULL,
    trade_date    DATE           NOT NULL,
    price_basis   VARCHAR(8)     NOT NULL,
    source        VARCHAR(3)     NOT NULL,
    exchange_code VARCHAR(3),
    close_price   NUMERIC(19,4)  NOT NULL,
    vintage_no    SMALLINT       NOT NULL,
    observed_at   TIMESTAMPTZ    NOT NULL,
    job_run_id    BIGINT         NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_asset_daily_price PRIMARY KEY (id),
    CONSTRAINT uq_asset_daily_price_vintage UNIQUE (asset_id, trade_date, price_basis, vintage_no),
    CONSTRAINT fk_asset_daily_price_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_asset_daily_price_job_run FOREIGN KEY (job_run_id) REFERENCES job_run (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_asset_daily_price_price_basis CHECK (price_basis IN ('ADJUSTED','RAW')),
    CONSTRAINT ck_asset_daily_price_source CHECK (source IN ('KIS')),
    CONSTRAINT ck_asset_daily_price_exchange_code CHECK (exchange_code IS NULL OR exchange_code IN ('NAS','NYS','AMS')),
    CONSTRAINT ck_asset_daily_price_close_positive CHECK (close_price > 0 AND close_price <> 'NaN'::numeric),
    CONSTRAINT ck_asset_daily_price_vintage_no CHECK (vintage_no >= 1),
    CONSTRAINT ck_asset_daily_price_after_session CHECK (trade_date < (observed_at AT TIME ZONE 'Asia/Seoul')::date)
);
CREATE INDEX idx_asset_daily_price_job_run ON asset_daily_price (job_run_id);
COMMENT ON TABLE asset_daily_price IS '종목 일별 종가(시점 기준)';
COMMENT ON COLUMN asset_daily_price.id IS '[내부] 관측번호';
COMMENT ON COLUMN asset_daily_price.asset_id IS '[내부] 종목번호';
COMMENT ON COLUMN asset_daily_price.trade_date IS '[복기 전용] 거래일';
COMMENT ON COLUMN asset_daily_price.price_basis IS '[내부] 가격 기준';
COMMENT ON COLUMN asset_daily_price.source IS '[내부] 출처';
COMMENT ON COLUMN asset_daily_price.exchange_code IS '[내부] 거래소 코드';
COMMENT ON COLUMN asset_daily_price.close_price IS '[복기 전용] 종가(현지 통화)';
COMMENT ON COLUMN asset_daily_price.vintage_no IS '[내부] 빈티지 번호';
COMMENT ON COLUMN asset_daily_price.observed_at IS '[내부] 관측일시';
COMMENT ON COLUMN asset_daily_price.job_run_id IS '[내부] 실행번호';
COMMENT ON COLUMN asset_daily_price.created_at IS '[내부] 생성일시';
