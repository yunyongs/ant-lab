-- =====================================================================
-- my Fund Manager Phase 0 — 턴 진행 정의 (P0-17): 턴 가격 생성기·턴 스냅샷·규칙 11·19·17개 묶음·턴 적용·턴 전환
-- 근거: docs/erd.md 6.3(season·season_path·turn·turn_price·lineup·reconciliation_log)·7.5(대사 규칙 1–19)·D9·D16·D23·D24·D25·D26·D28·D32·D36·D37·9절 P0-16·P0-17,
--       docs/PLAN.md §4.1-4·§4.1-7·§4.5·§4.10·§5.2(항등식 3개·반올림은 한 곳 한 번·일치해도 기록·불일치면 되돌림),
--       docs/SPRING_JPA.md §4.1(MoneyMath)·§5.2(잠금 순서)·§5.3(턴 전환 1–5·7·9·10)·§6(대사, 롤백 뒤 기록)
-- 단독 실행 대상이 아니다. db/23_turn_advance.sql(한 턴 진행)·db/23_turn_advance_check.sql(검증)·db/23_turn_advance_drill.sql(롤백 드릴)이 \ir 로 읽는다.
--       반드시 db/22_reconcile_season_def.sql 을 먼저 읽은 뒤(같은 세션, 트랜잭션 밖)에 이 파일을 읽는다 — 이 파일의 규칙 함수가 22 의 recon_detail·recon_code 를 쓴다.
--       22 파일은 고치지 않는다. 앞 번호 파일(20·21·22)은 이 파일을 읽지 않는다 (순서가 곧 의존).
-- 만드는 것: 세션 임시 객체(pg_temp)뿐이다. 스키마에 객체가 늘지 않는다.
--   pg_temp.ta_prices(시즌, 턴)             재귀 CTE: 저장된 0턴 가격에서 출발해 턴마다 (1 + 현지 수익률)(1 + 환율 변화)를 곱하고 마지막에 한 번 ROUND → (asset_id, price)
--   pg_temp.ta_drawdown(총액, 고점)         순수 함수: 낙폭을 정수 산술로 정확히 한 번 반올림 (HALF_UP) → numeric(12,8), 0 이하
--   pg_temp.ta_snapshot(시즌, 턴)           turn 행 한 줄의 값 (현금·보유 평가액·총액·포지션별 4개·낙폭). 골키퍼는 총액 − (수비 + 미드필더 + 공격)
--   pg_temp.recon_r11_valuation(시즌, 턴)   규칙 11 VALUATION   — 스냅샷과 다른 식으로 다시 계산해 대조. 22 규칙 함수와 같은 4컬럼 1행, 세 값 NULL
--   pg_temp.recon_r19_position_value(시즌, 턴) 규칙 19 POSITION_VALUE — 선언 포지션으로 직접 묶은 합과 대조. 같은 4컬럼 1행, 세 값 NULL
--   pg_temp.recon_rules_turn(시즌, 턴)      17행(rule_no 순): 22 의 recon_rules 15행 + 규칙 11·19, 한 문장이라 한 스냅샷
--   pg_temp.ta_precheck(시즌, 기대 턴)      쓰기 없음: 잠금·전제 확인·다음 턴 가격 계산 → 가격 jsonb 배열. 검증이 INSERT 없이 거부 사례를 시험하는 입구
--   pg_temp.ta_apply(시즌, 기대 턴)         트랜잭션 문 없음: precheck → turn_price → turn → 17개 대사 → 17행 jsonb 반환 (Java TurnService.advance 의 SQL 짝)
--   pg_temp.turn_advance(시즌, 기대 턴)     PROCEDURE, 트랜잭션 제어: 전부 MATCH 면 기록·턴 올림·COMMIT, 하나라도 MISMATCH 면 ROLLBACK → 기록 → COMMIT → 오류 (Java TurnFacade 의 SQL 짝)
-- 트랜잭션 제어 조건 (turn_advance 는 안에서 COMMIT·ROLLBACK 을 한다):
--   ① 최상위 CALL 로만 부른다 (psql 에서 `CALL pg_temp.turn_advance(…)` 한 문장). 바깥 BEGIN 안에서 부르면 SQLSTATE 2D000 invalid transaction termination,
--      EXCEPTION 블록(DO·함수 안의 BEGIN … EXCEPTION)이나 SELECT 로 부른 함수 안에서 부르면 마찬가지로 2D000 이다. 격리 DB 에서 확인했다.
--   ② 이 파일은 트랜잭션 밖에서 읽는다. 정의가 롤백될 트랜잭션 안에서 만들어지면 롤백과 함께 사라진다.
--   ③ ta_precheck·ta_apply 는 트랜잭션 문이 없는 함수라 서브트랜잭션(EXCEPTION 블록) 안에서 불러도 된다 — 검증 파일이 그렇게 시험한다.
-- 한 턴의 순서 (SPRING_JPA §5.3, 잠금 순서 season → cash_account): 쓰기 전에 전제를 전부 확인한다.
--   ta_precheck: 1 인자·격리 수준 → 2 season FOR UPDATE → 상태·턴 일치 → 3 cash_account FOR UPDATE → 4 다음 턴 경로 → 5 다음 턴 가격 계산·풀 크기 확인
--   ta_apply:    6 INSERT turn_price(다음 턴 풀 전체) → 7 INSERT turn ← ta_snapshot → 8 recon_rules_turn 17행 → 9 코드 묶음 단언
--   turn_advance: 10 ta_apply → 11 전부 MATCH: recon_log → season 갱신 → COMMIT / 12 MISMATCH: ROLLBACK → recon_log → COMMIT → 오류
--   소음 피드(§5.3 7b, P0-18)는 7단계 스냅샷 뒤에 생성하고 피드 오류도 턴 전체와 함께 되돌린다. 기준 포트폴리오(8)·적립금(6)은 이 기능에서 하지 않는다 (P0-20·EXT).
-- 오류: 모두 RAISE EXCEPTION (SQLSTATE P0001)이고 HINT 에 종류 코드를 넣는다. 메시지에 금액·가격·달·블록 번호를 넣지 않는다. Java 도메인 예외 이름과 짝이다.
--   HINT                   언제                                              Java 짝 (P0-33·P0-34)
--   SEASON_NOT_FOUND       시즌 번호가 없다                                  SeasonNotFound (404)
--   SEASON_NOT_ACTIVE      상태가 IN_PROGRESS·ABANDONING 이 아니다 (끝남 등) SeasonNotActive (409)
--   TURN_MISMATCH          기대 턴 ≠ 시즌의 현재 턴                          TurnMismatch (409)
--   NO_PATH                다음 턴의 season_path 행이 없다                   InconsistentSeason (500)
--   NO_RETURN              다음 턴 가격을 만들 수 없거나 0 이하인 풀 종목이 있다 (수익률·환율·0턴 가격 없음, −100 % 달, 반올림하면 0)  IllegalStateException → 500 INTERNAL_ERROR (TurnPriceCalculator)
--   ISOLATION_LEVEL        격리 수준이 READ COMMITTED 가 아니다              (환경 오류)
--   INVALID_ARGUMENT       인자가 NULL·음수, 또는 17개 묶음이 완전하지 않다  IllegalArgument (400/500)
--   RECONCILIATION_FAILED  대사 불일치 — 롤백한 뒤 로그를 남기고 이 오류로 끝난다  ReconciliationFailed (409)
--   FEED_*                 소음 피드 생성(24_turn_feed_def.sql)이 같은 규약으로 낸다: FEED_INPUT(턴·경로 없음)·FEED_EVENTS_UNREVIEWED(사건 편집 미완료)·FEED_REACTION_MISSING(반응값 NULL)·
--                          FEED_RENDER(렌더·생성 결과 계약 위반: 템플릿 없음, 15행 초과, 자리표시자 오류)·FEED_ALREADY_STORED(같은 턴 재저장)   Java 짝: 피드를 Java 가 도입할 때 정한다 (Phase 0 API 는 피드를 만들지 않는다)
--   앞의 일곱 가지는 아무것도 쓰기 전에 나므로 로그를 남기지 않는다 (대사를 하지 않았다 — 기록할 결과가 없다). 대사 불일치만 롤백 뒤 reconciliation_log 에 남는다.
-- 격리 수준: READ COMMITTED 를 전제로 한다 (21 과 같음). ta_snapshot·규칙 함수는 STABLE 이라 부른 문장의 스냅샷을 쓰고, 앞선 문장에서 같은 트랜잭션이 넣은 행을 본다.
--   ta_precheck·ta_apply 는 잠금을 잡으므로 VOLATILE(기본값)이어야 한다.
-- 사후 정보 배제 (PLAN §4.5): ta_prices 는 season_path 의 1..요청 턴 블록만 읽는다. 다음 턴 뒤의 수익률은 읽지 않는다.
-- 정수 산술 반올림: 21 의 buy_avg_price 와 같은 방식이다. div(2n + d, 2d) 는 n/d 를 0에서 먼 쪽(HALF_UP)으로 정수에 한 번 반올림한다 (n, d > 0).
-- 새 문법: WITH RECURSIVE(재귀 CTE) 누적 곱, CREATE PROCEDURE 와 프로시저 안의 COMMIT·ROLLBACK, 지역 변수로 롤백 건너 값 나르기, jsonb_to_recordset,
--       FULL 이 아닌 LEFT JOIN 으로 빠진 가격 찾기, 창 함수 max() OVER (ORDER BY …) 로 지금까지의 고점.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 턴 가격 생성기 — 재귀 CTE로 누적 곱을 한 턴씩 쌓는다 (기능 표 결정 8, PLAN §5.2, erd D16·D25)
--   시작 행 = 저장된 turn_price(턴 0). 재귀 단계 k → k+1 이 season_path(k+1)의 블록으로 asset_month_return·fx_month 를 붙여
--   acc × (1 + 현지 수익률) × (1 + 환율 변화) 를 곱한다. 원화 종목은 환율 인자 1 이고 환율 행을 찾지 않는다.
--   곱은 정확한 numeric 이고 반올림은 맨 끝의 ROUND(acc, 0) 한 곳뿐이다 (매달 반올림하면 달라진다 — 검증 02절이 그 칸 수를 센다).
--   수익률·환율·블록이 빠진 종목은 재귀가 끊겨 행이 나오지 않는다 → ta_precheck 가 풀 크기와 비교해 NO_RETURN 으로 막는다.
--   p_turn 뒤의 블록은 읽지 않는다 (acc.k < p_turn).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.ta_prices(p_season_id bigint, p_turn smallint)
RETURNS TABLE (asset_id bigint, price numeric)
LANGUAGE sql STABLE AS $$
    WITH RECURSIVE acc(k, asset_id, currency, v) AS (
        SELECT 0, tp.asset_id, a.currency, tp.price::numeric
        FROM turn_price tp
        JOIN asset a ON a.id = tp.asset_id
        WHERE tp.season_id = p_season_id AND tp.turn_no = 0
      UNION ALL
        SELECT acc.k + 1, acc.asset_id, acc.currency,
               acc.v * (1 + amr.local_return) * CASE WHEN acc.currency = 'KRW' THEN 1 ELSE 1 + fx.fx_change END
        FROM acc
        JOIN season_path sp ON sp.season_id = p_season_id AND sp.turn_no = acc.k + 1
        JOIN asset_month_return amr ON amr.asset_id = acc.asset_id AND amr.month_block_id = sp.month_block_id
        LEFT JOIN fx_month fx ON fx.month_block_id = sp.month_block_id AND fx.currency = acc.currency
        WHERE acc.k < p_turn
          AND (acc.currency = 'KRW' OR fx.fx_change IS NOT NULL)
    )
    SELECT acc.asset_id, ROUND(acc.v, 0)
    FROM acc
    WHERE acc.k = p_turn
$$;

-- ---------------------------------------------------------------------
-- 낙폭 = (총액 − 고점) ÷ 고점 을 소수 8자리로 정확히 한 번 반올림 (HALF_UP, 0에서 먼 쪽). 0 이하다.
--   분자 n = 고점 − 총액 ≥ 0, 분모 d = 고점 > 0 일 때 −div(2·n·10^8 + d, 2·d) ÷ 10^8. 나눗셈의 중간 반올림이 없다 (21 의 buy_avg_price 와 같은 방식).
--   erd 6.3 이 "8자리 반올림은 대입 반올림"이라고 한 것을 정밀하게 바꾼 것이다 (D37): 나눗셈 결과를 유한 자릿수로 끊은 뒤 다시 대입 반올림하면 이론상 이중 반올림이다.
--   총액 ≥ 고점이면 0 (새 고점). 총액 0 이면 −1.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.ta_drawdown(p_total numeric, p_peak numeric)
RETURNS numeric(12,8)
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN p_total >= p_peak THEN 0::numeric(12,8)
                ELSE (-(div(2 * (p_peak - p_total) * 100000000 + p_peak, 2 * p_peak))::numeric / 100000000)::numeric(12,8)
           END
$$;

-- ---------------------------------------------------------------------
-- 턴 스냅샷 값 (turn 행 한 줄) — 현재 보유·현금·열린 라인업·저장된 턴 가격에서 만든다 (erd 6.3 turn, D24, PLAN §4.10)
--   현금 = cash_account.balance, 보유 평가액 = Σ holding.quantity × turn_price(p_turn) (가격 없는 보유는 조용히 빠진다 — 규칙 11 이 NO_PRICE 로 잡는다),
--   총액 = 현금 + 보유 평가액. 수비·미드필더·공격 = 열린 lineup(start_turn < p_turn)의 선언 포지션별 Σ 수량 × 가격 (공격 = FORWARD_* 3종).
--   골키퍼 = 총액 − (수비 + 미드필더 + 공격)  ← 잔여 방식 (D37). 직접 합(현금 + 골키퍼 줄)으로 넣으면 라인업이 빠진 보유가 있을 때 ck_turn_position_sum 이
--     INSERT 에서 23514 로 터져 대사도 로그도 없이 멈춘다 (PLAN §5.2 "불일치면 되돌리고 기록"이 깨진다). 잔여 방식이면 CHECK 를 지나고 규칙 19(직접 합)와 17 이 잡아 기록된다.
--     정상 상태에서는 두 식이 같다 (erd 6.3 골키퍼 정의 = 현금 + 골키퍼 줄의 평가액).
--   고점 = max(initial_cash, 1..p_turn−1 턴의 총액, 이번 총액). 낙폭 = ta_drawdown(총액, 고점).
--   계좌 행이 없으면 0행을 돌려준다 (규칙 11 NO_TURN 과 규칙 1·2·9 NO_ACCOUNT 가 잡는다).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.ta_snapshot(p_season_id bigint, p_turn smallint)
RETURNS TABLE (cash_balance numeric(19,4), holdings_value numeric(19,4), total_value numeric(19,4), goalkeeper_value numeric(19,4),
               defender_value numeric(19,4), midfielder_value numeric(19,4), forward_value numeric(19,4), drawdown numeric(12,8))
LANGUAGE sql STABLE AS $$
    WITH h AS (
        SELECT COALESCE(sum(hd.quantity * tp.price), 0) AS hv
        FROM holding hd
        JOIN turn_price tp ON tp.season_id = hd.season_id AND tp.turn_no = p_turn AND tp.asset_id = hd.asset_id
        WHERE hd.season_id = p_season_id
    ), pv AS (
        SELECT COALESCE(sum(hd.quantity * tp.price) FILTER (WHERE lu.declared_position = 'DEFENDER'), 0)   AS dv,
               COALESCE(sum(hd.quantity * tp.price) FILTER (WHERE lu.declared_position = 'MIDFIELDER'), 0) AS mv,
               COALESCE(sum(hd.quantity * tp.price) FILTER (WHERE lu.declared_position LIKE 'FORWARD\_%'), 0) AS fv
        FROM lineup lu
        JOIN holding hd ON hd.season_id = lu.season_id AND hd.asset_id = lu.asset_id
        JOIN turn_price tp ON tp.season_id = lu.season_id AND tp.turn_no = p_turn AND tp.asset_id = lu.asset_id
        WHERE lu.season_id = p_season_id AND lu.end_turn IS NULL AND lu.start_turn < p_turn
    ), b AS (
        SELECT a.balance AS cash, h.hv, a.balance + h.hv AS total, pv.dv, pv.mv, pv.fv
        FROM cash_account a, h, pv
        WHERE a.season_id = p_season_id
    )
    SELECT b.cash, b.hv, b.total, b.total - b.dv - b.mv - b.fv, b.dv, b.mv, b.fv,
           pg_temp.ta_drawdown(b.total, GREATEST((SELECT s.initial_cash FROM season s WHERE s.id = p_season_id),
                                                 COALESCE((SELECT max(t.total_value) FROM turn t WHERE t.season_id = p_season_id AND t.turn_no < p_turn), 0),
                                                 b.total))
    FROM b
$$;

-- ---------------------------------------------------------------------
-- 규칙 11 VALUATION — 평가액 (erd 7.5 규칙 11, PLAN §5.2 항등식 ③): 새 turn 행의 보유 평가액·현금·낙폭을 스냅샷과 다른 식으로 다시 계산해 대조한다.
--   스냅샷(ta_snapshot)은 보유 ⋈ 가격 내부 조인의 합이고, 이 함수는 ① 종목마다 LEFT JOIN 으로 가격 없는 보유를 NO_PRICE 로 드러내고
--   ② 낙폭을 나눗셈 + ROUND(…, 8) 로 다시 계산하며(스냅샷은 정수 산술) ③ 고점을 turn 행들의 창 함수 max() OVER 로 구한다.
--   두 식이 갈리는 극히 드문 경우(나눗셈 자릿수 끝의 이중 반올림)는 보수적으로 MISMATCH 가 되어 턴을 멈춘다 — 돈이 틀린 쪽이 아니라 멈추는 쪽이다.
--   turn(p_turn) 행이 없으면 NO_TURN 하나만 보고한다.
--   불일치 수 = 항목 수. 항목 kind: NO_TURN(turn_no) · NO_PRICE(asset_id) · HOLDINGS(turn_no) · CASH(turn_no) · DRAWDOWN(turn_no). 세 값 NULL
--   (recon_code.scalar = false — 22 를 고치지 않는 범위에서 22 의 구분을 따른다, D37).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r11_valuation(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH t AS (
        SELECT x.turn_no, x.cash_balance, x.holdings_value, x.drawdown,
               GREATEST((SELECT s.initial_cash FROM season s WHERE s.id = p_season_id), x.runmax) AS peak, x.total_value
        FROM (SELECT tt.turn_no, tt.cash_balance, tt.holdings_value, tt.total_value, tt.drawdown,
                     max(tt.total_value) OVER (ORDER BY tt.turn_no) AS runmax
              FROM turn tt
              WHERE tt.season_id = p_season_id AND tt.turn_no <= p_turn) x
        WHERE x.turn_no = p_turn
    ), hv AS (
        SELECT hd.asset_id, hd.quantity, tp.price
        FROM holding hd
        LEFT JOIN turn_price tp ON tp.season_id = hd.season_id AND tp.turn_no = p_turn AND tp.asset_id = hd.asset_id
        WHERE hd.season_id = p_season_id AND hd.quantity > 0
    ), bad AS (
        SELECT 0::bigint AS k1, 0::bigint AS k2, jsonb_build_object('kind', 'NO_TURN', 'turn_no', p_turn) AS item
        WHERE NOT EXISTS (SELECT 1 FROM t)
        UNION ALL
        SELECT 1, hv.asset_id, jsonb_build_object('kind', 'NO_PRICE', 'asset_id', hv.asset_id)
        FROM hv
        WHERE hv.price IS NULL AND EXISTS (SELECT 1 FROM t)
        UNION ALL
        SELECT 2, 0, jsonb_build_object('kind', 'HOLDINGS', 'turn_no', p_turn)
        FROM t
        WHERE t.holdings_value <> (SELECT COALESCE(sum(hv.quantity * hv.price), 0) FROM hv)
        UNION ALL
        SELECT 3, 0, jsonb_build_object('kind', 'CASH', 'turn_no', p_turn)
        FROM t
        WHERE t.cash_balance IS DISTINCT FROM (SELECT a.balance FROM cash_account a WHERE a.season_id = p_season_id)
        UNION ALL
        SELECT 4, 0, jsonb_build_object('kind', 'DRAWDOWN', 'turn_no', p_turn)
        FROM t
        WHERE t.drawdown <> ROUND((t.total_value - t.peak) / t.peak, 8)
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1, bad.k2) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 19 POSITION_VALUE — 포지션별 평가액 (erd 7.5 규칙 19, PLAN §4.10): turn(p_turn)의 네 값을 열린 lineup 의 선언 포지션으로 직접 묶은 합과 대조한다.
--   직접 합: 수비·미드필더·공격 = Σ 수량 × 가격, 골키퍼 = 골키퍼 줄의 합 + 그 스냅샷의 cash_balance (W6: 현금 오류는 규칙 11 하나에만 걸리게 한다).
--   스냅샷의 골키퍼는 잔여 방식이므로 라인업이 빠진 보유는 여기서 GOALKEEPER 불일치로 드러난다. turn(p_turn) 행이 없으면 NO_TURN 하나만 보고한다.
--   불일치 수 = 항목 수. 항목 kind: NO_TURN · GOALKEEPER · DEFENDER · MIDFIELDER · FORWARD (모두 turn_no). 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r19_position_value(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH t AS (
        SELECT tt.cash_balance, tt.goalkeeper_value, tt.defender_value, tt.midfielder_value, tt.forward_value
        FROM turn tt
        WHERE tt.season_id = p_season_id AND tt.turn_no = p_turn
    ), lines AS (
        SELECT CASE WHEN lu.declared_position = 'GOALKEEPER' THEN 'G'
                    WHEN lu.declared_position = 'DEFENDER' THEN 'D'
                    WHEN lu.declared_position = 'MIDFIELDER' THEN 'M'
                    ELSE 'F' END AS pos,
               sum(hd.quantity * tp.price) AS v
        FROM lineup lu
        JOIN holding hd ON hd.season_id = lu.season_id AND hd.asset_id = lu.asset_id
        JOIN turn_price tp ON tp.season_id = lu.season_id AND tp.turn_no = p_turn AND tp.asset_id = lu.asset_id
        WHERE lu.season_id = p_season_id AND lu.end_turn IS NULL AND lu.start_turn < p_turn
        GROUP BY 1
    ), bad AS (
        SELECT 0::bigint AS k1, jsonb_build_object('kind', 'NO_TURN', 'turn_no', p_turn) AS item
        WHERE NOT EXISTS (SELECT 1 FROM t)
        UNION ALL
        SELECT 1, jsonb_build_object('kind', 'GOALKEEPER', 'turn_no', p_turn)
        FROM t WHERE t.goalkeeper_value <> t.cash_balance + COALESCE((SELECT l.v FROM lines l WHERE l.pos = 'G'), 0)
        UNION ALL
        SELECT 2, jsonb_build_object('kind', 'DEFENDER', 'turn_no', p_turn)
        FROM t WHERE t.defender_value <> COALESCE((SELECT l.v FROM lines l WHERE l.pos = 'D'), 0)
        UNION ALL
        SELECT 3, jsonb_build_object('kind', 'MIDFIELDER', 'turn_no', p_turn)
        FROM t WHERE t.midfielder_value <> COALESCE((SELECT l.v FROM lines l WHERE l.pos = 'M'), 0)
        UNION ALL
        SELECT 4, jsonb_build_object('kind', 'FORWARD', 'turn_no', p_turn)
        FROM t WHERE t.forward_value <> COALESCE((SELECT l.v FROM lines l WHERE l.pos = 'F'), 0)
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 턴 전환 대사 17행 — 22 의 recon_rules 15행(수동 검사와 같은 식)에 규칙 11·19 를 같은 방식(status·diff_value·분류·detail)으로 붙여 rule_no 순으로 묶는다.
--   한 문장이라 17개가 같은 스냅샷을 본다 (STABLE 함수의 스냅샷 — erd D36). 시즌이 없으면 22 의 recon_rules 가 P0001 SEASON_NOT_FOUND 로 막는다.
--   p_turn = 넘어가려던 턴 t+1 이다 (규칙 12 는 0..p_turn 의 가격을 보고, 규칙 18 은 p_turn 을 현재 턴 상한으로 본다).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_rules_turn(p_season_id bigint, p_turn smallint)
RETURNS TABLE (rule_no smallint, check_code varchar(20), status varchar(8), expected_value numeric(19,4), actual_value numeric(19,4),
               diff_value numeric(19,4), mismatch_count integer, classification varchar(11), detail jsonb)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
BEGIN
    RETURN QUERY
    SELECT q.rule_no, q.check_code, q.status, q.expected_value, q.actual_value, q.diff_value, q.mismatch_count, q.classification, q.detail
    FROM (
        SELECT r.rule_no, r.check_code, r.status, r.expected_value, r.actual_value, r.diff_value, r.mismatch_count, r.classification, r.detail
        FROM pg_temp.recon_rules(p_season_id, p_turn) r
        UNION ALL
        SELECT u.rule_no::smallint, c.check_code::varchar(20),
               (CASE WHEN u.mismatch_count = 0 THEN 'MATCH' ELSE 'MISMATCH' END)::varchar(8),
               u.expected_value, u.actual_value, u.actual_value - u.expected_value, u.mismatch_count,
               (CASE WHEN u.mismatch_count = 0 THEN NULL ELSE 'INVESTIGATE' END)::varchar(11),
               CASE WHEN u.mismatch_count = 0 THEN NULL ELSE u.detail END
        FROM (SELECT 11 AS rule_no, v.* FROM pg_temp.recon_r11_valuation(p_season_id, p_turn) v
              UNION ALL
              SELECT 19, w.* FROM pg_temp.recon_r19_position_value(p_season_id, p_turn) w) u
        JOIN pg_temp.recon_code c ON c.rule_no = u.rule_no
    ) q
    ORDER BY q.rule_no;
END $$;

-- ---------------------------------------------------------------------
-- 쓰기 전 확인 (트랜잭션 문 없음, 쓰기 없음): 인자·격리 수준 → season FOR UPDATE → 상태·턴 일치 → cash_account FOR UPDATE → 다음 턴 경로 → 다음 턴 가격 계산·풀 크기 확인
--   돌려주는 값: 다음 턴 풀 전체의 가격 jsonb 배열 [{"a": 종목 번호, "p": 가격}, …] (종목 번호 순). 임시 표가 아니라 값으로 건네므로 호출 문장 사이에 상태가 없다.
--   ABANDONING 도 진행을 받는다 (PLAN §4.1-7 — 남은 턴 자동 진행). FINISHED·그 밖은 SEASON_NOT_ACTIVE.
--   잠금 순서는 season → cash_account (21 의 order_buy 와 같다). 계좌 행이 없어도 여기서 막지 않는다 — 스냅샷이 비고 대사가 NO_ACCOUNT 로 기록한다.
--   풀 크기 확인: 기준일 풀(asset_position.base_date = season.base_date)의 종목마다 다음 턴 가격이 나와야 한다. 하나라도 없거나 반올림한 가격이 0 이하면 NO_RETURN (건수 하나만 메시지에 넣고 어느 종목인지는 넣지 않는다).
--   검증 파일이 이 함수만 불러 거부 사례를 INSERT 없이(identity 를 쓰지 않고) 시험한다.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.ta_precheck(p_season_id bigint, p_expected_turn smallint)
RETURNS jsonb
LANGUAGE plpgsql AS $$
DECLARE
    v_status  text;
    v_turn    smallint;
    v_base    date;
    v_next    smallint;
    v_prices  jsonb;
    v_missing bigint;
    v_nonpos  bigint;
BEGIN
    IF p_season_id IS NULL OR p_expected_turn IS NULL OR p_expected_turn < 0 THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 시즌 번호나 기대 턴이 없거나 음수다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 격리 수준이 READ COMMITTED 가 아니다. 잠금 뒤 읽기가 이 가정에 기댄다' USING HINT = 'ISOLATION_LEVEL';
    END IF;

    -- 잠금 ①: 시즌. 같은 시즌의 주문·턴 전환·전술 변경이 여기서 줄을 선다.
    SELECT s.status, s.current_turn_no, s.base_date INTO v_status, v_turn, v_base
    FROM season s
    WHERE s.id = p_season_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 시즌이 없다' USING HINT = 'SEASON_NOT_FOUND';
    END IF;
    IF v_status NOT IN ('IN_PROGRESS', 'ABANDONING') THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 진행할 수 있는 시즌이 아니다 (끝났거나 진행 중이 아니다)' USING HINT = 'SEASON_NOT_ACTIVE';
    END IF;
    IF v_turn IS DISTINCT FROM p_expected_turn THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 기대한 턴과 시즌의 현재 턴이 다르다' USING HINT = 'TURN_MISMATCH';
    END IF;

    -- 잠금 ②: 현금 계좌. 쓰지 않지만 스냅샷의 현금을 잠근 채 읽는다 (순서 season → cash_account).
    PERFORM 1 FROM cash_account a WHERE a.season_id = p_season_id FOR UPDATE;

    v_next := v_turn + 1;
    IF NOT EXISTS (SELECT 1 FROM season_path sp WHERE sp.season_id = p_season_id AND sp.turn_no = v_next) THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 다음 턴의 경로 블록이 없다' USING HINT = 'NO_PATH';
    END IF;

    SELECT jsonb_agg(jsonb_build_object('a', x.asset_id, 'p', x.price) ORDER BY x.asset_id) INTO v_prices
    FROM pg_temp.ta_prices(p_season_id, v_next) x;
    SELECT count(*) INTO v_missing
    FROM asset_position ap
    WHERE ap.base_date = v_base
      AND NOT (COALESCE(v_prices, '[]'::jsonb) @> jsonb_build_array(jsonb_build_object('a', ap.asset_id)));
    -- Rounded price <= 0 (a -100 % month, or a product that rounds to 0) would fail ck_turn_price_price_pos on INSERT; refuse it here with the same HINT.
    SELECT count(*) INTO v_nonpos
    FROM jsonb_to_recordset(COALESCE(v_prices, '[]'::jsonb)) AS x(a bigint, p numeric)
    WHERE x.p <= 0;
    IF v_prices IS NULL OR v_missing + v_nonpos > 0 THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 다음 턴 가격을 만들 수 없거나 0 이하인 풀 종목이 % 개 있다 (수익률·환율·0턴 가격 확인)', v_missing + v_nonpos USING HINT = 'NO_RETURN';
    END IF;
    RETURN v_prices;
END $$;

-- ---------------------------------------------------------------------
-- 턴 적용 (트랜잭션 문 없음): precheck → turn_price(다음 턴 풀 전체) → turn(스냅샷) → 17개 대사 → 17행 jsonb
--   돌려주는 값 = recon_rules_turn 17행의 jsonb_agg(키 = 컬럼 이름) — turn_advance 가 지역 변수로 들고 롤백을 건넌다.
--   묶음 단언: 코드 집합이 recon_code 17개 전체이고 중복이 없다. 아니면 INVALID_ARGUMENT (recon_log 는 15개 묶음도 받으므로 턴 전환 쪽에서 17개를 강제한다 — 하류 약속).
--   이 함수는 호출자의 트랜잭션 안에서 쓴다. 불일치여도 여기서는 되돌리지 않는다 (turn_advance 가 한다).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.ta_apply(p_season_id bigint, p_expected_turn smallint)
RETURNS jsonb
LANGUAGE plpgsql AS $$
DECLARE
    v_prices jsonb;
    v_next   smallint;
    v_rows   jsonb;
    v_ok     boolean;
BEGIN
    v_prices := pg_temp.ta_precheck(p_season_id, p_expected_turn);
    v_next := p_expected_turn + 1;

    -- 6 다음 턴 가격 풀 전체. 반올림은 ta_prices 에서 이미 끝났다.
    INSERT INTO turn_price (season_id, turn_no, asset_id, price)
    SELECT p_season_id, v_next, x.a, x.p
    FROM jsonb_to_recordset(v_prices) AS x(a bigint, p numeric)
    ORDER BY x.a;

    -- 7 턴 스냅샷. 앞 문장이 넣은 가격을 STABLE 함수가 본다 (문장마다 새 스냅샷).
    INSERT INTO turn (season_id, turn_no, cash_balance, holdings_value, total_value, goalkeeper_value, defender_value, midfielder_value, forward_value, drawdown)
    SELECT p_season_id, v_next, s.cash_balance, s.holdings_value, s.total_value, s.goalkeeper_value, s.defender_value, s.midfielder_value, s.forward_value, s.drawdown
    FROM pg_temp.ta_snapshot(p_season_id, v_next) s;

    -- 7b 현재 블록만으로 피드를 만들고, 대사 전 같은 트랜잭션에 넣는다.
    PERFORM pg_temp.tf_append(p_season_id, v_next);

    -- 8 대사 17개 (한 문장)
    SELECT jsonb_agg(to_jsonb(r) ORDER BY r.rule_no) INTO v_rows FROM pg_temp.recon_rules_turn(p_season_id, v_next) r;

    -- 9 묶음 단언
    SELECT bool_and(e ? 'check_code') AND count(*) = count(DISTINCT e ->> 'check_code')
           AND array_agg(e ->> 'check_code' ORDER BY e ->> 'check_code') IS NOT DISTINCT FROM (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c)
      INTO v_ok
    FROM jsonb_array_elements(v_rows) e;
    IF v_ok IS NOT TRUE THEN
        RAISE EXCEPTION 'P0-17 턴 진행: 대사 결과의 코드 집합이 17개 전체가 아니다 (빠짐·중복)' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    RETURN v_rows;
END $$;

-- ---------------------------------------------------------------------
-- 턴 전환 (트랜잭션 제어): 최상위 CALL 로만 부른다 (머리 주석의 트랜잭션 제어 조건).
--   10 ta_apply — 전제 오류는 그대로 올라간다: 아무것도 쓰지 않고 로그도 없다 (대사를 하지 않았다).
--   11 17개 모두 MATCH: 같은 트랜잭션에서 recon_log(시즌, t+1, 17행) → season 갱신(current_turn_no = t+1, 마지막 턴이면 FINISHED, version + 1, updated_at) → COMMIT.
--      일치해도 기록한다 (PLAN §5.2).
--   12 하나라도 MISMATCH: ROLLBACK (turn_price·turn 의 새 행과 잠금이 사라진다 — 진짜 롤백) → 새 트랜잭션에서 recon_log(시즌, t+1, 17행) → COMMIT → RAISE.
--      17행은 지역 변수 v 로 롤백을 건넌다 (임시 표는 롤백과 함께 사라진다 — erd D36). 오류는 로그가 커밋된 뒤에 나므로 로그는 남는다 (psql 종료 코드 3).
--      롤백과 로그 기록 사이에 다른 세션이 끼어들 수 있으나 recon_log 가 season FOR UPDATE 아래에서 시도 번호를 정하므로 번호가 겹치지 않는다.
--   시도 번호는 수동 검사(22)와 한 번호 공간이다 (recon_log 가 그 (시즌, 턴)의 max + 1).
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE pg_temp.turn_advance(p_season_id bigint, p_expected_turn smallint)
LANGUAGE plpgsql AS $$
DECLARE
    v_rows    jsonb;
    v_next    smallint;
    v_attempt smallint;
    v_bad     integer;
    v_codes   text;
BEGIN
    v_rows := pg_temp.ta_apply(p_season_id, p_expected_turn);
    v_next := p_expected_turn + 1;
    SELECT count(*), string_agg(e ->> 'check_code', ', ' ORDER BY (e ->> 'rule_no')::int)
      INTO v_bad, v_codes
    FROM jsonb_array_elements(v_rows) e
    WHERE e ->> 'status' = 'MISMATCH';

    IF v_bad = 0 THEN
        v_attempt := pg_temp.recon_log(p_season_id, v_next, v_rows);
        UPDATE season
           SET current_turn_no = v_next,
               status          = CASE WHEN v_next = path_length THEN 'FINISHED' ELSE status END,
               version         = version + 1,
               updated_at      = now()
         WHERE id = p_season_id;
        COMMIT;
    ELSE
        ROLLBACK;
        v_attempt := pg_temp.recon_log(p_season_id, v_next, v_rows);
        COMMIT;
        RAISE EXCEPTION 'P0-17 턴 진행: 대사 불일치 규칙 % 개 (%). 턴은 되돌렸고 시도 % 의 17행이 reconciliation_log 에 남았다. db/README.md "턴 진행 결과 읽는 법"을 본다',
            v_bad, v_codes, v_attempt USING HINT = 'RECONCILIATION_FAILED';
    END IF;
END $$;
