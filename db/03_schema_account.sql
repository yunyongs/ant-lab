-- =====================================================================
-- my Fund Manager Phase 0 — DDL ③ 시즌 계좌 5개 테이블 (P0-07)
-- 근거: docs/erd.md 7.3(엔티티 정의서), 7.4(제약과 인덱스), 2절(공통 규칙), 8절 D1·D2·D3·D4·D6·D7·D9·D29, 9절 P0-07
-- 대상: PostgreSQL 17, UTF8 DB, 스키마 public 하나 (이름에 스키마 접두를 붙이지 않는다)
-- 실행: psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/03_schema_account.sql
--       DB는 미리 만들고 이름은 실행할 때 고른다 (db/README.md "실행 방법").
--       `psql -1`(--single-transaction)은 쓰지 않는다. 파일 안에 BEGIN/COMMIT이 있어 겹치면 WARNING이 난다.
-- 주의: 이 파일의 5개 테이블을 지우고 다시 만든다. 들어 있던 데이터는 모두 사라진다.
--       DROP에 CASCADE를 쓰지 않는다. 뒤 번호 파일(04 이후)의 테이블이 이 테이블을 참조하고 있으면
--       DROP이 2BP01(dependent_objects_still_exist)로 멈춘다. 안전한 실패이며, 전체를 다시 만들 때는
--       db/README.md의 "DB 다시 만들기"를 쓴다.
--       반대로 이 파일이 있는 DB에서 02를 다시 실행하면 02의 turn_feed가 orders의 FK에 걸려 2BP01로 멈춘다.
--       02를 고쳐야 하면 03부터 지우고 02 → 03 순으로 다시 만든다.
-- 전제: 01_schema_history.sql과 02_schema_season.sql을 먼저 실행해 둔다
--       (asset, season, formation_change, turn_feed 4개를 FK로 참조한다).
--       02 테이블이 없으면 02를 가리키는 첫 FK(cash_account → season)에서 42P01로 멈추고 아무것도 남지 않는다 (안전한 실패).
--       DB timezone = 'Asia/Seoul' (표시와 ::date 변환용), DB 인코딩 UTF8
--       (db/README.md "실행 환경". 03_schema_account_check.sql 0절이 확인한다)
-- 이름: pk_{t}, uq_{t}_{cols}, fk_{t}_{부모 또는 컬럼}, ck_{t}_{규칙}, idx_{t}_{cols}. 63바이트 이하, 소문자 snake_case.
--       CHECK는 모두 컬럼 목록 뒤 테이블 제약으로 쓴다. 이름과 식은 erd 7.4 표 글자 그대로다.
-- 코멘트: 코멘트의 노출 태그는 상한이다. `[노출]` 컬럼의 노출 조건(괄호)은 erd 7.3이 정본이다.
-- 타입: 금액 NUMERIC(19,4), 비율 NUMERIC(12,8), 구분값 VARCHAR(n) + CHECK. 수량은 INTEGER, 턴 번호는 SMALLINT.
--       고정 길이 CHAR, ENUM, 부동소수 타입을 쓰지 않는다.
--       NUMERIC은 'NaN'을 받는다(NaN = NaN이 참이고 어떤 수보다도 크다). 그래서 금액 CHECK마다 <> 'NaN'::numeric을 함께 둔다 (erd D32). 무한대는 정밀도 지정 NUMERIC이 22003으로 거부한다.
--       orders.status VARCHAR(10)과 cash_ledger.type VARCHAR(12)는 v1 폭 그대로다 (erd 2절 "가장 긴 값"의 예외, 7.6).
-- 추가 전용: cash_ledger·execution의 UPDATE/DELETE를 이 DDL은 막지 않는다. 규칙과 권한으로 지킨다(erd 7.5 규칙 10 — MVP의 @Immutable·GRANT/REVOKE). Phase 0–1 SQL은 INSERT만 쓴다.
-- 기본값: D30 컬럼(요율·초기 자금 등)은 season에 있고 이 파일에는 없다.
--       orders.source에는 DB 기본값이 없다 — 서버가 반드시 기록한다. orders.status 기본값은 PENDING이지만
--       v2 주문은 FILLED로 넣는다 (SPRING_JPA 5.2 5단계). orders.turn_no와 cash_ledger.type·amount·balance_after에도 기본값이 없다.
-- 수수료: 체결 수수료 공식은 DDL에 없다. DDL은 execution.fee·tax가 0 이상이고 원 단위인 것만 막는다.
--       공식(fill_price × quantity × rate, 반올림은 한 번)은 P0-15·P0-19가 계산하고 대사 규칙 14(FEE_RULE)가 검사한다.
--       CHECK는 다른 테이블(season, asset)을 읽을 수 없고, 트리거로 계산하면 반올림이 DB와 코드 두 곳이 된다 (PLAN 5.2).
-- 복합 FK: fk_orders_formation_change, fk_orders_turn_feed, fk_cash_ledger_order는 다중 컬럼 FK이다 (erd D29).
--       MATCH는 쓰지 않는다 (기본 SIMPLE — 자식 컬럼 중 하나라도 NULL이면 검사하지 않는다). 대상 컬럼 목록과 정확히 같은
--       UNIQUE가 02(uq_formation_change_id_season_turn, uq_turn_feed_id_season)와 이 파일(uq_orders_id_season)에 있다.
-- 인덱스: UNIQUE 인덱스(제약이 아닌 uq_)는 이 파일에 없다. uq_orders_id_season, uq_holding_season_asset은 제약이다.
-- 안전: DROP 전에 9개 이름(이 파일의 5개 + FK가 가리키는 season, asset, formation_change, turn_feed 4개)과
--       current_schema()가 public을 가리키는지 확인하고, 아니면 아무것도 지우지 않고 멈춘다
--       (search_path 앞쪽 스키마의 같은 이름 테이블을 지우지 않거나 엉뚱한 테이블에 FK를 걸지 않기 위해서다).
-- 구조: [재실행 블록]은 이 파일의 테이블만 생성의 역순으로 지운다. 01·02의 테이블은 지우지 않는다. [스키마]는 erd 9절 생성 순서대로 만든다.
--       나중에 Flyway 마이그레이션으로 옮길 때는 [스키마] 부분만 복사한다(DROP, BEGIN, COMMIT은 뺀다).
-- =====================================================================

BEGIN;

-- [이름 해석 확인] 9개 이름이 모두 public으로 해석되고 current_schema()가 public일 때만 계속한다.
-- 빈 DB에서는 양쪽이 NULL이라 통과한다 (02가 없을 때의 멈춤은 아래 첫 REFERENCES가 맡는다). 이 블록은 [스키마] 경계 밖이다.
DO $$
DECLARE
    v_bad text;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['cash_account', 'orders', 'execution', 'holding', 'cash_ledger',
                      'season', 'asset', 'formation_change', 'turn_feed']) AS n
    WHERE to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-07 스키마: 이름이 public이 아닌 곳을 가리킨다 (current_schema=%, 어긋난 이름: %). 아무것도 지우지 않고 멈춘다. search_path를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
END $$;

-- [재실행 블록] 자기 테이블만, 생성의 역순, CASCADE 없음
DROP TABLE IF EXISTS cash_ledger;
DROP TABLE IF EXISTS holding;
DROP TABLE IF EXISTS execution;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS cash_account;
-- [/재실행 블록]

-- [스키마] 생성 순서: cash_account → orders → execution → holding → cash_ledger

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

-- [/스키마]

COMMIT;
