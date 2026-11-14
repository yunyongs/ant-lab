-- =====================================================================
-- my Fund Manager Phase 0 — 턴 진행 검증 SQL (P0-17)
-- 대상: db/23_turn_advance_def.sql 의 가격 생성기·낙폭·스냅샷·규칙 11·19·17개 묶음·턴 적용과, db/23_turn_advance.sql 이 남기는 turn_price·turn·reconciliation_log 구조
-- 근거: docs/erd.md 6.3(turn·turn_price)·7.5(대사 규칙 1–19)·D16·D23·D24·D25·D26·D36·D37·9절 P0-17, docs/PLAN.md §4.5·§5.2, db/22_reconcile_season_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/23_turn_advance_check.sql
--       21 팀 구성까지 실행한 DB 에서 한다 (20 만 실행한 DB 는 0절 전제가 FAIL 이다). INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아
--       실습 DB(antlab)에서도 돌릴 수 있다. 정의 파일(21·22·23)을 \ir 로 읽으므로 같은 폴더에 있어야 한다.
--       범위: 진행 전(0턴)부터 FINISHED 까지 턴 전환 직후나 끝난 뒤 어느 시점에서도 PASS 해야 한다. 규칙 11·19 사례(양성·음성)는 "마지막 스냅샷 뒤 매매·포지션 변경 없음"
--       (현재 턴 ≥ 1 이면 orders.turn_no = 현재 턴이 0건이고 현재 턴에 시작·끝난 라인업 구간이 0건 — 주문 없는 단독 포지션 변경도 저장된 스냅샷과 다른 선언을 만든다)일 때만 의미가 있으므로
--       그 전제가 깨진 상태(턴 중간 매매·포지션 변경 뒤)는 0절이 범위 밖 신호로 멈춘다 — 헛 PASS 가 아니다. 다음 턴으로 넘기면 다시 PASS 다.
--       현재 턴이 0 이면 규칙 11·19 사례는 건너뛰고(정보 행) 커버리지에서 빼며, 규칙 12 사례(턴 0 가격)는 그대로 돈다.
--       음성 사례의 대상(현금·비골키퍼 포지션 평가액·가격 있는 보유 종목·열린 라인업)은 포트폴리오에 따라 없을 수 있다(P0-19: 초기 팀 전부 매도·전액 현금 등). 그 사례는 0절 FAIL 이 아니라
--       "적용 불가" 정보 행이고 통과 사례 수에 세지 않으며, 적용 가능한 사례가 없는 규칙(예: 전액 현금의 POSITION_VALUE)은 커버리지 요구에서 빠진다. 양성 불변식 검사는 생략하지 않는다.
--       규칙 12 전 행 사례는 turn_price 행마다 서브트랜잭션을 한 번씩 열어 시간이 걸린다 (10턴 뒤 220행 — 몇 초).
-- 판정: 마지막 줄 `p0_17_check` 가 `PASS` 이고 종료 코드가 0이면 통과다. 0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고,
--       그 밖에 하나라도 어긋나면 09절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 09절 뒤에 ROLLBACK 한다. 10절이 행 수·identity 마지막 값이 실행 전과 같은지 확인한다.
-- 절:   00 전제(FAIL 이면 여기서 멈춤) → 01 진행 상태 계약 → 02 가격 — 네 구현 → 03 스냅샷 — 전 턴 재구성 → 04 사후 정보 → 05 양성 → 06 음성 → 07 거부 사례 → 08 단위
--       → 09 정보·판정 → ROLLBACK → 10 흔적
-- 판정의 틀 (단독성): 음성 사례마다 서브트랜잭션 안에서 바꾸고 17행(0턴이면 15행)을 읽어 "불일치 코드 집합 = 기대 집합"인지 본다 (22 와 같음).
--       규칙 11·19 는 서로 함의가 있다: 보유 평가액·총액·공격을 같이 바꾸면 {11, 19}, 가격이 현재 턴 보유 종목이면 {11, 12, 19} 이다.
-- 이 파일이 INSERT 없이 시험하지 못하는 것 (드릴 db/23_turn_advance_drill.sql 이 버리는 DB 에서 한다): 실제 ROLLBACK 을 지나 로그가 남는 경로, 시도 번호 2.
-- 출력: 건수와 판정만. 가격·수량·금액은 내지 않는다 (08절 단위 사례는 합성 값이라 시세가 아니다).
-- 판정 계약 (22 판정 계약에서 복사): ① pg_temp.zz_verdict ② 결과 표 zz_check_result ④ check_eq ⑤ 0절 뒤 멈춤과 판정(표식 2 또는 1) ⑥ 10절 흔적 확인(표식 0)과 마지막 CASE.
-- 새 문법: 재귀 CTE의 독립 검증(창 함수 배열·절차형 반복문으로 같은 값을 다시 만들기), 서브트랜잭션으로 "바꿔 보고 되돌리기", 함수 정의 바꿔치기(변이) 시험,
--       체결 이력에서 과거 스냅샷 다시 만들기(generate_series × 시점 조건).
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\ir 21_team_buy_def.sql
\ir 22_reconcile_season_def.sql
\ir 24_turn_feed_def.sql
\ir 23_turn_advance_def.sql

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 00–09절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

-- 흔적 확인용 스냅샷: 읽거나 일부러 바꿔 보는 표들의 행 수와 identity 마지막 값. 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE OR REPLACE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    c text;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'turn_price', 'turn', 'formation_change',
                             'reconciliation_log', 'season_path', 'asset_position', 'asset_month_return', 'fx_month'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            v := v || t || '=없음;';
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
        SELECT attname INTO c FROM pg_attribute WHERE attrelid = ('public.' || t)::regclass AND attidentity <> '' LIMIT 1;
        s := CASE WHEN c IS NULL THEN '-' ELSE COALESCE(pg_sequence_last_value(pg_get_serial_sequence('public.' || t, c)::regclass)::text, 'null') END;
        v := v || t || '=' || n || '/' || s || ';';
    END LOOP;
    RETURN v;
END $$;
SET client_min_messages TO warning;
DROP TABLE IF EXISTS pg_temp.zz_before;   -- 정리용 DROP 은 항상 pg_temp. 로 한정한다 (임시 표가 없을 때 public 의 같은 이름 표를 지우지 않게)
RESET client_min_messages;
CREATE TEMP TABLE zz_before AS SELECT pg_temp.zz_snap() AS snap;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '00 전제' … '09 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

-- 연습 시즌 찾기: 20·21·22 파일과 같은 이메일
CREATE TEMP VIEW zz_cur AS
SELECT s.* FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');

-- 음성 사례가 바꿀 대상과 시점 값: 현재 턴 c, 경로 길이, 풀 크기, 현재 턴에 가격이 있는 보유 종목(가장 작은 번호), 열린 라인업
-- 바꾸는 문장이 실행될 때마다 다시 계산된다 (바뀐 뒤 상태로). 사례는 대상을 한 번 고른 뒤 바꾸는 순서를 지킨다.
CREATE TEMP VIEW zz_t AS
SELECT c.id AS season_id, c.current_turn_no AS turn_no, c.path_length, c.status,
       (SELECT count(*) FROM asset_position ap WHERE ap.base_date = c.base_date) AS pool,
       (SELECT min(h.asset_id) FROM holding h
        WHERE h.season_id = c.id AND h.quantity > 0
          AND EXISTS (SELECT 1 FROM turn_price tp WHERE tp.season_id = c.id AND tp.turn_no = c.current_turn_no AND tp.asset_id = h.asset_id)) AS held_asset,
       (SELECT min(lu.id) FROM lineup lu WHERE lu.season_id = c.id AND lu.end_turn IS NULL) AS open_lineup,
       (SELECT min(tp.asset_id) FROM turn_price tp WHERE tp.season_id = c.id AND tp.turn_no = c.current_turn_no) AS any_asset
FROM zz_cur c;

-- 로그 제약 사본: reconciliation_log 의 CHECK 를 복사한 임시 표 (FK·PK·UNIQUE 는 복사되지 않는다). id 는 기본값이 없으므로 넣을 때 정한다.
CREATE TEMP TABLE zz_log (LIKE public.reconciliation_log INCLUDING DEFAULTS INCLUDING CONSTRAINTS);

-- 판정 프로시저 ------------------------------------------------------------

-- 함수로 둔 이유: 프로시저의 CALL 인자에는 서브쿼리를 쓸 수 없다 (SELECT 인자에는 쓸 수 있다)
CREATE FUNCTION pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- 위반 개수를 돌려주는 쿼리 한 개를 실행해 bigint 로 돌려준다
CREATE FUNCTION pg_temp.cnt(p_sql text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE v bigint;
BEGIN
    EXECUTE p_sql INTO v;
    RETURN v;
END $$;

-- 현재 턴의 대사 행: 현재 턴 ≥ 1 이면 17행, 0 이면 규칙 11·19 를 뺀 15행 (턴 0 에는 turn 행이 없어 11·19 가 의미 없다)
CREATE FUNCTION pg_temp.zz_rules(p_season_id bigint, p_turn smallint)
RETURNS TABLE (rule_no smallint, check_code varchar(20), status varchar(8), expected_value numeric(19,4), actual_value numeric(19,4),
               diff_value numeric(19,4), mismatch_count integer, classification varchar(11), detail jsonb)
LANGUAGE sql STABLE AS $$
    SELECT r.rule_no, r.check_code, r.status, r.expected_value, r.actual_value, r.diff_value, r.mismatch_count, r.classification, r.detail
    FROM pg_temp.recon_rules_turn(p_season_id, p_turn) r
    WHERE p_turn >= 1 OR r.rule_no NOT IN (11, 19)
$$;

-- 절차형 반복 곱: 세 번째 독립 가격 구현 (재귀 CTE ta_prices · 창 함수 배열 recon_expected_price 와 다른 코드).
--   종목마다 저장된 0턴 가격에서 시작해 턴 1..p_k 를 FOR 루프로 acc := acc × (1 + 현지)(1 + 환율), 마지막에 한 번 ROUND.
--   p_monthly 가 참이면 매달 ROUND 한다 ("매달 반올림"과 다른 칸을 세기 위한 대조 구현 — 저장되는 값이 아니다).
--   수익률·환율·블록이 없으면 그 종목은 행을 내지 않는다.
CREATE FUNCTION pg_temp.zz_price_loop(p_season_id bigint, p_k smallint, p_monthly boolean)
RETURNS TABLE (asset_id bigint, price numeric)
LANGUAGE plpgsql STABLE AS $$
#variable_conflict use_column
DECLARE
    r     record;
    v_acc numeric;
    v_j   smallint;
    v_blk bigint;
    v_lr  numeric;
    v_fx  numeric;
    v_ok  boolean;
BEGIN
    FOR r IN SELECT tp.asset_id AS a, x.currency AS cur, tp.price AS p0
             FROM turn_price tp JOIN asset x ON x.id = tp.asset_id
             WHERE tp.season_id = p_season_id AND tp.turn_no = 0
             ORDER BY tp.asset_id LOOP
        v_acc := r.p0;
        v_ok := true;
        FOR v_j IN 1..p_k LOOP
            SELECT sp.month_block_id INTO v_blk FROM season_path sp WHERE sp.season_id = p_season_id AND sp.turn_no = v_j;
            IF NOT FOUND THEN v_ok := false; EXIT; END IF;
            SELECT amr.local_return INTO v_lr FROM asset_month_return amr WHERE amr.asset_id = r.a AND amr.month_block_id = v_blk;
            IF NOT FOUND THEN v_ok := false; EXIT; END IF;
            v_fx := 0;
            IF r.cur <> 'KRW' THEN
                SELECT fx.fx_change INTO v_fx FROM fx_month fx WHERE fx.month_block_id = v_blk AND fx.currency = r.cur;
                IF NOT FOUND THEN v_ok := false; EXIT; END IF;
            END IF;
            v_acc := v_acc * (1 + v_lr) * (1 + v_fx);
            IF p_monthly THEN
                v_acc := ROUND(v_acc, 0);
            END IF;
        END LOOP;
        IF v_ok THEN
            asset_id := r.a;
            price := ROUND(v_acc, 0);
            RETURN NEXT;
        END IF;
    END LOOP;
END $$;

-- 음성 사례: 서브트랜잭션 안에서 p_mutate 를 실행하고 zz_rules(연습 시즌, 현재 턴) 행을 읽는다.
--   ① 불일치 규칙 번호 집합 = p_expect (단독성)  ② 행 모양: MATCH 는 분류·상세 NULL·불일치 0, MISMATCH 는 INVESTIGATE·상세 있음·total = 불일치 수·항목 ≤ 100
--   ③ p_kind 가 있으면 대상 규칙(p_code)의 detail 항목에 그 종류가 있다  ④ 행이 reconciliation_log 제약 사본 zz_log 의 CHECK 를 통과한다.
--   끝나면 RAISE 로 서브트랜잭션을 되돌린다. 코드(p_code)는 대상 규칙의 check_code 이고 09절 커버리지가 이 이름을 센다.
CREATE PROCEDURE pg_temp.neg_rule(p_code text, p_label text, p_mutate text, p_expect int[], p_kind text)
LANGUAGE plpgsql AS $$
DECLARE
    v_season bigint;
    v_turn   smallint;
    v_rule   smallint;
    v_got    int[];
    v_shape  boolean;
    v_kind   boolean;
    v_log    boolean;
    v_note   text := '';
    v_err    text;
    v_ok     boolean;
BEGIN
    SELECT t.season_id, t.turn_no INTO v_season, v_turn FROM zz_t t;
    SELECT c.rule_no INTO v_rule FROM pg_temp.recon_code c WHERE c.check_code = p_code;
    BEGIN
        EXECUTE p_mutate;
        SELECT COALESCE(array_agg(r.rule_no::int ORDER BY r.rule_no) FILTER (WHERE r.status = 'MISMATCH'), ARRAY[]::int[]) INTO v_got
        FROM pg_temp.zz_rules(v_season, v_turn) r;
        SELECT COALESCE(bool_and(
                   (r.status = 'MATCH' AND r.classification IS NULL AND r.detail IS NULL AND r.mismatch_count = 0)
                OR (r.status = 'MISMATCH' AND r.classification = 'INVESTIGATE' AND r.detail IS NOT NULL
                    AND (r.detail ->> 'total')::int = r.mismatch_count
                    AND jsonb_array_length(r.detail -> 'items') = LEAST(r.mismatch_count, 100)
                    AND r.mismatch_count >= 1)), false) INTO v_shape
        FROM pg_temp.zz_rules(v_season, v_turn) r;
        v_kind := true;
        IF p_kind IS NOT NULL THEN
            SELECT COALESCE(bool_or(jsonb_path_exists(r.detail, '$.items[*] ? (@.kind == $k || @.kinds[*] == $k)', jsonb_build_object('k', p_kind))), false) INTO v_kind
            FROM pg_temp.zz_rules(v_season, v_turn) r WHERE r.rule_no = v_rule;
        END IF;
        BEGIN
            INSERT INTO zz_log (id, season_id, turn_no, attempt_no, check_code, status, expected_value, actual_value, diff_value,
                                mismatch_count, classification, detail)
            SELECT r.rule_no, v_season, v_turn, 1, r.check_code, r.status, r.expected_value, r.actual_value, r.diff_value,
                   r.mismatch_count, r.classification, r.detail
            FROM pg_temp.zz_rules(v_season, v_turn) r;
            v_log := true;
        EXCEPTION WHEN OTHERS THEN
            v_log := false;
            v_note := ' (로그 제약 ' || SQLSTATE || ')';
        END;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            v_err := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
        END IF;
    END;
    IF v_err IS NOT NULL THEN
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('06 음성', '음성 [' || p_code || '] ' || p_label, '불일치 규칙 ' || array_to_string(p_expect, ','), v_err, false);
        RETURN;
    END IF;
    v_ok := v_got = p_expect AND v_shape AND v_kind AND v_log;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('06 음성', '음성 [' || p_code || '] ' || p_label ||
                       CASE WHEN p_kind IS NULL THEN '' ELSE ' (종류 ' || p_kind || ')' END,
            '불일치 규칙 {' || array_to_string(p_expect, ',') || '}',
            '불일치 규칙 {' || array_to_string(v_got, ',') || '}' ||
                CASE WHEN NOT v_shape THEN ' (판정 모양 어긋남)' ELSE '' END ||
                CASE WHEN NOT v_kind  THEN ' (종류 없음)' ELSE '' END || v_note,
            v_ok);
END $$;

-- 규칙 12 전 행 사례: 저장된 turn_price 의 모든 행(턴 0..현재 턴 × 풀)을 한 행씩 +1 해 보고 불일치 규칙 집합이 기대와 같은지 본다 (하류 ⑤ 실데이터 전 행 음성).
--   기대 집합 = {12} (종류 PRICE, 그 (턴, 종목) 항목 1개)
--             ∪ {13}      그 (턴, 종목)에 체결 주문이 있을 때 (체결가 ≠ 턴 가격)
--             ∪ {11, 19}  그 행이 현재 턴(≥ 1)이고 그 종목을 보유 중일 때 (11: 보유 평가액이 달라짐, 19: 그 종목이 열린 라인업이 있으면 포지션 합이 달라진다)
--   19 는 그 종목에 열린 라인업(start_turn < 현재 턴)이 있을 때만 걸린다. 결과는 한 행으로 모은다: 사례 수와 어긋난 사례 수.
CREATE PROCEDURE pg_temp.neg_price_rows()
LANGUAGE plpgsql AS $$
DECLARE
    v_season bigint;
    v_turn   smallint;
    r        record;
    v_got    int[];
    v_exp    int[];
    v_item   boolean;
    v_n      integer := 0;
    v_bad    integer := 0;
    v_err    text;
BEGIN
    SELECT t.season_id, t.turn_no INTO v_season, v_turn FROM zz_t t;
    FOR r IN SELECT tp.id, tp.turn_no AS k, tp.asset_id AS a,
                    EXISTS (SELECT 1 FROM orders o WHERE o.season_id = tp.season_id AND o.turn_no = tp.turn_no AND o.asset_id = tp.asset_id) AS has_order,
                    (v_turn >= 1 AND tp.turn_no = v_turn
                     AND EXISTS (SELECT 1 FROM holding h WHERE h.season_id = tp.season_id AND h.asset_id = tp.asset_id AND h.quantity > 0)) AS held_now,
                    EXISTS (SELECT 1 FROM lineup lu WHERE lu.season_id = tp.season_id AND lu.asset_id = tp.asset_id AND lu.end_turn IS NULL AND lu.start_turn < v_turn) AS in_lineup
             FROM turn_price tp
             WHERE tp.season_id = v_season AND tp.turn_no <= v_turn
             ORDER BY tp.turn_no, tp.asset_id LOOP
        v_n := v_n + 1;
        v_exp := ARRAY[12] || CASE WHEN r.has_order THEN ARRAY[13] ELSE ARRAY[]::int[] END
                           || CASE WHEN r.held_now THEN ARRAY[11] ELSE ARRAY[]::int[] END
                           || CASE WHEN r.held_now AND r.in_lineup THEN ARRAY[19] ELSE ARRAY[]::int[] END;
        SELECT COALESCE(array_agg(x ORDER BY x), ARRAY[]::int[]) INTO v_exp FROM unnest(v_exp) x;
        v_err := NULL;
        BEGIN
            UPDATE turn_price SET price = price + 1 WHERE id = r.id;
            SELECT COALESCE(array_agg(q.rule_no::int ORDER BY q.rule_no) FILTER (WHERE q.status = 'MISMATCH'), ARRAY[]::int[]) INTO v_got
            FROM pg_temp.zz_rules(v_season, v_turn) q;
            SELECT COALESCE(bool_or(jsonb_path_exists(q.detail, '$.items[*] ? (@.kind == "PRICE" && @.turn_no == $t && @.asset_id == $a)',
                                                      jsonb_build_object('t', r.k, 'a', r.a)) AND q.mismatch_count = 1), false) INTO v_item
            FROM pg_temp.zz_rules(v_season, v_turn) q WHERE q.rule_no = 12;
            RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
        EXCEPTION WHEN OTHERS THEN
            IF SQLERRM <> 'zz_undo' THEN
                v_err := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
            END IF;
        END;
        IF v_err IS NOT NULL OR v_got IS DISTINCT FROM v_exp OR v_item IS NOT TRUE THEN
            v_bad := v_bad + 1;
        END IF;
    END LOOP;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('06 음성', '음성 [TURN_PRICE] N12 turn_price 의 모든 행(턴 0..현재 턴 × 풀)을 한 행씩 +1 하면 기대 규칙 집합이고 12 의 항목이 그 (턴, 종목) 하나다 (사례 수 ' || v_n || ')',
            '어긋난 사례 0 · 사례 ≥ 1', '어긋난 사례 ' || v_bad || ' · 사례 ' || v_n, v_bad = 0 AND v_n >= 1);
END $$;

-- 변이 시험: 정의 함수를 바꿔치기(서브트랜잭션이라 되돌아간다)한 뒤 p_probe 쿼리가 p_min 건(기대와 달라지는 사례 수) 이상 나와야 한다.
CREATE PROCEDURE pg_temp.neg_unit(p_section text, p_label text, p_mutate text, p_probe text, p_min bigint)
LANGUAGE plpgsql AS $$
DECLARE
    v_n text;
BEGIN
    BEGIN
        EXECUTE p_mutate;
        v_n := pg_temp.cnt(p_probe)::text;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            v_n := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
        END IF;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, '변이 ' || p_label || ' — 사례 어긋남 ' || p_min || '건 이상', '≥ ' || p_min, v_n,
            v_n ~ '^[0-9]+$' AND v_n::bigint >= p_min);
END $$;

-- 거부 사례: 서브트랜잭션 안에서 p_mutate(없으면 생략)를 실행하고 p_call 이 P0001 과 p_hint 로 실패하는지 본다. 끝나면 되돌린다.
--   p_call 은 쓰기 전 단계(ta_precheck 등)만 부른다 — 쓰기가 있었다면 10절 흔적(identity)이 잡는다.
CREATE PROCEDURE pg_temp.reject_case(p_label text, p_mutate text, p_call text, p_hint text)
LANGUAGE plpgsql AS $$
DECLARE
    v_act  text;
    v_hint text;
BEGIN
    BEGIN
        IF p_mutate IS NOT NULL THEN
            EXECUTE p_mutate;
        END IF;
        BEGIN
            EXECUTE p_call;
            v_act := '오류 없음';
        EXCEPTION WHEN OTHERS THEN
            GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
            v_act := SQLSTATE || ' ' || COALESCE(v_hint, '-');
        END;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN
            v_act := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
        END IF;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('07 거부', p_label, 'P0001 ' || p_hint, v_act, v_act = 'P0001 ' || p_hint);
END $$;

-- 00 전제 -------------------------------------------------------------------
-- 하나라도 FAIL 이면 01절 이후 결과를 믿지 않는다.

CREATE TEMP TABLE zz_base AS
SELECT r.* FROM pg_temp.zz_rules((SELECT season_id FROM zz_t), (SELECT turn_no FROM zz_t)) r;

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('격리 수준이 READ COMMITTED 다 (한 스냅샷 가정의 전제)', 'read committed', current_setting('transaction_isolation')),
    ('17개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '17',
        (SELECT count(*)::text FROM unnest(ARRAY['member', 'season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'formation_change',
                                                 'turn_price', 'season_path', 'asset', 'asset_position', 'asset_month_return', 'fx_month', 'reconciliation_log', 'turn']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('정의 파일을 읽었다 (ta_apply·turn_advance·recon_rules_turn·order_buy 가 있다)', '4',
        ((to_regprocedure('pg_temp.ta_apply(bigint, smallint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.turn_advance(bigint, smallint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.recon_rules_turn(bigint, smallint)') IS NOT NULL)::int
       + (to_regprocedure('pg_temp.order_buy(bigint, smallint, bigint, integer, text, text)') IS NOT NULL)::int)::text),
    ('연습 시즌이 정확히 1개 있다 (db/20_season_start.sql 을 먼저 실행한다)', '1', (SELECT count(*)::text FROM zz_cur)),
    ('0턴 매수 주문이 있다 (없으면 db/21_team_buy.sql 을 먼저 실행한다)', 'true', ((SELECT count(*) FROM orders o JOIN zz_cur c ON c.id = o.season_id WHERE o.turn_no = 0 AND o.side = 'BUY') > 0)::text),
    ('범위: 현재 턴이 0 이거나 현재 턴의 주문·라인업 구간 변경이 없다 (마지막 스냅샷 뒤 매매·포지션 변경 없음 — 있으면 규칙 11·19 의 전제가 깨진 범위 밖)', 'true',
        ((SELECT turn_no FROM zz_t) = 0
         OR (NOT EXISTS (SELECT 1 FROM orders o JOIN zz_t t ON t.season_id = o.season_id AND o.turn_no = t.turn_no)
             AND NOT EXISTS (SELECT 1 FROM lineup lu JOIN zz_t t ON t.season_id = lu.season_id AND (lu.start_turn = t.turn_no OR lu.end_turn = t.turn_no))))::text),
    ('현재 턴이 0 이면 상태는 진행 중이고 turn 행이 없다', 'true',
        ((SELECT turn_no FROM zz_t) > 0 OR (NOT EXISTS (SELECT 1 FROM turn t JOIN zz_t z ON z.season_id = t.season_id) AND (SELECT status FROM zz_t) = 'IN_PROGRESS'))::text),
    ('현재 턴 ≥ 1 이면 그 turn 행이 있다 (현금·미드필더 평가액·보유·열린 구간이 있는지는 전제가 아니라 사례별 적용 가능성이다 — 06절)', 'true',
        ((SELECT turn_no FROM zz_t) = 0 OR EXISTS (SELECT 1 FROM turn t JOIN zz_t z ON z.season_id = t.season_id AND z.turn_no = t.turn_no))::text),
    ('경로 블록이 path_length 개다 (season_path)', 'true',
        ((SELECT count(*) FROM season_path sp JOIN zz_cur c ON c.id = sp.season_id) = (SELECT path_length FROM zz_cur))::text),
    ('zz_log 의 CHECK 가 9개다 (LIKE … INCLUDING CONSTRAINTS)', '9', (SELECT count(*)::text FROM pg_constraint WHERE conrelid = 'zz_log'::regclass AND contype = 'c')),
    ('양성: 연습 시즌의 현재 턴 대사가 모두 일치한다 (현재 턴 ≥ 1 이면 17개, 0 이면 15개 — 깨져 있으면 음성 결과를 믿지 않는다)',
        (SELECT CASE WHEN turn_no >= 1 THEN '17/17' ELSE '15/15' END FROM zz_t),
        (SELECT count(*) FILTER (WHERE b.status = 'MATCH')::text || '/' || count(*)::text FROM zz_base b))
) AS x(item, expected, actual);

INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('00 전제', '현재 DB 이름 (정보)', '(정보)', current_database(), true);
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', '음성 사례 적용 가능성 (정보): 가격 있는 보유 종목 · 열린 라인업 · 현금 ≥ 1 · 비골키퍼 포지션 평가액 ≥ 1', '(정보)',
       ((SELECT held_asset FROM zz_t) IS NOT NULL)::text || ' · ' || ((SELECT open_lineup FROM zz_t) IS NOT NULL)::text || ' · '
       || COALESCE((SELECT (t.cash_balance >= 1)::text FROM turn t JOIN zz_t z ON z.season_id = t.season_id AND z.turn_no = t.turn_no), '-') || ' · '
       || COALESCE((SELECT (t.defender_value + t.midfielder_value + t.forward_value >= 1)::text FROM turn t JOIN zz_t z ON z.season_id = t.season_id AND z.turn_no = t.turn_no), '-'), true;

SELECT seq, item, expected, actual, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result
FROM zz_check_result
WHERE section = '00 전제'
ORDER BY seq;

DO $$
DECLARE
    v_fail integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok) INTO v_fail FROM zz_check_result WHERE section = '00 전제';
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-17 전제 실패: 00절 FAIL % 건 (위의 00절 결과를 본다. 01·02·03 스키마와 10 적재, 12 판정, 20 시즌 시작, 21 팀 구성을 먼저 실행했는지 확인한다)', v_fail;
    END IF;
END $$;

\o /dev/null
-- 01 진행 상태 계약 ---------------------------------------------------------------
-- c = 현재 턴, L = 경로 길이, pool = 기준일 풀 크기.
SELECT pg_temp.check_eq('01 진행 상태', 'turn 행이 1..현재 턴에 빈틈 없이 하나씩 있다 (행 수 = 현재 턴, 범위 밖 행 0)', 'true',
    ((SELECT count(*) FROM turn t JOIN zz_t z ON z.season_id = t.season_id) = (SELECT turn_no FROM zz_t)
     AND NOT EXISTS (SELECT 1 FROM turn t JOIN zz_t z ON z.season_id = t.season_id WHERE t.turn_no NOT BETWEEN 1 AND z.turn_no))::text);
SELECT pg_temp.check_eq('01 진행 상태', 'turn_price 는 0..현재 턴의 모든 턴에 풀 크기만큼 행이 있다 (어긋난 턴 수)', '0',
    (SELECT count(*) FROM zz_t z, generate_series(0, z.turn_no) g(k)
     WHERE (SELECT count(*) FROM turn_price tp WHERE tp.season_id = z.season_id AND tp.turn_no = g.k) <> z.pool)::text);
SELECT pg_temp.check_eq('01 진행 상태', '미래 가격·미래 스냅샷이 없다 (turn_no > 현재 턴인 turn_price·turn 행)', '0',
    ((SELECT count(*) FROM turn_price tp JOIN zz_t z ON z.season_id = tp.season_id WHERE tp.turn_no > z.turn_no)
     + (SELECT count(*) FROM turn t JOIN zz_t z ON z.season_id = t.season_id WHERE t.turn_no > z.turn_no))::text);
SELECT pg_temp.check_eq('01 진행 상태', 'status = FINISHED ⇔ 현재 턴 = 경로 길이', 'true',
    ((SELECT status = 'FINISHED' FROM zz_t) = (SELECT turn_no = path_length FROM zz_t))::text);

-- 로그 계약 (턴 전환 시도 = 코드 17개 전체인 (턴, 시도) 묶음):
--  ① 1..c 의 각 턴에 "17개 전부 MATCH 인 시도"가 정확히 하나 ② (단언 아님) 성공보다 번호가 큰 실패 시도는 있을 수 있다 —
--     실패 기록은 ROLLBACK 뒤 잠금을 다시 얻어 쓰므로 그 사이 다른 세션의 성공이 먼저 번호를 받는다. 09절 정보 행이 센다
--  ③ c+1 턴에는 전부 MATCH 인 시도가 없다 (실패한 시도만) ④ 턴 0 과 c+2 이상에는 17개 시도가 없다
--  ⑤ 모든 (턴, 시도)는 수동 15개 집합이거나 17개 전체 집합이다 ⑥ 한 시도에 같은 코드가 둘 이상 나오지 않는다
CREATE TEMP TABLE zz_att AS
SELECT l.turn_no, l.attempt_no, array_agg(l.check_code::text ORDER BY l.check_code) AS codes,
       count(*) FILTER (WHERE l.status = 'MISMATCH') AS bad, count(*) AS n
FROM reconciliation_log l JOIN zz_cur c ON c.id = l.season_id
GROUP BY l.turn_no, l.attempt_no;
CREATE TEMP VIEW zz_att17 AS
SELECT a.* FROM zz_att a WHERE a.codes = (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c);

SELECT pg_temp.check_eq('01 진행 상태', '로그 ① 1..현재 턴의 각 턴에 17개 전부 MATCH 인 시도가 정확히 하나다 (어긋난 턴 수)', '0',
    (SELECT count(*) FROM zz_t z, generate_series(1, z.turn_no) g(k)
     WHERE (SELECT count(*) FROM zz_att17 a WHERE a.turn_no = g.k AND a.bad = 0) <> 1)::text);
SELECT pg_temp.check_eq('01 진행 상태', '로그 ③ 현재 턴 + 1 에는 전부 MATCH 인 17개 시도가 없다 (실패한 시도만 허용)', '0',
    (SELECT count(*) FROM zz_att17 a JOIN zz_t z ON a.turn_no = z.turn_no + 1 WHERE a.bad = 0)::text);
SELECT pg_temp.check_eq('01 진행 상태', '로그 ④ 턴 0 과 현재 턴 + 2 이상에는 17개 시도가 없다', '0',
    (SELECT count(*) FROM zz_att17 a JOIN zz_t z ON a.turn_no = 0 OR a.turn_no >= z.turn_no + 2)::text);
SELECT pg_temp.check_eq('01 진행 상태', '로그 ⑤ 모든 (턴, 시도)가 수동 15개 집합이거나 17개 전체 집합이다 (아닌 묶음 수)', '0',
    (SELECT count(*) FROM zz_att a
     WHERE a.codes IS DISTINCT FROM (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.manual)
       AND a.codes IS DISTINCT FROM (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c))::text);
SELECT pg_temp.check_eq('01 진행 상태', '로그 ⑥ 한 시도에 같은 코드가 두 번 나온 묶음 수 (행 수 ≠ 코드 수)', '0',
    (SELECT count(*) FROM zz_att a WHERE a.n <> cardinality(a.codes))::text);
SELECT pg_temp.check_eq('01 진행 상태', '로그 ⑦ 규칙 11·19 코드가 들어 있는 시도는 모두 17개 전체 집합이다 (15개와 17개가 섞인 묶음 수)', '0',
    (SELECT count(*) FROM zz_att a
     WHERE ('VALUATION' = ANY (a.codes) OR 'POSITION_VALUE' = ANY (a.codes))
       AND a.codes IS DISTINCT FROM (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c))::text);
SELECT pg_temp.check_eq('01 진행 상태', '로그 ⑧ 17개 시도의 행 수 = 17 이고 MATCH 행은 분류가 NULL, MISMATCH 행은 INVESTIGATE 다 (어긋난 행 수)', '0',
    (SELECT count(*) FROM reconciliation_log l JOIN zz_cur c ON c.id = l.season_id JOIN zz_att17 a ON a.turn_no = l.turn_no AND a.attempt_no = l.attempt_no
     WHERE (l.status = 'MATCH') <> (l.classification IS NULL) OR (l.status = 'MISMATCH' AND l.classification <> 'INVESTIGATE'))::text);
SELECT pg_temp.check_eq('01 진행 상태', '피드 ① 완료 턴마다 5–15행·seq 연속이다', '0',
    (SELECT count(*) FROM (SELECT f.turn_no FROM turn_feed f JOIN zz_cur c ON c.id = f.season_id
                           GROUP BY f.turn_no HAVING count(*) NOT BETWEEN 5 AND 15 OR min(f.seq) <> 1 OR max(f.seq) <> count(*)) x)::text);
SELECT pg_temp.check_eq('01 진행 상태', '피드 ② 0턴·현재 턴 이후 행이 없다', '0',
    (SELECT count(*) FROM turn_feed f JOIN zz_cur c ON c.id = f.season_id
      WHERE f.turn_no < 1 OR f.turn_no > c.current_turn_no)::text);

-- 02 가격 — 네 구현 -------------------------------------------------------------------
-- 저장된 turn_price(k ≥ 1)가 ① ta_prices(재귀 CTE) ② recon_expected_price(창 함수 배열 — 22 의 독립 구현) ③ zz_price_loop(절차형 반복 곱)와 전 행 같다.
-- ④ 네 번째(Python Fraction 정확 계산)는 QA 가 다른 도구로 한다. 반올림은 마지막 한 번 — 매달 반올림한 값과 다른 칸이 있어야 이 검증이 의미 있다.
SELECT pg_temp.check_eq('02 가격', '① 재귀 CTE ta_prices(시즌, k) = 저장된 turn_price (k = 1..현재 턴, 어긋난 (턴, 종목) 칸 수)', '0',
    (SELECT count(*) FROM (
         SELECT g.k, COALESCE(x.asset_id, tp.asset_id) AS a
         FROM zz_t z
         CROSS JOIN LATERAL generate_series(1, z.turn_no) g(k)
         CROSS JOIN LATERAL pg_temp.ta_prices(z.season_id, g.k::smallint) x
         FULL JOIN (SELECT t2.turn_no, t2.asset_id, t2.price FROM turn_price t2 WHERE t2.season_id = (SELECT season_id FROM zz_t) AND t2.turn_no BETWEEN 1 AND (SELECT turn_no FROM zz_t)) tp
                ON tp.turn_no = g.k AND tp.asset_id = x.asset_id
         WHERE x.price IS DISTINCT FROM tp.price) d)::text);
SELECT pg_temp.check_eq('02 가격', '② 창 함수 배열 recon_expected_price(시즌, 현재 턴) = 저장된 turn_price (턴 0..현재 턴, 어긋난 칸 수)', '0',
    (SELECT count(*) FROM zz_t z
     CROSS JOIN LATERAL (SELECT e.turn_no, e.asset_id, e.price AS ep, tp.price AS sp
                         FROM pg_temp.recon_expected_price(z.season_id, z.turn_no) e
                         FULL JOIN (SELECT t2.turn_no, t2.asset_id, t2.price FROM turn_price t2 WHERE t2.season_id = z.season_id AND t2.turn_no <= z.turn_no) tp
                                ON tp.turn_no = e.turn_no AND tp.asset_id = e.asset_id) d
     WHERE d.ep IS DISTINCT FROM d.sp)::text);
SELECT pg_temp.check_eq('02 가격', '③ 절차형 반복 곱 zz_price_loop(시즌, k) = 저장된 turn_price (k = 1..현재 턴, 어긋난 칸 수)', '0',
    (SELECT count(*) FROM (
         SELECT g.k, COALESCE(x.asset_id, tp.asset_id) AS a
         FROM zz_t z
         CROSS JOIN LATERAL generate_series(1, z.turn_no) g(k)
         CROSS JOIN LATERAL pg_temp.zz_price_loop(z.season_id, g.k::smallint, false) x
         FULL JOIN (SELECT t2.turn_no, t2.asset_id, t2.price FROM turn_price t2 WHERE t2.season_id = (SELECT season_id FROM zz_t) AND t2.turn_no BETWEEN 1 AND (SELECT turn_no FROM zz_t)) tp
                ON tp.turn_no = g.k AND tp.asset_id = x.asset_id
         WHERE x.price IS DISTINCT FROM tp.price) d)::text);
SELECT pg_temp.check_eq('02 가격', '풀 종목마다 모든 턴에 가격이 있고 양수·원 단위다 (어긋난 행 수)', '0',
    (SELECT count(*) FROM turn_price tp JOIN zz_t z ON z.season_id = tp.season_id WHERE tp.price <= 0 OR tp.price <> ROUND(tp.price, 0))::text);

-- "매달 반올림"과 다른 칸 수: 같은 입력에서 매달 ROUND 한 값 ≠ 저장값인 (턴, 종목) 칸. 정확한 곱을 마지막에 한 번 반올림했다는 증거다.
--   1턴은 곱이 한 번이라 매달 반올림과 같다. 2턴 이상부터 달라질 수 있고, 현재 턴 ≥ 2 이면 1 칸 이상이어야 한다 (이 표본에서는 그렇다).
CREATE TEMP TABLE zz_monthly AS
SELECT g.k AS k, count(*) FILTER (WHERE m.price IS DISTINCT FROM tp.price) AS diff, count(*) AS cells
FROM zz_t z
CROSS JOIN LATERAL generate_series(1, z.turn_no) g(k)
CROSS JOIN LATERAL pg_temp.zz_price_loop(z.season_id, g.k::smallint, true) m
JOIN turn_price tp ON tp.season_id = z.season_id AND tp.turn_no = g.k AND tp.asset_id = m.asset_id
GROUP BY g.k;
SELECT pg_temp.check_eq('02 가격', '"매달 반올림"과 다른 칸이 있다 (현재 턴 ≥ 2 이면 ≥ 1 칸, 아니면 조건 없음) — 건수는 09절 정보',
    'true', (((SELECT turn_no FROM zz_t) < 2) OR COALESCE((SELECT sum(diff) FROM zz_monthly), 0) >= 1)::text);

-- 03 스냅샷 — 전 턴 재구성 -------------------------------------------------------------
-- 각 턴 k(1..현재 턴)의 turn 행을 체결 이력에서 다시 만든 값과 대조한다. 이 식은 매매(P0-19) 뒤에도 성립하게 짠다:
--   보유_k = Σ 체결 수량 (orders.turn_no < k, 매수 +, 매도 −) — 턴 k 의 스냅샷은 턴 k−1 까지의 매매를 담는다 (D23: 매매는 그 턴 가격으로 체결, 스냅샷은 턴 전환 순간)
--   현금_k = Σ 원장 amount (ref_order_id 가 NULL 이거나 그 주문의 turn_no < k) — 입금 외에 주문 없는 원장 행이 생기면(EXT 적립금) 이 식을 고친다
--   라인업_k = start_turn < k AND (end_turn IS NULL OR k ≤ end_turn) — 턴 e 에 닫힌(end_turn = e) 구간은 스냅샷 e 까지 보이고 e+1 부터 빠진다 (D26 반열림)
--   포지션: 골키퍼 = 현금 + 골키퍼 줄, 수비·미드필더·공격 = 그 포지션 줄 (라인업이 없는 보유는 어느 줄에도 없어 불일치로 드러난다)
--   낙폭 사슬: turn 행 총액과 initial_cash 만으로 ta_drawdown(총액, 지금까지의 고점)을 다시 계산 (고점은 창 함수)
CREATE TEMP TABLE zz_rebuild AS
WITH z AS (SELECT * FROM zz_t),
ks AS (SELECT g.k::smallint AS k FROM z, generate_series(1, z.turn_no) g(k)),
q AS (
    SELECT ks.k, o.asset_id, sum(CASE o.side WHEN 'BUY' THEN e.quantity ELSE -e.quantity END) AS qty
    FROM ks
    JOIN orders o ON o.season_id = (SELECT season_id FROM z) AND o.turn_no < ks.k
    JOIN execution e ON e.order_id = o.id
    GROUP BY ks.k, o.asset_id
), lines AS (
    SELECT q.k, q.asset_id, q.qty * tp.price AS v, lu.declared_position AS pos
    FROM q
    JOIN turn_price tp ON tp.season_id = (SELECT season_id FROM z) AND tp.turn_no = q.k AND tp.asset_id = q.asset_id
    LEFT JOIN lineup lu ON lu.season_id = (SELECT season_id FROM z) AND lu.asset_id = q.asset_id AND lu.start_turn < q.k AND (lu.end_turn IS NULL OR q.k <= lu.end_turn)
    WHERE q.qty > 0
), cash AS (
    SELECT ks.k, sum(l.amount) AS c
    FROM ks
    JOIN cash_ledger l ON l.season_id = (SELECT season_id FROM z)
    LEFT JOIN orders o ON o.id = l.ref_order_id
    WHERE l.ref_order_id IS NULL OR o.turn_no < ks.k
    GROUP BY ks.k
), r AS (
    SELECT ks.k,
           COALESCE((SELECT c.c FROM cash c WHERE c.k = ks.k), 0) AS cash_v,
           COALESCE((SELECT sum(l.v) FROM lines l WHERE l.k = ks.k), 0) AS hold_v,
           COALESCE((SELECT sum(l.v) FROM lines l WHERE l.k = ks.k AND l.pos = 'GOALKEEPER'), 0) AS g_v,
           COALESCE((SELECT sum(l.v) FROM lines l WHERE l.k = ks.k AND l.pos = 'DEFENDER'), 0) AS d_v,
           COALESCE((SELECT sum(l.v) FROM lines l WHERE l.k = ks.k AND l.pos = 'MIDFIELDER'), 0) AS m_v,
           COALESCE((SELECT sum(l.v) FROM lines l WHERE l.k = ks.k AND l.pos LIKE 'FORWARD\_%'), 0) AS f_v
    FROM ks
), dd AS (
    SELECT t.turn_no AS k, t.drawdown AS stored,
           pg_temp.ta_drawdown(t.total_value, GREATEST((SELECT s.initial_cash FROM season s WHERE s.id = t.season_id), max(t.total_value) OVER (ORDER BY t.turn_no))) AS rebuilt
    FROM turn t
    WHERE t.season_id = (SELECT season_id FROM z)
)
SELECT t.turn_no AS k,
       t.cash_balance IS NOT DISTINCT FROM r.cash_v AS cash_ok,
       t.holdings_value IS NOT DISTINCT FROM r.hold_v AS hold_ok,
       t.total_value IS NOT DISTINCT FROM r.cash_v + r.hold_v AS total_ok,
       t.goalkeeper_value IS NOT DISTINCT FROM r.cash_v + r.g_v AS g_ok,
       t.defender_value IS NOT DISTINCT FROM r.d_v AS d_ok,
       t.midfielder_value IS NOT DISTINCT FROM r.m_v AS m_ok,
       t.forward_value IS NOT DISTINCT FROM r.f_v AS f_ok,
       dd.stored IS NOT DISTINCT FROM dd.rebuilt AS dd_ok
FROM turn t
JOIN r ON r.k = t.turn_no
JOIN dd ON dd.k = t.turn_no
WHERE t.season_id = (SELECT season_id FROM z);

SELECT pg_temp.check_eq('03 스냅샷', '재구성 대상 턴 수 = 현재 턴 (turn 행마다 재구성 한 줄)', 'true',
    ((SELECT count(*) FROM zz_rebuild) = (SELECT turn_no FROM zz_t))::text);
SELECT pg_temp.check_eq('03 스냅샷', '현금 = 원장 합 (주문 없는 행 + 앞선 턴 주문의 행) — 어긋난 턴 수', '0', (SELECT count(*) FROM zz_rebuild WHERE NOT cash_ok)::text);
SELECT pg_temp.check_eq('03 스냅샷', '보유 평가액 = Σ 체결 순수량 × 그 턴 가격 — 어긋난 턴 수', '0', (SELECT count(*) FROM zz_rebuild WHERE NOT hold_ok)::text);
SELECT pg_temp.check_eq('03 스냅샷', '총액 = 현금 + 보유 평가액 — 어긋난 턴 수', '0', (SELECT count(*) FROM zz_rebuild WHERE NOT total_ok)::text);
SELECT pg_temp.check_eq('03 스냅샷', '골키퍼 = 현금 + 골키퍼 줄 (라인업이 빠진 보유가 있으면 어긋난다) — 어긋난 턴 수', '0', (SELECT count(*) FROM zz_rebuild WHERE NOT g_ok)::text);
SELECT pg_temp.check_eq('03 스냅샷', '수비 = 수비 줄 — 어긋난 턴 수', '0', (SELECT count(*) FROM zz_rebuild WHERE NOT d_ok)::text);
SELECT pg_temp.check_eq('03 스냅샷', '미드필더 = 미드필더 줄 — 어긋난 턴 수', '0', (SELECT count(*) FROM zz_rebuild WHERE NOT m_ok)::text);
SELECT pg_temp.check_eq('03 스냅샷', '공격 = 공격 줄 (FORWARD_* 3종) — 어긋난 턴 수', '0', (SELECT count(*) FROM zz_rebuild WHERE NOT f_ok)::text);
SELECT pg_temp.check_eq('03 스냅샷', '낙폭 사슬 = turn 행 총액과 초기 자금만으로 다시 계산한 값 — 어긋난 턴 수', '0', (SELECT count(*) FROM zz_rebuild WHERE NOT dd_ok)::text);
SELECT pg_temp.check_eq('03 스냅샷', 'turn CHECK 사본: 총액 = 현금 + 보유, 포지션 합 = 총액, 골키퍼 ≥ 현금, 원 단위, 낙폭 −1..0 — 어긋난 행 수', '0',
    (SELECT count(*) FROM turn t JOIN zz_t z ON z.season_id = t.season_id
     WHERE t.total_value <> t.holdings_value + t.cash_balance
        OR t.goalkeeper_value + t.defender_value + t.midfielder_value + t.forward_value <> t.total_value
        OR t.goalkeeper_value < t.cash_balance
        OR t.total_value <> ROUND(t.total_value, 0) OR t.cash_balance <> ROUND(t.cash_balance, 0)
        OR t.drawdown NOT BETWEEN -1 AND 0)::text);

-- 04 사후 정보 ------------------------------------------------------------------
-- 턴 k 의 가격은 season_path 의 1..k 블록만으로 정해진다. 서브트랜잭션에서 "1..k 블록이 아닌 모든 블록"의 수익률·환율을 크게 바꾸고(+0.5) 다시 계산해도
-- ta_prices(시즌, k)가 같다 (k = 0..현재 턴). 현재 턴 < 경로 길이이면 대조군: 같은 바꿈이 다음 턴 가격 ta_prices(시즌, 현재 턴 + 1)은 바꾼다 (바꾸기가 실제로 영향을 주는 입력이다).
-- 현재 턴 ≥ 1 이면 그 턴 스냅샷·대사 17행의 판정도 같다.
DO $$
DECLARE
    v_s      bigint := (SELECT season_id FROM zz_t);
    v_c      smallint := (SELECT turn_no FROM zz_t);
    v_l      smallint := (SELECT path_length FROM zz_t);
    v_k      smallint;
    v_before text;
    v_after  text;
    v_bad    integer := 0;
    v_ctrl   text := 'n/a';
    v_sb     text;
    v_sa     text;
BEGIN
    FOR v_k IN 0..v_c LOOP
        SELECT COALESCE(string_agg(x.asset_id || ':' || x.price, ',' ORDER BY x.asset_id), '') INTO v_before FROM pg_temp.ta_prices(v_s, v_k::smallint) x;
        v_after := NULL;
        BEGIN
            UPDATE asset_month_return SET local_return = local_return + 0.5
             WHERE month_block_id NOT IN (SELECT sp.month_block_id FROM season_path sp WHERE sp.season_id = v_s AND sp.turn_no <= v_k);
            UPDATE fx_month SET fx_change = fx_change + 0.5
             WHERE month_block_id NOT IN (SELECT sp.month_block_id FROM season_path sp WHERE sp.season_id = v_s AND sp.turn_no <= v_k);
            SELECT COALESCE(string_agg(x.asset_id || ':' || x.price, ',' ORDER BY x.asset_id), '') INTO v_after FROM pg_temp.ta_prices(v_s, v_k::smallint) x;
            RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
        EXCEPTION WHEN OTHERS THEN
            IF SQLERRM <> 'zz_undo' THEN v_after := 'ERROR ' || SQLSTATE; END IF;
        END;
        IF v_before IS DISTINCT FROM v_after THEN v_bad := v_bad + 1; END IF;
    END LOOP;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('04 사후 정보', 'k 턴 이후 블록의 수익률·환율을 바꿔도 ta_prices(시즌, k) 가 같다 (k = 0..현재 턴, 달라진 k 수)', '0', v_bad::text, v_bad = 0);

    IF v_c >= 1 THEN
        SELECT string_agg(r.rule_no || ':' || r.status || ':' || r.mismatch_count, ',' ORDER BY r.rule_no) INTO v_sb FROM pg_temp.zz_rules(v_s, v_c) r;
        BEGIN
            UPDATE asset_month_return SET local_return = local_return + 0.5
             WHERE month_block_id NOT IN (SELECT sp.month_block_id FROM season_path sp WHERE sp.season_id = v_s AND sp.turn_no <= v_c);
            UPDATE fx_month SET fx_change = fx_change + 0.5
             WHERE month_block_id NOT IN (SELECT sp.month_block_id FROM season_path sp WHERE sp.season_id = v_s AND sp.turn_no <= v_c);
            SELECT string_agg(r.rule_no || ':' || r.status || ':' || r.mismatch_count, ',' ORDER BY r.rule_no) INTO v_sa FROM pg_temp.zz_rules(v_s, v_c) r;
            RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
        EXCEPTION WHEN OTHERS THEN
            IF SQLERRM <> 'zz_undo' THEN v_sa := 'ERROR ' || SQLSTATE; END IF;
        END;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('04 사후 정보', '현재 턴 뒤 블록을 바꿔도 현재 턴의 대사 17행 판정(규칙·상태·불일치 수)이 같다', 'same', CASE WHEN v_sb IS NOT DISTINCT FROM v_sa THEN 'same' ELSE 'differ' END,
                v_sb IS NOT DISTINCT FROM v_sa);
    END IF;

    IF v_c < v_l THEN
        SELECT COALESCE(string_agg(x.asset_id || ':' || x.price, ',' ORDER BY x.asset_id), '') INTO v_before FROM pg_temp.ta_prices(v_s, (v_c + 1)::smallint) x;
        v_after := NULL;
        BEGIN
            UPDATE asset_month_return SET local_return = local_return + 0.5
             WHERE month_block_id NOT IN (SELECT sp.month_block_id FROM season_path sp WHERE sp.season_id = v_s AND sp.turn_no <= v_c);
            UPDATE fx_month SET fx_change = fx_change + 0.5
             WHERE month_block_id NOT IN (SELECT sp.month_block_id FROM season_path sp WHERE sp.season_id = v_s AND sp.turn_no <= v_c);
            SELECT COALESCE(string_agg(x.asset_id || ':' || x.price, ',' ORDER BY x.asset_id), '') INTO v_after FROM pg_temp.ta_prices(v_s, (v_c + 1)::smallint) x;
            RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
        EXCEPTION WHEN OTHERS THEN
            IF SQLERRM <> 'zz_undo' THEN v_after := 'ERROR ' || SQLSTATE; END IF;
        END;
        v_ctrl := CASE WHEN v_after LIKE 'ERROR %' THEN v_after WHEN v_before IS DISTINCT FROM v_after THEN 'changed' ELSE 'same' END;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('04 사후 정보', '대조군: 같은 바꿈이 다음 턴 가격 ta_prices(시즌, 현재 턴 + 1)은 바꾼다 (바꾸기가 의미 있는 입력이다)', 'changed', v_ctrl, v_ctrl = 'changed');
    END IF;
END $$;

-- 05 양성 -----------------------------------------------------------------------
-- 현재 턴 ≥ 1 이면 recon_rules_turn(시즌, 현재 턴) 17행이 모두 MATCH (마지막 턴 전환이 본 것과 같은 판정). 0턴이면 15행.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '05 양성', '규칙 ' || c.rule_no || ' ' || c.check_code || ' 가 일치한다', 'MATCH', COALESCE(b.status, '(행 없음)'), b.status IS NOT DISTINCT FROM 'MATCH'
FROM pg_temp.recon_code c
LEFT JOIN zz_base b ON b.rule_no = c.rule_no
WHERE (SELECT turn_no FROM zz_t) >= 1 OR c.manual
ORDER BY c.rule_no;

SELECT pg_temp.check_eq('05 양성', 'zz_rules 가 정확히 기대한 행 수이고 rule_no 가 기대 집합과 같다',
    (SELECT CASE WHEN turn_no >= 1 THEN '17 · 같음' ELSE '15 · 같음' END FROM zz_t),
    (SELECT count(*)::text FROM zz_base) || ' · ' ||
    CASE WHEN (SELECT array_agg(rule_no ORDER BY rule_no) FROM zz_base)
            = (SELECT array_agg(c.rule_no ORDER BY c.rule_no) FROM pg_temp.recon_code c WHERE c.manual OR (SELECT turn_no FROM zz_t) >= 1) THEN '같음' ELSE '다름' END);
SELECT pg_temp.check_eq('05 양성', '규칙 11·19 는 세 값 NULL 이고 일치 행은 분류·상세가 NULL 이다 (어긋난 행 수)', '0',
    (SELECT count(*) FROM zz_base b
     WHERE (b.rule_no IN (11, 19) AND num_nonnulls(b.expected_value, b.actual_value, b.diff_value) <> 0)
        OR (b.status = 'MATCH' AND (b.classification IS NOT NULL OR b.detail IS NOT NULL OR b.mismatch_count <> 0)))::text);
SELECT pg_temp.check_eq('05 양성', '턴 전환 17행 모양: recon_rules_turn 이 17행 · 규칙 11·19 포함 · 코드 집합 = recon_code 전체 (현재 턴 ≥ 1 일 때)', 'true',
    ((SELECT turn_no FROM zz_t) = 0
     OR ((SELECT count(*) FROM pg_temp.recon_rules_turn((SELECT season_id FROM zz_t), (SELECT turn_no FROM zz_t))) = 17
         AND (SELECT array_agg(r.check_code::text ORDER BY r.check_code) FROM pg_temp.recon_rules_turn((SELECT season_id FROM zz_t), (SELECT turn_no FROM zz_t)) r)
           = (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c)))::text);

\o
-- 06 음성 ---------------------------------------------------------------------
-- 규칙을 일부러 어겨 보고 기대한 규칙 집합만 불일치로 잡히는지 본다. UPDATE · DELETE 만 쓰고 INSERT 는 쓰지 않는다 (번호표를 쓰지 않는다).
-- 한 사례에 조건 하나만 어긴다 (P0-05 R12).

-- 규칙 12 (모든 현재 턴에서 돈다: 턴 0 이면 턴 0 가격 행만, 이후는 턴 0..현재 턴 전 행)
CALL pg_temp.neg_price_rows();

-- 규칙 11·19 (현재 턴 ≥ 1 일 때만 — 턴 0 에는 turn 행이 없다. 건너뛰면 정보 행을 남긴다).
--   사례의 대상(현금·비골키퍼 포지션 평가액·가격 있는 보유 종목·열린 라인업)은 포트폴리오에 따라 없을 수 있다(P0-19: 전액 현금 등).
--   대상이 없는 사례는 헛 PASS 도 FAIL 도 아닌 "적용 불가" 정보 행이다 (통과 사례 수에 세지 않고, 적용 가능한 사례가 없는 규칙은 커버리지 요구에서 뺀다). 양성 검사(05절)는 그대로 모두 돈다.
CREATE PROCEDURE pg_temp.na_rule(p_code text, p_label text, p_why text)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('06 음성', '음성 [' || p_code || '] ' || p_label || ' — 적용 불가 (' || p_why || ') (정보)', '(정보)', '적용 불가', true);
END $$;

DO $$
DECLARE
    v_s bigint := (SELECT season_id FROM zz_t);
    v_c smallint := (SELECT turn_no FROM zz_t);
    t   record;
    v_src text;
BEGIN
    IF v_c = 0 THEN
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES ('06 음성', '규칙 11·19 음성 사례 건너뜀 (현재 턴 0 — turn 행이 없다) (정보)', '(정보)', '건너뜀', true);
        RETURN;
    END IF;
    SELECT * INTO t FROM turn WHERE season_id = v_s AND turn_no = v_c;
    CALL pg_temp.neg_rule('VALUATION', 'N11a 현재 턴 turn 행의 낙폭만 1e-8 바꾸면 (나머지는 그대로)',
        $m$UPDATE turn SET drawdown = CASE WHEN drawdown > -0.5 THEN drawdown - 0.00000001 ELSE drawdown + 0.00000001 END
           WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = (SELECT turn_no FROM zz_t)$m$, ARRAY[11], 'DRAWDOWN');
    -- N11b: 현금·총액·골키퍼를 같이 +1 (CHECK 는 유지, 계좌 잔액과 갈림). 줄이는 방향이 아니라 늘리는 방향이라 현금이 0 이어도 대상이 있다.
    CALL pg_temp.neg_rule('VALUATION', 'N11b 현재 턴 turn 행의 현금·총액·골키퍼를 같이 +1 하면 (CHECK 는 유지, 계좌 잔액과 갈림)',
        $m$UPDATE turn SET cash_balance = cash_balance + 1, total_value = total_value + 1, goalkeeper_value = goalkeeper_value + 1
           WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = (SELECT turn_no FROM zz_t)$m$, ARRAY[11], 'CASH');
    -- N19a: 합은 그대로 두고 1 을 골키퍼로 옮긴다 (11 은 그대로). 옮길 비골키퍼 포지션은 평가액 ≥ 1 인 것을 동적으로 고른다 (미드필더 → 수비 → 공격 순).
    v_src := CASE WHEN t.midfielder_value >= 1 THEN 'MIDFIELDER' WHEN t.defender_value >= 1 THEN 'DEFENDER' WHEN t.forward_value >= 1 THEN 'FORWARD' END;
    IF v_src IS NULL THEN
        CALL pg_temp.na_rule('POSITION_VALUE', 'N19a 비골키퍼 포지션 평가액 1 을 골키퍼로 옮기면', '수비·미드필더·공격 평가액이 모두 0 이다 (전액 현금)');
    ELSE
        CALL pg_temp.neg_rule('POSITION_VALUE', 'N19a 현재 턴 turn 행의 ' || v_src || ' 1 을 골키퍼로 옮기면 (합 유지 — 11 은 그대로)',
            format($m$UPDATE turn SET goalkeeper_value = goalkeeper_value + 1, %1$s = %1$s - 1
                      WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = (SELECT turn_no FROM zz_t)$m$,
                   CASE v_src WHEN 'MIDFIELDER' THEN 'midfielder_value' WHEN 'DEFENDER' THEN 'defender_value' ELSE 'forward_value' END),
            ARRAY[19], v_src);
    END IF;
    CALL pg_temp.neg_rule('VALUATION', 'N11c 현재 턴 turn 행의 보유 평가액·총액·공격을 같이 +1 하면 (함의: 포지션 합도 갈림)',
        $m$UPDATE turn SET holdings_value = holdings_value + 1, total_value = total_value + 1, forward_value = forward_value + 1
           WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = (SELECT turn_no FROM zz_t)$m$, ARRAY[11, 19], 'HOLDINGS');
    IF (SELECT held_asset FROM zz_t) IS NULL THEN
        CALL pg_temp.na_rule('VALUATION', 'N11d 보유 종목의 현재 턴 가격 행을 지우면', '가격이 있는 보유 종목이 없다 (전액 현금)');
    ELSE
        CALL pg_temp.neg_rule('VALUATION', 'N11d 보유 종목의 현재 턴 가격 행을 지우면 (가격 없는 보유 — 12 MISSING·19 도 걸린다)',
            $m$DELETE FROM turn_price WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = (SELECT turn_no FROM zz_t) AND asset_id = (SELECT held_asset FROM zz_t)$m$, ARRAY[11, 12, 19], 'NO_PRICE');
    END IF;
    CALL pg_temp.neg_rule('VALUATION', 'N11e 현재 턴 turn 행을 지우면 (11·19 모두 NO_TURN)',
        $m$DELETE FROM turn WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = (SELECT turn_no FROM zz_t)$m$, ARRAY[11, 19], 'NO_TURN');
    IF (SELECT open_lineup FROM zz_t) IS NULL THEN
        CALL pg_temp.na_rule('POSITION_VALUE', 'N19b 보유 종목의 열린 라인업 구간을 닫으면', '열린 라인업이 없다 (전액 현금)');
    ELSE
        CALL pg_temp.neg_rule('POSITION_VALUE', 'N19b 보유 종목의 열린 라인업 구간을 닫으면 (스냅샷은 그대로라 포지션 합이 갈리고 17 도 걸린다)',
            $m$UPDATE lineup SET end_turn = start_turn WHERE id = (SELECT open_lineup FROM zz_t)$m$, ARRAY[17, 19], NULL);
    END IF;
END $$;

-- 07 거부 사례 (쓰기 전, identity 불변) -----------------------------------------------
-- ta_precheck 만 부른다 (ta_apply 의 첫 단계 — 쓰기 전). 현재 상태가 무엇이든(진행 중·끝남) 같은 사례를 시험하도록 서브트랜잭션에서 시즌을 "진행 중, 0턴"으로 바꾼다.
\o /dev/null
CALL pg_temp.reject_case('없는 시즌은 SEASON_NOT_FOUND', NULL,
    'SELECT pg_temp.ta_precheck((SELECT max(id) + 1 FROM season), 0::smallint)', 'SEASON_NOT_FOUND');
CALL pg_temp.reject_case('끝난 시즌(FINISHED, 현재 턴 = 경로 길이)은 SEASON_NOT_ACTIVE',
    $m$UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = (SELECT season_id FROM zz_t)$m$,
    'SELECT pg_temp.ta_precheck((SELECT season_id FROM zz_t), (SELECT path_length FROM zz_t))', 'SEASON_NOT_ACTIVE');
CALL pg_temp.reject_case('틀린 기대 턴은 TURN_MISMATCH',
    $m$UPDATE season SET status = 'IN_PROGRESS', abandoned_turn_no = NULL, current_turn_no = 0 WHERE id = (SELECT season_id FROM zz_t)$m$,
    'SELECT pg_temp.ta_precheck((SELECT season_id FROM zz_t), 1::smallint)', 'TURN_MISMATCH');
CALL pg_temp.reject_case('다음 턴의 경로 블록이 없으면 NO_PATH',
    $m$UPDATE season SET status = 'IN_PROGRESS', abandoned_turn_no = NULL, current_turn_no = 0 WHERE id = (SELECT season_id FROM zz_t);
       DELETE FROM season_path WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = 1$m$,
    'SELECT pg_temp.ta_precheck((SELECT season_id FROM zz_t), 0::smallint)', 'NO_PATH');
CALL pg_temp.reject_case('다음 블록의 풀 종목 수익률 한 행이 없으면 NO_RETURN',
    $m$UPDATE season SET status = 'IN_PROGRESS', abandoned_turn_no = NULL, current_turn_no = 0 WHERE id = (SELECT season_id FROM zz_t);
       DELETE FROM asset_month_return WHERE id = (SELECT amr.id FROM asset_month_return amr
                                                   JOIN season_path sp ON sp.month_block_id = amr.month_block_id AND sp.season_id = (SELECT season_id FROM zz_t) AND sp.turn_no = 1
                                                   JOIN asset_position ap ON ap.asset_id = amr.asset_id AND ap.base_date = (SELECT base_date FROM zz_cur)
                                                   ORDER BY amr.asset_id LIMIT 1)$m$,
    'SELECT pg_temp.ta_precheck((SELECT season_id FROM zz_t), 0::smallint)', 'NO_RETURN');
CALL pg_temp.reject_case('원화 아닌 풀 종목의 환율 행이 없으면 NO_RETURN',
    $m$UPDATE season SET status = 'IN_PROGRESS', abandoned_turn_no = NULL, current_turn_no = 0 WHERE id = (SELECT season_id FROM zz_t);
       DELETE FROM fx_month WHERE month_block_id = (SELECT sp.month_block_id FROM season_path sp WHERE sp.season_id = (SELECT season_id FROM zz_t) AND sp.turn_no = 1)$m$,
    'SELECT pg_temp.ta_precheck((SELECT season_id FROM zz_t), 0::smallint)', 'NO_RETURN');
CALL pg_temp.reject_case('다음 턴 0턴 가격 행이 없는 풀 종목이 있으면 NO_RETURN',
    $m$UPDATE season SET status = 'IN_PROGRESS', abandoned_turn_no = NULL, current_turn_no = 0 WHERE id = (SELECT season_id FROM zz_t);
       DELETE FROM turn_price WHERE id = (SELECT tp.id FROM turn_price tp WHERE tp.season_id = (SELECT season_id FROM zz_t) AND tp.turn_no = 0 ORDER BY tp.asset_id LIMIT 1)$m$,
    'SELECT pg_temp.ta_precheck((SELECT season_id FROM zz_t), 0::smallint)', 'NO_RETURN');
CALL pg_temp.reject_case('시즌 번호가 NULL 이면 INVALID_ARGUMENT', NULL,
    'SELECT pg_temp.ta_precheck(NULL, 0::smallint)', 'INVALID_ARGUMENT');
CALL pg_temp.reject_case('기대 턴이 NULL 이면 INVALID_ARGUMENT', NULL,
    'SELECT pg_temp.ta_precheck((SELECT season_id FROM zz_t), NULL)', 'INVALID_ARGUMENT');
CALL pg_temp.reject_case('기대 턴이 음수면 INVALID_ARGUMENT', NULL,
    'SELECT pg_temp.ta_precheck((SELECT season_id FROM zz_t), (-1)::smallint)', 'INVALID_ARGUMENT');
CALL pg_temp.reject_case('ta_apply 도 같은 입구에서 막힌다 (없는 시즌 SEASON_NOT_FOUND — 쓰기 없음)', NULL,
    'SELECT pg_temp.ta_apply((SELECT max(id) + 1 FROM season), 0::smallint)', 'SEASON_NOT_FOUND');
-- 끝난 시즌에서 매수는 쓰기 전에 거부된다 (P0-15 C10): 서브트랜잭션에서 끝난 시즌으로 만든 뒤 order_buy (행 수·identity 는 10절 흔적과 아래 행 수 대조)
CALL pg_temp.reject_case('C10 끝난 시즌에서 order_buy 는 SEASON_NOT_ACTIVE (쓰기 전)',
    $m$UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = (SELECT season_id FROM zz_t)$m$,
    $c$SELECT pg_temp.order_buy((SELECT season_id FROM zz_t), (SELECT path_length FROM zz_t), COALESCE((SELECT held_asset FROM zz_t), (SELECT any_asset FROM zz_t)), 1, 'MIDFIELDER')$c$, 'SEASON_NOT_ACTIVE');

-- ABANDONING 은 거부되지 않고 전제를 통과한다 (포기 시즌의 남은 턴 자동 진행 — W7): 풀 크기만큼 가격 배열이 나온다
DO $$
DECLARE
    v_n   integer;
    v_err text;
BEGIN
    BEGIN
        UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 0, current_turn_no = 0 WHERE id = (SELECT season_id FROM zz_t);
        SELECT jsonb_array_length(pg_temp.ta_precheck((SELECT season_id FROM zz_t), 0::smallint)) INTO v_n;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN v_err := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60); END IF;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('07 거부', 'ABANDONING 시즌은 전제를 통과해 풀 크기만큼 다음 턴 가격을 돌려준다 (W7)',
            (SELECT pool::text FROM zz_t), COALESCE(v_err, v_n::text), v_err IS NULL AND v_n = (SELECT pool FROM zz_t));
    -- 현재 실제 상태: 끝난 시즌이면 SEASON_NOT_ACTIVE, 아니면 전제를 통과한다 (풀 크기만큼 가격)
    BEGIN
        SELECT jsonb_array_length(pg_temp.ta_precheck((SELECT season_id FROM zz_t), (SELECT turn_no FROM zz_t))) INTO v_n;
        v_err := NULL;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_err = PG_EXCEPTION_HINT;
        v_n := NULL;
    END;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('07 거부', '현재 실제 상태: 끝난 시즌이면 SEASON_NOT_ACTIVE, 아니면 전제를 통과해 풀 크기만큼 가격이 나온다',
            CASE WHEN (SELECT status FROM zz_t) = 'FINISHED' THEN 'SEASON_NOT_ACTIVE' ELSE (SELECT pool::text FROM zz_t) END,
            COALESCE(v_err, v_n::text),
            COALESCE(v_err, v_n::text) = CASE WHEN (SELECT status FROM zz_t) = 'FINISHED' THEN 'SEASON_NOT_ACTIVE' ELSE (SELECT pool::text FROM zz_t) END);
END $$;

-- 끝난 시즌이면 실제 상태에서 order_buy 도 거부된다 (C10, 서브트랜잭션 없이) — 행 수가 그대로
DO $$
DECLARE
    v_before text := pg_temp.zz_snap();
    v_hint   text;
    v_act    text := '해당 없음 (끝난 시즌이 아니다)';
BEGIN
    IF (SELECT status FROM zz_t) = 'FINISHED' THEN
        BEGIN
            PERFORM pg_temp.order_buy((SELECT season_id FROM zz_t), (SELECT turn_no FROM zz_t), (SELECT asset_id FROM turn_price WHERE season_id = (SELECT season_id FROM zz_t) AND turn_no = 0 ORDER BY asset_id LIMIT 1), 1, 'MIDFIELDER');
            v_act := '오류 없음';
        EXCEPTION WHEN OTHERS THEN
            GET STACKED DIAGNOSTICS v_hint = PG_EXCEPTION_HINT;
            v_act := SQLSTATE || ' ' || COALESCE(v_hint, '-');
        END;
        v_act := v_act || CASE WHEN pg_temp.zz_snap() = v_before THEN ' · 행 수·identity 그대로' ELSE ' · 행 수 또는 identity 가 바뀜' END;
    END IF;
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES ('07 거부', 'C10 실제로 끝난 시즌이면 order_buy 가 SEASON_NOT_ACTIVE 로 거부되고 행 수·identity 가 그대로다',
            CASE WHEN (SELECT status FROM zz_t) = 'FINISHED' THEN 'P0001 SEASON_NOT_ACTIVE · 행 수·identity 그대로' ELSE '해당 없음 (끝난 시즌이 아니다)' END,
            v_act,
            v_act = CASE WHEN (SELECT status FROM zz_t) = 'FINISHED' THEN 'P0001 SEASON_NOT_ACTIVE · 행 수·identity 그대로' ELSE '해당 없음 (끝난 시즌이 아니다)' END);
END $$;

-- 08 단위 (합성 값 — 시세가 아니다) -------------------------------------------------------
CREATE TEMP TABLE zz_unit (label text NOT NULL, got text, want text);
INSERT INTO zz_unit VALUES
    ('ta_drawdown: erd 6.3 예 (고점 120, 총액 90 → −0.25000000)',                    pg_temp.ta_drawdown(90, 120)::text, '-0.25000000'),
    ('ta_drawdown: 새 고점이면 0 (총액 150, 고점 150)',                               pg_temp.ta_drawdown(150, 150)::text, '0.00000000'),
    ('ta_drawdown: 총액이 고점보다 크면 0 (방어)',                                    pg_temp.ta_drawdown(151, 150)::text, '0.00000000'),
    ('ta_drawdown: 총액 0 이면 −1',                                                   pg_temp.ta_drawdown(0, 1000)::text, '-1.00000000'),
    ('ta_drawdown: 동점 경계 — 정확히 0.5e-8 은 0에서 먼 쪽 (총액 199999999, 고점 200000000 → −0.00000001)', pg_temp.ta_drawdown(199999999, 200000000)::text, '-0.00000001'),
    ('ta_drawdown: 1.5e-8 은 2e-8 로 (총액 199999997, 고점 200000000 → −0.00000002)', pg_temp.ta_drawdown(199999997, 200000000)::text, '-0.00000002'),
    ('ta_drawdown: 0.5e-8 미만은 0 으로 (총액 299999999, 고점 300000000 → 0)',        pg_temp.ta_drawdown(299999999, 300000000)::text, '0.00000000'),
    ('규칙 11 의 나눗셈 식과 같은 값: ROUND((총액 − 고점) ÷ 고점, 8) (동점 경계 사례)', ROUND((199999999 - 200000000)::numeric / 200000000, 8)::text, '-0.00000001'),
    ('"round(총액/고점, 8) − 1" 은 같은 동점 경계에서 다른 값 (0)',                    (ROUND(199999999::numeric / 200000000, 8) - 1)::text, '0.00000000');
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '08 단위', u.label, u.want, u.got, u.got IS NOT DISTINCT FROM u.want FROM zz_unit u;

\o /dev/null
-- ta_drawdown (정수 산술)과 규칙 11 의 나눗셈 + ROUND 식이 합성 값 2000쌍에서 같다 (갈린 쌍 수)
SELECT pg_temp.check_eq('08 단위', 'ta_drawdown 의 정수 식과 나눗셈 + ROUND 식이 합성 2000쌍에서 같다 (갈린 쌍 수)', '0',
    (SELECT count(*) FROM (SELECT g, 30000000 + g * 7919 AS peak, 30000000 + g * 7919 - g * 12347 AS total FROM generate_series(1, 2000) g) p
     WHERE pg_temp.ta_drawdown(p.total, p.peak) <> ROUND((p.total - p.peak)::numeric / p.peak, 8))::text);
-- 정보 행 하나: 진행 중에는 이미 지난 경로 블록만 센다 — 미래 경로의 성질을 판정·출력하지 않는다
SELECT pg_temp.check_eq('08 단위', '지난 경로 블록의 현지 수익률 −1·환율 변화 ≤ −1 인 행 수 (정보)', '0',
    ((SELECT count(*) FROM asset_month_return amr JOIN season_path sp ON sp.month_block_id = amr.month_block_id JOIN zz_t z ON z.season_id = sp.season_id WHERE sp.turn_no <= z.turn_no AND amr.local_return = -1)
     + (SELECT count(*) FROM fx_month fx JOIN season_path sp ON sp.month_block_id = fx.month_block_id JOIN zz_t z ON z.season_id = sp.season_id WHERE sp.turn_no <= z.turn_no AND fx.fx_change <= -1))::text);
\o

-- 변이: ta_drawdown 을 "round(총액/고점, 8) − 1" 로 바꿔치기하면 동점 경계 사례가 어긋난다
CALL pg_temp.neg_unit('08 단위', 'ta_drawdown 을 round(총액/고점, 8) − 1 로',
    $m$CREATE OR REPLACE FUNCTION pg_temp.ta_drawdown(p_total numeric, p_peak numeric) RETURNS numeric(12,8)
       LANGUAGE sql IMMUTABLE AS $f$ SELECT (ROUND(p_total / p_peak, 8) - 1)::numeric(12,8) $f$$m$,
    $q$SELECT count(*) FROM (VALUES (pg_temp.ta_drawdown(199999999, 200000000), -0.00000001::numeric),
                                    (pg_temp.ta_drawdown(199999997, 200000000), -0.00000002::numeric)) u(got, want) WHERE got IS DISTINCT FROM want$q$, 1);
-- 변이: ta_prices 의 마지막 ROUND 를 매달 반올림으로 바꿔치기하면 저장된 가격과 갈리는 칸이 생긴다 (현재 턴 ≥ 2 일 때만 갈린다 — 1턴은 곱이 한 번이다)
DO $do$
DECLARE
    v_min bigint := (SELECT CASE WHEN turn_no >= 2 THEN 1 ELSE 0 END FROM zz_t);
BEGIN
    CALL pg_temp.neg_unit('08 단위', 'ta_prices 를 매달 반올림으로 (현재 턴 ≥ 2 에서 저장 가격과 갈리는 칸이 ≥ 1)',
        $m$CREATE OR REPLACE FUNCTION pg_temp.ta_prices(p_season_id bigint, p_turn smallint) RETURNS TABLE (asset_id bigint, price numeric)
           LANGUAGE sql STABLE AS $f$
           WITH RECURSIVE acc(k, asset_id, currency, v) AS (
               SELECT 0, tp.asset_id, a.currency, tp.price::numeric FROM turn_price tp JOIN asset a ON a.id = tp.asset_id WHERE tp.season_id = p_season_id AND tp.turn_no = 0
             UNION ALL
               SELECT acc.k + 1, acc.asset_id, acc.currency, ROUND(acc.v * (1 + amr.local_return) * CASE WHEN acc.currency = 'KRW' THEN 1 ELSE 1 + fx.fx_change END, 0)
               FROM acc
               JOIN season_path sp ON sp.season_id = p_season_id AND sp.turn_no = acc.k + 1
               JOIN asset_month_return amr ON amr.asset_id = acc.asset_id AND amr.month_block_id = sp.month_block_id
               LEFT JOIN fx_month fx ON fx.month_block_id = sp.month_block_id AND fx.currency = acc.currency
               WHERE acc.k < p_turn AND (acc.currency = 'KRW' OR fx.fx_change IS NOT NULL))
           SELECT acc.asset_id, ROUND(acc.v, 0) FROM acc WHERE acc.k = p_turn $f$$m$,
        $q$SELECT count(*) FROM zz_t z CROSS JOIN LATERAL generate_series(1, z.turn_no) g(k)
           CROSS JOIN LATERAL pg_temp.ta_prices(z.season_id, g.k::smallint) x
           JOIN turn_price tp ON tp.season_id = z.season_id AND tp.turn_no = g.k AND tp.asset_id = x.asset_id
           WHERE x.price <> tp.price$q$,
        v_min);
END $do$;

\o /dev/null
-- 09 정보·판정 -----------------------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '09 정보', '현재 턴 · 경로 길이 · 상태 · 풀 크기 (정보)', '(정보)',
       z.turn_no || ' · ' || z.path_length || ' · ' || z.status || ' · ' || z.pool, true FROM zz_t z;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '09 정보', 'turn_price 행 수 · turn 행 수 (정보)', '(정보)',
       (SELECT count(*) FROM turn_price tp JOIN zz_cur c ON c.id = tp.season_id)::text || ' · ' || (SELECT count(*) FROM turn t JOIN zz_cur c ON c.id = t.season_id)::text, true;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '09 정보', '"매달 반올림"과 다른 (턴, 종목) 칸 수 / 1턴 이후 전체 칸 수 (정보)', '(정보)',
       COALESCE((SELECT sum(diff) FROM zz_monthly), 0)::text || ' / ' || COALESCE((SELECT sum(cells) FROM zz_monthly), 0)::text, true;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '09 정보', '17개 시도 수 · 그중 MISMATCH 를 담은 시도 수 · 15개(수동) 시도 수 (정보)', '(정보)',
       (SELECT count(*) FROM zz_att17)::text || ' · ' || (SELECT count(*) FROM zz_att17 WHERE bad > 0)::text || ' · '
       || (SELECT count(*) FROM zz_att a WHERE a.codes = (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.manual))::text, true;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '09 정보', '성공 시도보다 번호가 큰 같은 턴의 실패 17개 시도 수 (동시 실행에서 실패 기록이 늦게 커밋된 흔적 — 정보)', '(정보)',
       (SELECT count(*) FROM zz_att17 s JOIN zz_att17 o ON o.turn_no = s.turn_no AND o.attempt_no > s.attempt_no WHERE s.bad = 0)::text, true;

-- 09.1 규칙 커버리지: 이 파일의 음성 사례가 통과한 대상 규칙 코드 수 / 요구 코드 수.
--      요구 = TURN_PRICE, 그리고 현재 턴 ≥ 1 이면 VALUATION, 그리고 현재 턴 ≥ 1 이고 POSITION_VALUE 의 적용 가능한 사례가 하나라도 있으면 POSITION_VALUE
--      (전액 현금이면 N19a·N19b 가 모두 "적용 불가"라 POSITION_VALUE 는 요구에서 빠진다 — 적용 불가 코드는 항목 이름에 적는다).
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '09 판정', '규칙 커버리지 (적용 가능한 음성 사례가 통과한 규칙 코드 / 요구 규칙 코드: 12, 현재 턴 ≥ 1 이면 11 과 (적용 가능하면) 19 — 적용 불가 코드: ' || COALESCE(na.list, '없음') || ')',
       req.n || '/' || req.n, cov.n || '/' || req.n, req.n > 0 AND cov.n = req.n
FROM (SELECT count(*) AS n FROM pg_temp.recon_code c
      WHERE c.check_code = 'TURN_PRICE'
         OR (c.check_code = 'VALUATION' AND (SELECT turn_no FROM zz_t) >= 1)
         OR (c.check_code = 'POSITION_VALUE' AND (SELECT turn_no FROM zz_t) >= 1
             AND EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [POSITION_VALUE]%' AND r.actual IS DISTINCT FROM '적용 불가'))) req,
     (SELECT count(*) AS n FROM pg_temp.recon_code c
      WHERE (c.check_code = 'TURN_PRICE'
         OR (c.check_code = 'VALUATION' AND (SELECT turn_no FROM zz_t) >= 1)
         OR (c.check_code = 'POSITION_VALUE' AND (SELECT turn_no FROM zz_t) >= 1))
        AND EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || c.check_code || ']%' AND r.ok AND r.actual IS DISTINCT FROM '적용 불가')) cov,
     (SELECT string_agg(c.check_code, ',' ORDER BY c.check_code) AS list FROM pg_temp.recon_code c
      WHERE c.check_code IN ('VALUATION', 'POSITION_VALUE')
        AND EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || c.check_code || ']%적용 불가%')
        AND NOT EXISTS (SELECT 1 FROM zz_check_result r WHERE r.item LIKE '음성 [' || c.check_code || ']%' AND r.actual IS DISTINCT FROM '적용 불가')) na;
\o

-- 09.2 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok AND actual IS DISTINCT FROM '적용 불가') AS pass, count(*) FILTER (WHERE NOT ok) AS fail,
       count(*) FILTER (WHERE actual = '적용 불가') AS na
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 09.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 09.3b 커버리지 행·정보 행 (PASS 출력에서도 N/N 이 보이게)
SELECT section, item, expected, actual FROM zz_check_result WHERE section IN ('09 판정', '09 정보') ORDER BY seq;

-- 09.4 판정 행
-- 적용 불가 정보 행은 통과 사례 수에 세지 않는다 (P0-19)
SELECT CASE WHEN count(*) FILTER (WHERE actual IS DISTINCT FROM '적용 불가') > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) FILTER (WHERE actual IS DISTINCT FROM '적용 불가') AS total, count(*) FILTER (WHERE ok AND actual IS DISTINCT FROM '적용 불가') AS pass,
       count(*) FILTER (WHERE NOT ok) AS fail, count(*) FILTER (WHERE actual = '적용 불가') AS not_applicable
FROM zz_check_result;

-- 09.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3).
--      판정 표식은 트랜잭션이 되돌아가도 남는다. 통과해도 2(흔적 확인 대기)까지만 올린다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-17 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 10 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 읽거나 일부러 바꿔 본 표들의 행 수와 identity 마지막 값이 실행 전과 같고, public 에 zz_ 객체가 없다.
-- 검증은 INSERT 를 하지 않으므로 번호표를 소비하지 않는다 (거부 사례가 쓰기 전에 막혔다는 증거이기도 하다).
DO $$
DECLARE
    v_before text;
    v_after  text;
    v_zz     integer;
BEGIN
    SELECT snap INTO v_before FROM zz_before;
    v_after := pg_temp.zz_snap();
    SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
         + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%') INTO v_zz;
    IF v_before IS DISTINCT FROM v_after OR v_zz <> 0 THEN
        PERFORM setval('pg_temp.zz_verdict', 1);
        RAISE EXCEPTION E'P0-17 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    -- 단언을 모두 통과했고 00–09절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_17_check;
