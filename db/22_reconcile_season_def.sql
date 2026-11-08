-- =====================================================================
-- my Fund Manager Phase 0 — 대사 정의 (P0-16): 수동 검사 15개 규칙의 쿼리와 결과 → 로그 변환
-- 근거: docs/erd.md 7.5(대사 규칙 1–19, check_code 17개)·6.3(reconciliation_log·turn·turn_price)·D4·D7·D23·D26·D28·D29·D36·9절 P0-16·P0-17,
--       docs/PLAN.md §5.2(항등식 3개·일치해도 기록·불일치면 되돌림·분류는 전부 조사)·§5.3 질문 6·7·§4.10·§4.5, docs/SPRING_JPA.md §5.3(턴 전환 대사)·§6(대사, 롤백 뒤 기록)
-- 단독 실행 대상이 아니다. db/22_reconcile_season.sql(수동 검사)과 db/22_reconcile_season_check.sql(검증)이 \ir 로 읽고,
--       README 실습 (h)도 \ir 로 읽는다. 다음 기능인 턴 진행(P0-17)도 이 파일을 \ir 로 읽어 recon_rules 를 검사 턴 t+1 로 부른다 (이 파일을 고치지 않는다).
-- 만드는 것: 세션 임시 객체(pg_temp)뿐이다. 스키마에 객체가 늘지 않는다. 트랜잭션 문(BEGIN·COMMIT)이 없다 — 트랜잭션은 호출자가 연다.
--   pg_temp.recon_code                       임시 뷰, 17행: (rule_no, check_code, manual, scalar) — erd 7.5 와 ck_reconciliation_log_check_code 의 정본 사본
--   pg_temp.recon_detail(items jsonb)        불일치 항목 배열 → detail JSON (없으면 NULL). 항목은 최대 100개
--   pg_temp.recon_price(start, factors[])    ROUND(start × Π factors, 0) — 곱은 정확한 numeric, 반올림은 이 한 곳 (PLAN §5.2)
--   pg_temp.recon_expected_price(시즌, 턴)   규칙 12 의 기대 집합: 기준일 풀 × 턴 0..검사 턴
--   pg_temp.recon_rNN_이름(시즌, 검사 턴)    규칙 함수 15개 (아래 절). 각자 정확히 1행 (expected_value, actual_value, mismatch_count, detail), STABLE
--   pg_temp.recon_rules(시즌, 검사 턴)       15행(규칙 번호 순)에 status·diff_value·classification 을 한 곳에서 붙인다. 시즌이 없으면 P0001 SEASON_NOT_FOUND
--   pg_temp.recon_log(시즌, 턴, rows jsonb)  결과를 새 시도 번호로 reconciliation_log 에 INSERT … SELECT (시즌 잠금 → 코드 집합 검사(수동 15개 또는 전체 17개) → max + 1 → 23505 면 최대 3번 다시)
-- 규칙 번호와 check_code 는 erd 7.5 그대로다. 규칙 5(DDL 로 이동)·10(규칙과 권한)은 쿼리 대상이 아니고, 규칙 11·19(평가액)는 턴 전환 전용이라
--   P0-17 이 자기 정의 파일에 같은 4컬럼 모양의 함수로 둔다. 그래서 수동 검사는 15개다 (19 − 5·10 = 17 코드 − 11·19).
-- 기대·실제의 뜻: 기대 = 규칙과 바뀌지 않는 사실(원장·체결·역사 수익률·시즌 설정)에서 다시 계산한 값, 실제 = 저장된 값, 차이 = 실제 − 기대 (erd 6.3 diff_value).
--   스칼라 규칙 5개(CASH_SUM·ORDER_LEDGER·HOLDING_QTY·RESERVED_ZERO·FEE_RULE)만 세 값을 채우고 나머지는 세 값 NULL 이다.
--   스칼라 규칙도 비교 대상 자체가 없으면(계좌 행 없음) 세 값 NULL + 불일치 1 이다 (ck_reconciliation_log_values: NULL 개수 0 또는 3).
-- mismatch_count 는 항목 수다 (detail.items 의 길이 = 불일치 수). 판정: MATCH ⇔ 0, 아니면 MISMATCH + INVESTIGATE (분류 자동화는 MVP 이후, PLAN §5.2).
--   스칼라 합이 서로 상쇄돼 diff_value 가 0 이어도 항목 불일치가 있으면 MISMATCH 다 (erd ck_reconciliation_log_match_diff — 역방향은 강제하지 않는다).
-- detail JSON: {"items": [{"kind": 종류, "<대상>_id": 번호, …}], "total": n, "truncated": bool}. 항목은 id·턴 번호·종류 코드만 담고 금액·가격·수량·월 블록은 담지 않는다.
--   키 이름은 MVP 골든이 비교하는 계약이다: order_id · execution_id · ledger_id · asset_id · lineup_id · other_lineup_id · formation_change_id · turn_no · kinds(여러 종류일 때).
-- 한 스냅샷: 규칙 함수는 모두 STABLE 이고 recon_rules 가 15개를 UNION ALL 한 문장으로 부른다. READ COMMITTED 에서 STABLE 함수는 부른 문장의 스냅샷을 쓰므로
--   15개가 같은 시점을 본다 (21 의 order_buy 가 VOLATILE 이어야 하는 이유와 반대). 이 사실은 README 가 아니라 격리 DB 에서 두 세션으로 실행해 확인했다 (docs/erd.md D36).
-- 읽는 범위: 규칙 12 는 season_path 의 1..검사 턴 블록만 읽는다. 검사 턴 뒤의 수익률·블록은 읽지 않는다 (사후 정보 배제, PLAN §4.5).
-- 출력: 이 파일은 아무것도 출력하지 않는다. 오류 메시지에 금액·가격·수량을 넣지 않는다.
-- 새 문법: LANGUAGE sql STABLE 함수와 RETURNS TABLE, 윈도 함수 안의 array_agg(누적 배열), jsonb_agg … ORDER BY, jsonb_path_query_array 로 앞 100개 자르기,
--       FULL JOIN 으로 양쪽 누락 찾기, jsonb_to_recordset, 예외 블록 안의 재시도(unique_violation), #variable_conflict use_column.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 규칙 번호 ↔ 코드 ↔ 수동 여부 ↔ 스칼라 여부. erd 7.5 와 ck_reconciliation_log_check_code 의 사본 (검증이 카탈로그와 대조한다).
CREATE OR REPLACE TEMP VIEW recon_code AS
SELECT v.rule_no, v.check_code, v.manual, v.scalar
FROM (VALUES
    (1::smallint,  'ACCOUNT_OPENING'::text, true,  false),
    (2::smallint,  'CASH_SUM',              true,  true),
    (3::smallint,  'LEDGER_CHAIN',          true,  false),
    (4::smallint,  'ORDER_LEDGER',          true,  true),
    (6::smallint,  'HOLDING_QTY',           true,  true),
    (7::smallint,  'ORDER_FILL',            true,  false),
    (8::smallint,  'EXEC_VALUES',           true,  false),
    (9::smallint,  'RESERVED_ZERO',         true,  true),
    (11::smallint, 'VALUATION',             false, false),
    (12::smallint, 'TURN_PRICE',            true,  false),
    (13::smallint, 'FILL_PRICE',            true,  false),
    (14::smallint, 'FEE_RULE',              true,  true),
    (15::smallint, 'FORMATION',             true,  false),
    (16::smallint, 'FORMATION_BIND',        true,  false),
    (17::smallint, 'LINEUP_HOLDING',        true,  false),
    (18::smallint, 'LINEUP_OVERLAP',        true,  false),
    (19::smallint, 'POSITION_VALUE',        false, false)
) AS v(rule_no, check_code, manual, scalar);

-- 불일치 항목 배열(id 순으로 이미 정렬됨) → detail. 항목이 없으면 NULL (일치). 앞 100개만 담고 전체 수와 잘렸는지를 같이 남긴다.
CREATE OR REPLACE FUNCTION pg_temp.recon_detail(p_items jsonb)
RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN p_items IS NULL OR jsonb_array_length(p_items) = 0 THEN NULL
                ELSE jsonb_build_object('items', jsonb_path_query_array(p_items, '$[0 to 99]'),
                                        'total', jsonb_array_length(p_items),
                                        'truncated', jsonb_array_length(p_items) > 100)
           END
$$;

-- ROUND(시작가 × Π 인자, 0). 곱은 정확한 numeric 이고 반올림은 마지막 한 번 (PLAN §5.2, erd D16·D25). ROUND(numeric) 는 0에서 먼 쪽으로 반올림한다 (erd 규칙 12).
-- 인자 배열이 비면 시작가 그대로, 인자에 NULL 이 있으면 NULL (수익률 행이 없다는 뜻 — 규칙 12 가 NO_RETURN 으로 보고한다).
CREATE OR REPLACE FUNCTION pg_temp.recon_price(p_start numeric, p_factors numeric[])
RETURNS numeric
LANGUAGE sql IMMUTABLE AS $$
    WITH RECURSIVE r(i, acc) AS (
        SELECT 0, p_start
        UNION ALL
        SELECT r.i + 1, r.acc * p_factors[r.i + 1]
        FROM r
        WHERE r.i < COALESCE(cardinality(p_factors), 0)
    )
    SELECT ROUND(r.acc, 0) FROM r ORDER BY r.i DESC LIMIT 1
$$;

-- 규칙 12 의 기대 집합: 기준일 풀(asset_position.base_date = season.base_date) × 턴 0..p_upto.
--   0턴 = ROUND(base_price × base_fx_rate, 0) (시작가는 저장된 turn_price(0) 가 아니라 이 기대값 — 0턴 오류가 뒤 턴으로 번지지 않게),
--   k턴 = ROUND(0턴 가격 × Π_{j=1..k} (1 + r현지_j)(1 + r환율_j), 0). 원화 종목은 r환율 = 0 (환율 행을 찾지 않는다). j턴 블록 = season_path(j).
--   누적 인자 배열은 창 함수 array_agg 가 만든다 (j 순서). 수익률·환율·블록이 없으면 그 인자가 NULL 이라 그 턴부터 가격이 NULL 이다.
--   p_upto 뒤 블록은 읽지 않는다 (generate_series 가 1..p_upto).
CREATE OR REPLACE FUNCTION pg_temp.recon_expected_price(p_season_id bigint, p_upto smallint)
RETURNS TABLE (turn_no smallint, asset_id bigint, price numeric)
LANGUAGE sql STABLE AS $$
    WITH pool AS (
        SELECT ap.asset_id, a.currency, ROUND(ap.base_price * ap.base_fx_rate, 0) AS p0
        FROM season s
        JOIN asset_position ap ON ap.base_date = s.base_date
        JOIN asset a ON a.id = ap.asset_id
        WHERE s.id = p_season_id
    ), f AS (
        SELECT pl.asset_id, t.k, pl.p0,
               array_agg((1 + amr.local_return) * CASE WHEN pl.currency = 'KRW' THEN 1 ELSE 1 + fx.fx_change END)
                   OVER (PARTITION BY pl.asset_id ORDER BY t.k) AS arr
        FROM pool pl
        CROSS JOIN generate_series(1, p_upto) AS t(k)
        LEFT JOIN season_path sp ON sp.season_id = p_season_id AND sp.turn_no = t.k
        LEFT JOIN asset_month_return amr ON amr.asset_id = pl.asset_id AND amr.month_block_id = sp.month_block_id
        LEFT JOIN fx_month fx ON fx.month_block_id = sp.month_block_id AND fx.currency = pl.currency
    )
    SELECT 0::smallint, pl.asset_id, pg_temp.recon_price(pl.p0, ARRAY[]::numeric[]) FROM pool pl
    UNION ALL
    SELECT f.k::smallint, f.asset_id, pg_temp.recon_price(f.p0, f.arr) FROM f
$$;

-- ---------------------------------------------------------------------
-- 규칙 1 ACCOUNT_OPENING — 계좌 개설 (erd 7.5 규칙 1, P0-14)
--   조건 4개: 계좌 행 있음 · 원장 ≥ 1행 · 최소 id 행이 DEPOSIT · 그 금액 = season.initial_cash. 어긴 조건 수가 불일치 수다 (앞 조건이 없으면 뒤 조건도 어긴 것으로 센다).
--   항목 kind: NO_ACCOUNT · NO_LEDGER · FIRST_NOT_DEPOSIT · DEPOSIT_AMOUNT. 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r01_account_opening(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH f AS (
        SELECT (SELECT l.type   FROM cash_ledger l WHERE l.season_id = p_season_id ORDER BY l.id LIMIT 1) AS ftype,
               (SELECT l.amount FROM cash_ledger l WHERE l.season_id = p_season_id ORDER BY l.id LIMIT 1) AS famt,
               EXISTS (SELECT 1 FROM cash_account a WHERE a.season_id = p_season_id) AS has_acct,
               EXISTS (SELECT 1 FROM cash_ledger l WHERE l.season_id = p_season_id) AS has_led,
               (SELECT s.initial_cash FROM season s WHERE s.id = p_season_id) AS init
    ), bad AS (
        SELECT k.ord::bigint AS k1, jsonb_build_object('kind', k.kind) AS item
        FROM f
        CROSS JOIN LATERAL (VALUES (1, 'NO_ACCOUNT',        NOT f.has_acct),
                                   (2, 'NO_LEDGER',         NOT f.has_led),
                                   (3, 'FIRST_NOT_DEPOSIT', f.ftype IS DISTINCT FROM 'DEPOSIT'),
                                   (4, 'DEPOSIT_AMOUNT',    f.famt IS DISTINCT FROM f.init)) AS k(ord, kind, bad)
        WHERE k.bad
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 2 CASH_SUM — Σ cash_ledger.amount = cash_account.balance (PLAN §5.2 항등식 ①, erd 7.5 규칙 2)
--   스칼라: 기대 = COALESCE(Σ amount, 0) (빈 원장 합은 NULL 이라 0 으로), 실제 = balance. 계좌가 없으면 세 값 NULL + 불일치 1 (kind NO_ACCOUNT).
--   원장이 1행 이상이고 계좌가 있으면, 규칙 3 이 참일 때(사슬이 이어지고 최신 = 잔액) 최신 balance_after = Σ amount = balance 라서 규칙 2 가 저절로 참이다 (3 ⇒ 2).
--   그래서 그런 시즌에서는 규칙 2 만 깨뜨릴 수 없다 (검증의 규칙 2 사례 기대 집합이 {2, 3} 인 이유, D36). 원장이 비면 규칙 3 은 대상이 없어 일치하고, 규칙 2 는 빈 합(0)과 잔액을 비교하므로 잔액이 0 이면 일치한다. 어느 쪽이든 원장 부재는 규칙 1(NO_LEDGER)이 잡는다.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r02_cash_sum(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH x AS (
        SELECT a.season_id IS NOT NULL AS has_acct,
               COALESCE((SELECT sum(l.amount) FROM cash_ledger l WHERE l.season_id = p_season_id), 0) AS s,
               a.balance AS bal
        FROM (SELECT 1) one
        LEFT JOIN cash_account a ON a.season_id = p_season_id
    ), bad AS (
        SELECT 1::bigint AS k1,
               jsonb_build_object('kind', CASE WHEN NOT x.has_acct THEN 'NO_ACCOUNT' ELSE 'SUM' END) AS item
        FROM x
        WHERE NOT x.has_acct OR x.s <> x.bal
    )
    SELECT CASE WHEN x.has_acct THEN x.s END, x.bal,
           COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM x, (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 3 LEDGER_CHAIN — 잔액 연속성 (erd 7.5 규칙 3)
--   원장을 id 순으로 볼 때 각 행 balance_after = 직전 행의 balance_after(첫 행은 0) + amount, 그리고 최신 행 balance_after = balance.
--   불일치 수 = 끊긴 행 수 + 최신 불일치 1. 항목 kind: CHAIN(ledger_id) · LATEST. 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r03_ledger_chain(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH ch AS (
        SELECT l.id, l.balance_after, l.amount,
               COALESCE(lag(l.balance_after) OVER (ORDER BY l.id), 0) AS prev
        FROM cash_ledger l
        WHERE l.season_id = p_season_id
    ), bad AS (
        SELECT c.id AS k1, jsonb_build_object('kind', 'CHAIN', 'ledger_id', c.id) AS item
        FROM ch c
        WHERE c.balance_after <> c.prev + c.amount
        UNION ALL
        SELECT 9223372036854775807::bigint, jsonb_build_object('kind', 'LATEST')
        FROM (SELECT c.balance_after FROM ch c ORDER BY c.id DESC LIMIT 1) lst
        JOIN cash_account a ON a.season_id = p_season_id
        WHERE a.balance <> lst.balance_after
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 4 ORDER_LEDGER — 주문별 원장 합 = 체결 대금·수수료·세금 (erd 7.5 규칙 4, D4)
--   총액 판정: 주문별 기대 = (매도 +, 매수 −) Σ fill_price × quantity − Σ fee − Σ tax ↔ 실제 = ref_order_id 가 그 주문인 원장 행
--     (BUY·SELL·FEE·TAX·ADJUSTMENT 모두)의 합. 체결 쪽과 원장 쪽을 FULL JOIN 해서 한쪽에만 있는 주문도 잡는다.
--   유형별 진단: BUY/SELL 합 ↔ ±대금, FEE 합 ↔ −Σ fee, TAX 합 ↔ −Σ tax, 원장 BUY/SELL 의 유형 ↔ 주문 side.
--     한 (시도, 규칙)에 한 행이므로(D28) 두 판정을 한 행에 담는다: 스칼라는 총액이고 detail.items[].kinds 가 TOTAL(총액 불일치)과 TYPE(분류 오류)을 나눈다.
--     FEE 를 BUY 에 합친 행은 총액이 맞아 diff_value = 0 이지만 TYPE 으로 MISMATCH 가 된다 (합법 — 역방향 미강제).
--   ADJUSTMENT: 총액에는 들어가고(erd 문구), 유형 진단에 특례를 두지 않는다 — 정정이 있는 주문은 유형 합이 어긋나 MISMATCH·INVESTIGATE 로 사람이 본다 (PLAN §5.2).
--     Phase 0 에는 ADJUSTMENT 를 만드는 기능이 없고 자동 분류 ADJUSTMENT 는 MVP 몫이다.
--   불일치 수 = 문제가 하나 이상인 주문 수. 항목 kind(=첫 종류)·kinds: TOTAL · TYPE · SIDE · ORPHAN_LEDGER(체결 없는 주문의 원장) · NO_LEDGER(원장 없는 체결). 스칼라.
--   21 의 ORDER_LEDGER 는 매수만·BUY 1건·FEE 행 수까지 보는 더 엄격한 P0-15 사본이고, 정본은 이 규칙이다 (행 수 조건은 erd 에 없다).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r04_order_ledger(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH ex AS (
        SELECT o.id AS order_id, o.side, sum(e.fill_price * e.quantity) AS gross, sum(e.fee) AS fee, sum(e.tax) AS tax
        FROM orders o
        JOIN execution e ON e.order_id = o.id
        WHERE o.season_id = p_season_id
        GROUP BY o.id, o.side
    ), led AS (
        SELECT l.ref_order_id AS order_id,
               sum(l.amount) AS total,
               COALESCE(sum(l.amount) FILTER (WHERE l.type IN ('BUY', 'SELL')), 0) AS trade_sum,
               COALESCE(sum(l.amount) FILTER (WHERE l.type = 'FEE'), 0) AS fee_sum,
               COALESCE(sum(l.amount) FILTER (WHERE l.type = 'TAX'), 0) AS tax_sum,
               COALESCE(bool_or(l.type IN ('BUY', 'SELL') AND l.type <> o.side), false) AS side_bad
        FROM cash_ledger l
        JOIN orders o ON o.id = l.ref_order_id
        WHERE l.season_id = p_season_id AND l.ref_order_id IS NOT NULL
        GROUP BY l.ref_order_id
    ), j AS (
        SELECT COALESCE(ex.order_id, led.order_id) AS order_id,
               ex.order_id IS NULL AS no_exec,
               led.order_id IS NULL AS no_led,
               CASE WHEN ex.order_id IS NULL THEN 0
                    ELSE (CASE ex.side WHEN 'BUY' THEN -1 ELSE 1 END) * ex.gross - ex.fee - ex.tax END AS exp_total,
               COALESCE(led.total, 0) AS act_total,
               ex.order_id IS NOT NULL AND led.order_id IS NOT NULL
                   AND (led.trade_sum <> (CASE ex.side WHEN 'BUY' THEN -1 ELSE 1 END) * ex.gross
                        OR led.fee_sum <> -ex.fee OR led.tax_sum <> -ex.tax) AS type_bad,
               COALESCE(led.side_bad, false) AS side_bad
        FROM ex
        FULL JOIN led ON led.order_id = ex.order_id
    ), k AS (
        SELECT j.order_id,
               array_remove(ARRAY[CASE WHEN j.exp_total <> j.act_total THEN 'TOTAL' END,
                                  CASE WHEN j.type_bad  THEN 'TYPE' END,
                                  CASE WHEN j.side_bad  THEN 'SIDE' END,
                                  CASE WHEN j.no_exec   THEN 'ORPHAN_LEDGER' END,
                                  CASE WHEN j.no_led    THEN 'NO_LEDGER' END], NULL) AS kinds
        FROM j
    )
    SELECT (SELECT COALESCE(sum(j.exp_total), 0) FROM j)::numeric(19,4),
           (SELECT COALESCE(sum(j.act_total), 0) FROM j)::numeric(19,4),
           COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(jsonb_build_object('kind', k.kinds[1], 'order_id', k.order_id, 'kinds', to_jsonb(k.kinds)) ORDER BY k.order_id) AS items
          FROM k WHERE cardinality(k.kinds) > 0) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 5 — 원장 행의 시즌 = 참조 주문의 시즌: DDL 로 이동했다 (복합 FK fk_cash_ledger_order, D29). 쿼리 대상이 아니다.
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- 규칙 6 HOLDING_QTY — holding.quantity = Σ 매수 체결 수량 − Σ 매도 체결 수량, 양쪽 키로 (erd 7.5 규칙 6, PLAN §5.2 항등식 ②, D7)
--   보유 행과 (종목별 체결 합)을 FULL JOIN 한다. 한쪽에만 있거나 수량이 다르면 불일치다 — 전량 매도 뒤 수량 0 보유 행(D7)이 지워진 경우도 잡는다.
--   주문 상태로 거르지 않는다: 체결은 수량이 실제로 움직인 사실이고, 상태 표시와의 일치는 규칙 7 의 몫이다 (21 의 사본은 FILLED 로 거른다). avg_price 는 대사하지 않는다 (D9).
--   불일치 수 = 어긋난 (시즌, 종목) 수. kind: NO_HOLDING(체결만 있음) · NO_EXECUTION(보유 행만 있음) · QTY. 스칼라: 기대 Σ 체결 순수량 ↔ 실제 Σ holding.quantity.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r06_holding_qty(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH h AS (
        SELECT hd.asset_id, hd.quantity FROM holding hd WHERE hd.season_id = p_season_id
    ), x AS (
        SELECT o.asset_id, sum(CASE o.side WHEN 'BUY' THEN e.quantity ELSE -e.quantity END) AS q
        FROM orders o
        JOIN execution e ON e.order_id = o.id
        WHERE o.season_id = p_season_id
        GROUP BY o.asset_id
    ), j AS (
        SELECT COALESCE(h.asset_id, x.asset_id) AS asset_id, h.asset_id IS NULL AS no_h, x.asset_id IS NULL AS no_x,
               h.quantity AS hq, x.q AS xq
        FROM h
        FULL JOIN x ON x.asset_id = h.asset_id
    ), bad AS (
        SELECT j.asset_id AS k1,
               jsonb_build_object('kind', CASE WHEN j.no_h THEN 'NO_HOLDING' WHEN j.no_x THEN 'NO_EXECUTION' ELSE 'QTY' END,
                                  'asset_id', j.asset_id) AS item
        FROM j
        WHERE j.no_h OR j.no_x OR j.hq <> j.xq
    )
    SELECT (SELECT COALESCE(sum(j.xq), 0) FROM j)::numeric(19,4),
           (SELECT COALESCE(sum(j.hq), 0) FROM j)::numeric(19,4),
           COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 7 ORDER_FILL — 주문 상태 ↔ 체결 (erd 7.5 규칙 7, D2)
--   주문마다: status ≠ FILLED (v2 는 FILLED 만 쓴다 — 그 밖의 상태 행은 0건) · FILLED 인데 Σ 체결 수량 ≠ 주문 수량(체결 0건 포함) ·
--   type ≠ MARKET 이거나 limit_price 가 NULL 이 아님. 불일치 수 = 주문 수. kind(=첫 종류)·kinds: STATUS · FILL_QTY · TYPE. 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r07_order_fill(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH k AS (
        SELECT o.id AS order_id,
               array_remove(ARRAY[
                   CASE WHEN o.status <> 'FILLED' THEN 'STATUS' END,
                   CASE WHEN o.status = 'FILLED'
                             AND COALESCE((SELECT sum(e.quantity) FROM execution e WHERE e.order_id = o.id), 0) <> o.quantity THEN 'FILL_QTY' END,
                   CASE WHEN o.type <> 'MARKET' OR o.limit_price IS NOT NULL THEN 'TYPE' END], NULL) AS kinds
        FROM orders o
        WHERE o.season_id = p_season_id
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(jsonb_build_object('kind', k.kinds[1], 'order_id', k.order_id, 'kinds', to_jsonb(k.kinds)) ORDER BY k.order_id) AS items
          FROM k WHERE cardinality(k.kinds) > 0) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 8 EXEC_VALUES — 체결 값 (erd 7.5 규칙 8)
--   체결마다: 매수는 realized_pnl 이 NULL 이고 tax = 0, 매도는 realized_pnl 이 NOT NULL (MVP 는 매도 체결도 tax = 0 — 세금은 EXT).
--   불일치 수 = 체결 수. kind(=첫 종류)·kinds: BUY_PNL · SELL_PNL · TAX. 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r08_exec_values(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH k AS (
        SELECT e.id AS execution_id,
               array_remove(ARRAY[
                   CASE WHEN o.side = 'BUY'  AND e.realized_pnl IS NOT NULL THEN 'BUY_PNL' END,
                   CASE WHEN o.side = 'SELL' AND e.realized_pnl IS NULL     THEN 'SELL_PNL' END,
                   CASE WHEN e.tax <> 0 THEN 'TAX' END], NULL) AS kinds
        FROM execution e
        JOIN orders o ON o.id = e.order_id
        WHERE o.season_id = p_season_id
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(jsonb_build_object('kind', k.kinds[1], 'execution_id', k.execution_id, 'kinds', to_jsonb(k.kinds)) ORDER BY k.execution_id) AS items
          FROM k WHERE cardinality(k.kinds) > 0) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 9 RESERVED_ZERO — cash_account.reserved = 0 (지정가가 없다, PLAN §4.6, erd 7.5 규칙 9, D2)
--   스칼라: 기대 0 ↔ 실제 reserved. 계좌가 없으면 세 값 NULL + 불일치 1 (kind NO_ACCOUNT).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r09_reserved_zero(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH x AS (
        SELECT a.season_id IS NOT NULL AS has_acct, a.reserved AS rsv
        FROM (SELECT 1) one
        LEFT JOIN cash_account a ON a.season_id = p_season_id
    ), bad AS (
        SELECT 1::bigint AS k1,
               jsonb_build_object('kind', CASE WHEN NOT x.has_acct THEN 'NO_ACCOUNT' ELSE 'RESERVED' END) AS item
        FROM x
        WHERE NOT x.has_acct OR x.rsv <> 0
    )
    SELECT CASE WHEN x.has_acct THEN 0::numeric(19,4) END, x.rsv,
           COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM x, (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 10 — 원장 UPDATE/DELETE 금지: 추가 전용은 규칙과 권한(@Immutable, MVP 의 GRANT/REVOKE)으로 지킨다. 정정은 ADJUSTMENT 행 추가. 쿼리 대상이 아니다.
-- 규칙 11 VALUATION — 평가액(새 turn 행의 holdings_value·cash_balance·drawdown): 턴 전환 전용이다 (현재 턴의 매매가 스냅샷 뒤에 있다).
--   수동 검사에서는 돌리지 않고 기록하지 않는다. P0-17 이 자기 정의 파일에 같은 4컬럼 모양의 함수로 두고, turn 을 만드는 식과 다른 방법으로 계산한다.
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- 규칙 12 TURN_PRICE — 턴 가격 = PLAN §5.2 공식 (erd 7.5 규칙 12, 6.3 turn_price, D16·D25)
--   턴 0..검사 턴의 (턴, 종목) 기대 가격 집합(recon_expected_price) ↔ 저장된 turn_price 를 FULL JOIN. 검사 턴 뒤의 저장 행도 불일치다 (미래 가격은 없다).
--   기대 집합은 이 파일의 독립 구현이다 — 턴 진행(P0-17)의 가격 생성기와 코드를 공유하지 않는다. 두 구현이 같은 값을 내는 것이 P0-17 의 완료 기준이다.
--   수익률·환율·월 블록이 없는 (턴, 종목)은 오류로 멈추지 않고 NO_RETURN 으로 보고한다 (대사는 기록이 목적 — 멈추면 로그가 안 남는다).
--   불일치 수 = (턴, 종목) 수. kind: PRICE · MISSING · EXTRA(풀 밖 종목 또는 풀 밖 턴 이전의 행) · FUTURE(검사 턴 뒤 행) · NO_RETURN. 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r12_turn_price(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH e AS (
        SELECT x.turn_no, x.asset_id, x.price FROM pg_temp.recon_expected_price(p_season_id, p_turn) x
    ), s AS (
        SELECT tp.turn_no, tp.asset_id, tp.price FROM turn_price tp WHERE tp.season_id = p_season_id
    ), j AS (
        SELECT COALESCE(e.turn_no, s.turn_no) AS t, COALESCE(e.asset_id, s.asset_id) AS a,
               e.turn_no IS NULL AS no_e, s.turn_no IS NULL AS no_s, e.price AS ep, s.price AS sp
        FROM e
        FULL JOIN s ON s.turn_no = e.turn_no AND s.asset_id = e.asset_id
    ), bad AS (
        SELECT q.t, q.a, q.kind
        FROM (SELECT j.t, j.a,
                     CASE WHEN j.no_e AND j.t > p_turn THEN 'FUTURE'
                          WHEN j.no_e THEN 'EXTRA'
                          WHEN j.ep IS NULL THEN 'NO_RETURN'
                          WHEN j.no_s THEN 'MISSING'
                          WHEN j.sp <> j.ep THEN 'PRICE' END AS kind
              FROM j) q
        WHERE q.kind IS NOT NULL
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(jsonb_build_object('kind', bad.kind, 'turn_no', bad.t, 'asset_id', bad.a) ORDER BY bad.t, bad.a) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 13 FILL_PRICE — 체결가 = turn_price(시즌, orders.turn_no, 종목) (erd 7.5 규칙 13, D23, PLAN §5.2 "체결·평가는 그 값만")
--   LEFT JOIN + 값 비교 — 가격 행이 없어도 잡는다. 불일치 수 = 체결 수. kind: PRICE · NO_PRICE. 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r13_fill_price(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH bad AS (
        SELECT e.id AS execution_id, o.turn_no,
               CASE WHEN tp.id IS NULL THEN 'NO_PRICE' ELSE 'PRICE' END AS kind
        FROM execution e
        JOIN orders o ON o.id = e.order_id
        LEFT JOIN turn_price tp ON tp.season_id = o.season_id AND tp.turn_no = o.turn_no AND tp.asset_id = o.asset_id
        WHERE o.season_id = p_season_id AND tp.price IS DISTINCT FROM e.fill_price
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(jsonb_build_object('kind', bad.kind, 'execution_id', bad.execution_id, 'turn_no', bad.turn_no) ORDER BY bad.execution_id) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 14 FEE_RULE — 수수료 = ROUND(fill_price × quantity × rate(asset.market), 0) (erd 7.5 규칙 14, PLAN §4.6)
--   rate(KR) = season.fee_rate_kr, rate(US) = season.fee_rate_us + season.fx_spread_rate. 시장은 execution → orders → asset 으로 찾는다.
--   기대는 정수 산술이다: R = rate × 10^8 일 때 div(2·p·q·R + 10^8, 2·10^8) — 21 정의의 buy_fee(ROUND)를 부르지 않는 독립 계산이다.
--   불일치 수 = 체결 수. kind: FEE. 스칼라: 기대 Σ 수수료 ↔ 실제 Σ execution.fee.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r14_fee_rule(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH x AS (
        SELECT e.id AS execution_id, e.fee,
               div(2 * e.fill_price * e.quantity * r.r + 100000000, 200000000) AS exp_fee
        FROM execution e
        JOIN orders o ON o.id = e.order_id
        JOIN asset a ON a.id = o.asset_id
        JOIN season s ON s.id = o.season_id
        CROSS JOIN LATERAL (SELECT CASE a.market WHEN 'KR' THEN s.fee_rate_kr WHEN 'US' THEN s.fee_rate_us + s.fx_spread_rate END * 100000000 AS r) r
        WHERE o.season_id = p_season_id
    ), bad AS (
        SELECT x.execution_id AS k1, jsonb_build_object('kind', 'FEE', 'execution_id', x.execution_id) AS item
        FROM x
        WHERE x.fee IS DISTINCT FROM x.exp_fee
    )
    SELECT (SELECT COALESCE(sum(x.exp_fee), 0) FROM x)::numeric(19,4),
           (SELECT COALESCE(sum(x.fee), 0) FROM x)::numeric(19,4),
           COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 15 FORMATION — 포메이션 중복 컬럼과 이력의 일치 (erd 7.5 규칙 15, PLAN §5.3 질문 7)
--   ① season.current_* 네 값 = 마지막(최대 id) formation_change 의 after 네 값 (변경이 없으면 start_*).
--   ② 변경마다 before 네 값 = 직전 변경(id 순)의 after (첫 변경은 start_*).
--   불일치 수 = ①의 1 + ②의 끊긴 변경 수. kind: CURRENT · CHAIN(formation_change_id). 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r15_formation(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH s AS (
        SELECT ARRAY[se.start_goalkeeper, se.start_defender, se.start_midfielder, se.start_forward] AS st,
               ARRAY[se.current_goalkeeper, se.current_defender, se.current_midfielder, se.current_forward] AS cur
        FROM season se
        WHERE se.id = p_season_id
    ), chg AS (
        SELECT fc.id,
               ARRAY[fc.before_goalkeeper, fc.before_defender, fc.before_midfielder, fc.before_forward] AS b,
               ARRAY[fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward] AS a,
               lag(ARRAY[fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward]) OVER (ORDER BY fc.id) AS prev_a
        FROM formation_change fc
        WHERE fc.season_id = p_season_id
    ), bad AS (
        SELECT 0::bigint AS k1, jsonb_build_object('kind', 'CURRENT') AS item
        FROM s
        WHERE s.cur IS DISTINCT FROM COALESCE((SELECT c.a FROM chg c ORDER BY c.id DESC LIMIT 1), s.st)
        UNION ALL
        SELECT c.id, jsonb_build_object('kind', 'CHAIN', 'formation_change_id', c.id)
        FROM chg c
        CROSS JOIN s
        WHERE c.b IS DISTINCT FROM COALESCE(c.prev_a, s.st)
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 16 FORMATION_BIND — 전술 변경 묶기 (erd 7.5 규칙 16, PLAN §5.1 "자동으로 묶는다(초안)")
--   주문마다 기대 = 같은 (시즌, 턴)에서 created_at 이 그 주문보다 앞선 마지막 변경의 id (없으면 NULL) ↔ orders.formation_change_id (IS DISTINCT FROM).
--   주문은 넣은 뒤 바뀌지 않고 사후 UPDATE 로 채우지 않으므로(erd P0-19) 넣는 순간 없던 뒤 변경에는 묶일 수 없다 — 그래서 "앞선" 마지막 변경이다.
--   같은 시각의 변경이 있으면 순서를 정할 수 없어 불일치다 (id 는 같은 테이블 안에서만 순서). 이 규칙은 변경·주문의 created_at 이 clock_timestamp() 라는 전제를 둔다.
--   불일치 수 = 주문 수. kind: UNBOUND(앞선 변경이 있는데 묶이지 않음) · WRONG(다른 변경 또는 변경이 없는데 묶임) · TIE(같은 시각). 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r16_formation_bind(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH k AS (
        SELECT o.id AS order_id, o.turn_no,
               CASE WHEN EXISTS (SELECT 1 FROM formation_change fc
                                 WHERE fc.season_id = o.season_id AND fc.turn_no = o.turn_no AND fc.created_at = o.created_at) THEN 'TIE'
                    WHEN o.formation_change_id IS DISTINCT FROM
                         (SELECT fc.id FROM formation_change fc
                          WHERE fc.season_id = o.season_id AND fc.turn_no = o.turn_no AND fc.created_at < o.created_at
                          ORDER BY fc.created_at DESC, fc.id DESC LIMIT 1)
                    THEN CASE WHEN o.formation_change_id IS NULL THEN 'UNBOUND' ELSE 'WRONG' END
               END AS kind
        FROM orders o
        WHERE o.season_id = p_season_id
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(jsonb_build_object('kind', k.kind, 'order_id', k.order_id, 'turn_no', k.turn_no) ORDER BY k.order_id) AS items
          FROM k WHERE k.kind IS NOT NULL) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 17 LINEUP_HOLDING — 열린 lineup 행이 있다 ⇔ holding.quantity > 0 (시즌·종목별 양방향, erd 7.5 규칙 17, PLAN §4.10)
--   두 집합을 FULL JOIN 한다. 불일치 수 = 어긋난 (시즌, 종목) 수. kind: OPEN_NO_HOLDING(열린 구간만 있음) · HOLDING_NO_OPEN(보유만 있음). 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r17_lineup_holding(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH o AS (
        SELECT DISTINCT lu.asset_id FROM lineup lu WHERE lu.season_id = p_season_id AND lu.end_turn IS NULL
    ), h AS (
        SELECT hd.asset_id FROM holding hd WHERE hd.season_id = p_season_id AND hd.quantity > 0
    ), bad AS (
        SELECT COALESCE(o.asset_id, h.asset_id) AS asset_id,
               CASE WHEN h.asset_id IS NULL THEN 'OPEN_NO_HOLDING' ELSE 'HOLDING_NO_OPEN' END AS kind
        FROM o
        FULL JOIN h ON h.asset_id = o.asset_id
        WHERE o.asset_id IS NULL OR h.asset_id IS NULL
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(jsonb_build_object('kind', bad.kind, 'asset_id', bad.asset_id) ORDER BY bad.asset_id) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 18 LINEUP_OVERLAP — 라인업 구간 (erd 7.5 규칙 18, D26, PLAN §5.3 질문 6)
--   같은 시즌·종목의 모든 구간 쌍이 겹치지 않고(반열림 [start, end), 열린 구간의 end 는 +∞, 겹침 ⇔ s1 < e2 AND s2 < e1) start_turn ≤ 검사 턴, 닫힌 구간의 end_turn ≤ 검사 턴.
--   COALESCE(end_turn, 32767) 는 이 조회 안의 +∞ 일 뿐이다 (저장하는 sentinel 이 아니다 — D26 탈락안과 다르다).
--   불일치 수 = 겹친 쌍 수 + 검사 턴을 넘은 구간 수. kind: OVERLAP(lineup_id, other_lineup_id) · START_AFTER · END_AFTER(lineup_id). 세 값 NULL.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_r18_lineup_overlap(p_season_id bigint, p_turn smallint)
RETURNS TABLE (expected_value numeric(19,4), actual_value numeric(19,4), mismatch_count integer, detail jsonb)
LANGUAGE sql STABLE AS $$
    WITH bad AS (
        SELECT a.id AS k1, b.id AS k2,
               jsonb_build_object('kind', 'OVERLAP', 'lineup_id', a.id, 'other_lineup_id', b.id) AS item
        FROM lineup a
        JOIN lineup b ON b.season_id = a.season_id AND b.asset_id = a.asset_id AND b.id > a.id
        WHERE a.season_id = p_season_id
          AND a.start_turn < COALESCE(b.end_turn, 32767) AND b.start_turn < COALESCE(a.end_turn, 32767)
        UNION ALL
        SELECT lu.id, 0::bigint,
               jsonb_build_object('kind', CASE WHEN lu.start_turn > p_turn THEN 'START_AFTER' ELSE 'END_AFTER' END, 'lineup_id', lu.id)
        FROM lineup lu
        WHERE lu.season_id = p_season_id AND (lu.start_turn > p_turn OR lu.end_turn > p_turn)
    )
    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0), pg_temp.recon_detail(a.items)
    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1, bad.k2) AS items FROM bad) a
$$;

-- ---------------------------------------------------------------------
-- 규칙 19 POSITION_VALUE — 포지션별 평가액(새 turn 행의 네 값): 턴 전환 전용이다 (규칙 11 과 같은 이유). 수동 검사에서는 돌리지 않고 기록하지 않는다.
--   P0-17 이 자기 정의 파일에 같은 4컬럼 모양의 함수로 둔다.
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- 수동 검사 15개를 한 문장으로 묶고 판정·차이·분류를 한 곳에서 붙인다.
--   p_turn(검사 턴): 수동 검사는 season.current_turn_no, 턴 전환은 넘어가려던 턴 t+1 (erd 6.3 turn_no). 규칙 12(가격을 볼 턴 범위)와 18(현재 턴 상한)만 쓴다.
--   diff_value = actual_value − expected_value (스칼라 5개, 나머지는 NULL), status = MATCH ⇔ mismatch_count = 0, 분류 = MISMATCH 면 INVESTIGATE.
--   검사 턴이 NULL 이거나 음수면 P0001 + HINT INVALID_ARGUMENT (턴이 path_length 를 넘는 것은 NO_RETURN 으로 보고하므로 거절하지 않는다). 시즌이 없으면 P0001 + HINT SEASON_NOT_FOUND (21 의 오류 형식). 결과는 rule_no 순 정확히 15행이다.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_rules(p_season_id bigint, p_turn smallint)
RETURNS TABLE (rule_no smallint, check_code varchar(20), status varchar(8), expected_value numeric(19,4), actual_value numeric(19,4),
               diff_value numeric(19,4), mismatch_count integer, classification varchar(11), detail jsonb)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
BEGIN
    IF p_turn IS NULL OR p_turn < 0 THEN
        RAISE EXCEPTION 'P0-16 대사: 검사 턴이 없거나 음수다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM season s WHERE s.id = p_season_id) THEN
        RAISE EXCEPTION 'P0-16 대사: 시즌이 없다' USING HINT = 'SEASON_NOT_FOUND';
    END IF;
    RETURN QUERY
    SELECT u.rule_no::smallint, c.check_code::varchar(20),
           (CASE WHEN u.mismatch_count = 0 THEN 'MATCH' ELSE 'MISMATCH' END)::varchar(8),
           u.expected_value, u.actual_value, u.actual_value - u.expected_value, u.mismatch_count,
           (CASE WHEN u.mismatch_count = 0 THEN NULL ELSE 'INVESTIGATE' END)::varchar(11),
           CASE WHEN u.mismatch_count = 0 THEN NULL ELSE u.detail END
    FROM (
        SELECT 1 AS rule_no, r.* FROM pg_temp.recon_r01_account_opening(p_season_id, p_turn) r
        UNION ALL SELECT 2,  r.* FROM pg_temp.recon_r02_cash_sum(p_season_id, p_turn) r
        UNION ALL SELECT 3,  r.* FROM pg_temp.recon_r03_ledger_chain(p_season_id, p_turn) r
        UNION ALL SELECT 4,  r.* FROM pg_temp.recon_r04_order_ledger(p_season_id, p_turn) r
        UNION ALL SELECT 6,  r.* FROM pg_temp.recon_r06_holding_qty(p_season_id, p_turn) r
        UNION ALL SELECT 7,  r.* FROM pg_temp.recon_r07_order_fill(p_season_id, p_turn) r
        UNION ALL SELECT 8,  r.* FROM pg_temp.recon_r08_exec_values(p_season_id, p_turn) r
        UNION ALL SELECT 9,  r.* FROM pg_temp.recon_r09_reserved_zero(p_season_id, p_turn) r
        UNION ALL SELECT 12, r.* FROM pg_temp.recon_r12_turn_price(p_season_id, p_turn) r
        UNION ALL SELECT 13, r.* FROM pg_temp.recon_r13_fill_price(p_season_id, p_turn) r
        UNION ALL SELECT 14, r.* FROM pg_temp.recon_r14_fee_rule(p_season_id, p_turn) r
        UNION ALL SELECT 15, r.* FROM pg_temp.recon_r15_formation(p_season_id, p_turn) r
        UNION ALL SELECT 16, r.* FROM pg_temp.recon_r16_formation_bind(p_season_id, p_turn) r
        UNION ALL SELECT 17, r.* FROM pg_temp.recon_r17_lineup_holding(p_season_id, p_turn) r
        UNION ALL SELECT 18, r.* FROM pg_temp.recon_r18_lineup_overlap(p_season_id, p_turn) r
    ) u
    JOIN pg_temp.recon_code c ON c.rule_no = u.rule_no
    ORDER BY u.rule_no;
END $$;

-- ---------------------------------------------------------------------
-- 결과 행 묶음(recon_rules 의 jsonb_agg, 키 = 컬럼 이름)을 새 시도 번호로 reconciliation_log 에 남긴다. 새 attempt_no 를 돌려준다.
--   순서: season FOR UPDATE(잠금 순서의 맨 앞 — 같은 시즌의 매수·턴 전환은 그동안 기다린다) → 그 (시즌, 턴)의 max(attempt_no) + 1 (없으면 1)
--   → INSERT … SELECT … FROM jsonb_to_recordset. 두 호출이 겹쳐 uq_reconciliation_log_season_turn_attempt_code 가 깨지면(23505) 다시 읽어 최대 3번 시도한다.
--   트랜잭션 문이 없다 — 호출자 몫이다. 결과를 jsonb 로 받는 이유: 턴 전환(P0-17)이 롤백 너머로 결과를 운반해야 한다.
--   임시 표에 넣은 결과는 롤백과 함께 사라지고, 롤백 뒤 다시 계산하면 깨진 상태가 이미 없다. 그래서 jsonb 지역 변수로 들고 건넌다 (Java TurnFacade 와 같은 구조).
--   시도 번호는 수동 검사와 턴 전환이 한 번호 공간을 쓴다 (erd 9절 P0-17).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION pg_temp.recon_log(p_season_id bigint, p_turn smallint, p_rows jsonb)
RETURNS smallint
LANGUAGE plpgsql AS $$
DECLARE
    v_attempt smallint;
    v_try     integer;
    v_ok      boolean;
    v_codes   text[];
BEGIN
    -- 검사 턴은 NULL·음수만 거절하고 상한(path_length·현재 턴)은 보지 않는다 — 호출자가 수동 검사는 현재 턴, 턴 전환은 t+1 만 넘긴다 (P0-17 이 기록된 턴을 단언한다).
    IF p_turn IS NULL OR p_turn < 0 THEN
        RAISE EXCEPTION 'P0-16 대사 기록: 검사 턴이 없거나 음수다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    IF p_rows IS NULL OR jsonb_typeof(p_rows) <> 'array' OR jsonb_array_length(p_rows) = 0 THEN
        RAISE EXCEPTION 'P0-16 대사 기록: 결과 행 묶음이 비어 있다' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    PERFORM 1 FROM season s WHERE s.id = p_season_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'P0-16 대사 기록: 시즌이 없다' USING HINT = 'SEASON_NOT_FOUND';
    END IF;
    -- 코드 집합 검사: 모든 행에 check_code 가 있고 서로 다르며, 집합이 수동 15개 또는 전체 17개와 같아야 한다 (불완전한 시도가 커밋되지 않게 기록 시점에 강제한다).
    --   수동 검사 행(15개)과 턴 전환 행(17개)을 코드 집합으로 구분하는 약속(erd D36)의 보증이다. 잠금 뒤·INSERT 전이라 거절해도 번호표를 쓰지 않는다.
    SELECT bool_and(e ? 'check_code') AND count(*) = count(DISTINCT e ->> 'check_code'),
           array_agg(e ->> 'check_code' ORDER BY e ->> 'check_code')
      INTO v_ok, v_codes
    FROM jsonb_array_elements(p_rows) e;
    IF v_ok IS NOT TRUE
       OR (v_codes IS DISTINCT FROM (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.manual)
           AND v_codes IS DISTINCT FROM (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c)) THEN
        RAISE EXCEPTION 'P0-16 대사 기록: 결과 행의 코드 집합이 수동 15개도 전체 17개도 아니다 (빠짐·중복·check_code 없음)' USING HINT = 'INVALID_ARGUMENT';
    END IF;
    FOR v_try IN 1..3 LOOP
        SELECT COALESCE(max(l.attempt_no), 0) + 1 INTO v_attempt
        FROM reconciliation_log l
        WHERE l.season_id = p_season_id AND l.turn_no = p_turn;
        BEGIN
            INSERT INTO reconciliation_log (season_id, turn_no, attempt_no, check_code, status, expected_value, actual_value, diff_value,
                                            mismatch_count, classification, detail)
            SELECT p_season_id, p_turn, v_attempt, r.check_code, r.status, r.expected_value, r.actual_value, r.diff_value,
                   r.mismatch_count, r.classification, r.detail
            FROM jsonb_to_recordset(p_rows) AS r(check_code text, status text, expected_value numeric, actual_value numeric, diff_value numeric,
                                                 mismatch_count integer, classification text, detail jsonb);
            RETURN v_attempt;
        EXCEPTION WHEN unique_violation THEN
            NULL;   -- 시도 번호가 겹쳤다. 다시 읽어 한 번 더 (잠금 아래에서는 일어나지 않는 안전망)
        END;
    END LOOP;
    RAISE EXCEPTION 'P0-16 대사 기록: 시도 번호를 3번 시도해도 정하지 못했다' USING HINT = 'ATTEMPT_CONFLICT';
END $$;
