--                    → lineup → formation_change → turn_feed → reconciliation_log

-- 회원
CREATE TABLE member (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY,
    email      VARCHAR(255) NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    password   VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_member PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uq_member_email ON member (lower(email));
COMMENT ON TABLE member IS '회원';
COMMENT ON COLUMN member.id IS '[내부] 회원번호';
COMMENT ON COLUMN member.email IS '[내부] 이메일';
COMMENT ON COLUMN member.name IS '[노출] 이름';
COMMENT ON COLUMN member.password IS '[내부] 비밀번호 해시';
COMMENT ON COLUMN member.created_at IS '[내부] 가입일시';
COMMENT ON COLUMN member.updated_at IS '[내부] 수정일시';

-- 시즌
CREATE TABLE season (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY,
    member_id          BIGINT        NOT NULL,
    status             VARCHAR(11)   NOT NULL DEFAULT 'IN_PROGRESS',
    path_length        SMALLINT      NOT NULL,
    current_turn_no    SMALLINT      NOT NULL DEFAULT 0,
    abandoned_turn_no  SMALLINT,
    seed               BIGINT        NOT NULL,
    engine_version     VARCHAR(20)   NOT NULL,
    data_snapshot_date DATE          NOT NULL,
    base_date          DATE          NOT NULL,
    initial_cash       NUMERIC(19,4) NOT NULL,
    fee_rate_kr        NUMERIC(12,8) NOT NULL,
    fee_rate_us        NUMERIC(12,8) NOT NULL,
    fx_spread_rate     NUMERIC(12,8) NOT NULL,
    risk_limit         NUMERIC(12,8) NOT NULL,
    start_goalkeeper   SMALLINT      NOT NULL,
    start_defender     SMALLINT      NOT NULL,
    start_midfielder   SMALLINT      NOT NULL,
    start_forward      SMALLINT      NOT NULL,
    current_goalkeeper SMALLINT      NOT NULL,
    current_defender   SMALLINT      NOT NULL,
    current_midfielder SMALLINT      NOT NULL,
    current_forward    SMALLINT      NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_season PRIMARY KEY (id),
    CONSTRAINT fk_season_member FOREIGN KEY (member_id) REFERENCES member (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_season_status CHECK (status IN ('IN_PROGRESS','ABANDONING','FINISHED')),
    CONSTRAINT ck_season_path_length CHECK (path_length > 0),
    CONSTRAINT ck_season_current_turn_no CHECK (current_turn_no BETWEEN 0 AND path_length),
    CONSTRAINT ck_season_finished_turn CHECK (status <> 'FINISHED' OR current_turn_no = path_length),
    CONSTRAINT ck_season_abandoned_turn_no CHECK (abandoned_turn_no IS NULL OR abandoned_turn_no BETWEEN 0 AND current_turn_no),
    CONSTRAINT ck_season_abandoning CHECK (status <> 'ABANDONING' OR abandoned_turn_no IS NOT NULL),
    CONSTRAINT ck_season_abandoned_status CHECK (status <> 'IN_PROGRESS' OR abandoned_turn_no IS NULL),
    CONSTRAINT ck_season_initial_cash_pos CHECK (initial_cash > 0),
    CONSTRAINT ck_season_initial_cash_won CHECK (initial_cash = ROUND(initial_cash, 0) AND initial_cash <> 'NaN'::numeric),
    CONSTRAINT ck_season_fee_rate_kr CHECK (fee_rate_kr >= 0 AND fee_rate_kr < 1),
    CONSTRAINT ck_season_fee_rate_us CHECK (fee_rate_us >= 0 AND fee_rate_us < 1),
    CONSTRAINT ck_season_fx_spread_rate CHECK (fx_spread_rate >= 0 AND fx_spread_rate < 1),
    CONSTRAINT ck_season_risk_limit CHECK (risk_limit IN (-0.10, -0.20, -0.30, -0.40)),
    CONSTRAINT ck_season_start_formation_sum CHECK (start_goalkeeper + start_defender + start_midfielder + start_forward = 100),
    CONSTRAINT ck_season_start_formation_range CHECK (start_goalkeeper BETWEEN 0 AND 100 AND start_defender BETWEEN 0 AND 100 AND start_midfielder BETWEEN 0 AND 100 AND start_forward BETWEEN 0 AND 100),
    CONSTRAINT ck_season_start_goalkeeper_min CHECK (start_goalkeeper >= 1),
    CONSTRAINT ck_season_current_formation_sum CHECK (current_goalkeeper + current_defender + current_midfielder + current_forward = 100),
    CONSTRAINT ck_season_current_formation_range CHECK (current_goalkeeper BETWEEN 0 AND 100 AND current_defender BETWEEN 0 AND 100 AND current_midfielder BETWEEN 0 AND 100 AND current_forward BETWEEN 0 AND 100),
    CONSTRAINT ck_season_current_goalkeeper_min CHECK (current_goalkeeper >= 1)
);
CREATE INDEX idx_season_member ON season (member_id);
COMMENT ON TABLE season IS '시즌';
COMMENT ON COLUMN season.id IS '[내부] 시즌번호';
COMMENT ON COLUMN season.member_id IS '[내부] 회원번호';
COMMENT ON COLUMN season.status IS '[노출] 시즌 상태';
COMMENT ON COLUMN season.path_length IS '[노출] 경로 길이';
COMMENT ON COLUMN season.current_turn_no IS '[노출] 현재 턴';
COMMENT ON COLUMN season.abandoned_turn_no IS '[노출] 포기 턴';
COMMENT ON COLUMN season.seed IS '[내부] 시드';
COMMENT ON COLUMN season.engine_version IS '[내부] 엔진 버전';
COMMENT ON COLUMN season.data_snapshot_date IS '[내부] 데이터 스냅샷 날짜';
COMMENT ON COLUMN season.base_date IS '[내부] 기준일';
COMMENT ON COLUMN season.initial_cash IS '[노출] 초기 자금';
COMMENT ON COLUMN season.fee_rate_kr IS '[노출] 국내 수수료율';
COMMENT ON COLUMN season.fee_rate_us IS '[노출] 미국 수수료율';
COMMENT ON COLUMN season.fx_spread_rate IS '[노출] 환전 비용률';
COMMENT ON COLUMN season.risk_limit IS '[노출] 위험 선언';
COMMENT ON COLUMN season.start_goalkeeper IS '[노출] 시작 골키퍼 비중';
COMMENT ON COLUMN season.start_defender IS '[노출] 시작 수비수 비중';
COMMENT ON COLUMN season.start_midfielder IS '[노출] 시작 미드필더 비중';
COMMENT ON COLUMN season.start_forward IS '[노출] 시작 공격수 비중';
COMMENT ON COLUMN season.current_goalkeeper IS '[노출] 현재 골키퍼 비중';
COMMENT ON COLUMN season.current_defender IS '[노출] 현재 수비수 비중';
COMMENT ON COLUMN season.current_midfielder IS '[노출] 현재 미드필더 비중';
COMMENT ON COLUMN season.current_forward IS '[노출] 현재 공격수 비중';
COMMENT ON COLUMN season.version IS '[내부] 낙관적 락 버전';
COMMENT ON COLUMN season.created_at IS '[내부] 생성일시';
COMMENT ON COLUMN season.updated_at IS '[내부] 수정일시';

-- 시즌 경로
CREATE TABLE season_path (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY,
    season_id      BIGINT      NOT NULL,
    turn_no        SMALLINT    NOT NULL,
    month_block_id BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_season_path PRIMARY KEY (id),
    CONSTRAINT uq_season_path_season_turn UNIQUE (season_id, turn_no),
    CONSTRAINT fk_season_path_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_season_path_month_block FOREIGN KEY (month_block_id) REFERENCES month_block (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_season_path_turn_no CHECK (turn_no >= 1)
);
CREATE INDEX idx_season_path_month_block ON season_path (month_block_id);
COMMENT ON TABLE season_path IS '시즌 경로';
COMMENT ON COLUMN season_path.id IS '[내부] 경로번호';
COMMENT ON COLUMN season_path.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN season_path.turn_no IS '[노출] 턴 번호';
COMMENT ON COLUMN season_path.month_block_id IS '[복기 전용] 월 블록번호';
COMMENT ON COLUMN season_path.created_at IS '[내부] 생성일시';

-- 턴 스냅샷
CREATE TABLE turn (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY,
    season_id        BIGINT        NOT NULL,
    turn_no          SMALLINT      NOT NULL,
    cash_balance     NUMERIC(19,4) NOT NULL,
    holdings_value   NUMERIC(19,4) NOT NULL,
    total_value      NUMERIC(19,4) NOT NULL,
    goalkeeper_value NUMERIC(19,4) NOT NULL,
    defender_value   NUMERIC(19,4) NOT NULL,
    midfielder_value NUMERIC(19,4) NOT NULL,
    forward_value    NUMERIC(19,4) NOT NULL,
    drawdown         NUMERIC(12,8) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_turn PRIMARY KEY (id),
    CONSTRAINT uq_turn_season_turn UNIQUE (season_id, turn_no),
    CONSTRAINT fk_turn_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_turn_turn_no CHECK (turn_no >= 1),
    CONSTRAINT ck_turn_total_value CHECK (total_value = holdings_value + cash_balance),
    CONSTRAINT ck_turn_position_sum CHECK (goalkeeper_value + defender_value + midfielder_value + forward_value = total_value),
    CONSTRAINT ck_turn_goalkeeper_cash CHECK (goalkeeper_value >= cash_balance),
    CONSTRAINT ck_turn_drawdown CHECK (drawdown BETWEEN -1 AND 0),
    CONSTRAINT ck_turn_amounts_nonneg CHECK (cash_balance >= 0 AND holdings_value >= 0 AND total_value >= 0 AND goalkeeper_value >= 0 AND defender_value >= 0 AND midfielder_value >= 0 AND forward_value >= 0),
    CONSTRAINT ck_turn_amounts_won CHECK (cash_balance = ROUND(cash_balance, 0) AND holdings_value = ROUND(holdings_value, 0) AND total_value = ROUND(total_value, 0) AND goalkeeper_value = ROUND(goalkeeper_value, 0) AND defender_value = ROUND(defender_value, 0) AND midfielder_value = ROUND(midfielder_value, 0) AND forward_value = ROUND(forward_value, 0) AND cash_balance <> 'NaN'::numeric AND holdings_value <> 'NaN'::numeric AND total_value <> 'NaN'::numeric AND goalkeeper_value <> 'NaN'::numeric AND defender_value <> 'NaN'::numeric AND midfielder_value <> 'NaN'::numeric AND forward_value <> 'NaN'::numeric)
);
COMMENT ON TABLE turn IS '턴 스냅샷';
COMMENT ON COLUMN turn.id IS '[내부] 턴 스냅샷번호';
COMMENT ON COLUMN turn.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN turn.turn_no IS '[노출] 턴 번호';
COMMENT ON COLUMN turn.cash_balance IS '[노출] 현금 잔액';
COMMENT ON COLUMN turn.holdings_value IS '[노출] 보유 평가액';
COMMENT ON COLUMN turn.total_value IS '[노출] 총 평가액';
COMMENT ON COLUMN turn.goalkeeper_value IS '[노출] 골키퍼 평가액';
COMMENT ON COLUMN turn.defender_value IS '[노출] 수비수 평가액';
COMMENT ON COLUMN turn.midfielder_value IS '[노출] 미드필더 평가액';
COMMENT ON COLUMN turn.forward_value IS '[노출] 공격수 평가액';
COMMENT ON COLUMN turn.drawdown IS '[노출] 누적 낙폭';
COMMENT ON COLUMN turn.created_at IS '[내부] 생성일시';

-- 턴 가격
CREATE TABLE turn_price (
    id         BIGINT        GENERATED ALWAYS AS IDENTITY,
    season_id  BIGINT        NOT NULL,
    turn_no    SMALLINT      NOT NULL,
    asset_id   BIGINT        NOT NULL,
    price      NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_turn_price PRIMARY KEY (id),
    CONSTRAINT uq_turn_price_season_turn_asset UNIQUE (season_id, turn_no, asset_id),
    CONSTRAINT fk_turn_price_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_turn_price_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_turn_price_turn_no CHECK (turn_no >= 0),
    CONSTRAINT ck_turn_price_price_pos CHECK (price > 0),
    CONSTRAINT ck_turn_price_price_won CHECK (price = ROUND(price, 0) AND price <> 'NaN'::numeric)
);
CREATE INDEX idx_turn_price_asset ON turn_price (asset_id);
COMMENT ON TABLE turn_price IS '턴 가격';
COMMENT ON COLUMN turn_price.id IS '[내부] 가격번호';
COMMENT ON COLUMN turn_price.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN turn_price.turn_no IS '[노출] 턴 번호';
COMMENT ON COLUMN turn_price.asset_id IS '[내부] 종목번호';
COMMENT ON COLUMN turn_price.price IS '[노출] 가격';
COMMENT ON COLUMN turn_price.created_at IS '[내부] 생성일시';

-- 라인업
CREATE TABLE lineup (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY,
    season_id         BIGINT      NOT NULL,
    asset_id          BIGINT      NOT NULL,
    declared_position VARCHAR(17) NOT NULL,
    start_turn        SMALLINT    NOT NULL,
    end_turn          SMALLINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_lineup PRIMARY KEY (id),
    CONSTRAINT fk_lineup_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_lineup_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_lineup_declared_position CHECK (declared_position IN ('GOALKEEPER','DEFENDER','MIDFIELDER','FORWARD_TARGET','FORWARD_BREAKOUT','FORWARD_DEFENSIVE')),
    CONSTRAINT ck_lineup_turns CHECK (start_turn >= 0 AND (end_turn IS NULL OR end_turn >= start_turn))
);
CREATE UNIQUE INDEX uq_lineup_open ON lineup (season_id, asset_id) WHERE end_turn IS NULL;
CREATE INDEX idx_lineup_season_asset ON lineup (season_id, asset_id);
CREATE INDEX idx_lineup_asset ON lineup (asset_id);
COMMENT ON TABLE lineup IS '라인업';
COMMENT ON COLUMN lineup.id IS '[내부] 라인업번호';
COMMENT ON COLUMN lineup.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN lineup.asset_id IS '[내부] 종목번호';
COMMENT ON COLUMN lineup.declared_position IS '[노출] 선언 포지션';
COMMENT ON COLUMN lineup.start_turn IS '[노출] 시작 턴';
COMMENT ON COLUMN lineup.end_turn IS '[노출] 끝 턴';
COMMENT ON COLUMN lineup.created_at IS '[내부] 생성일시';
COMMENT ON COLUMN lineup.updated_at IS '[내부] 수정일시';

-- 포메이션 변경
CREATE TABLE formation_change (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY,
    season_id          BIGINT       NOT NULL,
    turn_no            SMALLINT     NOT NULL,
    before_goalkeeper  SMALLINT     NOT NULL,
    before_defender    SMALLINT     NOT NULL,
    before_midfielder  SMALLINT     NOT NULL,
    before_forward     SMALLINT     NOT NULL,
    after_goalkeeper   SMALLINT     NOT NULL,
    after_defender     SMALLINT     NOT NULL,
    after_midfielder   SMALLINT     NOT NULL,
    after_forward      SMALLINT     NOT NULL,
    reason             VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_formation_change PRIMARY KEY (id),
    CONSTRAINT uq_formation_change_id_season_turn UNIQUE (id, season_id, turn_no),
    CONSTRAINT fk_formation_change_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_formation_change_turn_no CHECK (turn_no >= 0),
    CONSTRAINT ck_formation_change_before_sum CHECK (before_goalkeeper + before_defender + before_midfielder + before_forward = 100),
    CONSTRAINT ck_formation_change_before_range CHECK (before_goalkeeper BETWEEN 0 AND 100 AND before_defender BETWEEN 0 AND 100 AND before_midfielder BETWEEN 0 AND 100 AND before_forward BETWEEN 0 AND 100),
    CONSTRAINT ck_formation_change_before_goalkeeper_min CHECK (before_goalkeeper >= 1),
    CONSTRAINT ck_formation_change_after_sum CHECK (after_goalkeeper + after_defender + after_midfielder + after_forward = 100),
    CONSTRAINT ck_formation_change_after_range CHECK (after_goalkeeper BETWEEN 0 AND 100 AND after_defender BETWEEN 0 AND 100 AND after_midfielder BETWEEN 0 AND 100 AND after_forward BETWEEN 0 AND 100),
    CONSTRAINT ck_formation_change_after_goalkeeper_min CHECK (after_goalkeeper >= 1),
    CONSTRAINT ck_formation_change_changed CHECK ((before_goalkeeper, before_defender, before_midfielder, before_forward) <> (after_goalkeeper, after_defender, after_midfielder, after_forward))
);
CREATE INDEX idx_formation_change_season_turn ON formation_change (season_id, turn_no);
COMMENT ON TABLE formation_change IS '포메이션 변경';
COMMENT ON COLUMN formation_change.id IS '[내부] 변경번호';
COMMENT ON COLUMN formation_change.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN formation_change.turn_no IS '[노출] 턴 번호';
COMMENT ON COLUMN formation_change.before_goalkeeper IS '[노출] 이전 골키퍼 비중';
COMMENT ON COLUMN formation_change.before_defender IS '[노출] 이전 수비수 비중';
COMMENT ON COLUMN formation_change.before_midfielder IS '[노출] 이전 미드필더 비중';
COMMENT ON COLUMN formation_change.before_forward IS '[노출] 이전 공격수 비중';
COMMENT ON COLUMN formation_change.after_goalkeeper IS '[노출] 이후 골키퍼 비중';
COMMENT ON COLUMN formation_change.after_defender IS '[노출] 이후 수비수 비중';
COMMENT ON COLUMN formation_change.after_midfielder IS '[노출] 이후 미드필더 비중';
COMMENT ON COLUMN formation_change.after_forward IS '[노출] 이후 공격수 비중';
COMMENT ON COLUMN formation_change.reason IS '[노출] 사유';
COMMENT ON COLUMN formation_change.created_at IS '[내부] 생성일시';

-- 턴 피드
CREATE TABLE turn_feed (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY,
    season_id       BIGINT       NOT NULL,
    turn_no         SMALLINT     NOT NULL,
    seq             SMALLINT     NOT NULL,
    kind            VARCHAR(6)   NOT NULL,
    asset_id        BIGINT,
    asset_group_id  BIGINT,
    month_event_id  BIGINT,
    category_hidden BOOLEAN      NOT NULL DEFAULT FALSE,
    direction       VARCHAR(4),
    strength        SMALLINT,
    repeat_count    SMALLINT     NOT NULL DEFAULT 0,
    template_id     VARCHAR(40)  NOT NULL,
    template_vars   JSONB        NOT NULL DEFAULT '{}',
    body            VARCHAR(300) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_turn_feed PRIMARY KEY (id),
    CONSTRAINT uq_turn_feed_season_turn_seq UNIQUE (season_id, turn_no, seq),
    CONSTRAINT uq_turn_feed_id_season UNIQUE (id, season_id),
    CONSTRAINT fk_turn_feed_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_turn_feed_month_event FOREIGN KEY (month_event_id) REFERENCES month_event (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_turn_feed_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_turn_feed_asset_group FOREIGN KEY (asset_group_id) REFERENCES asset_group (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_turn_feed_turn_no CHECK (turn_no >= 1),
    CONSTRAINT ck_turn_feed_seq CHECK (seq >= 1),
    CONSTRAINT ck_turn_feed_kind CHECK (kind IN ('RISE','FALL','ECHO','SPREAD','FADE','EVENT','NUDGE')),
    CONSTRAINT ck_turn_feed_category_hidden CHECK (NOT category_hidden OR kind = 'EVENT'),
    CONSTRAINT ck_turn_feed_direction CHECK (direction IN ('UP','DOWN')),
    CONSTRAINT ck_turn_feed_strength CHECK (strength BETWEEN 1 AND 3),
    CONSTRAINT ck_turn_feed_repeat_count CHECK (repeat_count >= 0 AND (kind <> 'ECHO' OR repeat_count >= 1)),
    CONSTRAINT ck_turn_feed_template_vars CHECK (jsonb_typeof(template_vars) = 'object'),
    CONSTRAINT ck_turn_feed_target_one CHECK (num_nonnulls(asset_id, asset_group_id) <= 1),
    CONSTRAINT ck_turn_feed_target_kind CHECK ((kind IN ('RISE','FALL','ECHO','FADE') AND num_nonnulls(asset_id, asset_group_id) = 1) OR (kind = 'SPREAD' AND asset_group_id IS NOT NULL) OR (kind = 'EVENT' AND asset_id IS NULL AND asset_group_id IS NULL) OR kind = 'NUDGE'),
    CONSTRAINT ck_turn_feed_event CHECK ((kind = 'EVENT') = (month_event_id IS NOT NULL))
);
CREATE INDEX idx_turn_feed_asset ON turn_feed (asset_id);
CREATE INDEX idx_turn_feed_asset_group ON turn_feed (asset_group_id);
CREATE INDEX idx_turn_feed_month_event ON turn_feed (month_event_id);
COMMENT ON TABLE turn_feed IS '턴 피드';
COMMENT ON COLUMN turn_feed.id IS '[내부] 피드번호';
COMMENT ON COLUMN turn_feed.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN turn_feed.turn_no IS '[노출] 턴 번호';
COMMENT ON COLUMN turn_feed.seq IS '[노출] 표시 순서';
COMMENT ON COLUMN turn_feed.kind IS '[복기 전용] 종류';
COMMENT ON COLUMN turn_feed.asset_id IS '[노출] 대상 종목번호';
COMMENT ON COLUMN turn_feed.asset_group_id IS '[노출] 대상 그룹번호';
COMMENT ON COLUMN turn_feed.month_event_id IS '[복기 전용] 사건 태그번호';
COMMENT ON COLUMN turn_feed.category_hidden IS '[내부] 범주 비공개 여부';
COMMENT ON COLUMN turn_feed.direction IS '[내부] 방향';
COMMENT ON COLUMN turn_feed.strength IS '[내부] 강도';
COMMENT ON COLUMN turn_feed.repeat_count IS '[내부] 재생산 횟수';
COMMENT ON COLUMN turn_feed.template_id IS '[내부] 템플릿 id';
COMMENT ON COLUMN turn_feed.template_vars IS '[내부] 템플릿 변수';
COMMENT ON COLUMN turn_feed.body IS '[노출] 문장';
COMMENT ON COLUMN turn_feed.created_at IS '[내부] 생성일시';

-- 대사 기록
CREATE TABLE reconciliation_log (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY,
    season_id      BIGINT        NOT NULL,
    turn_no        SMALLINT      NOT NULL,
    attempt_no     SMALLINT      NOT NULL,
    check_code     VARCHAR(20)   NOT NULL,
    status         VARCHAR(8)    NOT NULL,
    expected_value NUMERIC(19,4),
    actual_value   NUMERIC(19,4),
    diff_value     NUMERIC(19,4),
    mismatch_count INTEGER       NOT NULL,
    classification VARCHAR(11),
    detail         JSONB,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_reconciliation_log PRIMARY KEY (id),
    CONSTRAINT uq_reconciliation_log_season_turn_attempt_code UNIQUE (season_id, turn_no, attempt_no, check_code),
    CONSTRAINT fk_reconciliation_log_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_reconciliation_log_turn_no CHECK (turn_no >= 0),
    CONSTRAINT ck_reconciliation_log_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT ck_reconciliation_log_check_code CHECK (check_code IN ('ACCOUNT_OPENING','CASH_SUM','LEDGER_CHAIN','ORDER_LEDGER','HOLDING_QTY','ORDER_FILL','EXEC_VALUES','RESERVED_ZERO','VALUATION','TURN_PRICE','FILL_PRICE','FEE_RULE','FORMATION','FORMATION_BIND','LINEUP_HOLDING','LINEUP_OVERLAP','POSITION_VALUE')),
    CONSTRAINT ck_reconciliation_log_status CHECK (status IN ('MATCH','MISMATCH')),
    CONSTRAINT ck_reconciliation_log_values CHECK (num_nulls(expected_value, actual_value, diff_value) IN (0, 3) AND (diff_value IS NULL OR diff_value = actual_value - expected_value) AND expected_value <> 'NaN'::numeric AND actual_value <> 'NaN'::numeric),
    CONSTRAINT ck_reconciliation_log_match_diff CHECK (status <> 'MATCH' OR diff_value IS NULL OR diff_value = 0),
    CONSTRAINT ck_reconciliation_log_mismatch CHECK (mismatch_count >= 0 AND (status = 'MATCH') = (mismatch_count = 0)),
    CONSTRAINT ck_reconciliation_log_classification CHECK (classification IN ('TIMING','ADJUSTMENT','INVESTIGATE')),
    CONSTRAINT ck_reconciliation_log_status_classification CHECK ((status = 'MATCH') = (classification IS NULL))
);
COMMENT ON TABLE reconciliation_log IS '대사 기록';
COMMENT ON COLUMN reconciliation_log.id IS '[내부] 대사기록번호';
COMMENT ON COLUMN reconciliation_log.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN reconciliation_log.turn_no IS '[내부] 턴 번호';
COMMENT ON COLUMN reconciliation_log.attempt_no IS '[내부] 시도 번호';
COMMENT ON COLUMN reconciliation_log.check_code IS '[내부] 검사 코드';
COMMENT ON COLUMN reconciliation_log.status IS '[내부] 결과';
COMMENT ON COLUMN reconciliation_log.expected_value IS '[내부] 기대값';
COMMENT ON COLUMN reconciliation_log.actual_value IS '[내부] 실제값';
COMMENT ON COLUMN reconciliation_log.diff_value IS '[내부] 차이';
COMMENT ON COLUMN reconciliation_log.mismatch_count IS '[내부] 불일치 수';
COMMENT ON COLUMN reconciliation_log.classification IS '[내부] 분류';
COMMENT ON COLUMN reconciliation_log.detail IS '[내부] 상세';
COMMENT ON COLUMN reconciliation_log.created_at IS '[내부] 생성일시';

