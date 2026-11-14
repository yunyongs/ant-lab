-- =====================================================================
-- my Fund Manager Phase 0 — 시즌 성적표 정의 (P0-20): 결과·기준선·과정·비용·대사 상태를 저장 없이 조회로 계산하는 세션 임시 함수
-- 근거: docs/erd.md 6.3(season·turn·turn_price·lineup·formation_change·turn_feed·reconciliation_log)·7.3(orders·execution·cash_ledger)·D9·D23·D24·D26·D31·D35·D36·D37·D38·9절 P0-20,
--       docs/PLAN.md §4.6(비용)·§4.10(포지션)·§4.13(독려 반응)·§5.2(원 단위·반올림 한 번)·§6.1(기준선)·§6.2(과정 지표)·§6.3
-- 단독 실행 대상이 아니다. db/26_season_report.sql(출력)과 db/26_season_report_check.sql(검증)이 읽는다.
--       Java 성적표 API(P0-38)의 SQL 골든 하네스도 `-f` 로 이 파일 하나만 읽는다 — 그래서 이 파일이 의존 정의를 직접 \ir 로 읽는다 (아래).
-- 만드는 것: 세션 임시 함수(pg_temp)뿐이다. 트랜잭션 문이 없고 테이블에 쓰지 않는다. LANGUAGE sql 함수는 문자열 본문($$ … $$)만 쓴다 —
--       BEGIN ATOMIC 본문은 12 의 임시 뷰에 의존이 기록돼 12 정의를 다시 읽을 때 DROP VIEW 가 막힌다.
-- 읽는 정의 (순서가 곧 의존): 12_calc_position_def.sql(포지션 판정 + 배열 입력 함수 3개) → 21_team_buy_def.sql(buy_rate·buy_fee·team_qty) → 25_trade_formation_def.sql(formation_costs).
--       다시 읽어도 안전하다: 12 는 DROP 가드가 있고 21·25 는 CREATE OR REPLACE 다. 22 정의는 읽지 않는다 (대사 묶음은 행 수 15·17 로 센다).
-- 함수 (모두 STABLE, 쓰기 없음, 오류는 P0001 + HINT):
--   pg_temp.sr_report(season_id)           정확히 1행, 37열. 열 이름이 P0-38 DTO 필드와 1:1 이다 (docs/erd.md D38 의 표)
--   pg_temp.sr_turns(season_id)            턴 1..T 마다 1행: 평가액·기간 수익률·낙폭·기준선·그 턴 시작 시점의 포메이션·준수·최대 단일 비중·위험 위반
--   pg_temp.sr_season_positions(season_id) 기준일 풀 종목마다 1행: 시즌 경로에서 잰 지표와 시즌 실측 포지션 (판정 불능이면 reason)
--   pg_temp.sr_players(season_id)          (종목, 선언 포지션)마다 1행, 노출이 있는 것만: 노출 턴 수·노출 가치·실측·일치 여부
--   pg_temp.sr_change_costs(season_id)     보고 범위의 전술 변경마다 1행: 연결된 주문 수·매수·매도 대금·수수료·세금
--   내부 보조: sr_k(상수)·sr_div8(8자리 나눗셈)·sr_t(범위 검사)·sr_hold(턴별 보유)·sr_baseline(기준선)·sr_turn_base(턴별 정수 원천)·sr_frac_mean(정확한 평균)
-- 보고 범위: T = season.current_turn_no(마지막 스냅샷 턴). 지표는 그 스냅샷에 담긴 매매(orders.turn_no < T)로만 계산한다. 끝난 시즌(FINISHED)이면 확정, 진행 중이면 잠정이다(is_final).
--       턴 T 의 주문·전술 변경은 다음 스냅샷에 들어가므로 계산에서 빠지고 pending_orders·pending_changes 로 센다. 미래 행(경로·가격·수익률)은 읽지 않는다.
--       현재 보유로 과거를 다시 계산하지 않는다: 보유는 체결 이력에서 턴마다 재구성하고, 총액·포지션 평가액·낙폭은 turn 정본을 쓴다.
-- 반올림 한 번 (PLAN §5.2): 금액은 원 단위 정수의 합·곱이라 반올림이 없다. 비율은 sr_div8 한 곳에서 정수 ÷ 정수를 소수 8자리 HALF_UP(0 에서 먼 쪽)로 한 번 반올림한다.
--       변동성만 제곱근이라 정확한 한 번 반올림이 불가능하다: 수익률 분자를 소수 40자리로 올려 한 번 나누고 stddev_samp 뒤 ROUND(…, 8) 한 번 (위험: 8자리 경계 ±1e-38 안에서만 Java 와 갈릴 수 있다).
-- 오류 HINT: INVALID_ARGUMENT · SEASON_NOT_FOUND · REPORT_NO_TURN(스냅샷 없음) · REPORT_INCONSISTENT(저장 상태가 모순) · REPORT_NO_BENCHMARK(대용 2종이 풀·가격에 없음).
--       시즌 경로 블록의 대용·환율 수익률이 없으면 12 의 pos_fail 이 멈춘다. 메시지에 금액·가격·달을 넣지 않는다.
-- 출력에 날짜·블록 번호·경로 값이 없다 (끝난 시즌도 마찬가지 — 날짜 공개는 P0-39 복기의 몫). 종목은 자연 키(market, ticker)로만 가리킨다.
--
-- 이번에 뺀 항목과 이유 (Phase 0 범위 밖 — P0-38 응답 문서의 "제외" 목록이 이 표를 그대로 옮긴다):
--   X1  연평균 성장률(CAGR)      분수 거듭제곱(ln·exp)이 필요한데 Java BigDecimal 에 정확한 짝이 없어 골든 8자리 일치를 보장할 수 없다. 짧은 시즌에서는 값이 부풀려진다. → MVP(M3 점수와 함께)
--   X2  Sharpe                   10턴 표본으로는 의미가 없고, 무위험 수익률(현금 무이자) 정의도 함께 정해야 한다. → MVP
--   X3  경로 백분위·일관성        예상 분포(수천 경로)가 필요하다. → M2
--   X4  60/40·내 포메이션(따라감)·시작 포메이션(고정) 기준선, 선수 선택·전술 변경·구조 효과 3분해, 변경 뒤 6턴 효과
--                                밴드 리밸런싱 시뮬레이션과 포지션별 대표 ETF 표가 필요하다 (PLAN §6.1 "MVP 구현 때 정한다"). → MVP(Java)
--   X5  매매 규율(패닉·추격)·하락 직후 전술 변경   정의가 비어 있다: "상승 직후" 문턱이 없고 −8% 의 기준이 종목·팀·시장 중 무엇인지 문서에 없다. 기능 표 §3.4 의 "경로에 있으면 포함"과 다르다. → MVP
--   X6  분산 중 자산군 수·평균 상관 기능 표 P0-20 행에 없고 상관은 10턴으로는 불안정하다. → MVP
--   X7  소음 반응(방향)           피드 방향을 판정하는 규칙이 필요하다. → MVP
--   X8  관심 추종·수비수 득점·벤치 득점·신호 판별   복기 항목이라 점수에 넣지 않는다 (PLAN §6.2). 신호 판별은 EXT. → MVP/EXT
--   X9  세금·청산 시 세후·이연 세금  세금은 EXT 다 (PLAN §4.6). execution.tax 가 항상 0 인지는 검증이 단언한다. → EXT
--   X10 수수료 중 환전 몫 표시     몫을 나누면 원 단위 반올림 지점이 새로 생긴다. PLAN §4.6 이 "표시만"이라 한 항목이다. → MVP
--   X11 시즌 점수 0–100·레이팅     PLAN §6.3 은 초안이다. → M3
--   X12 성적표 저장(season_score)  MVP 스케치다 (PLAN §5.1, SPRING_JPA §12-1). → MVP
-- 공개 편향 (값이 아니라 정의의 한계): 기준선 대용 069500 은 가격 수익률이고 SPY 는 수정 종가(배당 포함)다 — PLAN §4.6 "국내 배당 제외"와 같다. 국내 매도 거래세는 반영하지 않았다.
--       기준선은 PLAN §6.1 의 KOSPI 200·S&P 500 지수 대신 Phase 0 대용 ETF 를 쓴다 (D35 M5 와 같은 이유, 같은 두 종목·같은 50/50).
-- 소유자 결정 두 가지 (2026-11-10, PLAN §13 — P0-20 Q1·Q2): ① 매매 규율을 뺀다(X5) ② 플랫폼 수익 중 독려 몫의 출발점 = RANKING·ALERT (sr_k 의 platform_sources 한 곳).
-- 새 문법: LANGUAGE sql 문자열 본문 함수, RETURNS TABLE 의 plpgsql 함수(RETURN QUERY, RETURN NEXT, #variable_conflict), unnest(배열 둘) 의 LATERAL, 창 함수 lag()·rank(),
--       정수 산술 div(), 집계 FILTER, 정확한 분수 합(공통 분모를 곱해 나눗셈을 마지막에 한 번), 배열 입력 함수 호출.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\ir 12_calc_position_def.sql
\ir 21_team_buy_def.sql
\ir 25_trade_formation_def.sql

-- 상수. 시즌용 최소 달 수 6·3·3 은 기준일 판정의 24·5·5 를 10턴 시즌이 넘을 수 없어서 따로 둔다 (erd D38). 판정 문턱은 12 의 pos_classify 한 곳에 그대로 있다.
CREATE OR REPLACE FUNCTION pg_temp.sr_k()
RETURNS TABLE (min_months integer, min_up integer, min_down integer, annual integer, nudge_sources text[], platform_sources text[])
LANGUAGE sql IMMUTABLE AS $$
    SELECT 6, 3, 3, 12,
           ARRAY['RANKING', 'FEED', 'ALERT']::text[],   -- 독려 반응(PLAN §6.2): 랭킹·피드·알림에서 시작된 매매
           ARRAY['RANKING', 'ALERT']::text[]            -- 플랫폼 수익 중 독려 몫(PLAN §4.13 "랭킹·알림에서 시작된 몫") — 소유자 결정 P0-20 Q2(PLAN §13), 바꾸려면 이 한 줄
$$;

-- 정수 ÷ 정수 → 소수 8자리, 0 에서 먼 쪽 HALF_UP, 중간 반올림 없음. 정수가 아닌 입력·0 으로 나누기는 거부한다.
--   2·|n|·10^8 + |d| 를 2·|d| 로 정수 나눗셈하면 round-half-up(|n|·10^8 ÷ |d|) 이다. Java 짝: new BigDecimal(n).divide(d, 8, RoundingMode.HALF_UP).
--   The last step is an exact product, not a division: `/` can drop decimal digits when the quotient is huge.
CREATE OR REPLACE FUNCTION pg_temp.sr_div8(n numeric, d numeric)
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF n IS NULL OR d IS NULL OR d = 0 OR n <> trunc(n) OR d <> trunc(d) THEN
        RAISE EXCEPTION 'P0-20 성적표: 정수 ÷ 0 이 아닌 정수만 나눈다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    RETURN sign(n) * sign(d) * div(2 * abs(n) * 100000000 + abs(d), 2 * abs(d)) * 0.00000001;
END $$;

-- 공통 분모로 합친 정확한 평균: (1/n)·Σ a_i/b_i = Σ(a_i·Π_{j≠i} b_j) ÷ (n·Π b_j). 분수를 하나씩 더해 나눗셈을 마지막 sr_div8 한 번으로 미룬다.
CREATE OR REPLACE FUNCTION pg_temp.sr_frac_mean(p_num numeric[], p_den numeric[])
RETURNS numeric
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    v_num numeric := 0;
    v_den numeric := 1;
    i     integer;
    n     integer := COALESCE(array_length(p_num, 1), 0);
BEGIN
    IF n = 0 OR n <> COALESCE(array_length(p_den, 1), 0) THEN
        RAISE EXCEPTION 'P0-20 성적표: 분수 목록이 비었거나 길이가 다르다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    FOR i IN 1..n LOOP
        v_num := v_num * p_den[i] + p_num[i] * v_den;
        v_den := v_den * p_den[i];
    END LOOP;
    RETURN pg_temp.sr_div8(v_num, n * v_den);
END $$;

-- 범위 검사: 인자·시즌·스냅샷·저장 상태의 모순·대용 2종을 보고, 이상이 없으면 T(보고 턴)를 돌려준다. 공개 함수는 모두 맨 앞에서 부른다.
CREATE OR REPLACE FUNCTION pg_temp.sr_t(p_season_id bigint)
RETURNS smallint
LANGUAGE plpgsql STABLE AS $$
DECLARE
    s        record;
    v_n      bigint;
    v_min    smallint;
    v_max    smallint;
    v_prox   bigint;
    v_px     bigint;
BEGIN
    IF p_season_id IS NULL THEN
        RAISE EXCEPTION 'P0-20 성적표: 시즌 번호가 비어 있다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    SELECT se.status, se.path_length, se.current_turn_no, se.base_date INTO s FROM season se WHERE se.id = p_season_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-20 성적표: 그런 시즌이 없다' USING HINT = 'SEASON_NOT_FOUND';
    END IF;
    IF s.current_turn_no = 0 THEN
        RAISE EXCEPTION 'P0-20 성적표: 턴 스냅샷이 아직 없다 (0턴)' USING HINT = 'REPORT_NO_TURN';
    END IF;
    -- 스냅샷은 정확히 1..T
    SELECT count(*), min(tn.turn_no), max(tn.turn_no) INTO v_n, v_min, v_max FROM turn tn WHERE tn.season_id = p_season_id;
    IF v_n <> s.current_turn_no OR v_min <> 1 OR v_max <> s.current_turn_no THEN
        RAISE EXCEPTION 'P0-20 성적표: turn 행이 1..현재 턴과 같지 않다' USING HINT = 'REPORT_INCONSISTENT';
    END IF;
    -- 주문은 T 이하 (T 의 주문은 보류), 끝난 시즌은 T = 경로 길이이고 T 의 주문·전술 변경이 없다
    IF EXISTS (SELECT 1 FROM orders o WHERE o.season_id = p_season_id AND o.turn_no > s.current_turn_no)
       OR (s.status = 'FINISHED' AND (s.current_turn_no <> s.path_length
            OR EXISTS (SELECT 1 FROM orders o WHERE o.season_id = p_season_id AND o.turn_no = s.current_turn_no)
            OR EXISTS (SELECT 1 FROM formation_change fc WHERE fc.season_id = p_season_id AND fc.turn_no = s.current_turn_no))) THEN
        RAISE EXCEPTION 'P0-20 성적표: 현재 턴 뒤의 주문이 있거나 끝난 시즌에 마지막 턴의 주문·전술 변경이 있다' USING HINT = 'REPORT_INCONSISTENT';
    END IF;
    -- a zero-value turn would divide by zero later; stop here with a clear hint
    IF EXISTS (SELECT 1 FROM turn tn WHERE tn.season_id = p_season_id AND tn.total_value = 0) THEN
        RAISE EXCEPTION 'P0-20 성적표: 평가액이 0 인 턴이 있다' USING HINT = 'REPORT_INCONSISTENT';
    END IF;
    -- cumulative filled quantity below zero at any turn (sr_hold keeps only quantity > 0 and would drop it silently)
    IF EXISTS (SELECT 1
               FROM (SELECT sum(x.dq) OVER (PARTITION BY x.asset_id ORDER BY x.turn_no) AS cum
                     FROM (SELECT o.asset_id, o.turn_no, sum(CASE WHEN o.side = 'BUY' THEN e.quantity ELSE -e.quantity END) AS dq
                           FROM orders o JOIN execution e ON e.order_id = o.id
                           WHERE o.season_id = p_season_id AND o.turn_no < s.current_turn_no
                           GROUP BY o.asset_id, o.turn_no) x) y
               WHERE y.cum < 0) THEN
        RAISE EXCEPTION 'P0-20 성적표: 체결 이력의 누적 수량이 0 미만인 턴이 있다' USING HINT = 'REPORT_INCONSISTENT';
    END IF;
    -- 기준선 대용 2종: 시즌 기준일 풀에 있고 0..T 의 가격이 모두 있다
    SELECT count(DISTINCT a.id), count(tp.id) INTO v_prox, v_px
    FROM pos_k k
    JOIN asset a ON (a.market, a.ticker) IN ((k.kr_market, k.kr_ticker), (k.us_market, k.us_ticker))
    JOIN asset_position ap ON ap.asset_id = a.id AND ap.base_date = s.base_date
    LEFT JOIN turn_price tp ON tp.season_id = p_season_id AND tp.asset_id = a.id AND tp.turn_no BETWEEN 0 AND s.current_turn_no;
    IF v_prox <> 2 OR v_px <> 2 * (s.current_turn_no + 1) THEN
        RAISE EXCEPTION 'P0-20 성적표: 기준선 대용 2종이 시즌 풀 또는 가격에 없다' USING HINT = 'REPORT_NO_BENCHMARK';
    END IF;
    -- 경로 1..T 와 풀 전체의 0..T 가격
    SELECT count(*) INTO v_n FROM season_path sp WHERE sp.season_id = p_season_id AND sp.turn_no BETWEEN 1 AND s.current_turn_no;
    IF v_n <> s.current_turn_no THEN
        RAISE EXCEPTION 'P0-20 성적표: season_path 가 1..현재 턴을 채우지 않는다' USING HINT = 'REPORT_INCONSISTENT';
    END IF;
    IF EXISTS (SELECT 1 FROM asset_position ap CROSS JOIN generate_series(0, s.current_turn_no) AS g(n)
               LEFT JOIN turn_price tp ON tp.season_id = p_season_id AND tp.asset_id = ap.asset_id AND tp.turn_no = g.n
               WHERE ap.base_date = s.base_date AND tp.id IS NULL) THEN
        RAISE EXCEPTION 'P0-20 성적표: 풀 종목의 턴 가격이 비어 있다' USING HINT = 'REPORT_INCONSISTENT';
    END IF;
    RETURN s.current_turn_no;
END $$;

-- 턴별 보유 재구성(체결 이력): q_i(t) = turn_no < t 인 체결의 매수 − 매도 수량. t = 1..T, 수량 > 0 인 종목만. 값 = 수량 × 그 턴의 turn_price.
--   현재 보유(holding)로 과거를 다시 계산하지 않는다. 입력은 sr_t 를 통과한 시즌이라고 가정한다.
CREATE OR REPLACE FUNCTION pg_temp.sr_hold(p_season_id bigint)
RETURNS TABLE (asset_id bigint, turn_no integer, qty bigint, price numeric, value numeric)
LANGUAGE sql STABLE AS $$
    WITH s AS (
        SELECT se.current_turn_no AS t FROM season se WHERE se.id = p_season_id
    ), po AS (
        SELECT o.asset_id, o.turn_no, sum(CASE WHEN o.side = 'BUY' THEN e.quantity ELSE -e.quantity END) AS dq
        FROM orders o
        JOIN execution e ON e.order_id = o.id
        WHERE o.season_id = p_season_id AND o.turn_no < (SELECT t FROM s)
        GROUP BY o.asset_id, o.turn_no
    )
    SELECT a.asset_id, g.n, sum(po.dq)::bigint, tp.price, sum(po.dq) * tp.price
    FROM (SELECT DISTINCT po0.asset_id FROM po po0) a
    CROSS JOIN generate_series(1, (SELECT t FROM s)) AS g(n)
    JOIN po ON po.asset_id = a.asset_id AND po.turn_no < g.n
    JOIN turn_price tp ON tp.season_id = p_season_id AND tp.asset_id = a.asset_id AND tp.turn_no = g.n
    GROUP BY a.asset_id, g.n, tp.price
    HAVING sum(po.dq) > 0
$$;

-- 기준선(PLAN §6.1): 시즌 기준일의 대용 2종 매수 후 보유. 0턴에 예산 C × 비중으로 정수 주를 사고(21 의 team_qty·buy_fee·buy_rate 를 그대로) 이후 매매는 없다.
--   B_t = Σ 수량 × turn_price(t) + 남은 현금. t = 0..T. 가격은 turn_price 만 쓴다 (평가는 그 값만 — PLAN §5.2).
CREATE OR REPLACE FUNCTION pg_temp.sr_baseline(p_season_id bigint)
RETURNS TABLE (turn_no integer, baseline_value numeric)
LANGUAGE sql STABLE AS $$
    WITH s AS (
        SELECT se.initial_cash, se.fee_rate_kr, se.fee_rate_us, se.fx_spread_rate, se.current_turn_no AS t FROM season se WHERE se.id = p_season_id
    ), prox AS (
        SELECT a.id AS asset_id, k.w_kr AS w, a.market
        FROM pos_k k JOIN asset a ON a.market = k.kr_market AND a.ticker = k.kr_ticker
        UNION ALL
        SELECT a.id, k.w_us, a.market
        FROM pos_k k JOIN asset a ON a.market = k.us_market AND a.ticker = k.us_ticker
    ), buy AS (
        SELECT x.asset_id,
               pg_temp.team_qty(s.initial_cash * x.w, tp.price, x.rate) AS qty,
               tp.price AS p0,
               x.rate
        FROM s
        CROSS JOIN LATERAL (SELECT pr.asset_id, pr.w, pg_temp.buy_rate(pr.market, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate) AS rate FROM prox pr) x
        JOIN turn_price tp ON tp.season_id = p_season_id AND tp.asset_id = x.asset_id AND tp.turn_no = 0
    ), cash AS (
        SELECT (SELECT s.initial_cash FROM s) - sum(b.qty * b.p0 + pg_temp.buy_fee(b.p0, b.qty, b.rate)) AS c_b FROM buy b
    )
    SELECT g.n, sum(b.qty * tp.price) + (SELECT c_b FROM cash)
    FROM generate_series(0, (SELECT t FROM s)) AS g(n)
    CROSS JOIN buy b
    JOIN turn_price tp ON tp.season_id = p_season_id AND tp.asset_id = b.asset_id AND tp.turn_no = g.n
    GROUP BY g.n
$$;

-- 턴별 정수 원천: 총액·직전 총액(1턴은 초기 자금)·낙폭·그 턴 시작 시점의 목표 포메이션·준수 분자/분모·그 턴의 가장 큰 종목 가치.
--   목표 포메이션 f(t) = turn_no < t 인 마지막 formation_change(id 순)의 after 4개, 없으면 시즌 시작 포메이션 (erd 9절 P0-20).
--   준수 a_t = Σ_p |100·v_p − f_p·V|, b_t = 100·V (v_p = turn 의 포지션별 평가액 — 현금은 골키퍼). 입력은 sr_t 를 통과한 시즌이라고 가정한다.
CREATE OR REPLACE FUNCTION pg_temp.sr_turn_base(p_season_id bigint)
RETURNS TABLE (turn_no smallint, total_value numeric, prev_value numeric, drawdown numeric,
               target_goalkeeper smallint, target_defender smallint, target_midfielder smallint, target_forward smallint,
               dev_num numeric, dev_den numeric, max_pos_value numeric)
LANGUAGE sql STABLE AS $$
    WITH s AS (
        SELECT se.* FROM season se WHERE se.id = p_season_id
    ), hv AS (
        SELECT h.turn_no, max(h.value) AS mx FROM pg_temp.sr_hold(p_season_id) h GROUP BY h.turn_no
    ), base AS (
        SELECT tn.turn_no, tn.total_value, tn.drawdown,
               COALESCE(lag(tn.total_value) OVER (ORDER BY tn.turn_no), s.initial_cash) AS prev_value,
               COALESCE(f.after_goalkeeper, s.start_goalkeeper) AS t_gk,
               COALESCE(f.after_defender,   s.start_defender)   AS t_df,
               COALESCE(f.after_midfielder, s.start_midfielder) AS t_mf,
               COALESCE(f.after_forward,    s.start_forward)    AS t_fw,
               tn.goalkeeper_value AS v_gk, tn.defender_value AS v_df, tn.midfielder_value AS v_mf, tn.forward_value AS v_fw
        FROM s
        JOIN turn tn ON tn.season_id = s.id AND tn.turn_no BETWEEN 1 AND s.current_turn_no
        LEFT JOIN LATERAL (
            SELECT fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward
            FROM formation_change fc
            WHERE fc.season_id = s.id AND fc.turn_no < tn.turn_no
            ORDER BY fc.id DESC LIMIT 1
        ) f ON true
    )
    SELECT b.turn_no, b.total_value, b.prev_value, b.drawdown, b.t_gk, b.t_df, b.t_mf, b.t_fw,
           abs(100 * b.v_gk - b.t_gk * b.total_value) + abs(100 * b.v_df - b.t_df * b.total_value)
         + abs(100 * b.v_mf - b.t_mf * b.total_value) + abs(100 * b.v_fw - b.t_fw * b.total_value),
           100 * b.total_value,
           COALESCE(hv.mx, 0)
    FROM base b
    LEFT JOIN hv ON hv.turn_no = b.turn_no
    ORDER BY b.turn_no
$$;

-- 턴별 표: 표시용이다 (period_return·deviation·max_weight 는 그 자체로 한 번 반올림한 값 — 시즌 집계는 이 값을 다시 쓰지 않고 정수 원천에서 계산한다).
CREATE OR REPLACE FUNCTION pg_temp.sr_turns(p_season_id bigint)
RETURNS TABLE (turn_no smallint, total_value numeric, period_return numeric, drawdown numeric, baseline_value numeric,
               target_goalkeeper smallint, target_defender smallint, target_midfielder smallint, target_forward smallint,
               deviation numeric, max_weight numeric, breach boolean)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
BEGIN
    PERFORM pg_temp.sr_t(p_season_id);
    RETURN QUERY
    SELECT b.turn_no, b.total_value, pg_temp.sr_div8(b.total_value - b.prev_value, b.prev_value), b.drawdown, bl.baseline_value,
           b.target_goalkeeper, b.target_defender, b.target_midfielder, b.target_forward,
           pg_temp.sr_div8(b.dev_num, b.dev_den), pg_temp.sr_div8(b.max_pos_value, b.total_value),
           b.drawdown < se.risk_limit
    FROM pg_temp.sr_turn_base(p_season_id) b
    JOIN pg_temp.sr_baseline(p_season_id) bl ON bl.turn_no = b.turn_no
    CROSS JOIN (SELECT s0.risk_limit FROM season s0 WHERE s0.id = p_season_id) se
    ORDER BY b.turn_no;
END $$;

-- 시즌 실측 포지션: 기준일 풀 전체(상대 순위에 풀이 필요하다)를 시즌 경로 1..T 의 월 블록 수익률로 잰다. 12 의 배열 함수와 pos_classify 를 그대로 쓴다.
--   순위: 판정 가능한 N 개에서 k = CEIL(top_ratio × N), 변동성 순위(RANK, 동률 포함) ≤ k. 시즌 최소 달 수는 sr_k (6·3·3).
CREATE OR REPLACE FUNCTION pg_temp.sr_season_positions(p_season_id bigint)
RETURNS TABLE (market text, ticker text, months_used integer, n_up integer, n_down integer,
               up_participation numeric, down_participation numeric, volatility numeric, max_drawdown numeric,
               measured_position text, reason text)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
BEGIN
    PERFORM pg_temp.sr_t(p_season_id);
    RETURN QUERY
    WITH s AS (
        SELECT se.base_date, se.current_turn_no AS t FROM season se WHERE se.id = p_season_id
    ), pth AS (
        SELECT sp.turn_no, sp.month_block_id, pg_temp.pos_block_mkt(sp.month_block_id) AS m
        FROM season_path sp, s
        WHERE sp.season_id = p_season_id AND sp.turn_no BETWEEN 1 AND s.t
    ), mk AS (
        SELECT pg_temp.pos_max_drawdown(array_agg(pth.m ORDER BY pth.turn_no)) AS mkt_mdd FROM pth
    ), pool AS (
        SELECT a.id AS asset_id, a.market::text AS market, a.ticker::text AS ticker
        FROM s JOIN asset_position ap ON ap.base_date = s.base_date JOIN asset a ON a.id = ap.asset_id
    ), met AS (
        SELECT p.asset_id, p.market, p.ticker, x.months_used, x.n_up, x.n_down, x.up, x.down, x.vol, x.mdd, x.reason
        FROM pool p
        LEFT JOIN LATERAL (
            SELECT array_agg(r.r_krw ORDER BY pa.turn_no) AS ra, array_agg(pa.m ORDER BY pa.turn_no) AS ma
            FROM pth pa JOIN pos_ret r ON r.asset_id = p.asset_id AND r.month_block_id = pa.month_block_id
        ) ar ON true
        CROSS JOIN pg_temp.sr_k() k
        CROSS JOIN LATERAL pg_temp.pos_metrics(ar.ra, ar.ma, k.min_months, k.min_up, k.min_down) x
    ), rk AS (
        SELECT q.asset_id,
               q.rd <= q.kk AS vol_top, q.ra <= q.kk AS vol_bottom
        FROM (SELECT m.asset_id,
                     ceil(pk.top_ratio * count(*) OVER ()) AS kk,
                     rank() OVER (ORDER BY m.vol DESC) AS rd,
                     rank() OVER (ORDER BY m.vol ASC)  AS ra
              FROM met m CROSS JOIN pos_k pk
              WHERE m.reason IS NULL) q
    )
    SELECT m.market, m.ticker, m.months_used, m.n_up, m.n_down, m.up, m.down, m.vol, m.mdd,
           CASE WHEN m.reason IS NULL THEN pg_temp.pos_classify(m.up, m.down, m.mdd, mk.mkt_mdd, r.vol_top, r.vol_bottom) END,
           m.reason
    FROM met m
    CROSS JOIN mk
    LEFT JOIN rk r ON r.asset_id = m.asset_id
    ORDER BY m.market COLLATE "C", m.ticker COLLATE "C";
END $$;

-- 선수별 역할: 노출(그 턴 시작 시점에 수량 > 0)이 있는 (종목, 선언 포지션)마다 1행. 선언은 start_turn < k ≤ end_turn(열린 끝은 무한대)인 lineup 구간 — 정확히 1개여야 한다.
--   실측이 NULL(표본 부족)이면 matched 도 NULL 이다. 선언·판정 문자열은 6개 포지션 글자 그대로 비교한다 (FORWARD_* 세 종류를 구분한다).
CREATE OR REPLACE FUNCTION pg_temp.sr_players(p_season_id bigint)
RETURNS TABLE (market text, ticker text, declared_position text, exposure_turns integer, exposure_value numeric,
               measured_position text, matched boolean)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
BEGIN
    PERFORM pg_temp.sr_t(p_season_id);
    IF EXISTS (SELECT 1 FROM pg_temp.sr_hold(p_season_id) h
               WHERE (SELECT count(*) FROM lineup lu
                      WHERE lu.season_id = p_season_id AND lu.asset_id = h.asset_id
                        AND lu.start_turn < h.turn_no AND (lu.end_turn IS NULL OR h.turn_no <= lu.end_turn)) <> 1) THEN
        RAISE EXCEPTION 'P0-20 성적표: 보유한 턴에 열린 선언 구간이 하나가 아니다' USING HINT = 'REPORT_INCONSISTENT';
    END IF;
    RETURN QUERY
    SELECT a.market::text, a.ticker::text, lu.declared_position::text, count(*)::integer, sum(h.value), sp.measured_position,
           CASE WHEN sp.measured_position IS NULL THEN NULL ELSE sp.measured_position = lu.declared_position END
    FROM pg_temp.sr_hold(p_season_id) h
    JOIN asset a ON a.id = h.asset_id
    JOIN lineup lu ON lu.season_id = p_season_id AND lu.asset_id = h.asset_id
                  AND lu.start_turn < h.turn_no AND (lu.end_turn IS NULL OR h.turn_no <= lu.end_turn)
    LEFT JOIN pg_temp.sr_season_positions(p_season_id) sp ON sp.market = a.market AND sp.ticker = a.ticker
    GROUP BY a.market, a.ticker, lu.declared_position, sp.measured_position
    ORDER BY a.market::text COLLATE "C", a.ticker::text COLLATE "C", lu.declared_position::text COLLATE "C";
END $$;

-- 전술 변경별 비용: 25 의 formation_costs 를 다시 쓴다 (식을 복사하지 않는다). 보고 범위(turn_no < T)의 변경만. change_seq 는 시즌 안 id 순 1..n.
CREATE OR REPLACE FUNCTION pg_temp.sr_change_costs(p_season_id bigint)
RETURNS TABLE (change_seq integer, turn_no smallint, order_count integer, buy_notional numeric, sell_notional numeric, fee_sum numeric, tax_sum numeric)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
DECLARE
    v_t smallint := pg_temp.sr_t(p_season_id);
BEGIN
    RETURN QUERY
    SELECT (row_number() OVER (ORDER BY c.formation_change_id))::integer, c.turn_no, c.order_count::integer, c.buy_notional, c.sell_notional, c.fee_sum, c.tax_sum
    FROM pg_temp.formation_costs(p_season_id) c
    WHERE c.turn_no < v_t
    ORDER BY c.formation_change_id;
END $$;

-- 성적표 1행 (37열). 열 순서·이름은 docs/erd.md D38 의 표와 같고 P0-38 DTO 필드와 1:1 이다. 금액은 원 단위, 비율은 소수 8자리(−0.2 = −20%).
CREATE OR REPLACE FUNCTION pg_temp.sr_report(p_season_id bigint)
RETURNS TABLE (
    season_status text, is_final boolean, report_turns smallint, path_length smallint, pending_orders integer, pending_changes integer,
    initial_cash numeric, final_value numeric, total_return numeric, monthly_volatility numeric, max_drawdown numeric,
    baseline_final_value numeric, baseline_return numeric, excess_return numeric,
    risk_limit numeric, risk_breach_turns integer, risk_breach_depth numeric, max_single_weight numeric,
    trade_notional numeric, turnover_annual numeric, formation_deviation numeric,
    role_fit numeric, role_players_judged integer, role_players_unjudged integer,
    nudge_share numeric, nudge_same_turn_share numeric,
    total_fee numeric, team_fee numeric, formation_change_fee numeric, platform_nudge_fee numeric,
    formation_changes integer, formation_change_orders integer, changes_without_orders integer,
    recon_mismatch_rows integer, recon_turn_bundles integer, recon_manual_bundles integer, recon_out_of_range_rows integer)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
DECLARE
    v_t        smallint;
    s          record;
    v_sum_v    numeric;
    v_min_diff numeric;
    v_a        numeric[];
    v_b        numeric[];
    v_nudge    numeric;
    v_same     numeric;
    v_role_num numeric;
    v_role_den numeric;
BEGIN
    v_t := pg_temp.sr_t(p_season_id);
    SELECT se.status, se.path_length, se.initial_cash, se.risk_limit INTO s FROM season se WHERE se.id = p_season_id;

    season_status := s.status;
    is_final := s.status = 'FINISHED';
    report_turns := v_t;
    path_length := s.path_length;
    initial_cash := s.initial_cash;
    risk_limit := s.risk_limit;

    SELECT count(*) INTO pending_orders FROM orders o WHERE o.season_id = p_season_id AND o.turn_no = v_t;
    SELECT count(*) INTO pending_changes FROM formation_change fc WHERE fc.season_id = p_season_id AND fc.turn_no = v_t;

    -- 결과·위험·포메이션 준수: 턴 스냅샷의 정수 원천
    SELECT max(b.total_value) FILTER (WHERE b.turn_no = v_t), sum(b.total_value),
           (count(*) FILTER (WHERE b.drawdown < s.risk_limit))::integer, min(b.drawdown), min(b.drawdown - s.risk_limit),
           array_agg(b.dev_num ORDER BY b.turn_no), array_agg(b.dev_den ORDER BY b.turn_no),
           max(pg_temp.sr_div8(b.max_pos_value, b.total_value))
    INTO final_value, v_sum_v, risk_breach_turns, max_drawdown, v_min_diff, v_a, v_b, max_single_weight
    FROM pg_temp.sr_turn_base(p_season_id) b;
    total_return := pg_temp.sr_div8(final_value - s.initial_cash, s.initial_cash);
    risk_breach_depth := ROUND(LEAST(0, v_min_diff), 8);
    formation_deviation := pg_temp.sr_frac_mean(v_a, v_b);

    -- 월 변동성: 수익률 분자를 소수 40자리로 올려 한 번 나누고 표본 표준편차(n − 1, 월 단위, 연율 아님) 뒤 ROUND(…, 8) 한 번. T = 1 이면 NULL.
    SELECT ROUND(stddev_samp((b.total_value - b.prev_value + k.pad40) / b.prev_value), 8) INTO monthly_volatility
    FROM pg_temp.sr_turn_base(p_season_id) b CROSS JOIN pos_k k;

    -- 기준선
    SELECT bl.baseline_value INTO baseline_final_value FROM pg_temp.sr_baseline(p_season_id) bl WHERE bl.turn_no = v_t;
    baseline_return := pg_temp.sr_div8(baseline_final_value - s.initial_cash, s.initial_cash);
    excess_return := pg_temp.sr_div8(final_value - baseline_final_value, s.initial_cash);

    -- 주문: 주문 단위로 먼저 합쳐 조인 증폭을 막는다. 보고 범위 turn_no < T. 0턴(팀 구성)은 회전율·독려 비중에서 빼고 수수료에는 넣는다.
    WITH po AS (
        SELECT o.id, o.turn_no, o.source, f.turn_no AS feed_turn, sum(e.fill_price * e.quantity) AS notional, sum(e.fee) AS fee
        FROM orders o
        JOIN execution e ON e.order_id = o.id
        LEFT JOIN turn_feed f ON f.id = o.turn_feed_id
        WHERE o.season_id = p_season_id AND o.turn_no < v_t
        GROUP BY o.id, o.turn_no, o.source, f.turn_no
    )
    SELECT COALESCE(sum(po.notional) FILTER (WHERE po.turn_no >= 1), 0),
           COALESCE(sum(po.fee), 0),
           COALESCE(sum(po.fee) FILTER (WHERE po.turn_no = 0), 0),
           COALESCE(sum(po.fee) FILTER (WHERE po.turn_no >= 1 AND po.source = ANY (k.platform_sources)), 0),
           COALESCE(sum(po.notional) FILTER (WHERE po.turn_no >= 1 AND po.source = ANY (k.nudge_sources)), 0),
           COALESCE(sum(po.notional) FILTER (WHERE po.turn_no >= 1 AND po.source = ANY (k.nudge_sources)
                                              AND (po.source = 'RANKING' OR po.feed_turn = po.turn_no)), 0)
    INTO trade_notional, total_fee, team_fee, platform_nudge_fee, v_nudge, v_same
    FROM po CROSS JOIN pg_temp.sr_k() k;
    turnover_annual := pg_temp.sr_div8((SELECT k.annual FROM pg_temp.sr_k() k) * trade_notional, v_sum_v);
    IF trade_notional > 0 THEN
        nudge_share := pg_temp.sr_div8(v_nudge, trade_notional);
        nudge_same_turn_share := pg_temp.sr_div8(v_same, trade_notional);
    END IF;

    -- 전술 변경 비용과 연결 구분
    SELECT COALESCE(sum(c.fee_sum), 0), count(*)::integer, COALESCE(sum(c.order_count), 0)::integer, (count(*) FILTER (WHERE c.order_count = 0))::integer
    INTO formation_change_fee, formation_changes, formation_change_orders, changes_without_orders
    FROM pg_temp.sr_change_costs(p_season_id) c;

    -- 역할 수행: 노출 가치 가중, 실측이 있는 선수만(표본 부족은 분자·분모 양쪽에서 빠진다)
    SELECT sum(pl.exposure_value) FILTER (WHERE pl.matched), sum(pl.exposure_value) FILTER (WHERE pl.matched IS NOT NULL),
           (count(DISTINCT pl.market || ':' || pl.ticker) FILTER (WHERE pl.measured_position IS NOT NULL))::integer,
           (count(DISTINCT pl.market || ':' || pl.ticker) FILTER (WHERE pl.measured_position IS NULL))::integer
    INTO v_role_num, v_role_den, role_players_judged, role_players_unjudged
    FROM pg_temp.sr_players(p_season_id) pl;
    IF v_role_den IS NOT NULL AND v_role_den > 0 THEN
        role_fit := pg_temp.sr_div8(COALESCE(v_role_num, 0), v_role_den);
    END IF;

    -- 대사 상태: 묶음은 (turn_no, attempt_no) 마다 행 수로 센다 (턴 전환 17행, 수동 15행). 코드 집합이 맞는지는 검증이 22 의 recon_code 로 확인한다.
    SELECT (count(*) FILTER (WHERE rl.status = 'MISMATCH'))::integer, (count(*) FILTER (WHERE rl.turn_no > s.path_length))::integer
    INTO recon_mismatch_rows, recon_out_of_range_rows
    FROM reconciliation_log rl WHERE rl.season_id = p_season_id;
    SELECT (count(*) FILTER (WHERE q.n = 17))::integer, (count(*) FILTER (WHERE q.n = 15))::integer
    INTO recon_turn_bundles, recon_manual_bundles
    FROM (SELECT count(*) AS n FROM reconciliation_log rl WHERE rl.season_id = p_season_id GROUP BY rl.turn_no, rl.attempt_no) q;

    RETURN NEXT;
END $$;
