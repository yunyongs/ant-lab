-- =====================================================================
-- my Fund Manager Phase 0 — 매매·전술 변경 드릴 (P0-19): 실제 쓰기·롤백·경합·극단 포트폴리오를 버리는 DB 에서 시험한다
-- 근거: docs/erd.md 7.3·7.5(대사 규칙 3·4·6·15–18)·D4·D7·D9·D26·D30·9절 P0-19, docs/SPRING_JPA.md §5.2·§5.4, 계획 P0-19 §8.6
-- ★ 버리는 DB 전용이다. 이 파일은 커밋된 매매·전술 변경을 만들고, 합성 피드를 넣고, 전 종목을 팔기도 한다. 소유자의 상시 DB(antlab·antlab_test)에서는 가드가 멈춘다.
--   사본에서 한다:  psql -X -d postgres -c "CREATE DATABASE antlab_drill TEMPLATE antlab"   (antlab 에 다른 접속이 없어야 복사된다 — README)
--   정상 재현 DB 와 섞지 않는다 (드릴은 일부러 만든 극단 상태를 남긴다).
-- 실행:  psql -X -q -v ON_ERROR_STOP=1 -v drill=1 -v drill_case=<사례> [-v op=… -v mode=… -v hold_s=…] -d <버리는 DB> -f db/25_trade_formation_drill.sql
--   전제: 연습 시즌이 진행 중이고 현재 턴 ≥ 1, 팀이 있다 (21 뒤 23 을 한 번 이상). 사례마다 새 사본에서 시작한다.
-- 사례 (-v drill_case=):
--   allcash    보유 종목을 전부 판다 → 전액 현금 (이어서 25 검증·21·22·20 검증, db/23_turn_advance.sql, 23 검증 — README 순서). 전량 매도 구간 닫힘·보유 행 0·0 단언.
--   replace    초기 팀을 전부 팔고 다른 종목 6개를 산다 → 초기 팀 종목에 열린 구간 없음, 새 구간 start_turn = 현재 턴.
--   extremes   미드필더 선언 보유를 전부 팔고, 남은 현금을 한 종목에 거의 다 쓰고(현금 여유 없음), 수비 종목 부분 매도 뒤 포지션 변경.
--   rebuy      같은 턴 신규 매수 뒤 전량 매도(길이 없는 구간) · 전량 매도 종목 재매수 · 한 턴 안 전술 변경 둘과 사이의 주문 연결(변경 전 NULL → 변경1 → 변경2) · 변경 비용 조회(주문 없는 변경 포함).
--   changeonly 주문 없이 전술 변경 1회와 포지션 변경 1회만 한다 (23 검증의 범위 가드 시험용: 다음 턴 전환 전에는 23 검증이 0절에서 멈추고 전환 뒤 PASS).
--   atomic     중간 실패를 일부러 일으켜(임시 트리거) 업무 행 전체가 복원되는지 본다: 매수 중간(원장 FEE 행), 매도 중간, 전술 변경 중간(시즌 갱신), 포지션 변경 중간. 트랜잭션 안에서 만들고 ROLLBACK 한다.
--   alert      합성 NUDGE 피드(이 DB 에서만)로 ALERT 주문 성공, FEED 로 NUDGE 참조 거절, ALERT 로 일반 피드 거절.
--   pastfeed   지난 턴 피드(소유자 결정 2026-11-10, 이슈 #94)에서 시작한 주문 성공(FEED, 합성 NUDGE + ALERT): 주문 턴 = 현재 턴, 참조 피드는 원래 턴 그대로. 미래 피드 거절도 확인한다.
--   race       두 세션 경합의 한 세션 (아래 -v op, mode). 사례 대본은 db/README.md.
--      -v mode=hold  트랜잭션을 열어 op 를 실행하고 -v hold_s 초 붙든 뒤 COMMIT (잠금 보유자)
--      -v mode=run   자동 커밋으로 op 를 실행하고 결과 한 줄을 낸다 (잠금을 기다린다) / op=turn 은 CALL pg_temp.turn_advance
--      -v mode=cashlock  cash_account 행을 -v hold_s 초 잠근다 (턴 전환이 시즌 잠금을 쥔 채 기다리게 하는 보조 세션)
--      op ∈ sell_all(보유 종목 하나 전량) · buy1(그 종목 1주) · formation(수비 −1 · 공격 +1) · lineup(그 종목 선언 토글) · turn
--   결과 줄: `RESULT <op> OK` 또는 `RESULT <op> <HINT>`.
-- 보고: PASS/FAIL 과 건수·종류 코드만. 가격·금액·평가액을 내지 않는다. 마지막 줄 `p0_19_drill_<사례>` 가 PASS 여야 한다 (race 는 RESULT 줄).
-- 새 문법: psql \if 와 변수 분기, \gset, 트랜잭션 DDL 로 임시 트리거를 만들고 ROLLBACK 으로 지우기, 서브트랜잭션(BEGIN … EXCEPTION)으로 실패를 잡고 복원 확인, 경합 대본(잠금 보유 세션 + 대기 세션).
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 1 가드
\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'P0-19 드릴: ON_ERROR_STOP 이 꺼져 있다. psql -X -q -v ON_ERROR_STOP=1 -v drill=1 -v drill_case=<사례> -d <버리는 DB> -f db/25_trade_formation_drill.sql 로 다시 실행한다'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'P0-19 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
\else
  \echo 'P0-19 드릴: -v ON_ERROR_STOP=1 없이 실행할 수 없다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-19 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
\if :{?drill}
\else
  \echo 'P0-19 드릴: -v drill=1 이 없다. 이 파일은 커밋된 매매를 만들고 극단 상태를 남긴다 — 버리는 DB 에서만 -v drill=1 로 실행한다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-19 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
SELECT (:'drill' = '1') AS dr_flag_ok \gset
\if :dr_flag_ok
\else
  \echo 'P0-19 드릴: -v drill=1 이어야 한다 (다른 값은 받지 않는다)'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-19 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
SELECT (current_database() IN ('antlab', 'antlab_test')) AS dr_is_keep \gset
\if :dr_is_keep
  \echo 'P0-19 드릴: 이 DB 는 소유자의 상시 DB 이름(antlab·antlab_test)이다. 사본(CREATE DATABASE antlab_drill TEMPLATE antlab)에서 실행한다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-19 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
\if :{?drill_case}
\else
  \echo 'P0-19 드릴: -v drill_case=<allcash|replace|extremes|rebuy|changeonly|atomic|alert|pastfeed|race> 가 없다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-19 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif
SELECT (:'drill_case' IN ('allcash', 'replace', 'extremes', 'rebuy', 'changeonly', 'atomic', 'alert', 'pastfeed', 'race')) AS dr_case_ok \gset
\if :dr_case_ok
\else
  \echo 'P0-19 드릴: 알 수 없는 사례다 (allcash|replace|extremes|rebuy|changeonly|atomic|alert|pastfeed|race)'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-19 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif

\ir 21_team_buy_def.sql
\ir 22_reconcile_season_def.sql
\ir 24_turn_feed_def.sql
\ir 23_turn_advance_def.sql
\ir 25_trade_formation_def.sql

-- 임시 보조 함수 (정리용 DROP 은 pg_temp. 로 한정)
SET client_min_messages TO warning;
DROP FUNCTION IF EXISTS pg_temp.dr_assert(boolean, text);
DROP FUNCTION IF EXISTS pg_temp.dr_op(text, bigint, smallint);
DROP FUNCTION IF EXISTS pg_temp.dr_state();
RESET client_min_messages;
CREATE FUNCTION pg_temp.dr_assert(p_ok boolean, p_what text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF p_ok IS NOT TRUE THEN
        RAISE EXCEPTION 'P0-19 드릴: 단언 실패 — %', p_what;
    END IF;
END $$;

-- 업무 행 상태 지문: 행 수와 잔액 연쇄 (identity 는 롤백되지 않으므로 넣지 않는다)
CREATE FUNCTION pg_temp.dr_state() RETURNS text LANGUAGE sql STABLE AS $$
    SELECT (SELECT count(*) FROM orders) || '/' || (SELECT count(*) FROM execution) || '/' || (SELECT count(*) FROM holding) || '/' || (SELECT count(*) FROM cash_ledger) || '/'
        || (SELECT count(*) FROM lineup) || '/' || (SELECT count(*) FROM formation_change) || '/' || (SELECT md5(string_agg(concat_ws(',', season_id, balance, version), ';' ORDER BY season_id)) FROM cash_account)
        || '/' || (SELECT md5(string_agg(concat_ws(',', id, current_goalkeeper, current_defender, current_midfielder, current_forward, version), ';' ORDER BY id)) FROM season)
        || '/' || (SELECT md5(string_agg(concat_ws(',', id, asset_id, quantity, avg_price), ';' ORDER BY id)) FROM holding)
        || '/' || (SELECT md5(string_agg(concat_ws(',', id, asset_id, declared_position, start_turn, end_turn), ';' ORDER BY id)) FROM lineup)
$$;

-- 연습 시즌과 현재 턴
SELECT se.id AS sid, se.current_turn_no AS cur, (se.status = 'IN_PROGRESS' AND se.current_turn_no >= 1) AS dr_ready
FROM season se JOIN member m ON m.id = se.member_id
WHERE lower(m.email) = lower('phase0@example.invalid')
\gset
\if :dr_ready
\else
  \echo 'P0-19 드릴: 연습 시즌이 진행 중이고 현재 턴이 1 이상이어야 한다'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-19 드릴: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif

-- 경합 보조 함수: op 를 하나 실행해 'OK' 또는 HINT 를 돌려준다 (잠금을 기다린 뒤 실행된다). 대상 종목은 저장 상태에서 규칙으로 고른다 — 보유 종목 중 번호가 가장 작은 것.
CREATE FUNCTION pg_temp.dr_op(p_op text, p_sid bigint, p_cur smallint) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v_a bigint; v_q integer; v_pos text; v_hint text; v_c smallint[];
BEGIN
    SELECT h.asset_id, h.quantity, lu.declared_position INTO v_a, v_q, v_pos
    FROM holding h JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL
    WHERE h.season_id = p_sid AND h.quantity > 0 ORDER BY h.asset_id LIMIT 1;
    SELECT ARRAY[s.current_goalkeeper, s.current_defender, s.current_midfielder, s.current_forward] INTO v_c FROM season s WHERE s.id = p_sid;
    BEGIN
        IF p_op = 'sell_all' THEN
            PERFORM pg_temp.trade_sell(p_sid, p_cur, v_a, v_q, 'DIRECT', NULL);
        ELSIF p_op = 'buy1' THEN
            PERFORM pg_temp.trade_buy(p_sid, p_cur, v_a, 1, v_pos, 'DIRECT', NULL);
        ELSIF p_op = 'formation' THEN
            PERFORM pg_temp.formation_change(p_sid, p_cur, v_c[1], (v_c[2] - 1)::smallint, v_c[3], (v_c[4] + 1)::smallint, '드릴 경합');
        ELSIF p_op = 'lineup' THEN
            PERFORM pg_temp.lineup_change(p_sid, p_cur, v_a, CASE WHEN v_pos = 'DEFENDER' THEN 'MIDFIELDER' ELSE 'DEFENDER' END);
        ELSE
            RAISE EXCEPTION 'P0-19 드릴: 알 수 없는 op' USING HINT = 'INVALID_ARGUMENT';
        END IF;
        RETURN 'OK';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
        RETURN COALESCE(v_hint, SQLSTATE);
    END;
END $$;

SELECT (:'drill_case' = 'allcash') AS is_allcash, (:'drill_case' = 'replace') AS is_replace, (:'drill_case' = 'extremes') AS is_extremes, (:'drill_case' = 'rebuy') AS is_rebuy,
       (:'drill_case' = 'changeonly') AS is_changeonly, (:'drill_case' = 'atomic') AS is_atomic, (:'drill_case' = 'alert') AS is_alert, (:'drill_case' = 'pastfeed') AS is_pastfeed, (:'drill_case' = 'race') AS is_race \gset

-- ---------------------------------------------------------------------
-- allcash: 전부 매도
-- ---------------------------------------------------------------------
\if :is_allcash
DO $$
DECLARE
    s record; r record; v_n integer := 0;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    FOR r IN SELECT h.asset_id, h.quantity FROM holding h WHERE h.season_id = s.id AND h.quantity > 0 ORDER BY h.asset_id LOOP
        PERFORM pg_temp.trade_sell(s.id, s.cur, r.asset_id, r.quantity, 'DIRECT', NULL);
        v_n := v_n + 1;
    END LOOP;
    PERFORM pg_temp.dr_assert(v_n >= 1, '팔 보유가 없다');
    PERFORM pg_temp.dr_assert(NOT EXISTS (SELECT 1 FROM holding h WHERE h.season_id = s.id AND h.quantity > 0), '전량 매도 뒤 수량이 남았다');
    PERFORM pg_temp.dr_assert(NOT EXISTS (SELECT 1 FROM holding h WHERE h.season_id = s.id AND h.quantity = 0 AND h.avg_price <> 0), '전량 매도 뒤 평균단가가 0 이 아니다');
    PERFORM pg_temp.dr_assert(NOT EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = s.id AND lu.end_turn IS NULL), '전량 매도 뒤 열린 구간이 있다');
    PERFORM pg_temp.dr_assert((SELECT count(*) = 15 AND count(*) FILTER (WHERE status = 'MATCH') = 15 FROM pg_temp.recon_rules(s.id, s.cur)), '전액 현금 상태에서 22 수동 검사 15개가 일치하지 않는다');
    RAISE NOTICE 'allcash: sold % holdings', v_n;
END $$;
SELECT 'PASS' AS p0_19_drill_allcash;
\endif

-- ---------------------------------------------------------------------
-- replace: 초기 팀 전부 교체
-- ---------------------------------------------------------------------
\if :is_replace
DO $$
DECLARE
    s record; r record; v_n integer := 0; v_old bigint[]; v_new bigint[] := ARRAY[]::bigint[]; v_budget numeric; v_q integer;
BEGIN
    SELECT se.id, se.current_turn_no AS cur, se.fee_rate_kr, se.fee_rate_us, se.fx_spread_rate, se.base_date INTO s
    FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT array_agg(h.asset_id ORDER BY h.asset_id) INTO v_old FROM holding h WHERE h.season_id = s.id AND h.quantity > 0;
    FOR r IN SELECT h.asset_id, h.quantity FROM holding h WHERE h.season_id = s.id AND h.quantity > 0 ORDER BY h.asset_id LOOP
        PERFORM pg_temp.trade_sell(s.id, s.cur, r.asset_id, r.quantity, 'DIRECT', NULL);
    END LOOP;
    SELECT floor(a.balance / 6) INTO v_budget FROM cash_account a WHERE a.season_id = s.id;
    FOR r IN SELECT tp.asset_id, tp.price, x.market, ap.system_position AS pos
             FROM turn_price tp JOIN asset x ON x.id = tp.asset_id JOIN asset_position ap ON ap.asset_id = tp.asset_id AND ap.base_date = s.base_date
             WHERE tp.season_id = s.id AND tp.turn_no = s.cur AND tp.asset_id <> ALL (v_old)
             ORDER BY tp.asset_id LIMIT 6 LOOP
        v_q := pg_temp.team_qty(v_budget, r.price, pg_temp.buy_rate(r.market, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate));
        IF v_q >= 1 THEN
            PERFORM pg_temp.trade_buy(s.id, s.cur, r.asset_id, v_q, r.pos, 'DIRECT', NULL);
            v_new := v_new || r.asset_id;
            v_n := v_n + 1;
        END IF;
    END LOOP;
    PERFORM pg_temp.dr_assert(v_n >= 1, '새 종목을 하나도 못 샀다');
    PERFORM pg_temp.dr_assert(NOT EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = s.id AND lu.end_turn IS NULL AND lu.asset_id = ANY (v_old)), '초기 팀 종목에 열린 구간이 남았다');
    PERFORM pg_temp.dr_assert((SELECT count(*) FROM lineup lu WHERE lu.season_id = s.id AND lu.end_turn IS NULL AND lu.start_turn = s.cur AND lu.asset_id = ANY (v_new)) = v_n, '새 구간이 start_turn = 현재 턴이 아니다');
    PERFORM pg_temp.dr_assert((SELECT count(*) = 15 AND count(*) FILTER (WHERE status = 'MATCH') = 15 FROM pg_temp.recon_rules(s.id, s.cur)), '22 수동 검사 15개가 일치하지 않는다');
    RAISE NOTICE 'replace: sold % bought %', cardinality(v_old), v_n;
END $$;
SELECT 'PASS' AS p0_19_drill_replace;
\endif

-- ---------------------------------------------------------------------
-- extremes: 미드필더 없음 · 현금 여유 없음 · 부분 매도 뒤 포지션 변경
-- ---------------------------------------------------------------------
\if :is_extremes
DO $$
DECLARE
    s record; r record; v_a bigint; v_q integer; v_price numeric; v_mkt text; v_rate numeric; v_cash numeric; v_pos text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur, se.fee_rate_kr, se.fee_rate_us, se.fx_spread_rate INTO s
    FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    -- 미드필더 선언 보유 전량 매도
    FOR r IN SELECT h.asset_id, h.quantity FROM holding h JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL AND lu.declared_position = 'MIDFIELDER'
             WHERE h.season_id = s.id AND h.quantity > 0 ORDER BY h.asset_id LOOP
        PERFORM pg_temp.trade_sell(s.id, s.cur, r.asset_id, r.quantity, 'DIRECT', NULL);
    END LOOP;
    PERFORM pg_temp.dr_assert(NOT EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = s.id AND lu.end_turn IS NULL AND lu.declared_position = 'MIDFIELDER'), '미드필더 열린 구간이 남았다');
    -- 수비 종목 하나 부분 매도 뒤 포지션 변경
    SELECT h.asset_id, h.quantity / 2 INTO v_a, v_q FROM holding h JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL AND lu.declared_position = 'DEFENDER'
    WHERE h.season_id = s.id AND h.quantity >= 2 ORDER BY h.asset_id LIMIT 1;
    PERFORM pg_temp.dr_assert(v_a IS NOT NULL, '부분 매도할 수비 보유가 없다');
    PERFORM pg_temp.trade_sell(s.id, s.cur, v_a, v_q, 'DIRECT', NULL);
    PERFORM pg_temp.lineup_change(s.id, s.cur, v_a, 'FORWARD_BREAKOUT');
    -- 현금 여유 없음: 남은 현금 전부를 예산으로 가장 번호 작은 미보유 종목 하나를 산다 (수수료 포함 정수 주 → 남는 현금은 한 주 값보다 작다)
    SELECT tp.asset_id, tp.price, x.market, ap.system_position INTO v_a, v_price, v_mkt, v_pos
    FROM turn_price tp JOIN asset x ON x.id = tp.asset_id JOIN season se ON se.id = tp.season_id JOIN asset_position ap ON ap.asset_id = tp.asset_id AND ap.base_date = se.base_date
    WHERE tp.season_id = s.id AND tp.turn_no = s.cur
      AND NOT EXISTS (SELECT 1 FROM holding h WHERE h.season_id = s.id AND h.asset_id = tp.asset_id AND h.quantity > 0)
      AND ap.system_position <> 'MIDFIELDER'
    ORDER BY tp.asset_id LIMIT 1;
    v_rate := pg_temp.buy_rate(v_mkt, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate);
    SELECT a.balance - a.reserved INTO v_cash FROM cash_account a WHERE a.season_id = s.id;
    v_q := pg_temp.team_qty(v_cash, v_price, v_rate);
    PERFORM pg_temp.dr_assert(v_q >= 1, '남은 현금으로 한 주도 못 산다');
    PERFORM pg_temp.trade_buy(s.id, s.cur, v_a, v_q, v_pos, 'DIRECT', NULL);
    SELECT a.balance INTO v_cash FROM cash_account a WHERE a.season_id = s.id;
    PERFORM pg_temp.dr_assert(v_cash >= 0 AND v_cash < v_price * (1 + v_rate) + 1, '현금이 한 주 값보다 작게 남아야 한다');
    PERFORM pg_temp.dr_assert((SELECT count(*) = 15 AND count(*) FILTER (WHERE status = 'MATCH') = 15 FROM pg_temp.recon_rules(s.id, s.cur)), '22 수동 검사 15개가 일치하지 않는다');
END $$;
SELECT 'PASS' AS p0_19_drill_extremes;
\endif

-- ---------------------------------------------------------------------
-- rebuy: 같은 턴 매수→전량 매도, 전량 매도→재매수, 한 턴 전술 변경 둘과 주문 연결, 변경 비용 조회
-- ---------------------------------------------------------------------
\if :is_rebuy
DO $$
DECLARE
    s record; r record; v_new bigint; v_pos text; v_price numeric; v_mkt text; v_old bigint; v_oldq integer; v_oldpos text;
    o1 bigint; o2 bigint; o3 bigint; o4 bigint; o5 bigint; f1 bigint; f2 bigint; c smallint[]; v_len_zero integer;
BEGIN
    SELECT se.id, se.current_turn_no AS cur, se.base_date INTO s
    FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    -- 미보유 종목 하나를 사고(신규 구간 start_turn = t) 같은 턴에 전량 매도 → 길이 없는 구간 [t, t)
    SELECT tp.asset_id, ap.system_position INTO v_new, v_pos
    FROM turn_price tp JOIN asset_position ap ON ap.asset_id = tp.asset_id AND ap.base_date = s.base_date
    WHERE tp.season_id = s.id AND tp.turn_no = s.cur AND NOT EXISTS (SELECT 1 FROM holding h WHERE h.season_id = s.id AND h.asset_id = tp.asset_id AND h.quantity > 0)
    ORDER BY tp.asset_id LIMIT 1;
    o1 := pg_temp.trade_buy(s.id, s.cur, v_new, 2, v_pos, 'DIRECT', NULL);                 -- 변경 전 주문
    f1 := pg_temp.formation_change(s.id, s.cur, 10::smallint, 20::smallint, 30::smallint, 40::smallint, '드릴 변경 1');
    o2 := pg_temp.trade_sell(s.id, s.cur, v_new, 2, 'DIRECT', NULL);                       -- 변경 1 뒤: 전량 매도
    PERFORM pg_temp.dr_assert((SELECT count(*) FROM lineup lu WHERE lu.season_id = s.id AND lu.asset_id = v_new AND lu.start_turn = s.cur AND lu.end_turn = s.cur) = 1, '길이 없는 구간이 생기지 않았다');
    o3 := pg_temp.trade_buy(s.id, s.cur, v_new, 1, v_pos, 'RANKING', NULL);                -- 전량 매도 종목 재매수 (새 구간 start_turn = t)
    f2 := pg_temp.formation_change(s.id, s.cur, 10::smallint, 25::smallint, 25::smallint, 40::smallint, '드릴 변경 2');
    o4 := pg_temp.trade_sell(s.id, s.cur, v_new, 1, 'BENCH', NULL);                        -- 변경 2 뒤
    -- 기존 보유 하나: 추가 매수 1주 (같은 선언) 변경 2 에 묶임
    SELECT h.asset_id, lu.declared_position INTO v_old, v_oldpos FROM holding h JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL
    WHERE h.season_id = s.id AND h.quantity > 0 ORDER BY h.asset_id LIMIT 1;
    o5 := pg_temp.trade_buy(s.id, s.cur, v_old, 1, v_oldpos, 'DIRECT', NULL);
    PERFORM pg_temp.dr_assert((SELECT formation_change_id IS NULL FROM orders WHERE id = o1), '변경 전 주문이 변경에 묶였다');
    PERFORM pg_temp.dr_assert((SELECT formation_change_id = f1 FROM orders WHERE id = o2), '변경 1 뒤 주문 o2 가 변경 1 에 묶이지 않았다');
    PERFORM pg_temp.dr_assert((SELECT formation_change_id = f1 FROM orders WHERE id = o3), '변경 1 뒤 주문 o3 가 변경 1 에 묶이지 않았다 (DIRECT 가 아니어도 같은 규칙)');
    PERFORM pg_temp.dr_assert((SELECT formation_change_id = f2 FROM orders WHERE id = o4), '변경 2 뒤 주문 o4 가 변경 2 에 묶이지 않았다');
    PERFORM pg_temp.dr_assert((SELECT formation_change_id = f2 FROM orders WHERE id = o5), '변경 2 뒤 주문 o5 가 변경 2 에 묶이지 않았다');
    PERFORM pg_temp.dr_assert((SELECT count(*) = 2 FROM pg_temp.formation_costs(s.id) WHERE order_count >= 1), '변경 비용 조회가 두 변경을 보여 주지 않는다');
    PERFORM pg_temp.dr_assert((SELECT (SELECT before_goalkeeper FROM formation_change WHERE id = f2) = 10 AND (SELECT before_defender FROM formation_change WHERE id = f2) = 20), '변경 2 의 before 가 변경 1 의 after 가 아니다');
    PERFORM pg_temp.dr_assert((SELECT count(*) = 15 AND count(*) FILTER (WHERE status = 'MATCH') = 15 FROM pg_temp.recon_rules(s.id, s.cur)), '22 수동 검사 15개가 일치하지 않는다');
    SELECT count(*) INTO v_len_zero FROM lineup lu WHERE lu.season_id = s.id AND lu.end_turn = lu.start_turn;
    RAISE NOTICE 'rebuy: zero-length intervals %', v_len_zero;
END $$;
-- 주문 없는 전술 변경은 변경 비용 조회에 주문 0건으로 남는다 (변경만 하고 주문하지 않는 경우)
DO $$
DECLARE
    s record; f3 bigint; c smallint[];
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT ARRAY[x.current_goalkeeper, x.current_defender, x.current_midfielder, x.current_forward] INTO c FROM season x WHERE x.id = s.id;
    f3 := pg_temp.formation_change(s.id, s.cur, c[1], (c[2] + 1)::smallint, (c[3] - 1)::smallint, c[4], '드릴 주문 없는 변경');
    PERFORM pg_temp.dr_assert((SELECT order_count = 0 AND buy_notional = 0 AND sell_notional = 0 AND fee_sum = 0 FROM pg_temp.formation_costs(s.id) WHERE formation_change_id = f3), '주문 없는 변경이 주문 0건으로 남지 않았다');
    PERFORM pg_temp.dr_assert((SELECT count(*) FROM pg_temp.formation_costs(s.id)) = (SELECT count(*) FROM formation_change WHERE season_id = s.id), '변경 비용 조회 행 수 ≠ 변경 수');
END $$;
SELECT 'PASS' AS p0_19_drill_rebuy;
\endif

-- ---------------------------------------------------------------------
-- changeonly: 주문 없이 전술 변경 1회 + 포지션 변경 1회
-- ---------------------------------------------------------------------
\if :is_changeonly
DO $$
DECLARE
    s record; c smallint[]; v_a bigint; v_pos text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    PERFORM pg_temp.dr_assert(NOT EXISTS (SELECT 1 FROM orders o WHERE o.season_id = s.id AND o.turn_no = s.cur), '이 사례는 현재 턴에 주문이 없어야 한다 (새 턴에서 시작한다)');
    SELECT ARRAY[x.current_goalkeeper, x.current_defender, x.current_midfielder, x.current_forward] INTO c FROM season x WHERE x.id = s.id;
    PERFORM pg_temp.formation_change(s.id, s.cur, c[1], (c[2] + 1)::smallint, (c[3] - 1)::smallint, c[4], '드릴 변경만');
    SELECT h.asset_id, lu.declared_position INTO v_a, v_pos FROM holding h JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL
    WHERE h.season_id = s.id AND h.quantity > 0 AND lu.declared_position IN ('DEFENDER', 'MIDFIELDER') ORDER BY h.asset_id LIMIT 1;
    PERFORM pg_temp.dr_assert(v_a IS NOT NULL, '포지션을 바꿀 수비·미드필더 보유가 없다');
    PERFORM pg_temp.lineup_change(s.id, s.cur, v_a, CASE WHEN v_pos = 'DEFENDER' THEN 'MIDFIELDER' ELSE 'DEFENDER' END);
    PERFORM pg_temp.dr_assert(NOT EXISTS (SELECT 1 FROM orders o WHERE o.season_id = s.id AND o.turn_no = s.cur), '주문이 생겼다');
    PERFORM pg_temp.dr_assert((SELECT count(*) = 15 AND count(*) FILTER (WHERE status = 'MATCH') = 15 FROM pg_temp.recon_rules(s.id, s.cur)), '22 수동 검사 15개가 일치하지 않는다');
END $$;
SELECT 'PASS' AS p0_19_drill_changeonly;
\endif

-- ---------------------------------------------------------------------
-- atomic: 중간 실패 뒤 업무 행 전체 복원. 임시 트리거(트랜잭션 DDL)로 쓰기 한가운데에서 실패시키고 ROLLBACK 으로 트리거까지 지운다.
-- ---------------------------------------------------------------------
\if :is_atomic
SET client_min_messages TO warning;
DROP TABLE IF EXISTS pg_temp.dr_before;
RESET client_min_messages;
CREATE TEMP TABLE dr_before AS SELECT pg_temp.dr_state() AS st;
CREATE FUNCTION pg_temp.dr_boom() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'P0-19 드릴: 일부러 낸 중간 실패' USING HINT = 'DRILL_FORCED';
END $$;
CREATE TEMP TABLE dr_atomic (name text, hint text, restored boolean);
BEGIN;
-- ① 매수 중간: 원장 FEE 행을 넣을 때 실패 (주문·체결·보유·BUY 원장·라인업이 이미 쓰인 뒤)
CREATE TRIGGER dr_t1 BEFORE INSERT ON cash_ledger FOR EACH ROW WHEN (NEW.type = 'FEE') EXECUTE FUNCTION pg_temp.dr_boom();
DO $$
DECLARE
    s record; v_a bigint; v_pos text; v_before text; v_hint text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT tp.asset_id, ap.system_position INTO v_a, v_pos FROM turn_price tp JOIN season se ON se.id = tp.season_id JOIN asset_position ap ON ap.asset_id = tp.asset_id AND ap.base_date = se.base_date
    WHERE tp.season_id = s.id AND tp.turn_no = s.cur AND NOT EXISTS (SELECT 1 FROM holding h WHERE h.season_id = s.id AND h.asset_id = tp.asset_id AND h.quantity > 0) ORDER BY tp.asset_id LIMIT 1;
    v_before := pg_temp.dr_state();
    BEGIN
        PERFORM pg_temp.trade_buy(s.id, s.cur, v_a, 1, v_pos, 'DIRECT', NULL);
        v_hint := 'NO_ERROR';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
    END;
    INSERT INTO dr_atomic VALUES ('매수 중간 실패', v_hint, pg_temp.dr_state() = v_before);
END $$;
DROP TRIGGER dr_t1 ON cash_ledger;
-- ② 매도 중간: 같은 지점 (원장 FEE)
CREATE TRIGGER dr_t2 BEFORE INSERT ON cash_ledger FOR EACH ROW WHEN (NEW.type = 'FEE') EXECUTE FUNCTION pg_temp.dr_boom();
DO $$
DECLARE
    s record; v_a bigint; v_q integer; v_before text; v_hint text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT h.asset_id, h.quantity INTO v_a, v_q FROM holding h WHERE h.season_id = s.id AND h.quantity > 0 ORDER BY h.asset_id LIMIT 1;
    v_before := pg_temp.dr_state();
    BEGIN
        PERFORM pg_temp.trade_sell(s.id, s.cur, v_a, v_q, 'DIRECT', NULL);     -- 전량 매도: 구간 닫기까지 가기 전에 실패
        v_hint := 'NO_ERROR';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
    END;
    INSERT INTO dr_atomic VALUES ('매도 중간 실패', v_hint, pg_temp.dr_state() = v_before);
END $$;
DROP TRIGGER dr_t2 ON cash_ledger;
-- ③ 전술 변경 중간: 변경 이력은 INSERT 됐고 시즌 갱신에서 실패
CREATE TRIGGER dr_t3 BEFORE UPDATE ON season FOR EACH ROW WHEN (OLD.current_defender IS DISTINCT FROM NEW.current_defender) EXECUTE FUNCTION pg_temp.dr_boom();
DO $$
DECLARE
    s record; c smallint[]; v_before text; v_hint text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT ARRAY[x.current_goalkeeper, x.current_defender, x.current_midfielder, x.current_forward] INTO c FROM season x WHERE x.id = s.id;
    v_before := pg_temp.dr_state();
    BEGIN
        PERFORM pg_temp.formation_change(s.id, s.cur, c[1], (c[2] - 1)::smallint, (c[3] + 1)::smallint, c[4], '드릴 중간 실패');
        v_hint := 'NO_ERROR';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
    END;
    INSERT INTO dr_atomic VALUES ('전술 변경 중간 실패', v_hint, pg_temp.dr_state() = v_before);
END $$;
DROP TRIGGER dr_t3 ON season;
-- ④ 포지션 변경 중간: 옛 구간을 닫은 뒤 새 구간 INSERT 에서 실패
CREATE TRIGGER dr_t4 BEFORE INSERT ON lineup FOR EACH ROW EXECUTE FUNCTION pg_temp.dr_boom();
DO $$
DECLARE
    s record; v_a bigint; v_pos text; v_before text; v_hint text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT h.asset_id, lu.declared_position INTO v_a, v_pos FROM holding h JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL
    WHERE h.season_id = s.id AND h.quantity > 0 ORDER BY h.asset_id LIMIT 1;
    v_before := pg_temp.dr_state();
    BEGIN
        PERFORM pg_temp.lineup_change(s.id, s.cur, v_a, CASE WHEN v_pos = 'DEFENDER' THEN 'MIDFIELDER' ELSE 'DEFENDER' END);
        v_hint := 'NO_ERROR';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
    END;
    INSERT INTO dr_atomic VALUES ('포지션 변경 중간 실패', v_hint, pg_temp.dr_state() = v_before);
END $$;
DROP TRIGGER dr_t4 ON lineup;
-- ⑤ 호출자 롤백: 성공한 매수·전술 변경을 한 트랜잭션으로 묶은 뒤 ROLLBACK 하면 전부 사라진다
DO $$
DECLARE
    s record; c smallint[]; v_a bigint; v_pos text; v_before text; v_hint text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT tp.asset_id, ap.system_position INTO v_a, v_pos FROM turn_price tp JOIN season se ON se.id = tp.season_id JOIN asset_position ap ON ap.asset_id = tp.asset_id AND ap.base_date = se.base_date
    WHERE tp.season_id = s.id AND tp.turn_no = s.cur AND NOT EXISTS (SELECT 1 FROM holding h WHERE h.season_id = s.id AND h.asset_id = tp.asset_id AND h.quantity > 0) ORDER BY tp.asset_id LIMIT 1;
    SELECT ARRAY[x.current_goalkeeper, x.current_defender, x.current_midfielder, x.current_forward] INTO c FROM season x WHERE x.id = s.id;
    v_before := pg_temp.dr_state();
    BEGIN
        PERFORM pg_temp.formation_change(s.id, s.cur, c[1], (c[2] - 1)::smallint, (c[3] + 1)::smallint, c[4], '드릴 호출자 롤백');
        PERFORM pg_temp.trade_buy(s.id, s.cur, v_a, 1, v_pos, 'DIRECT', NULL);
        PERFORM pg_temp.dr_assert(pg_temp.dr_state() <> v_before, '성공한 쓰기가 상태를 바꾸지 않았다');
        RAISE EXCEPTION '호출자 롤백' USING HINT = 'CALLER_ROLLBACK';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
    END;
    INSERT INTO dr_atomic VALUES ('호출자 롤백', v_hint, pg_temp.dr_state() = v_before);
END $$;
-- 결과를 트랜잭션 밖으로 건너 보낸다 (임시 표는 롤백에 사라지므로 psql 변수로)
SELECT count(*) AS dr_n, count(*) FILTER (WHERE restored AND hint IN ('DRILL_FORCED', 'CALLER_ROLLBACK')) AS dr_ok FROM dr_atomic \gset
ROLLBACK;
-- 롤백 뒤: 업무 행이 처음과 같고 트리거가 남지 않았다
SELECT (pg_temp.dr_state() = (SELECT st FROM dr_before)) AS dr_same,
       NOT EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgname LIKE 'dr\_t%' AND NOT t.tgisinternal) AS dr_notrig \gset
SELECT pg_temp.dr_assert(:dr_n = 5 AND :dr_ok = 5, '중간 실패 사례 5개가 모두 복원되지 않았다') AS ok1 \gset
SELECT pg_temp.dr_assert(:'dr_same'::boolean, '롤백 뒤 업무 행이 처음과 다르다') AS ok2 \gset
SELECT pg_temp.dr_assert(:'dr_notrig'::boolean, '임시 트리거가 남았다') AS ok3 \gset
SELECT :dr_n AS cases, :dr_ok AS restored, 'PASS' AS p0_19_drill_atomic;
\endif

-- ---------------------------------------------------------------------
-- alert: 합성 NUDGE 로 ALERT 성공 (이 DB 에서만 — P0-18 은 NUDGE 를 만들지 않는다)
-- ---------------------------------------------------------------------
\if :is_alert
INSERT INTO turn_feed (season_id, turn_no, seq, kind, direction, strength, template_id, body)
SELECT :sid, :cur, COALESCE(max(f.seq), 0) + 1, 'NUDGE', 'UP', 1, 'DRILL_SYNTHETIC', '합성 알림 (드릴)'
FROM turn_feed f WHERE f.season_id = :sid AND f.turn_no = :cur;
SELECT f.id AS nudge_id FROM turn_feed f WHERE f.season_id = :sid AND f.turn_no = :cur AND f.kind = 'NUDGE' ORDER BY f.id DESC LIMIT 1 \gset
SELECT f.id AS plain_id FROM turn_feed f WHERE f.season_id = :sid AND f.turn_no = :cur AND f.kind <> 'NUDGE' ORDER BY f.seq LIMIT 1 \gset
DO $$
DECLARE
    s record; v_a bigint; v_pos text; v_nudge bigint; v_plain bigint; v_o bigint; v_hint text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT f.id INTO v_nudge FROM turn_feed f WHERE f.season_id = s.id AND f.turn_no = s.cur AND f.kind = 'NUDGE' ORDER BY f.id DESC LIMIT 1;
    SELECT f.id INTO v_plain FROM turn_feed f WHERE f.season_id = s.id AND f.turn_no = s.cur AND f.kind <> 'NUDGE' ORDER BY f.seq LIMIT 1;
    SELECT h.asset_id, lu.declared_position INTO v_a, v_pos FROM holding h JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL
    WHERE h.season_id = s.id AND h.quantity > 0 ORDER BY h.asset_id LIMIT 1;
    -- ALERT + NUDGE 는 성공
    v_o := pg_temp.trade_buy(s.id, s.cur, v_a, 1, v_pos, 'ALERT', v_nudge);
    PERFORM pg_temp.dr_assert((SELECT source = 'ALERT' AND turn_feed_id = v_nudge FROM orders WHERE id = v_o), 'ALERT 주문이 NUDGE 를 참조하지 않는다');
    -- FEED 로 NUDGE 참조는 거절
    BEGIN
        PERFORM pg_temp.trade_buy(s.id, s.cur, v_a, 1, v_pos, 'FEED', v_nudge);
        v_hint := 'NO_ERROR';
    EXCEPTION WHEN OTHERS THEN GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT; END;
    PERFORM pg_temp.dr_assert(v_hint = 'INVALID_FEED_REFERENCE', 'FEED 로 NUDGE 를 참조했는데 거절되지 않았다');
    -- ALERT 로 일반 피드 참조는 거절
    BEGIN
        PERFORM pg_temp.trade_sell(s.id, s.cur, v_a, 1, 'ALERT', v_plain);
        v_hint := 'NO_ERROR';
    EXCEPTION WHEN OTHERS THEN GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT; END;
    PERFORM pg_temp.dr_assert(v_hint = 'INVALID_FEED_REFERENCE', 'ALERT 로 일반 피드를 참조했는데 거절되지 않았다');
    PERFORM pg_temp.dr_assert((SELECT count(*) = 15 AND count(*) FILTER (WHERE status = 'MATCH') = 15 FROM pg_temp.recon_rules(s.id, s.cur)), '22 수동 검사 15개가 일치하지 않는다');
END $$;
SELECT 'PASS' AS p0_19_drill_alert;
\endif

-- ---------------------------------------------------------------------
-- pastfeed: 지난 턴 피드에서 시작한 현재 턴 주문 (소유자 결정 2026-11-10, 이슈 #94)
-- ---------------------------------------------------------------------
\if :is_pastfeed
DO $$
DECLARE
    s record; v_a bigint; v_pos text; v_past bigint; v_nudge bigint; v_o bigint; v_hint text;
BEGIN
    SELECT se.id, se.current_turn_no AS cur INTO s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    SELECT f.id INTO v_past FROM turn_feed f WHERE f.season_id = s.id AND f.turn_no < s.cur AND f.kind <> 'NUDGE' ORDER BY f.turn_no DESC, f.seq LIMIT 1;
    PERFORM pg_temp.dr_assert(v_past IS NOT NULL, '지난 턴에 일반 피드가 없다 (현재 턴 ≥ 2 이어야 한다)');
    SELECT h.asset_id, lu.declared_position INTO v_a, v_pos FROM holding h JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL
    WHERE h.season_id = s.id AND h.quantity > 0 ORDER BY h.asset_id LIMIT 1;
    PERFORM pg_temp.dr_assert(v_a IS NOT NULL, '보유 종목이 없다 (25 시나리오 뒤에 실행한다)');
    -- 지난 턴 FEED 피드로 시작한 매수는 성공: 주문 턴은 현재 턴, 참조 피드는 원래 턴 그대로
    v_o := pg_temp.trade_buy(s.id, s.cur, v_a, 1, v_pos, 'FEED', v_past);
    PERFORM pg_temp.dr_assert((SELECT o.turn_no = s.cur AND o.turn_feed_id = v_past AND o.source = 'FEED' AND f.turn_no < o.turn_no
                               FROM orders o JOIN turn_feed f ON f.id = o.turn_feed_id AND f.season_id = o.season_id WHERE o.id = v_o), '지난 턴 피드 주문의 턴·피드 참조가 기대와 다르다');
    -- 지난 턴 NUDGE 피드(이 DB 에서만 만드는 합성 알림) + ALERT 도 성공: 피드는 원래 턴 그대로
    INSERT INTO turn_feed (season_id, turn_no, seq, kind, direction, strength, template_id, body)
    SELECT s.id, s.cur - 1, COALESCE(max(f.seq), 0) + 1, 'NUDGE', 'UP', 1, 'DRILL_SYNTHETIC', '합성 알림 (드릴)'
    FROM turn_feed f WHERE f.season_id = s.id AND f.turn_no = s.cur - 1;
    SELECT f.id INTO v_nudge FROM turn_feed f WHERE f.season_id = s.id AND f.turn_no = s.cur - 1 AND f.kind = 'NUDGE' ORDER BY f.id DESC LIMIT 1;
    v_o := pg_temp.trade_buy(s.id, s.cur, v_a, 1, v_pos, 'ALERT', v_nudge);
    PERFORM pg_temp.dr_assert((SELECT o.turn_no = s.cur AND o.turn_feed_id = v_nudge AND o.source = 'ALERT' AND f.turn_no = s.cur - 1
                               FROM orders o JOIN turn_feed f ON f.id = o.turn_feed_id AND f.season_id = o.season_id WHERE o.id = v_o), '지난 턴 NUDGE + ALERT 주문의 턴·피드 참조가 기대와 다르다');
    -- 미래 피드(turn_no > 현재 턴)는 계속 거절 (피드 턴을 현재 턴 + 1 로 바꾼 서브트랜잭션)
    BEGIN
        UPDATE turn_feed SET turn_no = s.cur + 1 WHERE id = v_past;
        PERFORM pg_temp.trade_buy(s.id, s.cur, v_a, 1, v_pos, 'FEED', v_past);
        v_hint := 'NO_ERROR';
    EXCEPTION WHEN OTHERS THEN GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT; END;
    PERFORM pg_temp.dr_assert(v_hint = 'INVALID_FEED_REFERENCE', '미래 피드가 거절되지 않았다');
    PERFORM pg_temp.dr_assert((SELECT turn_no < s.cur FROM turn_feed WHERE id = v_past), '서브트랜잭션이 피드 턴을 되돌리지 않았다');
    PERFORM pg_temp.dr_assert((SELECT count(*) = 15 AND count(*) FILTER (WHERE status = 'MATCH') = 15 FROM pg_temp.recon_rules(s.id, s.cur)), '22 수동 검사 15개가 일치하지 않는다');
END $$;
SELECT 'PASS' AS p0_19_drill_pastfeed;
\endif

-- ---------------------------------------------------------------------
-- race: 경합의 한 세션
-- ---------------------------------------------------------------------
\if :is_race
\if :{?mode}
\else
  \echo 'P0-19 드릴: race 는 -v mode=hold|run|cashlock 이 필요하다'
  \quit
\endif
\if :{?hold_s}
\else
  \set hold_s 4
\endif
SELECT (:'mode' = 'hold') AS m_hold, (:'mode' = 'run') AS m_run, (:'mode' = 'cashlock') AS m_cash \gset
\if :m_cash
BEGIN;
SELECT 1 AS locked FROM cash_account WHERE season_id = :sid FOR UPDATE \gset
SELECT pg_sleep(:hold_s::numeric) AS slept \gset
COMMIT;
\echo RESULT cashlock OK
\endif
\if :m_hold
BEGIN;
SELECT pg_temp.dr_op(:'op', :sid, :cur::smallint) AS res \gset
SELECT pg_sleep(:hold_s::numeric) AS slept \gset
COMMIT;
\echo RESULT :op :res
\endif
\if :m_run
SELECT (:'op' = 'turn') AS is_turn \gset
\if :is_turn
CALL pg_temp.turn_advance(:sid::bigint, :cur::smallint);
\echo RESULT turn OK
\else
SELECT pg_temp.dr_op(:'op', :sid, :cur::smallint) AS res \gset
\echo RESULT :op :res
\endif
\endif
\endif
