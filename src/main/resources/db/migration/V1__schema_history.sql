--                    → asset_month_return → fx_month → month_event → asset_month_attention

-- 종목 그룹
CREATE TABLE asset_group (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY,
    code       VARCHAR(30) NOT NULL,
    name       VARCHAR(50) NOT NULL,
    kind       VARCHAR(11) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_asset_group PRIMARY KEY (id),
    CONSTRAINT uq_asset_group_code UNIQUE (code),
    CONSTRAINT ck_asset_group_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_asset_group_kind CHECK (kind IN ('SECTOR','ASSET_CLASS'))
);
COMMENT ON TABLE asset_group IS '종목 그룹';
COMMENT ON COLUMN asset_group.id IS '[내부] 그룹번호';
COMMENT ON COLUMN asset_group.code IS '[내부] 그룹코드';
COMMENT ON COLUMN asset_group.name IS '[노출] 그룹명';
COMMENT ON COLUMN asset_group.kind IS '[노출] 그룹 구분';
COMMENT ON COLUMN asset_group.created_at IS '[내부] 생성일시';

-- 종목
CREATE TABLE asset (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY,
    market           VARCHAR(2)   NOT NULL,
    ticker           VARCHAR(12)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    asset_type       VARCHAR(5)   NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    asset_group_id   BIGINT       NOT NULL,
    listed_on        DATE         NOT NULL,
    return_basis     VARCHAR(5)   NOT NULL,
    proxy_index_name VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_asset PRIMARY KEY (id),
    CONSTRAINT uq_asset_market_ticker UNIQUE (market, ticker),
    CONSTRAINT fk_asset_asset_group FOREIGN KEY (asset_group_id) REFERENCES asset_group (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_asset_market CHECK (market IN ('KR','US')),
    CONSTRAINT ck_asset_ticker_upper CHECK (ticker = UPPER(ticker)),
    CONSTRAINT ck_asset_asset_type CHECK (asset_type IN ('STOCK','ETF')),
    CONSTRAINT ck_asset_currency CHECK (currency IN ('KRW','USD')),
    CONSTRAINT ck_asset_market_currency CHECK ((market = 'KR' AND currency = 'KRW') OR (market = 'US' AND currency = 'USD')),
    CONSTRAINT ck_asset_return_basis CHECK (return_basis IN ('PRICE','TOTAL')),
    CONSTRAINT ck_asset_proxy_etf CHECK (proxy_index_name IS NULL OR asset_type = 'ETF')
);
CREATE INDEX idx_asset_asset_group ON asset (asset_group_id);
COMMENT ON TABLE asset IS '종목';
COMMENT ON COLUMN asset.id IS '[내부] 종목번호';
COMMENT ON COLUMN asset.market IS '[노출] 시장';
COMMENT ON COLUMN asset.ticker IS '[노출] 종목코드';
COMMENT ON COLUMN asset.name IS '[노출] 종목명';
COMMENT ON COLUMN asset.asset_type IS '[노출] 종목 유형';
COMMENT ON COLUMN asset.currency IS '[내부] 거래 통화';
COMMENT ON COLUMN asset.asset_group_id IS '[노출] 그룹번호';
COMMENT ON COLUMN asset.listed_on IS '[복기 전용] 상장일';
COMMENT ON COLUMN asset.return_basis IS '[노출] 수익률 기준';
COMMENT ON COLUMN asset.proxy_index_name IS '[노출] 대체 지수명';
COMMENT ON COLUMN asset.created_at IS '[내부] 생성일시';

-- 종목 기준일
CREATE TABLE asset_position (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY,
    asset_id           BIGINT        NOT NULL,
    base_date          DATE          NOT NULL,
    base_price         NUMERIC(19,4) NOT NULL,
    base_fx_rate       NUMERIC(19,4) NOT NULL,
    market_cap_rank    SMALLINT,
    window_start       DATE,
    window_end         DATE,
    months_used        SMALLINT,
    up_participation   NUMERIC(12,8),
    down_participation NUMERIC(12,8),
    volatility         NUMERIC(12,8),
    max_drawdown       NUMERIC(12,8),
    system_position    VARCHAR(17),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_asset_position PRIMARY KEY (id),
    CONSTRAINT uq_asset_position_asset_base_date UNIQUE (asset_id, base_date),
    CONSTRAINT fk_asset_position_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_asset_position_base_price CHECK (base_price > 0 AND base_price <> 'NaN'::numeric),
    CONSTRAINT ck_asset_position_base_fx_rate CHECK (base_fx_rate > 0 AND base_fx_rate <> 'NaN'::numeric),
    CONSTRAINT ck_asset_position_market_cap_rank CHECK (market_cap_rank IS NULL OR market_cap_rank > 0),
    CONSTRAINT ck_asset_position_window CHECK (window_start <= window_end AND window_end < date_trunc('month', base_date::timestamp)::date),
    CONSTRAINT ck_asset_position_window_month_start CHECK (EXTRACT(DAY FROM window_start) = 1 AND EXTRACT(DAY FROM window_end) = 1),
    CONSTRAINT ck_asset_position_months_used CHECK (months_used > 0),
    CONSTRAINT ck_asset_position_participation_not_nan CHECK (up_participation <> 'NaN'::numeric AND down_participation <> 'NaN'::numeric),
    CONSTRAINT ck_asset_position_volatility CHECK (volatility >= 0 AND volatility <> 'NaN'::numeric),
    CONSTRAINT ck_asset_position_max_drawdown CHECK (max_drawdown BETWEEN -1 AND 0),
    CONSTRAINT ck_asset_position_system_position CHECK (system_position IN ('GOALKEEPER','DEFENDER','MIDFIELDER','FORWARD_TARGET','FORWARD_BREAKOUT','FORWARD_DEFENSIVE')),
    CONSTRAINT ck_asset_position_judgment_all_or_none CHECK (num_nulls(window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position) IN (0, 8))
);
CREATE INDEX idx_asset_position_base_date ON asset_position (base_date);
COMMENT ON TABLE asset_position IS '종목 기준일';
COMMENT ON COLUMN asset_position.id IS '[내부] 기준일 종목번호';
COMMENT ON COLUMN asset_position.asset_id IS '[내부] 종목번호';
COMMENT ON COLUMN asset_position.base_date IS '[내부] 기준일';
COMMENT ON COLUMN asset_position.base_price IS '[노출] 기준일 가격';
COMMENT ON COLUMN asset_position.base_fx_rate IS '[내부] 기준일 환율';
COMMENT ON COLUMN asset_position.market_cap_rank IS '[내부] 시가총액 순위';
COMMENT ON COLUMN asset_position.window_start IS '[내부] 판정 창 첫 달';
COMMENT ON COLUMN asset_position.window_end IS '[내부] 판정 창 마지막 달';
COMMENT ON COLUMN asset_position.months_used IS '[내부] 사용한 달 수';
COMMENT ON COLUMN asset_position.up_participation IS '[노출] 상승장 참여율';
COMMENT ON COLUMN asset_position.down_participation IS '[노출] 하락장 참여율';
COMMENT ON COLUMN asset_position.volatility IS '[노출] 변동성';
COMMENT ON COLUMN asset_position.max_drawdown IS '[노출] 최대 낙폭';
COMMENT ON COLUMN asset_position.system_position IS '[노출] 시스템 판정 포지션';
COMMENT ON COLUMN asset_position.created_at IS '[내부] 생성일시';

-- 월 블록
CREATE TABLE month_block (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY,
    month_start        DATE          NOT NULL,
    rate_dir           VARCHAR(7),
    growth_dir         VARCHAR(7),
    fx_dir             VARCHAR(7),
    sentiment_dir      VARCHAR(7),
    rate_strength      NUMERIC(12,8),
    growth_strength    NUMERIC(12,8),
    fx_strength        NUMERIC(12,8),
    sentiment_strength NUMERIC(12,8),
    prob_p1            NUMERIC(5,4),
    prob_p2            NUMERIC(5,4),
    prob_p3            NUMERIC(5,4),
    prob_p4            NUMERIC(5,4),
    event_summary      VARCHAR(200),
    events_reviewed    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_month_block PRIMARY KEY (id),
    CONSTRAINT uq_month_block_month_start UNIQUE (month_start),
    CONSTRAINT ck_month_block_month_start CHECK (EXTRACT(DAY FROM month_start) = 1),
    CONSTRAINT ck_month_block_rate_dir CHECK (rate_dir IN ('UP','DOWN','NEUTRAL')),
    CONSTRAINT ck_month_block_growth_dir CHECK (growth_dir IN ('UP','DOWN','NEUTRAL')),
    CONSTRAINT ck_month_block_fx_dir CHECK (fx_dir IN ('UP','DOWN','NEUTRAL')),
    CONSTRAINT ck_month_block_sentiment_dir CHECK (sentiment_dir IN ('UP','DOWN','NEUTRAL')),
    CONSTRAINT ck_month_block_rate_pair CHECK ((rate_dir IS NULL) = (rate_strength IS NULL) AND (rate_strength IS NULL OR (rate_strength >= 0 AND rate_strength <> 'NaN'::numeric))),
    CONSTRAINT ck_month_block_growth_pair CHECK ((growth_dir IS NULL) = (growth_strength IS NULL) AND (growth_strength IS NULL OR (growth_strength >= 0 AND growth_strength <> 'NaN'::numeric))),
    CONSTRAINT ck_month_block_fx_pair CHECK ((fx_dir IS NULL) = (fx_strength IS NULL) AND (fx_strength IS NULL OR (fx_strength >= 0 AND fx_strength <> 'NaN'::numeric))),
    CONSTRAINT ck_month_block_sentiment_pair CHECK ((sentiment_dir IS NULL) = (sentiment_strength IS NULL) AND (sentiment_strength IS NULL OR (sentiment_strength >= 0 AND sentiment_strength <> 'NaN'::numeric))),
    CONSTRAINT ck_month_block_prob_range CHECK (prob_p1 BETWEEN 0 AND 1 AND prob_p2 BETWEEN 0 AND 1 AND prob_p3 BETWEEN 0 AND 1 AND prob_p4 BETWEEN 0 AND 1),
    CONSTRAINT ck_month_block_prob_all_or_none CHECK (num_nulls(prob_p1, prob_p2, prob_p3, prob_p4) IN (0, 4)),
    CONSTRAINT ck_month_block_prob_sum CHECK (prob_p1 IS NULL OR ABS(prob_p1 + prob_p2 + prob_p3 + prob_p4 - 1) <= 0.0002)
);
COMMENT ON TABLE month_block IS '월 블록';
COMMENT ON COLUMN month_block.id IS '[내부] 월 블록번호';
COMMENT ON COLUMN month_block.month_start IS '[복기 전용] 월 시작일';
COMMENT ON COLUMN month_block.rate_dir IS '[노출] 금리 방향';
COMMENT ON COLUMN month_block.growth_dir IS '[노출] 성장 방향';
COMMENT ON COLUMN month_block.fx_dir IS '[노출] 환율 방향';
COMMENT ON COLUMN month_block.sentiment_dir IS '[노출] 심리 방향';
COMMENT ON COLUMN month_block.rate_strength IS '[노출] 금리 강도';
COMMENT ON COLUMN month_block.growth_strength IS '[노출] 성장 강도';
COMMENT ON COLUMN month_block.fx_strength IS '[노출] 환율 강도';
COMMENT ON COLUMN month_block.sentiment_strength IS '[노출] 심리 강도';
COMMENT ON COLUMN month_block.prob_p1 IS '[노출] 침체 확률';
COMMENT ON COLUMN month_block.prob_p2 IS '[노출] 골디락스 확률';
COMMENT ON COLUMN month_block.prob_p3 IS '[노출] 리플레이션 확률';
COMMENT ON COLUMN month_block.prob_p4 IS '[노출] 스태그플레이션 확률';
COMMENT ON COLUMN month_block.event_summary IS '[복기 전용] 사건 요약';
COMMENT ON COLUMN month_block.events_reviewed IS '[내부] 사건 태그 편집 완료';
COMMENT ON COLUMN month_block.created_at IS '[내부] 생성일시';

-- 종목 달 수익률
CREATE TABLE asset_month_return (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY,
    asset_id       BIGINT        NOT NULL,
    month_block_id BIGINT        NOT NULL,
    local_return   NUMERIC(12,8) NOT NULL,
    return_kind    VARCHAR(11)   NOT NULL DEFAULT 'ACTUAL',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_asset_month_return PRIMARY KEY (id),
    CONSTRAINT uq_asset_month_return_asset_month UNIQUE (asset_id, month_block_id),
    CONSTRAINT fk_asset_month_return_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_asset_month_return_month_block FOREIGN KEY (month_block_id) REFERENCES month_block (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_asset_month_return_local_return CHECK (local_return >= -1 AND local_return <> 'NaN'::numeric),
    CONSTRAINT ck_asset_month_return_return_kind CHECK (return_kind IN ('ACTUAL','PROXY_INDEX'))
);
CREATE INDEX idx_asset_month_return_month_block ON asset_month_return (month_block_id);
COMMENT ON TABLE asset_month_return IS '종목 달 수익률';
COMMENT ON COLUMN asset_month_return.id IS '[내부] 수익률번호';
COMMENT ON COLUMN asset_month_return.asset_id IS '[내부] 종목번호';
COMMENT ON COLUMN asset_month_return.month_block_id IS '[내부] 월 블록번호';
COMMENT ON COLUMN asset_month_return.local_return IS '[노출] 현지 통화 월 수익률';
COMMENT ON COLUMN asset_month_return.return_kind IS '[노출] 수익률 종류';
COMMENT ON COLUMN asset_month_return.created_at IS '[내부] 생성일시';

-- 달 환율 변화
CREATE TABLE fx_month (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY,
    month_block_id BIGINT        NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    fx_change      NUMERIC(12,8) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_fx_month PRIMARY KEY (id),
    CONSTRAINT uq_fx_month_month_block_currency UNIQUE (month_block_id, currency),
    CONSTRAINT fk_fx_month_month_block FOREIGN KEY (month_block_id) REFERENCES month_block (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_fx_month_currency CHECK (currency IN ('USD')),
    CONSTRAINT ck_fx_month_fx_change CHECK (fx_change > -1 AND fx_change <> 'NaN'::numeric)
);
COMMENT ON TABLE fx_month IS '달 환율 변화';
COMMENT ON COLUMN fx_month.id IS '[내부] 환율번호';
COMMENT ON COLUMN fx_month.month_block_id IS '[내부] 월 블록번호';
COMMENT ON COLUMN fx_month.currency IS '[내부] 통화';
COMMENT ON COLUMN fx_month.fx_change IS '[노출] 환율 변화율';
COMMENT ON COLUMN fx_month.created_at IS '[내부] 생성일시';

-- 달 사건 태그
CREATE TABLE month_event (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY,
    event_key       VARCHAR(40)   NOT NULL,
    month_block_id  BIGINT        NOT NULL,
    category        VARCHAR(12)   NOT NULL,
    event_type      VARCHAR(30)   NOT NULL,
    phase           VARCHAR(7)    NOT NULL,
    outcome         VARCHAR(20),
    direction       VARCHAR(4),
    strength        SMALLINT      NOT NULL,
    reaction_change NUMERIC(12,8),
    title           VARCHAR(100)  NOT NULL,
    source          VARCHAR(500)  NOT NULL,
    hindsight       VARCHAR(300),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_month_event PRIMARY KEY (id),
    CONSTRAINT uq_month_event_event_key UNIQUE (event_key),
    CONSTRAINT fk_month_event_month_block FOREIGN KEY (month_block_id) REFERENCES month_block (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_month_event_category CHECK (category IN ('GEOPOLITICS','TRADE','POLITICS','SUPPLY_SHOCK','FINANCIAL','POLICY','HEALTH')),
    CONSTRAINT ck_month_event_event_type_format CHECK (event_type ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_month_event_phase CHECK (phase IN ('START','ONGOING','END')),
    CONSTRAINT ck_month_event_outcome_format CHECK (outcome IS NULL OR outcome ~ '^[A-Z][A-Z_]*$'),
    CONSTRAINT ck_month_event_direction CHECK (direction IN ('UP','DOWN')),
    CONSTRAINT ck_month_event_strength CHECK (strength BETWEEN 1 AND 3),
    CONSTRAINT ck_month_event_reaction_change CHECK (reaction_change IS NULL OR (reaction_change >= -1 AND reaction_change <> 'NaN'::numeric))
);
CREATE INDEX idx_month_event_month_block ON month_event (month_block_id);
COMMENT ON TABLE month_event IS '달 사건 태그';
COMMENT ON COLUMN month_event.id IS '[내부] 사건번호';
COMMENT ON COLUMN month_event.event_key IS '[내부] 사건 키';
COMMENT ON COLUMN month_event.month_block_id IS '[내부] 월 블록번호';
COMMENT ON COLUMN month_event.category IS '[노출] 범주';
COMMENT ON COLUMN month_event.event_type IS '[노출] 사건 유형';
COMMENT ON COLUMN month_event.phase IS '[노출] 단계';
COMMENT ON COLUMN month_event.outcome IS '[노출] 결과';
COMMENT ON COLUMN month_event.direction IS '[노출] 방향';
COMMENT ON COLUMN month_event.strength IS '[노출] 강도 단계';
COMMENT ON COLUMN month_event.reaction_change IS '[노출] 반응 변화율';
COMMENT ON COLUMN month_event.title IS '[복기 전용] 실제 이름';
COMMENT ON COLUMN month_event.source IS '[복기 전용] 출처';
COMMENT ON COLUMN month_event.hindsight IS '[복기 전용] 사후 평가';
COMMENT ON COLUMN month_event.created_at IS '[내부] 생성일시';

-- 종목 달 관심 지수
CREATE TABLE asset_month_attention (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY,
    asset_id        BIGINT        NOT NULL,
    month_block_id  BIGINT        NOT NULL,
    window_months   SMALLINT      NOT NULL,
    attention_z     NUMERIC(12,8),
    streak          SMALLINT      NOT NULL,
    attention_index NUMERIC(12,8),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_asset_month_attention PRIMARY KEY (id),
    CONSTRAINT uq_asset_month_attention_asset_month UNIQUE (asset_id, month_block_id),
    CONSTRAINT fk_asset_month_attention_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_asset_month_attention_month_block FOREIGN KEY (month_block_id) REFERENCES month_block (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_asset_month_attention_window_months CHECK (window_months BETWEEN 0 AND 12),
    CONSTRAINT ck_asset_month_attention_z_window CHECK (attention_z IS NULL OR window_months >= 2),
    CONSTRAINT ck_asset_month_attention_index_needs_z CHECK (attention_z IS NOT NULL OR attention_index IS NULL),
    CONSTRAINT ck_asset_month_attention_not_nan CHECK (attention_z <> 'NaN'::numeric AND attention_index <> 'NaN'::numeric)
);
CREATE INDEX idx_asset_month_attention_month_block ON asset_month_attention (month_block_id);
COMMENT ON TABLE asset_month_attention IS '종목 달 관심 지수';
COMMENT ON COLUMN asset_month_attention.id IS '[내부] 관심번호';
COMMENT ON COLUMN asset_month_attention.asset_id IS '[내부] 종목번호';
COMMENT ON COLUMN asset_month_attention.month_block_id IS '[내부] 월 블록번호';
COMMENT ON COLUMN asset_month_attention.window_months IS '[내부] z-score 창 달 수';
COMMENT ON COLUMN asset_month_attention.attention_z IS '[내부] 수익률 크기 z-score';
COMMENT ON COLUMN asset_month_attention.streak IS '[노출] 연속 방향 달 수';
COMMENT ON COLUMN asset_month_attention.attention_index IS '[노출] 관심 지수';
COMMENT ON COLUMN asset_month_attention.created_at IS '[내부] 생성일시';

