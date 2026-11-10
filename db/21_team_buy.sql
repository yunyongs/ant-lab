-- =====================================================================
-- my Fund Manager Phase 0 — 팀 구성 (P0-15): 연습 시즌의 0턴에 감독의 팀 8종목을 매수한다
-- 근거: docs/erd.md 7.3·7.5(대사 규칙 2·3·4·6·7·8·9·13·14·17·18)·6.3(season·lineup)·D4·D9·D23·9절 P0-15, docs/SPRING_JPA.md §5.2,
--       docs/PLAN.md §4.1-2(종목·수량·포지션)·§4.6(정수 주·수수료)·§4.10(선언 포지션·현금은 골키퍼)·§5.2
-- 대상: db/01·02·03 스키마, 10 적재, 12 판정, 20 시즌 시작을 실행한 DB (PostgreSQL 17). antlab 에서 실행한다. 11·13 은 필요 없다.
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/21_team_buy.sql
--       같은 폴더의 21_team_buy_def.sql 을 \ir 로 읽는다. -v ON_ERROR_STOP=1 이 없으면 맨 앞 가드가 아무것도 하지 않고 종료 코드 3으로 끝낸다
--       (줄마다 COMMIT 하므로 단언이 실패해도 매수가 이어지면 안 된다). 가드를 지나도 전제 단언 표(tb_gate)가 없으면 team_build() 가 매수 전에 멈춘다.
-- 팀 명단은 아래 [팀 명단 한 곳]에만 적는다. 한 줄에 한 종목이다. 명단에는 종목 코드·비중(%)·선언 포지션만 있고 수량은 없다 —
--       수량은 실행할 때 0턴 가격에서 규칙으로 계산한다 (수량은 가격을 역산하게 하므로 저장소 파일에 적지 않는다).
-- 수량 규칙: 줄 예산 b = floor(초기 자금 × 비중 ÷ 100) (잔액이 아니라 초기 자금 기준 — 줄 순서와 무관하게 같은 값),
--       q = floor(b ÷ (체결가 × (1 + 요율))) — 수수료를 포함한 예산 안의 정수 주. 대금 + 수수료 ≤ b 가 보장된다 (docs/erd.md §9 P0-15 메모).
--       q = 0 인 줄이 있으면 아무것도 사지 않고 멈춘다 (비중을 임의로 바꾸지 않는다).
-- 비중은 선언 포지션의 4분류(골키퍼·수비·미드필더·공격)로 센다. 골키퍼 목표 = 골키퍼 종목 줄 비중 합 + 현금 목표, 현금은 골키퍼다 (PLAN §4.10).
--       분류 합이 시즌의 시작 포메이션과 다르면 매수 전에 멈춘다.
-- 한 줄 = 한 트랜잭션: 임시 프로시저가 줄마다 pg_temp.order_buy 를 부르고 COMMIT 한다. 줄 k 에서 실패하면 1..k-1 은 커밋된 채 남고 k 부터는 없던 일이다.
--       그래서 파일에는 바깥 BEGIN 이 없다 (프로시저 안의 COMMIT 은 바깥 트랜잭션 블록 안에서는 실패한다).
-- 다시 실행(줄 단위 멱등): 이 시즌의 0턴 주문을 명단과 비교한다. 비교하는 것은 종목별 주문 건수(0 또는 1), 수량 = 규칙 값, source·status·type·side,
--       체결 1건과 체결가 = 0턴 가격 · 수수료 = 규칙 14 · 세금 0, 그 종목의 start_turn = 0 라인업의 선언 포지션이다. 명단 밖 종목의 0턴 주문이 있거나
--       하나라도 다르면 아무것도 사지 않고 오류(종료 코드 3)로 멈춘다.
--       두 실행이 겹치면 줄마다 시즌 잠금을 잡은 뒤 그 종목의 0턴 주문과 명단 전체 비교를 다시 읽는다. 같은 줄을 두 번 사지 않고, 다른 명단의 실행과 기록이 섞이지 않는다 (멈춘 쪽은 같은 명령을 다시 실행한다). 비교하지 않는 것은 holding · 잔액 · 라인업 end_turn · 1턴 이후 주문이다
--       (턴 진행·매매가 정당하게 바꾼다). 앞 실행이 줄 k 에서 실패해 일부만 산 상태면 남은 줄만 산다 (이어서 사기).
-- 쓰는 곳: orders · execution · holding · cash_ledger · cash_account · lineup. 아무것도 지우지 않는다. 판정(asset_position)은 읽지 않는다.
-- 보고: 건수와 상태만 낸다. 가격·수량·금액·비중은 출력하지 않는다. 실제 비중은 README 의 "실제 비중 보기" 쿼리로 로컬에서만 본다.
-- 감독의 선택(소유자 결정 2026-11-08, PLAN §13): 경로의 달 수익률을 기억해 고르지 않고 역할·분산·비용으로 골랐다.
-- 새 문법: 임시 프로시저(CREATE PROCEDURE pg_temp.…)와 그 안의 COMMIT, 임시 표 PRESERVE ROWS(기본값 — ON COMMIT DROP 이면 첫 COMMIT 에서 사라진다),
--       임시 뷰로 계산 한 곳 두기, FOR … IN SELECT 반복, div() 정수 나눗셈.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\if :{?ON_ERROR_STOP}
  \if :ON_ERROR_STOP
  \else
    \echo 'P0-15 팀 구성: ON_ERROR_STOP 이 꺼져 있다. psql -X -v ON_ERROR_STOP=1 -d <DB> -f db/21_team_buy.sql 로 다시 실행한다'
    \set ON_ERROR_STOP on
    DO $$ BEGIN RAISE EXCEPTION 'P0-15 팀 구성: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
  \endif
\else
  \echo 'P0-15 팀 구성: -v ON_ERROR_STOP=1 없이 실행할 수 없다 (줄마다 COMMIT 하므로 단언이 실패해도 매수가 이어질 수 있다)'
  \set ON_ERROR_STOP on
  DO $$ BEGIN RAISE EXCEPTION 'P0-15 팀 구성: 실행하지 않았다 (위 안내 참고)' USING HINT = 'ON_ERROR_STOP'; END $$;
\endif

\ir 21_team_buy_def.sql

-- 지난 실행이 남긴 임시 객체를 지운다 (같은 세션에서 다시 읽어도 되도록. 없을 때의 알림은 숨긴다)
SET client_min_messages TO warning;
DROP PROCEDURE IF EXISTS pg_temp.team_build();
DROP VIEW IF EXISTS pg_temp.tb_diff;
DROP VIEW IF EXISTS pg_temp.tb_plan;
DROP VIEW IF EXISTS pg_temp.tb_season;
DROP TABLE IF EXISTS pg_temp.tb_gate;
DROP TABLE IF EXISTS pg_temp.tb_todo;
DROP TABLE IF EXISTS pg_temp.tb_before;
DROP TABLE IF EXISTS pg_temp.tb_cash;
DROP TABLE IF EXISTS pg_temp.tb_team;
RESET client_min_messages;

-- [팀 명단 한 곳] 한 줄에 한 종목: (줄, 시장, 종목 코드, 비중 %, 선언 포지션).
--   선언 포지션은 감독이 정한다. 시스템 판정(asset_position.system_position)은 제안일 뿐 복사하지 않는다 (PLAN §4.10).
--   줄 7(MSFT)은 시스템 판정과 다른 선언이다 — P0-20 의 역할 수행이 이 선언을 되묻는다.
CREATE TEMP TABLE tb_team (
    line_no           smallint PRIMARY KEY,
    market            text     NOT NULL,
    ticker            text     NOT NULL,
    weight_pct        smallint NOT NULL,
    declared_position text     NOT NULL
);
INSERT INTO tb_team VALUES
    (1, 'KR', '411060', 15, 'DEFENDER'),
    (2, 'US', 'KO',     15, 'DEFENDER'),
    (3, 'KR', '005930', 15, 'MIDFIELDER'),
    (4, 'US', 'JNJ',    15, 'MIDFIELDER'),
    (5, 'KR', '069500', 10, 'FORWARD_TARGET'),
    (6, 'US', 'NVDA',   10, 'FORWARD_BREAKOUT'),
    (7, 'US', 'MSFT',   10, 'FORWARD_TARGET'),
    (8, 'US', 'SHY',     5, 'GOALKEEPER');
-- 현금 목표 % (골키퍼의 일부). 골키퍼 종목 줄 비중 합 + 현금 목표 = 시작 포메이션의 골키퍼.
CREATE TEMP TABLE tb_cash (weight_pct smallint NOT NULL);
INSERT INTO tb_cash VALUES (5);

-- 연습 시즌 찾기: 20 과 같은 이메일 조회 (시즌 번호를 적지 않는다)
CREATE TEMP VIEW tb_season AS
SELECT se.*
FROM season se JOIN member m ON m.id = se.member_id
WHERE lower(m.email) = lower('phase0@example.invalid');

-- 줄마다 계산: 예산 · 0턴 체결가 · 요율 · 수량 · 수수료. 가격 행이 없는 줄(풀 밖)은 나오지 않는다.
CREATE TEMP VIEW tb_plan AS
SELECT t.line_no, t.market, t.ticker, t.weight_pct, t.declared_position,
       a.id AS asset_id, s.id AS season_id,
       b.budget, tp.price,
       pg_temp.buy_rate(a.market, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate) AS rate,
       pg_temp.team_qty(b.budget, tp.price, pg_temp.buy_rate(a.market, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate)) AS qty,
       pg_temp.buy_fee(tp.price, pg_temp.team_qty(b.budget, tp.price, pg_temp.buy_rate(a.market, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate)),
                       pg_temp.buy_rate(a.market, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate)) AS fee
FROM tb_team t
JOIN asset a ON a.market = t.market AND a.ticker = t.ticker
CROSS JOIN tb_season s
CROSS JOIN LATERAL (SELECT div(s.initial_cash * t.weight_pct, 100) AS budget) b
JOIN turn_price tp ON tp.season_id = s.id AND tp.turn_no = 0 AND tp.asset_id = a.id;

-- 비교: 이 시즌의 0턴 주문이 명단과 다른 점을 한 줄씩 낸다 (0행이면 같다). 값은 메시지에 넣지 않는다.
CREATE TEMP VIEW tb_diff AS
SELECT '명단 밖 종목의 0턴 주문이 있다 (' || a.market || ' ' || a.ticker || ')' AS msg
FROM tb_season s
JOIN orders o ON o.season_id = s.id AND o.turn_no = 0
JOIN asset a ON a.id = o.asset_id
WHERE NOT EXISTS (SELECT 1 FROM tb_plan p WHERE p.asset_id = o.asset_id)
UNION ALL
SELECT '0턴 주문이 매수·시장가·체결·DIRECT 가 아니다 (' || a.market || ' ' || a.ticker || ')'
FROM tb_season s
JOIN orders o ON o.season_id = s.id AND o.turn_no = 0
JOIN asset a ON a.id = o.asset_id
WHERE NOT (o.side = 'BUY' AND o.type = 'MARKET' AND o.status = 'FILLED' AND o.source = 'DIRECT'
           AND o.limit_price IS NULL AND o.formation_change_id IS NULL AND o.turn_feed_id IS NULL)
UNION ALL
SELECT '같은 종목의 0턴 주문이 ' || count(*) || ' 건이다 (' || p.market || ' ' || p.ticker || ')'
FROM tb_plan p JOIN orders o ON o.season_id = p.season_id AND o.asset_id = p.asset_id AND o.turn_no = 0
GROUP BY p.market, p.ticker HAVING count(*) > 1
UNION ALL
SELECT '주문 수량이 규칙 값과 다르다 (' || p.market || ' ' || p.ticker || ')'
FROM tb_plan p JOIN orders o ON o.season_id = p.season_id AND o.asset_id = p.asset_id AND o.turn_no = 0
WHERE o.quantity <> p.qty
UNION ALL
SELECT '체결이 1건이 아니거나 체결가·수량·수수료·세금이 규칙과 다르다 (' || p.market || ' ' || p.ticker || ')'
FROM tb_plan p JOIN orders o ON o.season_id = p.season_id AND o.asset_id = p.asset_id AND o.turn_no = 0
WHERE (SELECT count(*) FROM execution e WHERE e.order_id = o.id) <> 1
   OR EXISTS (SELECT 1 FROM execution e WHERE e.order_id = o.id
              AND (e.fill_price <> p.price OR e.quantity <> p.qty OR e.fee <> p.fee OR e.tax <> 0 OR e.realized_pnl IS NOT NULL))
UNION ALL
SELECT '시작 턴 0 라인업이 1건이 아니거나 선언 포지션이 명단과 다르다 (' || p.market || ' ' || p.ticker || ')'
FROM tb_plan p JOIN orders o ON o.season_id = p.season_id AND o.asset_id = p.asset_id AND o.turn_no = 0
WHERE (SELECT count(*) FROM lineup lu WHERE lu.season_id = p.season_id AND lu.asset_id = p.asset_id AND lu.start_turn = 0) <> 1
   OR EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = p.season_id AND lu.asset_id = p.asset_id AND lu.start_turn = 0
              AND lu.declared_position <> p.declared_position);

-- [전제 단언] 아무것도 쓰지 않는다 (임시 객체만). 하나라도 어긋나면 첫 20건을 보여 주고 멈춘다.
DO $$
DECLARE
    v_bad  text;
    v_n    bigint;
    v_msg  text;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'turn_price', 'asset', 'asset_position']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-15 팀 구성: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01·02·03 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;

    CREATE TEMP TABLE tb_err (msg text) ON COMMIT DROP;

    SELECT count(*) INTO v_n FROM tb_season;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'P0-15 팀 구성: 연습 시즌이 % 개다 (1 개여야 한다). db/20_season_start.sql 을 먼저 실행한다 (20 시즌 시작을 먼저)', v_n;
    END IF;

    -- 명단 검사
    SELECT count(*) INTO v_n FROM tb_team;
    IF v_n < 6 OR v_n > 8 THEN
        INSERT INTO tb_err VALUES ('팀 크기가 ' || v_n || ' 줄이다 (6–8 줄이어야 한다)');
    END IF;
    INSERT INTO tb_err
    SELECT '명단에 같은 종목이 ' || count(*) || ' 줄이다 (' || market || ' ' || ticker || ')' FROM tb_team GROUP BY market, ticker HAVING count(*) > 1;
    INSERT INTO tb_err
    SELECT '줄 ' || t.line_no || ': 기준일 풀에 없는 종목이다 (' || t.market || ' ' || t.ticker || ')'
    FROM tb_team t CROSS JOIN tb_season s
    WHERE NOT EXISTS (SELECT 1 FROM asset a JOIN asset_position ap ON ap.asset_id = a.id AND ap.base_date = s.base_date
                      WHERE a.market = t.market AND a.ticker = t.ticker);
    INSERT INTO tb_err
    SELECT '줄 ' || t.line_no || ': 선언 포지션이 여섯 가지 안에 없다'
    FROM tb_team t WHERE t.declared_position NOT IN ('GOALKEEPER', 'DEFENDER', 'MIDFIELDER', 'FORWARD_TARGET', 'FORWARD_BREAKOUT', 'FORWARD_DEFENSIVE');
    INSERT INTO tb_err
    SELECT '줄 ' || t.line_no || ': 비중이 1 이상의 정수 %가 아니다' FROM tb_team t WHERE t.weight_pct < 1;
    INSERT INTO tb_err SELECT '현금 목표가 1 미만이다 (골키퍼 최소 1, ck_season_start_goalkeeper_min 과 같은 뜻)' FROM tb_cash WHERE weight_pct < 1;

    -- 분류 합 = 시즌의 시작 포메이션 (선언 포지션 기준. 현금은 골키퍼)
    INSERT INTO tb_err
    SELECT c.cls || ' 분류 비중 합이 시작 포메이션과 다르다'
    FROM (
        SELECT s.id,
               x.cls, x.got, x.want
        FROM tb_season s
        CROSS JOIN LATERAL (VALUES
            ('골키퍼(현금 포함)', (SELECT COALESCE(sum(weight_pct), 0) FROM tb_team WHERE declared_position = 'GOALKEEPER') + (SELECT COALESCE(sum(weight_pct), 0) FROM tb_cash), s.start_goalkeeper::bigint),
            ('수비',               (SELECT COALESCE(sum(weight_pct), 0) FROM tb_team WHERE declared_position = 'DEFENDER'),                                                              s.start_defender::bigint),
            ('미드필더',           (SELECT COALESCE(sum(weight_pct), 0) FROM tb_team WHERE declared_position = 'MIDFIELDER'),                                                            s.start_midfielder::bigint),
            ('공격',               (SELECT COALESCE(sum(weight_pct), 0) FROM tb_team WHERE declared_position LIKE 'FORWARD\_%'),                                                         s.start_forward::bigint)
        ) AS x(cls, got, want)
    ) c
    WHERE c.got <> c.want;

    -- 줄마다 q >= 1 (가격 행이 있는 줄만 계산된다. 값은 메시지에 넣지 않는다)
    INSERT INTO tb_err
    SELECT '줄 ' || p.line_no || ': 수량이 0 이다 — 1주가 줄 예산보다 비싸다. 비중을 늘리거나 종목을 바꾼다 (' || p.market || ' ' || p.ticker || ')'
    FROM tb_plan p WHERE p.qty < 1;
    INSERT INTO tb_err
    SELECT '줄 ' || t.line_no || ': 0턴 가격 행이 없다 (' || t.market || ' ' || t.ticker || ')'
    FROM tb_team t WHERE NOT EXISTS (SELECT 1 FROM tb_plan p WHERE p.line_no = t.line_no);

    -- 예산 안: 대금 + 수수료 ≤ 줄 예산 (보장되지만 사후 단언이 아니라 사전에도 본다)
    INSERT INTO tb_err
    SELECT '줄 ' || p.line_no || ': 대금 + 수수료가 줄 예산을 넘는다 (' || p.market || ' ' || p.ticker || ')'
    FROM tb_plan p WHERE p.qty >= 1 AND p.price * p.qty + p.fee > p.budget;

    -- 다시 실행 비교: 이미 있는 0턴 주문이 명단과 다르면 멈춘다
    INSERT INTO tb_err SELECT msg FROM tb_diff;

    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM tb_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-15 팀 구성: 명단·전제가 맞지 않아 아무것도 사지 않고 멈춘다 (처음 % 건)\n%', v_n, v_msg;
    END IF;
    CREATE TEMP TABLE tb_gate (ok boolean);   -- 전제 단언을 통과했다는 표식 (team_build 가 확인한다)
END $$;

-- 실행 전 행 수 (보고용)
CREATE TEMP TABLE tb_before AS
SELECT (SELECT count(*) FROM orders o JOIN tb_season s ON s.id = o.season_id)       AS orders_n,
       (SELECT count(*) FROM execution e JOIN orders o ON o.id = e.order_id JOIN tb_season s ON s.id = o.season_id) AS execution_n,
       (SELECT count(*) FROM holding h JOIN tb_season s ON s.id = h.season_id)       AS holding_n,
       (SELECT count(*) FROM cash_ledger l JOIN tb_season s ON s.id = l.season_id)   AS ledger_n,
       (SELECT count(*) FROM lineup lu JOIN tb_season s ON s.id = lu.season_id)      AS lineup_n,
       (SELECT a.version FROM cash_account a JOIN tb_season s ON s.id = a.season_id)  AS account_version,
       (SELECT count(*) FROM tb_plan p WHERE EXISTS (SELECT 1 FROM orders o WHERE o.season_id = p.season_id AND o.asset_id = p.asset_id AND o.turn_no = 0)) AS lines_done;

-- 아직 안 산 줄만 모은다 (PRESERVE ROWS — 프로시저 안의 COMMIT 을 넘겨 살아남아야 한다)
CREATE TEMP TABLE tb_todo AS
SELECT p.line_no, p.season_id, p.asset_id, p.qty, p.declared_position
FROM tb_plan p
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.season_id = p.season_id AND o.asset_id = p.asset_id AND o.turn_no = 0);

-- 줄마다 매수 한 건 + COMMIT. 줄 k 에서 오류가 나면 이 호출이 오류로 끝나고 k 줄의 트랜잭션만 되돌아간다.
CREATE PROCEDURE pg_temp.team_build()
LANGUAGE plpgsql AS $$
DECLARE
    r        record;
    v_order  bigint;
BEGIN
    IF to_regclass('pg_temp.tb_gate') IS NULL THEN
        RAISE EXCEPTION 'P0-15 팀 구성: 전제 단언을 통과하지 못했다. 아무것도 사지 않고 멈춘다';
    END IF;
    FOR r IN SELECT * FROM tb_todo ORDER BY line_no LOOP
        -- 잠금 ① 을 먼저 잡고 다시 읽는다. 다른 실행이 그사이 같은 줄을 샀으면 사지 않는다 (줄 단위 멱등은 잠금 뒤의 읽기로만 지켜진다).
        --   같은 트랜잭션 안에서 order_buy 1단계의 FOR UPDATE 는 이미 가진 잠금이라 바로 지나간다. 잠금 순서 season → cash_account 는 그대로다.
        PERFORM 1 FROM season WHERE id = r.season_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM orders o WHERE o.season_id = r.season_id AND o.asset_id = r.asset_id AND o.turn_no = 0) THEN
            RAISE EXCEPTION 'P0-15 팀 구성: 줄 % 를 다른 실행이 이미 샀다. 같은 명령을 다시 실행해 비교한다', r.line_no;
        END IF;
        -- 잠금 뒤 전체 비교: 겹친 다른 명단의 실행이 그사이 산 줄이 이 명단과 다르면 이 줄을 쓰기 전에 멈춘다 (기록이 섞이지 않는다)
        IF EXISTS (SELECT 1 FROM tb_diff) THEN
            RAISE EXCEPTION 'P0-15 팀 구성: 줄 % 를 사기 전에 이 시즌의 0턴 주문이 명단과 달라졌다 (다른 명단의 실행이 겹쳤다). 아무것도 더 사지 않는다', r.line_no;
        END IF;
        v_order := pg_temp.order_buy(r.season_id, 0::smallint, r.asset_id, r.qty, r.declared_position);
        COMMIT;
    END LOOP;
END $$;

CALL pg_temp.team_build();

-- [사후 단언] 명단 모든 줄에 주문·체결·라인업이 있고 비교를 다시 통과한다. 대사 규칙 2·3(잔액 = Σ 원장, 연쇄)도 본다.
DO $$
DECLARE
    v_n   bigint;
    v_msg text;
BEGIN
    CREATE TEMP TABLE tb_err2 (msg text) ON COMMIT DROP;
    INSERT INTO tb_err2
    SELECT '줄 ' || p.line_no || ': 주문 · 체결 · 라인업 중 빠진 것이 있다 (' || p.market || ' ' || p.ticker || ')'
    FROM tb_plan p
    WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.season_id = p.season_id AND o.asset_id = p.asset_id AND o.turn_no = 0)
       OR NOT EXISTS (SELECT 1 FROM orders o JOIN execution e ON e.order_id = o.id WHERE o.season_id = p.season_id AND o.asset_id = p.asset_id AND o.turn_no = 0)
       OR NOT EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = p.season_id AND lu.asset_id = p.asset_id AND lu.start_turn = 0);
    INSERT INTO tb_err2 SELECT msg FROM tb_diff;
    INSERT INTO tb_err2
    SELECT '대사 규칙 2: 잔액이 원장 합과 다르다'
    FROM tb_season s JOIN cash_account a ON a.season_id = s.id
    WHERE a.balance <> (SELECT COALESCE(sum(l.amount), 0) FROM cash_ledger l WHERE l.season_id = s.id);
    INSERT INTO tb_err2
    SELECT '대사 규칙 3: 원장 연쇄가 끊겼거나 최신 거래 후 잔액이 잔액과 다르다'
    FROM tb_season s JOIN cash_account a ON a.season_id = s.id
    WHERE a.balance <> (SELECT l.balance_after FROM cash_ledger l WHERE l.season_id = s.id ORDER BY l.id DESC LIMIT 1)
       OR EXISTS (SELECT 1 FROM (SELECT l.balance_after, l.amount, COALESCE(lag(l.balance_after) OVER (ORDER BY l.id), 0) AS prev
                                 FROM cash_ledger l WHERE l.season_id = s.id) t WHERE t.balance_after <> t.prev + t.amount);
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM tb_err2 ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-15 팀 구성: 매수 뒤 단언 실패 (처음 % 건)\n%', v_n, v_msg;
    END IF;
END $$;

-- 보고 1: 상태와 이번에 늘어난 행 수 (이미 같은 팀이면 모두 0)
SELECT CASE WHEN (SELECT count(*) FROM tb_todo) = 0 THEN '이미 같은 팀 (새 행 없음)'
            WHEN (SELECT count(*) FROM tb_todo) = (SELECT count(*) FROM tb_team) THEN '새로 삼'
            ELSE '이어서 삼' END                                                   AS state,
       (SELECT count(*) FROM tb_team)                                              AS team_lines,
       (SELECT count(*) FROM tb_todo)                                              AS bought_now,
       (SELECT count(*) FROM orders o JOIN tb_season s ON s.id = o.season_id) - b.orders_n AS orders_inserted,
       (SELECT count(*) FROM execution e JOIN orders o ON o.id = e.order_id JOIN tb_season s ON s.id = o.season_id) - b.execution_n AS execution_inserted,
       (SELECT count(*) FROM holding h JOIN tb_season s ON s.id = h.season_id) - b.holding_n AS holding_inserted,
       (SELECT count(*) FROM cash_ledger l JOIN tb_season s ON s.id = l.season_id) - b.ledger_n AS ledger_inserted,
       (SELECT count(*) FROM lineup lu JOIN tb_season s ON s.id = lu.season_id) - b.lineup_n AS lineup_inserted,
       (SELECT a.version FROM cash_account a JOIN tb_season s ON s.id = a.season_id) - b.account_version AS account_updates
FROM tb_before b;

-- 보고 2: 건수만 (가격·수량·금액·비중은 내지 않는다)
SELECT (SELECT count(*) FROM orders o JOIN tb_season s ON s.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY')                 AS team_orders,
       (SELECT count(*) FROM lineup lu JOIN tb_season s ON s.id = lu.season_id WHERE lu.start_turn = 0 AND lu.end_turn IS NULL)      AS open_lineup,
       (SELECT count(*) FROM cash_ledger l JOIN tb_season s ON s.id = l.season_id WHERE l.type = 'FEE')                              AS fee_rows,
       (SELECT count(*) FROM tb_diff)                                                                                               AS diff_from_roster;

DROP PROCEDURE pg_temp.team_build();
DROP VIEW pg_temp.tb_diff;
DROP VIEW pg_temp.tb_plan;
DROP VIEW pg_temp.tb_season;
DROP TABLE pg_temp.tb_gate;
DROP TABLE pg_temp.tb_todo;
DROP TABLE pg_temp.tb_before;
DROP TABLE pg_temp.tb_cash;
DROP TABLE pg_temp.tb_team;
