-- =====================================================================
-- my Fund Manager Phase 0 — 턴 사이 매매와 전술 변경 정의 (P0-19): 매수 확장·매도·전술 변경·포지션 변경·변경 비용 조회
-- 근거: docs/erd.md 6.3(season·lineup·formation_change·orders·execution)·7.3(잠금 전제)·7.5(대사 규칙 2·3·4·6·7·8·9·13·14·15·16·17·18)·6.5 S6·S7·
--       D4·D7·D9·D23·D26·D29–D31·9절 P0-19, docs/SPRING_JPA.md §5.1(도메인 예외)·§5.2(주문 체결 10단계·락 순서)·§5.4(포지션 지정·전술 변경),
--       docs/PLAN.md §4.1-5·§4.6(수수료)·§4.10(전술 변경 이력·선언 포지션)·§4.13(주문 출발점)·§5.1·§5.2(원 단위·반올림 한 번)
-- 단독 실행 대상이 아니다. db/25_trade_formation.sql(5턴 뒤 변경·매매 시나리오)·db/25_trade_formation_check.sql(검증)·db/25_trade_formation_drill.sql(드릴)이 \ir 로 읽는다.
--       반드시 db/21_team_buy_def.sql 을 먼저 읽은 뒤(같은 세션, 트랜잭션 밖)에 이 파일을 읽는다 — 이 파일이 21 의 순수 계산 함수(buy_rate·buy_fee·buy_avg_price·buy_affordable)를 재사용한다.
--       21 은 이 파일을 읽지 않는다 (순서가 곧 의존). 21·22·23 의 정의·스키마는 이 파일이 고치지 않는다.
-- 만드는 것: 세션 임시 함수(pg_temp)뿐이다. 트랜잭션 문(BEGIN·COMMIT)이 없다 — 트랜잭션은 호출자가 연다. 스키마에 객체가 늘지 않는다.
--   pg_temp.tr_open(시즌, 기대 턴, 계좌 잠금 여부)             공통 입구: 인자·격리 수준 → season FOR UPDATE → 상태·턴 → (선택) cash_account FOR UPDATE → 최신 원장 확인. OUT: 요율 3개·잔액·예약금
--   pg_temp.tr_source(시즌, 턴, 출발점, 피드 번호)             출발점 enum·NULL 계약·피드 참조(같은 시즌·노출·종류) 검사 (S6·S7)
--   pg_temp.tr_event_time(시즌, 턴)                           잠금 뒤 clock_timestamp() 를 정하고 같은 시즌·턴의 앞선 주문·변경보다 늦은지 본다 (규칙 16 의 전제)
--   pg_temp.tr_last_change(시즌, 턴)                          그 턴의 마지막 전술 변경 번호 (없으면 NULL) — 주문의 formation_change_id
--   pg_temp.trade_buy(시즌, 기대 턴, 종목, 수량, 선언 포지션, 출발점, 피드 번호)  → orders.id   21 의 order_buy 확장: 출발점·피드·변경 연결을 INSERT 때 함께 넣는다
--   pg_temp.trade_sell(시즌, 기대 턴, 종목, 수량, 출발점, 피드 번호)             → orders.id   부분·전량 매도, 실현 손익(D9), 전량 매도 시 구간 닫기(end_turn = t)
--   pg_temp.formation_change(시즌, 기대 턴, 골키퍼, 수비, 미드필더, 공격, 사유)  → formation_change.id   변경 이력 INSERT + season.current_* 갱신
--   pg_temp.lineup_change(시즌, 기대 턴, 종목, 새 선언 포지션)                  → lineup.id (새 구간, 같은 포지션이면 열린 구간)   구간 닫기 + 열기
--   pg_temp.formation_costs(시즌)                                              변경별 매수·매도 체결 대금·수수료·세금 집계 (조회 전용, 저장하지 않는다 — D31)
-- 공통 순서(SPRING_JPA §5.2): 인자 검사 → 잠금 ① season → 상태·턴 재검사 → 잠금 ② cash_account → 최신 원장 = 계좌 잔액 → 체결가·요율 → 출발점·잔액·보유·라인업 검사 →
--       주문 시각 확정 → orders → execution → holding → cash_ledger → cash_account → lineup. 호출자가 COMMIT/ROLLBACK 한다. 실패하면 REJECTED 주문도 남기지 않는다.
-- 잠금 순서: season → cash_account (고정). 전술 변경·포지션 변경은 cash_account 를 쓰지 않으므로 season 만 잠근다.
-- 격리 수준: READ COMMITTED 를 전제로 한다. 쓰기 함수는 VOLATILE(기본값)이어야 한다 (문장마다 새 스냅샷). 조회 보조(tr_last_change·formation_costs)는 STABLE 이어도 된다 (호출 문장의 스냅샷).
-- 진입점은 0턴을 거부한다 (0턴 주문은 P0-15 팀 구성만 만든다). 기대 턴은 1 이상이어야 한다.
-- 오류: 모두 RAISE EXCEPTION (SQLSTATE P0001) + HINT 종류 코드. 메시지에 금액·가격·수량을 넣지 않는다.
--   21 과 같은 것: ISOLATION_LEVEL · INVALID_ARGUMENT · INVALID_QUANTITY · SEASON_NOT_ACTIVE · TURN_MISMATCH · ASSET_NOT_IN_POOL · INSUFFICIENT_CASH · LEDGER_MISMATCH · POSITION_CONFLICT
--   이 파일이 더하는 것: INSUFFICIENT_HOLDING(미보유·초과 매도) · INVALID_FEED_REFERENCE(없는 피드·다른 시즌·미노출·종류 불일치·허용 밖 턴) · INVALID_FORMATION(비중·동일 포메이션·사슬) ·
--                         LINEUP_MISMATCH(보유와 열린 구간 불일치) · EVENT_TIME_CONFLICT(변경·주문 시각의 순서를 확정할 수 없음)
--   Java 예외·HTTP 매핑은 P0-36·P0-37 이 정한다.
-- 출발점(source): DIRECT·RANKING·BENCH 는 피드 NULL, FEED 는 피드 필수(kind <> NUDGE), ALERT 는 피드 필수(kind = NUDGE). 피드는 같은 시즌이고 이미 노출된(turn_no ≤ 현재 턴) 것이다.
--   ★ 소유자 결정 대기 (P0-19 계획 Q1): 지난 턴에 노출된 피드에서 시작한 현재 턴 주문을 허용할지는 정해지지 않았다. 정책이 정해지기 전까지 이 파일은
--     피드의 턴이 현재 턴과 같은 것만 받고 지난 턴 피드는 INVALID_FEED_REFERENCE 로 거절한다 (tr_source 한 곳). 정책 확정이 아니다.
--   복합 FK(turn_feed_id, season_id)는 같은 시즌만 보장한다. 노출 시점·종류 일치는 DDL 이 못 막으므로 이 함수가 한다.
-- 전술 변경과 주문 묶기(규칙 16): 변경·주문의 created_at 은 season 잠금을 얻은 뒤의 clock_timestamp() 다. 주문은 같은 시즌·같은 턴에서 자기보다 앞선 마지막 변경에 묶인다
--   (DIRECT 를 포함한 모든 출발점). 사후 UPDATE 로 채우지 않는다. 앞선 이벤트와 시각이 같거나 거꾸로면 순서를 확정할 수 없으므로 쓰기 전에 EVENT_TIME_CONFLICT 로 멈춘다 (id 로 추정하거나 시각을 보정하지 않는다).
-- 구간 규칙: 신규 매수·전량 매도 뒤 재매수 = start_turn = t 로 열기. 같은 포지션 추가 매수·부분 매도 = 열린 구간 유지. 전량 매도 = end_turn = t.
--   저장 구간은 반열림 [start_turn, end_turn) 로 겹침을 보고, 과거 스냅샷 k 에 기여하는 구간은 start_turn < k ≤ end_turn 이다 (열린 끝은 무한대). 두 조건을 섞지 않는다.
--   같은 턴에 사고 전량 팔면 길이 없는 구간 [t, t) 이 남는다 (허용 — ck_lineup_turns 는 end ≥ start).
-- 단일 종목 비중 한도는 강제하지 않는다 (erd 6.5 가 후속 서비스 규칙으로 분리). 세금·지정가·공매도·레버리지도 이 기능이 아니다.
-- 새 문법: OUT 매개변수가 있는 함수와 SELECT … INTO, SELECT … FOR UPDATE 로 보유 행 잠그기, FILTER 집계, LEFT JOIN 으로 주문 없는 변경 남기기, 주문 단위로 먼저 집계해 조인 증폭 막기.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 공통 입구. 인자·격리 수준 → 잠금 ① → 상태·턴 → (선택) 잠금 ② → 최신 원장 확인.
CREATE OR REPLACE FUNCTION pg_temp.tr_open(
    p_season_id bigint, p_expected_turn smallint, p_with_cash boolean,
    OUT o_rate_kr numeric, OUT o_rate_us numeric, OUT o_rate_fx numeric, OUT o_balance numeric, OUT o_reserved numeric)
RETURNS record
LANGUAGE plpgsql AS $$
DECLARE
    v_status      text;
    v_turn        smallint;
    v_ledger_last numeric;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'P0-19 매매: 격리 수준이 READ COMMITTED 가 아니다. 잠금 뒤 읽기가 이 가정에 기댄다' USING HINT = 'ISOLATION_LEVEL';
    END IF;
    IF p_season_id IS NULL OR p_expected_turn IS NULL THEN
        RAISE EXCEPTION 'P0-19 매매: 시즌 번호와 기대 턴은 비어 있을 수 없다' USING HINT = 'INVALID_ARGUMENT';
    END IF;

    -- 잠금 ①: 시즌. 같은 시즌의 모든 쓰기(주문·턴 전환·전술 변경)가 여기서 줄을 선다. 시즌 행은 잠그기만 하고 이 함수는 고치지 않는다.
    SELECT s.status, s.current_turn_no, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate
      INTO v_status, v_turn, o_rate_kr, o_rate_us, o_rate_fx
    FROM season s
    WHERE s.id = p_season_id
    FOR UPDATE;
    IF NOT FOUND OR v_status <> 'IN_PROGRESS' THEN
        RAISE EXCEPTION 'P0-19 매매: 시즌이 없거나 진행 중이 아니다' USING HINT = 'SEASON_NOT_ACTIVE';
    END IF;
    IF v_turn IS DISTINCT FROM p_expected_turn THEN
        RAISE EXCEPTION 'P0-19 매매: 요청이 보는 턴과 시즌의 현재 턴이 다르다' USING HINT = 'TURN_MISMATCH';
    END IF;
    IF v_turn < 1 THEN
        RAISE EXCEPTION 'P0-19 매매: 0턴 주문은 팀 구성(P0-15)만 만든다. 1턴 이후에만 매매·변경한다' USING HINT = 'TURN_MISMATCH';
    END IF;

    IF p_with_cash THEN
        -- 잠금 ②: 현금 계좌 (순서 season → cash_account)
        SELECT a.balance, a.reserved INTO o_balance, o_reserved
        FROM cash_account a
        WHERE a.season_id = p_season_id
        FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'P0-19 매매: 현금 계좌가 없다' USING HINT = 'LEDGER_MISMATCH';
        END IF;
        -- 잠근 뒤의 읽기: 최신 원장 잔액이 계좌 잔액과 같아야 한다 (대사 규칙 3). 다르면 망가진 계좌 위에 쓰지 않고 멈춘다.
        SELECT l.balance_after INTO v_ledger_last
        FROM cash_ledger l
        WHERE l.season_id = p_season_id
        ORDER BY l.id DESC
        LIMIT 1;
        IF NOT FOUND OR v_ledger_last <> o_balance THEN
            RAISE EXCEPTION 'P0-19 매매: 계좌 잔액과 최신 원장의 거래 후 잔액이 다르다' USING HINT = 'LEDGER_MISMATCH';
        END IF;
        IF o_reserved < 0 OR o_reserved > o_balance THEN
            RAISE EXCEPTION 'P0-19 매매: 예약금이 0 미만이거나 잔액을 넘는다' USING HINT = 'LEDGER_MISMATCH';
        END IF;
    END IF;
END $$;

-- 출발점 검사. 잠금 ①을 얻은 뒤 부른다 (피드 행은 추가 전용이라 잠금이 필요하진 않지만, 현재 턴은 잠금 아래에서 읽어야 한다).
CREATE OR REPLACE FUNCTION pg_temp.tr_source(p_season_id bigint, p_turn smallint, p_source text, p_feed_id bigint)
RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_kind text;
    v_feed_turn smallint;
BEGIN
    IF p_source IS NULL OR p_source NOT IN ('RANKING', 'FEED', 'ALERT', 'BENCH', 'DIRECT') THEN
        RAISE EXCEPTION 'P0-19 매매: 출발점이 다섯 가지 안에 없다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    IF (p_source IN ('FEED', 'ALERT')) <> (p_feed_id IS NOT NULL) THEN
        RAISE EXCEPTION 'P0-19 매매: FEED·ALERT 는 피드 번호가 필요하고 그 밖의 출발점은 피드 번호가 없어야 한다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    IF p_feed_id IS NULL THEN
        RETURN;
    END IF;
    SELECT f.kind, f.turn_no INTO v_kind, v_feed_turn
    FROM turn_feed f
    WHERE f.id = p_feed_id AND f.season_id = p_season_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-19 매매: 이 시즌에 그런 피드가 없다' USING HINT = 'INVALID_FEED_REFERENCE';
    END IF;
    IF (p_source = 'ALERT') <> (v_kind = 'NUDGE') THEN
        RAISE EXCEPTION 'P0-19 매매: 피드 종류가 출발점과 맞지 않는다 (ALERT = NUDGE, FEED = NUDGE 가 아님)' USING HINT = 'INVALID_FEED_REFERENCE';
    END IF;
    IF v_feed_turn > p_turn THEN
        RAISE EXCEPTION 'P0-19 매매: 아직 노출되지 않은 피드다' USING HINT = 'INVALID_FEED_REFERENCE';
    END IF;
    -- ★ 소유자 결정 대기(계획 Q1): 지난 턴에 노출된 피드로 현재 턴 주문을 시작할 수 있는지는 정해지지 않았다.
    --   정책이 정해질 때까지 현재 턴 피드만 받는다. 이 한 곳만 바꾸면 정책이 바뀐다 (확정 아님).
    IF v_feed_turn < p_turn THEN
        RAISE EXCEPTION 'P0-19 매매: 지난 턴 피드에서 시작한 주문은 아직 받지 않는다 (소유자 결정 대기)' USING HINT = 'INVALID_FEED_REFERENCE';
    END IF;
END $$;

-- 주문·변경 시각. 잠금을 얻은 뒤 clock_timestamp() 를 읽고, 같은 시즌·턴의 앞선 주문·변경보다 늦어야 한다.
CREATE OR REPLACE FUNCTION pg_temp.tr_event_time(p_season_id bigint, p_turn smallint)
RETURNS timestamptz
LANGUAGE plpgsql AS $$
DECLARE
    v_now  timestamptz := clock_timestamp();
    v_prev timestamptz;
BEGIN
    SELECT max(x.t) INTO v_prev
    FROM (SELECT o.created_at AS t FROM orders o WHERE o.season_id = p_season_id AND o.turn_no = p_turn
          UNION ALL
          SELECT fc.created_at FROM formation_change fc WHERE fc.season_id = p_season_id AND fc.turn_no = p_turn) x;
    IF v_prev IS NOT NULL AND v_now <= v_prev THEN
        RAISE EXCEPTION 'P0-19 매매: 앞선 주문·변경과 시각이 같거나 거꾸로라 순서를 확정할 수 없다' USING HINT = 'EVENT_TIME_CONFLICT';
    END IF;
    RETURN v_now;
END $$;

-- 그 턴의 마지막 전술 변경 번호. 시각이 아닌 id 순 마지막이지만, tr_event_time 이 새 이벤트가 모든 앞선 이벤트보다 늦음을 보장하므로 시각 순 마지막과 같다.
CREATE OR REPLACE FUNCTION pg_temp.tr_last_change(p_season_id bigint, p_turn smallint)
RETURNS bigint
LANGUAGE sql STABLE AS $$
    SELECT fc.id FROM formation_change fc
    WHERE fc.season_id = p_season_id AND fc.turn_no = p_turn
    ORDER BY fc.created_at DESC, fc.id DESC
    LIMIT 1
$$;

-- 매수 한 건. 21 의 order_buy 에 출발점·피드·변경 연결을 더했다 (사후 UPDATE 로 채우지 않는다).
CREATE OR REPLACE FUNCTION pg_temp.trade_buy(
    p_season_id         bigint,
    p_expected_turn_no  smallint,
    p_asset_id          bigint,
    p_quantity          integer,
    p_declared_position text,
    p_source            text DEFAULT 'DIRECT',
    p_turn_feed_id      bigint DEFAULT NULL)
RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_rate_kr numeric; v_rate_us numeric; v_rate_fx numeric; v_balance numeric; v_reserved numeric;
    v_price numeric; v_market text; v_rate numeric; v_notional numeric; v_fee numeric;
    v_open_pos text; v_hold_q integer;
    v_now timestamptz; v_change bigint; v_order_id bigint;
    v_after_buy numeric; v_after_fee numeric; v_new_balance numeric;
BEGIN
    -- 0 인자 검사 (잠그기 전)
    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RAISE EXCEPTION 'P0-19 매수: 수량은 1 이상의 정수여야 한다' USING HINT = 'INVALID_QUANTITY';
    END IF;
    IF p_asset_id IS NULL OR p_declared_position IS NULL
       OR p_declared_position NOT IN ('GOALKEEPER', 'DEFENDER', 'MIDFIELDER', 'FORWARD_TARGET', 'FORWARD_BREAKOUT', 'FORWARD_DEFENSIVE') THEN
        RAISE EXCEPTION 'P0-19 매수: 종목이 없거나 선언 포지션이 여섯 가지 안에 없다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    IF p_source IS NULL OR p_source NOT IN ('RANKING', 'FEED', 'ALERT', 'BENCH', 'DIRECT')
       OR (p_source IN ('FEED', 'ALERT')) <> (p_turn_feed_id IS NOT NULL) THEN
        RAISE EXCEPTION 'P0-19 매수: 출발점이 다섯 가지 밖이거나 피드 번호 계약(FEED·ALERT 만 필요)에 맞지 않는다' USING HINT = 'INVALID_ARGUMENT';
    END IF;

    -- 1–5 잠금·상태·턴·계좌·원장
    SELECT t.o_rate_kr, t.o_rate_us, t.o_rate_fx, t.o_balance, t.o_reserved
      INTO v_rate_kr, v_rate_us, v_rate_fx, v_balance, v_reserved
    FROM pg_temp.tr_open(p_season_id, p_expected_turn_no, true) t;

    -- 6 체결가 = 그 턴의 turn_price (규칙 13). 정확히 1행이 아니면 풀 밖 종목이다. 요율은 종목 시장으로 고른다 (규칙 14).
    SELECT tp.price, a.market INTO v_price, v_market
    FROM turn_price tp
    JOIN asset a ON a.id = tp.asset_id
    WHERE tp.season_id = p_season_id AND tp.turn_no = p_expected_turn_no AND tp.asset_id = p_asset_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-19 매수: 이 시즌의 현재 턴 가격 풀에 없는 종목이다' USING HINT = 'ASSET_NOT_IN_POOL';
    END IF;
    v_rate     := pg_temp.buy_rate(v_market, v_rate_kr, v_rate_us, v_rate_fx);
    v_notional := v_price * p_quantity;
    v_fee      := pg_temp.buy_fee(v_price, p_quantity, v_rate);

    -- 7 출발점·피드 (잠금 아래)
    PERFORM pg_temp.tr_source(p_season_id, p_expected_turn_no, p_source, p_turn_feed_id);

    -- 8 INSERT 전에 잔액을 본다 (실패해도 번호표를 쓰지 않는다)
    IF NOT pg_temp.buy_affordable(v_balance - v_reserved, v_notional, v_fee) THEN
        RAISE EXCEPTION 'P0-19 매수: 주문 가능 금액이 모자란다' USING HINT = 'INSUFFICIENT_CASH';
    END IF;

    -- 9 보유와 열린 구간의 정합성·선언 충돌. 보유 > 0 ⇔ 열린 구간 하나 (규칙 17). 포지션 변경은 lineup_change 라는 별도 동작이다.
    SELECT h.quantity INTO v_hold_q FROM holding h WHERE h.season_id = p_season_id AND h.asset_id = p_asset_id FOR UPDATE;
    v_hold_q := COALESCE(v_hold_q, 0);
    SELECT lu.declared_position INTO v_open_pos
    FROM lineup lu
    WHERE lu.season_id = p_season_id AND lu.asset_id = p_asset_id AND lu.end_turn IS NULL;
    IF (v_hold_q > 0) <> FOUND THEN
        RAISE EXCEPTION 'P0-19 매수: 보유 수량과 열린 라인업 구간이 어긋나 있다' USING HINT = 'LINEUP_MISMATCH';
    END IF;
    IF v_open_pos IS NOT NULL AND v_open_pos <> p_declared_position THEN
        RAISE EXCEPTION 'P0-19 매수: 이미 다른 선언 포지션으로 보유 중이다. 포지션 변경은 별도 동작이다' USING HINT = 'POSITION_CONFLICT';
    END IF;

    -- 10 주문 시각과 자동 변경 연결 (규칙 16)
    v_now    := pg_temp.tr_event_time(p_season_id, p_expected_turn_no);
    v_change := pg_temp.tr_last_change(p_season_id, p_expected_turn_no);

    -- 11 주문 → 체결 → 보유 → 원장 → 계좌 → 라인업
    INSERT INTO orders (season_id, asset_id, turn_no, side, type, quantity, limit_price, status, source, formation_change_id, turn_feed_id, created_at)
    VALUES (p_season_id, p_asset_id, p_expected_turn_no, 'BUY', 'MARKET', p_quantity, NULL, 'FILLED', p_source, v_change, p_turn_feed_id, v_now)
    RETURNING id INTO v_order_id;

    INSERT INTO execution (order_id, fill_price, quantity, fee, tax, realized_pnl)
    VALUES (v_order_id, v_price, p_quantity, v_fee, 0, NULL);

    INSERT INTO holding (season_id, asset_id, quantity, avg_price)
    VALUES (p_season_id, p_asset_id, p_quantity, v_price)
    ON CONFLICT (season_id, asset_id) DO UPDATE
       SET quantity   = holding.quantity + EXCLUDED.quantity,
           avg_price  = pg_temp.buy_avg_price(holding.quantity, holding.avg_price, EXCLUDED.quantity, v_price),
           updated_at = now();

    v_after_buy := v_balance - v_notional;
    INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
    VALUES (p_season_id, 'BUY', -v_notional, v_after_buy, v_order_id);
    v_after_fee := v_after_buy;
    IF v_fee > 0 THEN
        v_after_fee := v_after_buy - v_fee;
        INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
        VALUES (p_season_id, 'FEE', -v_fee, v_after_fee, v_order_id);
    END IF;

    UPDATE cash_account
       SET balance = balance - v_notional - v_fee, version = version + 1, updated_at = now()
     WHERE season_id = p_season_id
    RETURNING balance INTO v_new_balance;
    IF v_new_balance IS DISTINCT FROM v_after_fee THEN
        RAISE EXCEPTION 'P0-19 매수: 갱신한 잔액이 원장의 마지막 거래 후 잔액과 다르다' USING HINT = 'LEDGER_MISMATCH';
    END IF;

    IF v_open_pos IS NULL THEN
        INSERT INTO lineup (season_id, asset_id, declared_position, start_turn, end_turn)
        VALUES (p_season_id, p_asset_id, p_declared_position, p_expected_turn_no, NULL);
    END IF;
    RETURN v_order_id;
END $$;

-- 매도 한 건. 체결가 = turn_price(t), 수수료는 매수와 같은 요율·같은 buy_fee, 실현 손익 = ROUND((체결가 − 직전 평균단가) × 수량, 4) (수수료·세금 제외, D9).
CREATE OR REPLACE FUNCTION pg_temp.trade_sell(
    p_season_id         bigint,
    p_expected_turn_no  smallint,
    p_asset_id          bigint,
    p_quantity          integer,
    p_source            text DEFAULT 'DIRECT',
    p_turn_feed_id      bigint DEFAULT NULL)
RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_rate_kr numeric; v_rate_us numeric; v_rate_fx numeric; v_balance numeric; v_reserved numeric;
    v_price numeric; v_market text; v_rate numeric; v_notional numeric; v_fee numeric; v_pnl numeric;
    v_hold_q integer; v_hold_avg numeric; v_open_lineup bigint;
    v_now timestamptz; v_change bigint; v_order_id bigint;
    v_after_sell numeric; v_after_fee numeric; v_new_balance numeric; v_new_q integer;
BEGIN
    IF p_quantity IS NULL OR p_quantity <= 0 THEN
        RAISE EXCEPTION 'P0-19 매도: 수량은 1 이상의 정수여야 한다' USING HINT = 'INVALID_QUANTITY';
    END IF;
    IF p_asset_id IS NULL THEN
        RAISE EXCEPTION 'P0-19 매도: 종목이 비어 있다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    IF p_source IS NULL OR p_source NOT IN ('RANKING', 'FEED', 'ALERT', 'BENCH', 'DIRECT')
       OR (p_source IN ('FEED', 'ALERT')) <> (p_turn_feed_id IS NOT NULL) THEN
        RAISE EXCEPTION 'P0-19 매도: 출발점이 다섯 가지 밖이거나 피드 번호 계약(FEED·ALERT 만 필요)에 맞지 않는다' USING HINT = 'INVALID_ARGUMENT';
    END IF;

    SELECT t.o_rate_kr, t.o_rate_us, t.o_rate_fx, t.o_balance, t.o_reserved
      INTO v_rate_kr, v_rate_us, v_rate_fx, v_balance, v_reserved
    FROM pg_temp.tr_open(p_season_id, p_expected_turn_no, true) t;

    SELECT tp.price, a.market INTO v_price, v_market
    FROM turn_price tp
    JOIN asset a ON a.id = tp.asset_id
    WHERE tp.season_id = p_season_id AND tp.turn_no = p_expected_turn_no AND tp.asset_id = p_asset_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-19 매도: 이 시즌의 현재 턴 가격 풀에 없는 종목이다' USING HINT = 'ASSET_NOT_IN_POOL';
    END IF;
    v_rate     := pg_temp.buy_rate(v_market, v_rate_kr, v_rate_us, v_rate_fx);
    v_notional := v_price * p_quantity;
    v_fee      := pg_temp.buy_fee(v_price, p_quantity, v_rate);

    PERFORM pg_temp.tr_source(p_season_id, p_expected_turn_no, p_source, p_turn_feed_id);

    -- 보유: 없거나 모자라면 거절 (공매도 없음)
    SELECT h.quantity, h.avg_price INTO v_hold_q, v_hold_avg
    FROM holding h WHERE h.season_id = p_season_id AND h.asset_id = p_asset_id
    FOR UPDATE;
    IF NOT FOUND OR v_hold_q < p_quantity THEN
        RAISE EXCEPTION 'P0-19 매도: 보유 수량이 모자란다' USING HINT = 'INSUFFICIENT_HOLDING';
    END IF;
    SELECT lu.id INTO v_open_lineup
    FROM lineup lu
    WHERE lu.season_id = p_season_id AND lu.asset_id = p_asset_id AND lu.end_turn IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-19 매도: 보유 중인데 열린 라인업 구간이 없다' USING HINT = 'LINEUP_MISMATCH';
    END IF;

    v_pnl := ROUND((v_price - v_hold_avg) * p_quantity, 4);

    v_now    := pg_temp.tr_event_time(p_season_id, p_expected_turn_no);
    v_change := pg_temp.tr_last_change(p_season_id, p_expected_turn_no);

    INSERT INTO orders (season_id, asset_id, turn_no, side, type, quantity, limit_price, status, source, formation_change_id, turn_feed_id, created_at)
    VALUES (p_season_id, p_asset_id, p_expected_turn_no, 'SELL', 'MARKET', p_quantity, NULL, 'FILLED', p_source, v_change, p_turn_feed_id, v_now)
    RETURNING id INTO v_order_id;

    INSERT INTO execution (order_id, fill_price, quantity, fee, tax, realized_pnl)
    VALUES (v_order_id, v_price, p_quantity, v_fee, 0, v_pnl);

    -- 부분 매도는 평균단가 유지, 전량 매도는 행을 남기고 수량·평균단가를 0 으로 (D7)
    v_new_q := v_hold_q - p_quantity;
    UPDATE holding
       SET quantity = v_new_q, avg_price = CASE WHEN v_new_q = 0 THEN 0 ELSE avg_price END, updated_at = now()
     WHERE season_id = p_season_id AND asset_id = p_asset_id;

    v_after_sell := v_balance + v_notional;
    INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
    VALUES (p_season_id, 'SELL', v_notional, v_after_sell, v_order_id);
    v_after_fee := v_after_sell;
    IF v_fee > 0 THEN
        v_after_fee := v_after_sell - v_fee;
        INSERT INTO cash_ledger (season_id, type, amount, balance_after, ref_order_id)
        VALUES (p_season_id, 'FEE', -v_fee, v_after_fee, v_order_id);
    END IF;

    UPDATE cash_account
       SET balance = balance + v_notional - v_fee, version = version + 1, updated_at = now()
     WHERE season_id = p_season_id
    RETURNING balance INTO v_new_balance;
    IF v_new_balance IS DISTINCT FROM v_after_fee THEN
        RAISE EXCEPTION 'P0-19 매도: 갱신한 잔액이 원장의 마지막 거래 후 잔액과 다르다' USING HINT = 'LEDGER_MISMATCH';
    END IF;

    IF v_new_q = 0 THEN
        UPDATE lineup SET end_turn = p_expected_turn_no, updated_at = now() WHERE id = v_open_lineup;
    END IF;
    RETURN v_order_id;
END $$;

-- 전술 변경 한 건: formation_change INSERT + season.current_* 갱신. 같은 포메이션 재선언은 거절한다 (ck_formation_change_changed).
-- 이 함수는 종목의 선언 포지션·수량을 바꾸지 않는다 (자동 리밸런싱 없음). 변경만 하고 주문하지 않아도 된다.
CREATE OR REPLACE FUNCTION pg_temp.formation_change(
    p_season_id        bigint,
    p_expected_turn_no smallint,
    p_goalkeeper       smallint,
    p_defender         smallint,
    p_midfielder       smallint,
    p_forward          smallint,
    p_reason           text DEFAULT NULL)
RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_cur   smallint[];
    v_last  smallint[];
    v_start smallint[];
    v_new   smallint[];
    v_now   timestamptz;
    v_id    bigint;
BEGIN
    IF p_goalkeeper IS NULL OR p_defender IS NULL OR p_midfielder IS NULL OR p_forward IS NULL THEN
        RAISE EXCEPTION 'P0-19 전술 변경: 비중 네 값은 비어 있을 수 없다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    v_new := ARRAY[p_goalkeeper, p_defender, p_midfielder, p_forward];
    IF p_goalkeeper NOT BETWEEN 0 AND 100 OR p_defender NOT BETWEEN 0 AND 100 OR p_midfielder NOT BETWEEN 0 AND 100 OR p_forward NOT BETWEEN 0 AND 100
       OR p_goalkeeper + p_defender + p_midfielder + p_forward <> 100 OR p_goalkeeper < 1 THEN
        RAISE EXCEPTION 'P0-19 전술 변경: 비중은 각 0–100, 합 100, 골키퍼 1 이상이어야 한다' USING HINT = 'INVALID_FORMATION';
    END IF;
    IF p_reason IS NOT NULL AND length(p_reason) > 200 THEN
        RAISE EXCEPTION 'P0-19 전술 변경: 사유는 200자 이하여야 한다' USING HINT = 'INVALID_ARGUMENT';
    END IF;

    PERFORM pg_temp.tr_open(p_season_id, p_expected_turn_no, false);

    SELECT ARRAY[s.current_goalkeeper, s.current_defender, s.current_midfielder, s.current_forward],
           ARRAY[s.start_goalkeeper, s.start_defender, s.start_midfielder, s.start_forward]
      INTO v_cur, v_start
    FROM season s WHERE s.id = p_season_id;
    IF v_cur = v_new THEN
        RAISE EXCEPTION 'P0-19 전술 변경: 현재 포메이션과 같다' USING HINT = 'INVALID_FORMATION';
    END IF;
    -- 이력 사슬: 현재 값은 마지막 변경의 after (변경이 없으면 시작 값)와 같아야 한다 (규칙 15)
    SELECT ARRAY[fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward] INTO v_last
    FROM formation_change fc WHERE fc.season_id = p_season_id ORDER BY fc.id DESC LIMIT 1;
    IF COALESCE(v_last, v_start) <> v_cur THEN
        RAISE EXCEPTION 'P0-19 전술 변경: 현재 포메이션이 변경 이력의 마지막 값과 다르다' USING HINT = 'INVALID_FORMATION';
    END IF;

    v_now := pg_temp.tr_event_time(p_season_id, p_expected_turn_no);
    INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, before_midfielder, before_forward,
                                  after_goalkeeper, after_defender, after_midfielder, after_forward, reason, created_at)
    VALUES (p_season_id, p_expected_turn_no, v_cur[1], v_cur[2], v_cur[3], v_cur[4], p_goalkeeper, p_defender, p_midfielder, p_forward, p_reason, v_now)
    RETURNING id INTO v_id;
    UPDATE season
       SET current_goalkeeper = p_goalkeeper, current_defender = p_defender, current_midfielder = p_midfielder, current_forward = p_forward,
           version = version + 1, updated_at = now()
     WHERE id = p_season_id;
    RETURN v_id;
END $$;

-- 선언 포지션 단독 변경: 보유 중인 종목의 열린 구간을 닫고(end_turn = t) 새 구간을 연다(start_turn = t). 같은 포지션이면 새 행 없이 열린 구간을 돌려준다.
CREATE OR REPLACE FUNCTION pg_temp.lineup_change(
    p_season_id        bigint,
    p_expected_turn_no smallint,
    p_asset_id         bigint,
    p_new_position     text)
RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_hold_q  integer;
    v_open_id bigint;
    v_open_pos text;
    v_new_id  bigint;
BEGIN
    IF p_asset_id IS NULL OR p_new_position IS NULL
       OR p_new_position NOT IN ('GOALKEEPER', 'DEFENDER', 'MIDFIELDER', 'FORWARD_TARGET', 'FORWARD_BREAKOUT', 'FORWARD_DEFENSIVE') THEN
        RAISE EXCEPTION 'P0-19 포지션 변경: 종목이 없거나 선언 포지션이 여섯 가지 안에 없다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    PERFORM pg_temp.tr_open(p_season_id, p_expected_turn_no, false);

    SELECT h.quantity INTO v_hold_q FROM holding h WHERE h.season_id = p_season_id AND h.asset_id = p_asset_id FOR UPDATE;
    IF NOT FOUND OR v_hold_q <= 0 THEN
        RAISE EXCEPTION 'P0-19 포지션 변경: 보유 중인 종목이 아니다' USING HINT = 'INSUFFICIENT_HOLDING';
    END IF;
    SELECT lu.id, lu.declared_position INTO v_open_id, v_open_pos
    FROM lineup lu WHERE lu.season_id = p_season_id AND lu.asset_id = p_asset_id AND lu.end_turn IS NULL;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-19 포지션 변경: 보유 중인데 열린 라인업 구간이 없다' USING HINT = 'LINEUP_MISMATCH';
    END IF;
    IF v_open_pos = p_new_position THEN
        RETURN v_open_id;
    END IF;
    UPDATE lineup SET end_turn = p_expected_turn_no, updated_at = now() WHERE id = v_open_id;
    INSERT INTO lineup (season_id, asset_id, declared_position, start_turn, end_turn)
    VALUES (p_season_id, p_asset_id, p_new_position, p_expected_turn_no, NULL)
    RETURNING id INTO v_new_id;
    RETURN v_new_id;
END $$;

-- 변경 비용 조회: 변경마다 연결된 체결의 매수·매도 대금과 수수료·세금 합. 변경만 있고 주문이 없는 변경도 한 줄(주문 0건)로 남긴다.
--   체결을 주문 단위로 먼저 합쳐 조인 증폭을 막는다. 원장의 FEE 와는 대사 대상이지만 여기서 더하지 않는다 (execution 비용과 원장 비용을 이중으로 세지 않는다).
CREATE OR REPLACE FUNCTION pg_temp.formation_costs(p_season_id bigint)
RETURNS TABLE (formation_change_id bigint, turn_no smallint, order_count bigint, buy_notional numeric, sell_notional numeric, fee_sum numeric, tax_sum numeric)
LANGUAGE sql STABLE AS $$
    WITH po AS (
        SELECT o.id, o.formation_change_id AS fcid, o.side,
               sum(e.fill_price * e.quantity) AS notional, sum(e.fee) AS fee, sum(e.tax) AS tax
        FROM orders o
        JOIN execution e ON e.order_id = o.id
        WHERE o.season_id = p_season_id AND o.formation_change_id IS NOT NULL
        GROUP BY o.id, o.formation_change_id, o.side
    )
    SELECT fc.id, fc.turn_no,
           count(po.id),
           COALESCE(sum(po.notional) FILTER (WHERE po.side = 'BUY'), 0),
           COALESCE(sum(po.notional) FILTER (WHERE po.side = 'SELL'), 0),
           COALESCE(sum(po.fee), 0),
           COALESCE(sum(po.tax), 0)
    FROM formation_change fc
    LEFT JOIN po ON po.fcid = fc.id
    WHERE fc.season_id = p_season_id
    GROUP BY fc.id, fc.turn_no
    ORDER BY fc.id
$$;
