-- =====================================================================
-- my Fund Manager Phase 0 — 5턴 뒤 전술 변경 1회와 매매 (P0-19): 연습 시즌의 현재 턴에서 변경·매도·매수·FEED 주문·포지션 변경을 한 트랜잭션으로 실행한다
-- 근거: docs/erd.md 6.3·7.5(대사 규칙 2·3·4·6–9·13–18)·6.5 S6·S7·D4·D7·D9·D23·D26·D29–D31·9절 P0-19, docs/SPRING_JPA.md §5.2·§5.4, docs/PLAN.md §4.1-5·§4.6·§4.10·§4.13
-- 대상: db/01·02·03 스키마, 10 적재, 11 사건, 12 판정, 13 관심 지수, 20 시즌 시작, 21 팀 구성을 실행하고 db/23_turn_advance.sql 을 5번 실행한 DB (PostgreSQL 17). 14 는 필요 없다.
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/25_trade_formation.sql
--       21·22·25 정의를 \ir 로 읽는다. -v ON_ERROR_STOP=1 이 없으면 맨 앞 가드가 아무것도 하지 않고 끝낸다 (단언이 실패했는데 문장이 이어지면 안 된다).
-- 하는 일 (현재 턴 = 5, 전부 한 트랜잭션 — 어디서든 실패하면 아무것도 남지 않는다):
--       ① 변경 전 DIRECT 부분 매도 (formation_change_id NULL)  ② 전술 변경 1회 (수비 −10 · 공격 +10)  ③ 변경 뒤 FEED 매수 — 현재 턴 피드가 가리키는, 보유하지 않은 종목 (변경에 묶임)
--       ④ 변경 뒤 DIRECT 전량 매도 (미드필더 한 종목)  ⑤ 변경 뒤 RANKING 추가 매수 1주 (공격 한 종목, 이동평균)  ⑥ 부분 매도했던 수비 종목의 선언 포지션을 미드필더로 변경 (구간 닫기 + 열기)
--       종목·수량은 저장된 상태에서 규칙으로 고른다: 각 줄의 선택 기준은 아래 \gset 쿼리 위의 주석이다. 대상을 못 찾으면 아무것도 쓰기 전에 멈춘다 (기대 상태를 생략하지 않는다).
-- 다시 실행(멱등이 아니다 — 의도): 1턴 이후 주문이나 전술 변경이 이미 있으면 "이미 적용된 상태"로 아무것도 하지 않고 오류(종료 코드 3)로 멈춘다. 현재 턴이 5가 아니어도 멈춘다.
-- 끝난 뒤: 22 수동 검사 15개가 모두 일치하는지 이 파일이 단언한다 (로그에는 쓰지 않는다). 이어서 db/25_trade_formation_check.sql 로 검증하고 db/23_turn_advance.sql 로 턴을 넘긴다.
-- 쓰는 곳: orders · execution · holding · cash_ledger · cash_account · lineup · formation_change · season(current_*·version·updated_at). 아무것도 지우지 않는다.
-- 보고: 건수만 낸다. 가격·수량·금액·비중은 출력하지 않는다.
-- 새 문법: psql \gset 으로 쿼리 결과를 변수에 담아 함수에 넘기기, 트랜잭션 안의 \gset, 임시 단언 함수.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'P0-19 매매: ON_ERROR_STOP 이 꺼져 있다. psql -X -v ON_ERROR_STOP=1 -d <DB> -f db/25_trade_formation.sql 로 다시 실행한다'
    \quit
  \endif
\else
  \echo 'P0-19 매매: -v ON_ERROR_STOP=1 없이 실행할 수 없다 (단언이 실패하면 멈춰야 한다)'
  \quit
\endif

-- 정의는 트랜잭션 밖에서 읽는다 (21 → 22 → 25 순서가 곧 의존)
\ir 21_team_buy_def.sql
\ir 22_reconcile_season_def.sql
\ir 25_trade_formation_def.sql

-- 전제 단언 (쓰지 않는다)
DO $$
DECLARE
    v_bad text;
    v_n   bigint;
    v_s   record;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'formation_change', 'turn_feed',
                      'turn_price', 'season_path', 'asset', 'asset_position', 'reconciliation_log', 'turn']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-19 매매: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %)', current_schema(), COALESCE(v_bad, '-');
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'P0-19 매매: 격리 수준이 READ COMMITTED 가 아니다';
    END IF;
    SELECT count(*) INTO v_n FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'P0-19 매매: 연습 시즌이 % 개다 (1 개여야 한다). 20·21·23 을 먼저 실행한다', v_n;
    END IF;
    SELECT se.* INTO v_s FROM season se JOIN member m ON m.id = se.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
    IF v_s.status <> 'IN_PROGRESS' OR v_s.current_turn_no <> 5 THEN
        RAISE EXCEPTION 'P0-19 매매: 이 시나리오는 진행 중이고 현재 턴이 5 인 시즌에서만 실행한다 (db/23_turn_advance.sql 을 5번 실행한 상태)';
    END IF;
    IF EXISTS (SELECT 1 FROM orders o WHERE o.season_id = v_s.id AND o.turn_no >= 1) OR EXISTS (SELECT 1 FROM formation_change fc WHERE fc.season_id = v_s.id) THEN
        RAISE EXCEPTION 'P0-19 매매: 1턴 이후 주문이나 전술 변경이 이미 있다 — 이미 적용된 상태다 (멱등이 아니다)';
    END IF;
    IF v_s.current_defender < 10 OR v_s.current_forward > 90 THEN
        RAISE EXCEPTION 'P0-19 매매: 현재 포메이션에서 수비 −10 · 공격 +10 을 할 수 없다';
    END IF;
END $$;

-- 임시 단언 함수 (정리용 DROP 은 pg_temp. 로 한정)
SET client_min_messages TO warning;
DROP FUNCTION IF EXISTS pg_temp.sc_assert(boolean, text);
RESET client_min_messages;
CREATE FUNCTION pg_temp.sc_assert(p_ok boolean, p_what text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF p_ok IS NOT TRUE THEN
        RAISE EXCEPTION 'P0-19 매매: 단언 실패 — %', p_what;
    END IF;
END $$;

-- 시즌과 현재 턴
SELECT se.id AS sid, se.current_turn_no AS cur
FROM season se JOIN member m ON m.id = se.member_id
WHERE lower(m.email) = lower('phase0@example.invalid')
\gset

-- 선택 기준 ①: 열린 구간이 수비이고 보유 2주 이상이며 현재 턴 가격이 있는 종목 중 번호가 가장 작은 것 — 보유의 절반(내림)을 판다.
SELECT h.asset_id AS a1, h.quantity / 2 AS q1
FROM holding h
JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL AND lu.declared_position = 'DEFENDER'
JOIN turn_price tp ON tp.season_id = h.season_id AND tp.turn_no = :cur AND tp.asset_id = h.asset_id
WHERE h.season_id = :sid AND h.quantity >= 2
ORDER BY h.asset_id
LIMIT 1
\gset
SELECT pg_temp.sc_assert(:{?a1}, '선택 ①: 보유 2주 이상인 수비 종목이 없다') AS ok_ \gset

-- 선택 기준 ③: 현재 턴 피드 중 종류가 NUDGE 가 아니고 종목을 가리키며, 현재 턴 가격이 있고 지금 보유하지 않는 종목 — 순번(seq)이 가장 작은 피드.
--   선언 포지션은 시스템 판정(asset_position.system_position)을 그대로 쓴다.
SELECT f.id AS feed_id, f.asset_id AS b1, ap.system_position AS pos2
FROM turn_feed f
JOIN turn_price tp ON tp.season_id = f.season_id AND tp.turn_no = f.turn_no AND tp.asset_id = f.asset_id
JOIN season se ON se.id = f.season_id
JOIN asset_position ap ON ap.asset_id = f.asset_id AND ap.base_date = se.base_date
WHERE f.season_id = :sid AND f.turn_no = :cur AND f.kind <> 'NUDGE' AND f.asset_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM holding h WHERE h.season_id = f.season_id AND h.asset_id = f.asset_id AND h.quantity > 0)
ORDER BY f.seq
LIMIT 1
\gset
SELECT pg_temp.sc_assert(:{?feed_id}, '선택 ③: 현재 턴 피드에서 보유하지 않은 종목을 찾지 못했다') AS ok_ \gset

-- 선택 기준 ④: 열린 구간이 미드필더이고 현재 턴 가격이 있는 보유 종목 중 번호가 가장 작은 것 — 전량.
SELECT h.asset_id AS c1, h.quantity AS q3
FROM holding h
JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL AND lu.declared_position = 'MIDFIELDER'
JOIN turn_price tp ON tp.season_id = h.season_id AND tp.turn_no = :cur AND tp.asset_id = h.asset_id
WHERE h.season_id = :sid AND h.quantity > 0
ORDER BY h.asset_id
LIMIT 1
\gset
SELECT pg_temp.sc_assert(:{?c1}, '선택 ④: 미드필더 보유 종목이 없다') AS ok_ \gset

-- 선택 기준 ⑤: 열린 구간이 FORWARD_TARGET 인 보유 종목 중 번호가 가장 작은 것 — 같은 선언으로 1주 추가.
SELECT h.asset_id AS d1
FROM holding h
JOIN lineup lu ON lu.season_id = h.season_id AND lu.asset_id = h.asset_id AND lu.end_turn IS NULL AND lu.declared_position = 'FORWARD_TARGET'
JOIN turn_price tp ON tp.season_id = h.season_id AND tp.turn_no = :cur AND tp.asset_id = h.asset_id
WHERE h.season_id = :sid AND h.quantity > 0
ORDER BY h.asset_id
LIMIT 1
\gset
SELECT pg_temp.sc_assert(:{?d1}, '선택 ⑤: FORWARD_TARGET 보유 종목이 없다') AS ok_ \gset

BEGIN;

-- ① 변경 전 DIRECT 부분 매도 — 아직 전술 변경이 없으므로 formation_change_id 는 NULL 이다
SELECT pg_temp.trade_sell(:sid, :cur::smallint, :a1, :q1, 'DIRECT', NULL) AS o1 \gset

-- ② 전술 변경 1회: 수비 −10, 공격 +10 (골키퍼·미드필더 그대로). 종목의 선언 포지션·수량은 자동으로 바뀌지 않는다.
SELECT pg_temp.formation_change(:sid, :cur::smallint, s.current_goalkeeper, (s.current_defender - 10)::smallint, s.current_midfielder, (s.current_forward + 10)::smallint,
                                '5턴 뒤 수비를 줄이고 공격을 늘림') AS fc1
FROM season s WHERE s.id = :sid
\gset

-- ③ 변경 뒤 FEED 매수 — 수량: 지금 주문 가능 금액(잔액 − 예약금)의 절반 예산 안에서 수수료를 포함해 살 수 있는 정수 주
SELECT pg_temp.team_qty(floor((a.balance - a.reserved) / 2), tp.price,
                        pg_temp.buy_rate(x.market, se.fee_rate_kr, se.fee_rate_us, se.fx_spread_rate)) AS q2
FROM cash_account a
JOIN season se ON se.id = a.season_id
JOIN asset x ON x.id = :b1
JOIN turn_price tp ON tp.season_id = a.season_id AND tp.turn_no = :cur AND tp.asset_id = :b1
WHERE a.season_id = :sid
\gset
SELECT pg_temp.sc_assert(:q2 >= 1, '선택 ③: 절반 예산으로 1주도 못 산다') AS ok_ \gset
SELECT pg_temp.trade_buy(:sid, :cur::smallint, :b1, :q2, :'pos2', 'FEED', :feed_id) AS o2 \gset

-- ④ 변경 뒤 DIRECT 전량 매도
SELECT pg_temp.trade_sell(:sid, :cur::smallint, :c1, :q3, 'DIRECT', NULL) AS o3 \gset

-- ⑤ 변경 뒤 RANKING 추가 매수 1주 (같은 선언 — 열린 구간 유지, 이동평균)
SELECT pg_temp.trade_buy(:sid, :cur::smallint, :d1, 1, 'FORWARD_TARGET', 'RANKING', NULL) AS o4 \gset

-- ⑥ 부분 매도했던 수비 종목의 선언을 미드필더로 (구간 닫고 열기)
SELECT pg_temp.lineup_change(:sid, :cur::smallint, :a1, 'MIDFIELDER') AS lu1 \gset

-- 단언: 연결·모양 (건수만)
SELECT pg_temp.sc_assert((SELECT formation_change_id IS NULL AND source = 'DIRECT' AND side = 'SELL' FROM orders WHERE id = :o1), '① 변경 전 주문은 변경에 묶이지 않는다') AS ok_ \gset
SELECT pg_temp.sc_assert((SELECT count(*) = 3 FROM orders WHERE season_id = :sid AND turn_no = :cur AND formation_change_id = :fc1), '② 변경 뒤 주문 3건이 변경에 묶인다') AS ok_ \gset
SELECT pg_temp.sc_assert((SELECT source = 'FEED' AND turn_feed_id = :feed_id AND side = 'BUY' FROM orders WHERE id = :o2), '③ FEED 주문은 피드를 참조한다') AS ok_ \gset
SELECT pg_temp.sc_assert((SELECT quantity = 0 AND avg_price = 0 FROM holding WHERE season_id = :sid AND asset_id = :c1), '④ 전량 매도 뒤 보유 행은 0·0 으로 남는다') AS ok_ \gset
SELECT pg_temp.sc_assert((SELECT end_turn = :cur FROM lineup WHERE season_id = :sid AND asset_id = :c1 AND end_turn IS NOT NULL ORDER BY id DESC LIMIT 1), '④ 전량 매도 뒤 구간이 닫힌다') AS ok_ \gset
SELECT pg_temp.sc_assert((SELECT count(*) = 2 AND count(*) FILTER (WHERE end_turn IS NULL) = 1 FROM lineup WHERE season_id = :sid AND asset_id = :a1), '⑥ 포지션 변경 뒤 구간이 2개이고 하나만 열려 있다') AS ok_ \gset
SELECT pg_temp.sc_assert((SELECT count(*) = 15 AND count(*) FILTER (WHERE status = 'MATCH') = 15 FROM pg_temp.recon_rules(:sid, :cur::smallint)), '22 수동 검사 15개가 모두 일치한다') AS ok_ \gset

COMMIT;

-- 보고: 건수만
SELECT (SELECT count(*) FROM orders WHERE season_id = :sid AND turn_no = :cur)                                   AS orders_now,
       (SELECT count(*) FROM orders WHERE season_id = :sid AND turn_no = :cur AND formation_change_id IS NOT NULL) AS bound_to_change,
       (SELECT count(*) FROM orders WHERE season_id = :sid AND turn_no = :cur AND source = 'FEED')                AS from_feed,
       (SELECT count(*) FROM formation_change WHERE season_id = :sid)                                             AS formation_changes,
       (SELECT count(*) FROM execution e JOIN orders o ON o.id = e.order_id WHERE o.season_id = :sid AND o.turn_no = :cur) AS executions_now,
       (SELECT count(*) FROM cash_ledger WHERE season_id = :sid AND type IN ('SELL', 'FEE') AND ref_order_id IN (SELECT id FROM orders WHERE turn_no = :cur)) AS sell_or_fee_rows_now,
       (SELECT count(*) FROM lineup WHERE season_id = :sid AND end_turn IS NULL)                                  AS open_lineup,
       :cur                                                                                                       AS turn_no;
