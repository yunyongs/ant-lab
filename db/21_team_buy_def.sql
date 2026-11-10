-- =====================================================================
-- my Fund Manager Phase 0 — 매수 정의 (P0-15): 순수 계산 함수 5개와 매수 한 건의 함수 pg_temp.order_buy
-- 근거: docs/erd.md 7.3(cash_account·orders·execution·holding·cash_ledger 잠금 전제)·6.3(season 요율 3·turn_price·lineup)·7.5(대사 규칙 2·3·4·6·7·8·9·13·14·17·18)·
--       D1·D2·D3·D4·D7·D9·D23·D26·9절 P0-15, docs/SPRING_JPA.md §5.1(도메인 예외)·§5.2(주문 체결 10단계·락 순서)·§5.3(@Version)·§5.4(포지션 지정),
--       docs/PLAN.md §4.1-2·§4.6(수수료)·§4.10(선언 포지션·현금은 골키퍼)·§5.2(원 단위·반올림 한 번)
-- 단독 실행 대상이 아니다. db/21_team_buy.sql(팀 구성)과 db/21_team_buy_check.sql(검증)이 \ir 로 읽고,
--       두 세션 동시 매수 실습(db/README.md (f)·(g))도 세션마다 \ir 로 읽는다. 이후 기능(매매 P0-19)도 이 파일을 \ir 로 재사용한다.
-- 만드는 것: 세션 임시 함수 6개(pg_temp). 트랜잭션 문(BEGIN·COMMIT)이 없다 — 트랜잭션은 호출자가 연다. 스키마에 객체가 늘지 않는다.
-- 순수 계산(테이블을 읽지 않음):
--   pg_temp.buy_rate(market, fee_rate_kr, fee_rate_us, fx_spread_rate) → numeric(12,8)   KR = fee_rate_kr, US = fee_rate_us + fx_spread_rate (erd 규칙 14)
--   pg_temp.buy_fee(price, qty, rate) → numeric                                          ROUND(price × qty × rate, 0) — 반올림은 이 함수 한 곳 (PLAN §4.6·§5.2)
--   pg_temp.buy_affordable(available, notional, fee) → boolean                           notional + fee ≤ available
--   pg_temp.buy_avg_price(old_q, old_avg, q, price) → numeric(19,4)                      이동평균, 수수료 제외 (D9). 정수 산술로 한 번만 반올림
--   pg_temp.team_qty(budget, price, rate) → integer                                      floor(budget ÷ (price × (1 + rate))) — 수수료를 포함한 예산 안의 정수 주 (정수 산술)
-- 매수 한 건:
--   pg_temp.order_buy(season_id, expected_turn_no, asset_id, quantity, declared_position, source DEFAULT 'DIRECT') → bigint (새 orders.id)
--     하나의 트랜잭션 안에서 orders · execution · holding · cash_ledger(BUY → FEE) · cash_account · lineup 을 함께 만든다. 호출자가 COMMIT/ROLLBACK 한다.
--     순서(SPRING_JPA §5.2): 인자 검사 → 잠금 ① season FOR UPDATE → 잠금 ② cash_account FOR UPDATE → 최신 원장 읽기 → 체결가·요율 → 계산 →
--     INSERT 전에 잔액 검사 → 라인업 충돌 검사 → (1턴 이후면 거부) → orders → execution → holding(UPSERT) → cash_ledger → cash_account → lineup
--     0턴 팀 구성 전용이다: 1턴 이후에는 HINT USE_TRADE_BUY 로 거부한다 (전술 변경 연결·시각 순서 검사가 없어 규칙 16 이 어긋난다). 1턴 이후 매수는 25 의 trade_buy.
-- 잠금 순서: season → cash_account (고정). 시즌 계좌 5개 테이블에 쓰는 모든 SQL 은 먼저 season 을 잠근다. 같은 순서로 잠그면 서로 기다리는 고리(교착)가 생기지 않는다.
-- 격리 수준: READ COMMITTED (PostgreSQL 기본값)를 전제로 한다. 문장마다 새 스냅샷을 쓰므로 잠금을 얻은 뒤의 일반 SELECT 가 앞 트랜잭션이 커밋한 행을 본다.
--     다른 수준이면 첫 줄에서 멈춘다 (HINT ISOLATION_LEVEL).
--     order_buy 는 VOLATILE(기본값)이어야 한다 — 문장마다 새 스냅샷을 쓰기 때문이다. STABLE·IMMUTABLE 로 바꾸면 함수 시작 스냅샷을 써서 3단계 원장 읽기가 낡은 값을 본다.
-- 오류: 모두 RAISE EXCEPTION (SQLSTATE P0001) 이고 HINT 에 종류 코드를 넣는다. Java 도메인 예외 이름과 짝이다 (SPRING_JPA §5.1).
--     SEASON_NOT_ACTIVE · TURN_MISMATCH · ASSET_NOT_IN_POOL · INVALID_QUANTITY · INSUFFICIENT_CASH · POSITION_CONFLICT · LEDGER_MISMATCH · USE_TRADE_BUY(1턴 이후)
--     그리고 인자·환경 오류 둘: INVALID_ARGUMENT(선언 포지션·source) · ISOLATION_LEVEL.
--     메시지에 금액·가격·수량을 넣지 않는다 (터미널에 남고 PR 에 복사되기 쉽다).
-- 이번 기능은 source 로 DIRECT 만 받는다. FEED·ALERT 는 turn_feed_id 가 필요하다 (ck_orders_source_feed) — P0-19 가 자기 함수를 둔다.
-- formation_change_id 는 항상 NULL 이다. 같은 턴에 전술 변경이 있으면 P0-19 가 시즌 잠금 아래에서 그 턴의 마지막 변경을 정해 INSERT 때 함께 넣는 인자나 확장 함수를 둔다 (사후 UPDATE 로 채우지 않는다).
-- 새 문법: SELECT … FOR UPDATE(행 잠금), INSERT … RETURNING … INTO, INSERT … ON CONFLICT … DO UPDATE(UPSERT)와 EXCLUDED, RAISE … USING HINT,
--       clock_timestamp()(문장 실행 시각 — now() 는 트랜잭션 시작 시각), 세션 임시 함수 CREATE FUNCTION pg_temp.…
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 시장별 요율. 국내 = 국내 수수료율, 미국 = 미국 수수료율 + 환전 비용률 (환전 비용도 수수료 한 값 안에 들어가 FEE 한 행이 된다 — D4, PLAN §4.6)
CREATE OR REPLACE FUNCTION pg_temp.buy_rate(p_market text, p_fee_rate_kr numeric, p_fee_rate_us numeric, p_fx_spread_rate numeric)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF p_market = 'KR' THEN
        RETURN p_fee_rate_kr::numeric(12,8);
    ELSIF p_market = 'US' THEN
        RETURN (p_fee_rate_us + p_fx_spread_rate)::numeric(12,8);
    END IF;
    RAISE EXCEPTION 'P0-15 매수 정의: 알 수 없는 시장 코드다 (KR · US 만 안다)' USING HINT = 'ASSET_NOT_IN_POOL';
END $$;

-- 수수료 = ROUND(체결가 × 수량 × 요율, 0). 요율은 소수 8자리라 곱이 정확한 numeric 이고 반올림은 이 한 번뿐이다.
-- ROUND(numeric) 은 0에서 먼 쪽으로 반올림한다 (양수에서 Java HALF_UP 과 같다, erd 규칙 12).
CREATE OR REPLACE FUNCTION pg_temp.buy_fee(p_price numeric, p_qty integer, p_rate numeric)
RETURNS numeric
LANGUAGE sql IMMUTABLE AS $$
    SELECT ROUND(p_price * p_qty * p_rate, 0)
$$;

-- 주문 가능 금액(잔액 − 예약금) 안에 대금 + 수수료가 들어가는가. 정확히 같으면 참 (잔액 0 이 되는 매수도 통과).
CREATE OR REPLACE FUNCTION pg_temp.buy_affordable(p_available numeric, p_notional numeric, p_fee numeric)
RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT p_notional + p_fee <= p_available
$$;

-- 이동평균 매수 원가 (D9): 새 평균 = (기존 수량 × 기존 평균 + 체결 수량 × 체결가) ÷ 새 수량, 수수료 제외, 소수 4자리로 한 번 반올림(HALF_UP).
-- 분자에 10000 을 곱한 정수로 나눗셈을 하고 div(2n + d, 2d) 로 반올림하므로 나눗셈의 중간 반올림이 없다.
-- 기존 수량 0 (D7: 전량 매도 뒤 quantity 0 · avg_price 0 행)에서 다시 사면 체결가가 나온다.
CREATE OR REPLACE FUNCTION pg_temp.buy_avg_price(p_old_q integer, p_old_avg numeric, p_q integer, p_price numeric)
RETURNS numeric
LANGUAGE sql IMMUTABLE AS $$
    SELECT (div(2 * ((p_old_q * p_old_avg + p_q * p_price) * 10000) + (p_old_q + p_q), 2 * (p_old_q + p_q))::numeric / 10000)::numeric(19,4)
$$;

-- 줄 예산 안의 수량 = floor(예산 ÷ (체결가 × (1 + 요율))). 요율을 R/10^8 (정수 R)로 보고 정수 산술로 계산한다: div(예산 × 10^8, 체결가 × (10^8 + R)).
-- 수수료를 빼고 floor(예산 ÷ 체결가) 를 쓰면 예산을 넘을 수 있어 쓰지 않는다. 수수료 반올림이 내려가면 이 값보다 1주 더 들어가는 경우가 이론상 있으나
-- 이 공식이 정본이다 (손으로 추적한 증명은 docs/erd.md §9 P0-15 메모).
CREATE OR REPLACE FUNCTION pg_temp.team_qty(p_budget numeric, p_price numeric, p_rate numeric)
RETURNS integer
LANGUAGE sql IMMUTABLE AS $$
    SELECT div(p_budget * 100000000, p_price * (100000000 + p_rate * 100000000))::integer
$$;

-- 매수 한 건. 호출자가 트랜잭션을 연다 (이 함수는 BEGIN·COMMIT 을 하지 않는다).
CREATE OR REPLACE FUNCTION pg_temp.order_buy(
    p_season_id         bigint,
    p_expected_turn_no  smallint,
    p_asset_id          bigint,
    p_quantity          integer,
    p_declared_position text,
    p_source            text DEFAULT 'DIRECT')
RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_status      text;
    v_turn        smallint;
    v_rate_kr     numeric;
    v_rate_us     numeric;
    v_rate_fx     numeric;
    v_balance     numeric;
    v_reserved    numeric;
    v_ledger_last numeric;
    v_price       numeric;
    v_market      text;
    v_rate        numeric;
    v_notional    numeric;
    v_fee         numeric;
    v_open_pos    text;
    v_order_id    bigint;
    v_new_balance numeric;
    v_after_buy   numeric;
    v_after_fee   numeric;
BEGIN
    -- 0 인자 검사: 잠그기 전에 거른다 (잠금 시간을 줄인다). DB CHECK 는 마지막 안전망이다.
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'P0-15 매수: 격리 수준이 READ COMMITTED 가 아니다. 원장 연쇄가 이 가정에 기댄다' USING HINT = 'ISOLATION_LEVEL';
    END IF;
    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RAISE EXCEPTION 'P0-15 매수: 수량은 1 이상의 정수여야 한다' USING HINT = 'INVALID_QUANTITY';
    END IF;
    IF p_declared_position IS NULL
       OR p_declared_position NOT IN ('GOALKEEPER', 'DEFENDER', 'MIDFIELDER', 'FORWARD_TARGET', 'FORWARD_BREAKOUT', 'FORWARD_DEFENSIVE') THEN
        RAISE EXCEPTION 'P0-15 매수: 선언 포지션이 여섯 가지 안에 없다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    IF p_source IS DISTINCT FROM 'DIRECT' THEN
        RAISE EXCEPTION 'P0-15 매수: 이 함수는 source = DIRECT 만 받는다 (FEED·ALERT 는 피드 번호가 필요하다)' USING HINT = 'INVALID_ARGUMENT';
    END IF;

    -- 1 잠금 ①: 시즌. 같은 시즌의 모든 쓰기(주문·턴 전환·전술 변경)가 여기서 줄을 선다. 시즌 행은 잠그기만 하고 고치지 않는다.
    SELECT s.status, s.current_turn_no, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate
      INTO v_status, v_turn, v_rate_kr, v_rate_us, v_rate_fx
    FROM season s
    WHERE s.id = p_season_id
    FOR UPDATE;
    IF NOT FOUND OR v_status <> 'IN_PROGRESS' THEN
        RAISE EXCEPTION 'P0-15 매수: 시즌이 없거나 진행 중이 아니다' USING HINT = 'SEASON_NOT_ACTIVE';
    END IF;
    IF v_turn IS DISTINCT FROM p_expected_turn_no THEN
        RAISE EXCEPTION 'P0-15 매수: 주문이 보는 턴과 시즌의 현재 턴이 다르다' USING HINT = 'TURN_MISMATCH';
    END IF;

    -- 2 잠금 ②: 현금 계좌 (순서 season → cash_account)
    SELECT a.balance, a.reserved INTO v_balance, v_reserved
    FROM cash_account a
    WHERE a.season_id = p_season_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-15 매수: 현금 계좌가 없다' USING HINT = 'LEDGER_MISMATCH';
    END IF;

    -- 3 잠근 뒤의 읽기: 최신 원장 잔액. READ COMMITTED 는 문장마다 새 스냅샷이라 앞 트랜잭션이 커밋한 행을 본다 (P0-01 C1 의 PostgreSQL 답).
    --   계좌 잔액과 다르면 망가진 계좌 위에 원장을 더 쓰지 않고 멈춘다 (대사 규칙 3).
    SELECT l.balance_after INTO v_ledger_last
    FROM cash_ledger l
    WHERE l.season_id = p_season_id
    ORDER BY l.id DESC
    LIMIT 1;
    IF NOT FOUND OR v_ledger_last <> v_balance THEN
        RAISE EXCEPTION 'P0-15 매수: 계좌 잔액과 최신 원장의 거래 후 잔액이 다르다' USING HINT = 'LEDGER_MISMATCH';
    END IF;

    -- 4 체결가 = 그 턴의 turn_price (대사 규칙 13). 정확히 1행이 아니면 풀 밖 종목이다. 요율은 종목 시장으로 고른다 (규칙 14).
    SELECT tp.price, a.market INTO v_price, v_market
    FROM turn_price tp
    JOIN asset a ON a.id = tp.asset_id
    WHERE tp.season_id = p_season_id AND tp.turn_no = v_turn AND tp.asset_id = p_asset_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-15 매수: 이 시즌의 현재 턴 가격 풀에 없는 종목이다' USING HINT = 'ASSET_NOT_IN_POOL';
    END IF;
    v_rate := pg_temp.buy_rate(v_market, v_rate_kr, v_rate_us, v_rate_fx);

    -- 5 계산: 대금 = 체결가 × 수량(정확한 곱), 수수료 = buy_fee (반올림은 거기서 한 번)
    v_notional := v_price * p_quantity;
    v_fee      := pg_temp.buy_fee(v_price, p_quantity, v_rate);

    -- 6 INSERT 전에 잔액을 본다. 실패해도 identity(번호표)를 쓰지 않는다. reserved 는 항상 0 이지만 주문 가능 금액의 정의대로 뺀다 (erd 7.3).
    IF NOT pg_temp.buy_affordable(v_balance - v_reserved, v_notional, v_fee) THEN
        RAISE EXCEPTION 'P0-15 매수: 주문 가능 금액이 모자란다' USING HINT = 'INSUFFICIENT_CASH';
    END IF;

    -- 7 열린 라인업: 선언이 다르면 충돌. 포지션 변경은 구간을 닫고 여는 별도 동작이다 (SPRING_JPA §5.4, P0-19).
    SELECT lu.declared_position INTO v_open_pos
    FROM lineup lu
    WHERE lu.season_id = p_season_id AND lu.asset_id = p_asset_id AND lu.end_turn IS NULL;
    IF FOUND AND v_open_pos <> p_declared_position THEN
        RAISE EXCEPTION 'P0-15 매수: 이미 다른 선언 포지션으로 보유 중이다. 포지션 변경은 별도 동작이다' USING HINT = 'POSITION_CONFLICT';
    END IF;

    -- 7b 1턴 이후에는 쓰지 않는다 (P0-19 리뷰 R1): 이 함수는 전술 변경 연결(formation_change_id)과 시각 순서 검사를 하지 않는다.
    --   같은 턴에 전술 변경이 있으면 주문이 변경에 묶이지 않아 대사 규칙 16 이 어긋나고 턴 전환이 막힌다. 주문은 불변이라 고칠 길이 없다.
    --   1턴 이후 매수는 25 의 trade_buy 를 쓴다. 위의 검사들 뒤에 두어 다른 오류 사례를 가리지 않는다.
    IF v_turn > 0 THEN
        RAISE EXCEPTION 'P0-15 매수: 1턴 이후의 매수는 이 함수로 하지 않는다 (전술 변경 연결이 없다). 25 의 trade_buy 를 쓴다' USING HINT = 'USE_TRADE_BUY';
    END IF;

    -- 8 주문: 접수와 체결이 한 번 (시장가). created_at 은 잠금을 얻은 뒤의 시각 clock_timestamp() — 대사 규칙 16 의 순서 기준.
    INSERT INTO orders (season_id, asset_id, turn_no, side, type, quantity, limit_price, status, source,
                        formation_change_id, turn_feed_id, created_at)
    VALUES (p_season_id, p_asset_id, v_turn, 'BUY', 'MARKET', p_quantity, NULL, 'FILLED', p_source,
            NULL, NULL, clock_timestamp())
    RETURNING id INTO v_order_id;

    -- 9 체결: 주문 1 : 체결 1 (D3). 매수는 realized_pnl NULL · tax 0 (규칙 8)
    INSERT INTO execution (order_id, fill_price, quantity, fee, tax, realized_pnl)
    VALUES (v_order_id, v_price, p_quantity, v_fee, 0, NULL);

    -- 10 보유 UPSERT: 첫 매수는 평균 = 체결가. 이미 있으면(전량 매도 뒤 0 · 0 행 포함) 이동평균 (D9)
    INSERT INTO holding (season_id, asset_id, quantity, avg_price)
    VALUES (p_season_id, p_asset_id, p_quantity, v_price)
    ON CONFLICT (season_id, asset_id) DO UPDATE
       SET quantity   = holding.quantity + EXCLUDED.quantity,
           avg_price  = pg_temp.buy_avg_price(holding.quantity, holding.avg_price, EXCLUDED.quantity, v_price),
           updated_at = now();

    -- 11 원장: BUY = 체결 대금만, 비용은 따로 FEE (D4 — 합치면 규칙 4 의 유형별 진단이 잡는다). FEE 가 0원이면 행을 만들지 않는다.
    --   한 매수의 원장은 항상 BUY → FEE 순서다 (id 순서 = 기록 순서, 잠금 전제).
    v_after_buy := v_balance - v_notional;
    INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
    VALUES (p_season_id, 'BUY', -v_notional, v_after_buy, v_order_id);
    v_after_fee := v_after_buy;
    IF v_fee > 0 THEN
        v_after_fee := v_after_buy - v_fee;
        INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
        VALUES (p_season_id, 'FEE', -v_fee, v_after_fee, v_order_id);
    END IF;

    -- 12 계좌: 결과 잔액이 원장 마지막 거래 후 잔액과 다르면 멈춘다. version + 1 은 JPA @Version 을 흉내 낸다 (SPRING_JPA §5.3).
    --   ck_cash_account_balance_nonneg 는 6단계가 있어 닿지 않는 마지막 안전망이다.
    UPDATE cash_account
       SET balance = balance - v_notional - v_fee, version = version + 1, updated_at = now()
     WHERE season_id = p_season_id
    RETURNING balance INTO v_new_balance;
    IF v_new_balance IS DISTINCT FROM v_after_fee THEN
        RAISE EXCEPTION 'P0-15 매수: 갱신한 잔액이 원장의 마지막 거래 후 잔액과 다르다' USING HINT = 'LEDGER_MISMATCH';
    END IF;

    -- 13 라인업: 신규 보유 = 구간 열기 (열린 구간 하나는 uq_lineup_open 이 DDL 로 지킨다). 같은 선언이 이미 열려 있으면 아무것도 하지 않는다.
    IF NOT EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = p_season_id AND lu.asset_id = p_asset_id AND lu.end_turn IS NULL) THEN
        INSERT INTO lineup (season_id, asset_id, declared_position, start_turn, end_turn)
        VALUES (p_season_id, p_asset_id, p_declared_position, v_turn, NULL);
    END IF;

    -- 14
    RETURN v_order_id;
END $$;
