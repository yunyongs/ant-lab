
-- 현금 계좌 (식별 1:1 — PK가 곧 FK, identity 없음)
CREATE TABLE cash_account (
    season_id  BIGINT        NOT NULL,
    balance    NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserved   NUMERIC(19,4) NOT NULL DEFAULT 0,
    version    BIGINT        NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cash_account PRIMARY KEY (season_id),
    CONSTRAINT fk_cash_account_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_cash_account_balance_nonneg CHECK (balance >= 0),
    CONSTRAINT ck_cash_account_reserved_range CHECK (reserved >= 0 AND reserved <= balance),
    CONSTRAINT ck_cash_account_balance_won CHECK (balance = ROUND(balance, 0) AND balance <> 'NaN'::numeric),
    CONSTRAINT ck_cash_account_reserved_won CHECK (reserved = ROUND(reserved, 0) AND reserved <> 'NaN'::numeric)
);
COMMENT ON TABLE cash_account IS '현금 계좌';
COMMENT ON COLUMN cash_account.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN cash_account.balance IS '[노출] 잔액';
COMMENT ON COLUMN cash_account.reserved IS '[내부] 예약금';
COMMENT ON COLUMN cash_account.version IS '[내부] 낙관적 락 버전';
COMMENT ON COLUMN cash_account.created_at IS '[내부] 생성일시';
COMMENT ON COLUMN cash_account.updated_at IS '[내부] 수정일시';

-- 주문
CREATE TABLE orders (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY,
    season_id           BIGINT        NOT NULL,
    asset_id            BIGINT        NOT NULL,
    turn_no             SMALLINT      NOT NULL,
    side                VARCHAR(4)    NOT NULL,
    type                VARCHAR(6)    NOT NULL DEFAULT 'MARKET',
    quantity            INTEGER       NOT NULL,
    limit_price         NUMERIC(19,4),
    status              VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    source              VARCHAR(7)    NOT NULL,
    formation_change_id BIGINT,
    turn_feed_id        BIGINT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uq_orders_id_season UNIQUE (id, season_id),
    CONSTRAINT fk_orders_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_orders_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_orders_formation_change FOREIGN KEY (formation_change_id, season_id, turn_no) REFERENCES formation_change (id, season_id, turn_no)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_orders_turn_feed FOREIGN KEY (turn_feed_id, season_id) REFERENCES turn_feed (id, season_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_orders_side CHECK (side IN ('BUY','SELL')),
    CONSTRAINT ck_orders_type CHECK (type IN ('MARKET','LIMIT')),
    CONSTRAINT ck_orders_quantity_pos CHECK (quantity > 0),
    CONSTRAINT ck_orders_limit_price CHECK ((type = 'MARKET' AND limit_price IS NULL) OR (type = 'LIMIT' AND limit_price IS NOT NULL AND limit_price > 0)),
    CONSTRAINT ck_orders_limit_price_won CHECK (limit_price = ROUND(limit_price, 0) AND limit_price <> 'NaN'::numeric),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING','FILLED','CANCELED','REJECTED')),
    CONSTRAINT ck_orders_turn_no CHECK (turn_no >= 0),
    CONSTRAINT ck_orders_source CHECK (source IN ('RANKING','FEED','ALERT','BENCH','DIRECT')),
    CONSTRAINT ck_orders_source_feed CHECK ((source IN ('FEED','ALERT')) = (turn_feed_id IS NOT NULL))
);
CREATE INDEX idx_orders_season_turn ON orders (season_id, turn_no);
CREATE INDEX idx_orders_asset ON orders (asset_id);
CREATE INDEX idx_orders_formation_change ON orders (formation_change_id);
CREATE INDEX idx_orders_turn_feed ON orders (turn_feed_id);
COMMENT ON TABLE orders IS '주문';
COMMENT ON COLUMN orders.id IS '[내부] 주문번호';
COMMENT ON COLUMN orders.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN orders.asset_id IS '[내부] 종목번호';
COMMENT ON COLUMN orders.turn_no IS '[노출] 턴 번호';
COMMENT ON COLUMN orders.side IS '[노출] 매수매도 구분';
COMMENT ON COLUMN orders.type IS '[내부] 주문 유형';
COMMENT ON COLUMN orders.quantity IS '[노출] 수량';
COMMENT ON COLUMN orders.limit_price IS '[내부] 지정가';
COMMENT ON COLUMN orders.status IS '[내부] 주문 상태';
COMMENT ON COLUMN orders.source IS '[노출] 출발점';
COMMENT ON COLUMN orders.formation_change_id IS '[내부] 전술 변경번호';
COMMENT ON COLUMN orders.turn_feed_id IS '[내부] 피드 항목번호';
COMMENT ON COLUMN orders.created_at IS '[내부] 주문일시';

-- 체결
CREATE TABLE execution (
    id           BIGINT        GENERATED ALWAYS AS IDENTITY,
    order_id     BIGINT        NOT NULL,
    fill_price   NUMERIC(19,4) NOT NULL,
    quantity     INTEGER       NOT NULL,
    fee          NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax          NUMERIC(19,4) NOT NULL DEFAULT 0,
    realized_pnl NUMERIC(19,4),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_execution PRIMARY KEY (id),
    CONSTRAINT fk_execution_order FOREIGN KEY (order_id) REFERENCES orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_execution_fill_price_pos CHECK (fill_price > 0),
    CONSTRAINT ck_execution_fill_price_won CHECK (fill_price = ROUND(fill_price, 0) AND fill_price <> 'NaN'::numeric),
    CONSTRAINT ck_execution_quantity_pos CHECK (quantity > 0),
    CONSTRAINT ck_execution_fee_nonneg CHECK (fee >= 0),
    CONSTRAINT ck_execution_tax_nonneg CHECK (tax >= 0),
    CONSTRAINT ck_execution_fee_won CHECK (fee = ROUND(fee, 0) AND fee <> 'NaN'::numeric),
    CONSTRAINT ck_execution_tax_won CHECK (tax = ROUND(tax, 0) AND tax <> 'NaN'::numeric),
    CONSTRAINT ck_execution_realized_pnl_not_nan CHECK (realized_pnl <> 'NaN'::numeric)
);
CREATE INDEX idx_execution_order ON execution (order_id);
COMMENT ON TABLE execution IS '체결';
COMMENT ON COLUMN execution.id IS '[내부] 체결번호';
COMMENT ON COLUMN execution.order_id IS '[내부] 주문번호';
COMMENT ON COLUMN execution.fill_price IS '[노출] 체결가';
COMMENT ON COLUMN execution.quantity IS '[노출] 체결 수량';
COMMENT ON COLUMN execution.fee IS '[노출] 수수료';
COMMENT ON COLUMN execution.tax IS '[노출] 세금';
COMMENT ON COLUMN execution.realized_pnl IS '[노출] 실현 손익';
COMMENT ON COLUMN execution.created_at IS '[내부] 체결일시';

-- 보유
CREATE TABLE holding (
    id         BIGINT        GENERATED ALWAYS AS IDENTITY,
    season_id  BIGINT        NOT NULL,
    asset_id   BIGINT        NOT NULL,
    quantity   INTEGER       NOT NULL,
    avg_price  NUMERIC(19,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_holding PRIMARY KEY (id),
    CONSTRAINT uq_holding_season_asset UNIQUE (season_id, asset_id),
    CONSTRAINT fk_holding_season FOREIGN KEY (season_id) REFERENCES season (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_holding_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_holding_quantity_avg_price CHECK ((quantity = 0 AND avg_price = 0) OR (quantity > 0 AND avg_price > 0 AND avg_price <> 'NaN'::numeric))
);
CREATE INDEX idx_holding_asset ON holding (asset_id);
COMMENT ON TABLE holding IS '보유';
COMMENT ON COLUMN holding.id IS '[내부] 보유번호';
COMMENT ON COLUMN holding.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN holding.asset_id IS '[내부] 종목번호';
COMMENT ON COLUMN holding.quantity IS '[노출] 보유 수량';
COMMENT ON COLUMN holding.avg_price IS '[노출] 평균단가';
COMMENT ON COLUMN holding.created_at IS '[내부] 생성일시';
COMMENT ON COLUMN holding.updated_at IS '[내부] 수정일시';

-- 현금 원장 (원장은 계좌의 원장 — FK는 season이 아니라 cash_account를 가리킨다)
CREATE TABLE cash_ledger (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY,
    season_id     BIGINT        NOT NULL,
    type          VARCHAR(12)   NOT NULL,
    amount        NUMERIC(19,4) NOT NULL,
    balance_after NUMERIC(19,4) NOT NULL,
    ref_order_id  BIGINT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cash_ledger PRIMARY KEY (id),
    CONSTRAINT fk_cash_ledger_cash_account FOREIGN KEY (season_id) REFERENCES cash_account (season_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_cash_ledger_order FOREIGN KEY (ref_order_id, season_id) REFERENCES orders (id, season_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_cash_ledger_type CHECK (type IN ('DEPOSIT','BUY','SELL','FEE','TAX','ADJUSTMENT')),
    CONSTRAINT ck_cash_ledger_amount_sign CHECK ((type IN ('DEPOSIT','SELL') AND amount > 0) OR (type IN ('BUY','FEE','TAX') AND amount < 0) OR (type = 'ADJUSTMENT' AND amount <> 0)),
    CONSTRAINT ck_cash_ledger_balance_after_nonneg CHECK (balance_after >= 0),
    CONSTRAINT ck_cash_ledger_amount_won CHECK (amount = ROUND(amount, 0) AND amount <> 'NaN'::numeric),
    CONSTRAINT ck_cash_ledger_balance_after_won CHECK (balance_after = ROUND(balance_after, 0) AND balance_after <> 'NaN'::numeric),
    CONSTRAINT ck_cash_ledger_ref_order CHECK ((type IN ('BUY','SELL','FEE','TAX') AND ref_order_id IS NOT NULL) OR (type = 'DEPOSIT' AND ref_order_id IS NULL) OR type = 'ADJUSTMENT')
);
CREATE INDEX idx_cash_ledger_season_id ON cash_ledger (season_id, id);
CREATE INDEX idx_cash_ledger_ref_order ON cash_ledger (ref_order_id);
COMMENT ON TABLE cash_ledger IS '현금 원장';
COMMENT ON COLUMN cash_ledger.id IS '[내부] 원장번호';
COMMENT ON COLUMN cash_ledger.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN cash_ledger.type IS '[노출] 원장 유형';
COMMENT ON COLUMN cash_ledger.amount IS '[노출] 부호 있는 금액';
COMMENT ON COLUMN cash_ledger.balance_after IS '[노출] 거래 후 잔액';
COMMENT ON COLUMN cash_ledger.ref_order_id IS '[내부] 참조 주문번호';
COMMENT ON COLUMN cash_ledger.created_at IS '[내부] 기록일시';

