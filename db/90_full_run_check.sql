-- =====================================================================
-- my Fund Manager Phase 0 — 전체 실행 순서의 최종 판정 SQL (P0-21)
-- 대상: db/README.md 「전체 순서」 표를 S36 까지 실행한 DB (10턴을 끝내고 마지막 수동 대사 22 까지 돌린 연습 시즌)
-- 근거: docs/erd.md 7.5(대사 규칙 1–19)·6.3(reconciliation_log), docs/PLAN.md §5.2·§11.1, db/22_reconcile_season_def.sql(코드 집합), db/26_season_report_def.sql(대사 상태 집계)
-- 실행: 저장소 루트에서  psql -X -q -A -P footer=off -v ON_ERROR_STOP=1 -d <DB이름> -f db/90_full_run_check.sql | tail -2   (종료 코드도 보려면 set -o pipefail)
--       정의 파일(22·26)을 \ir 로 읽는다. INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아 실습 DB(antlab)에서도 돌릴 수 있다.
--       새 계산은 하지 않는다 — 이미 정의된 집계(sr_report)와 로그만 읽어 "대사 불일치 0건"을 판정한다.
-- 전제: 10턴이 끝나 시즌이 FINISHED 다. 진행 중인 시즌에서는 0절이 FAIL 이고 여기서 멈춘다(정상). 턴 중간에는 README 의 중간 검증을 쓴다.
-- 판정 일곱 가지 (J1–J7, 모두 만족할 때만 PASS):
--       J1 마지막 수동 대사(코드 15개 묶음 중 id 가 가장 큰 것)가 시즌의 마지막 턴에서 15행 모두 MATCH.   시도 번호로 고르지 않는다 — 번호는 (시즌, 턴)마다 새로 매겨진다.
--       J2 sr_report.recon_mismatch_rows = 0 (이 시즌의 로그 전체에 MISMATCH 행이 없다)
--       J3 시즌이 FINISHED, current_turn_no = path_length, 성적표가 확정(is_final)
--       J4 턴 1..path_length 마다 코드 17개 묶음이 있고(턴 전환 대사), 범위 밖 턴의 로그가 0행(recon_out_of_range_rows = 0)
--       J5 pending_orders = 0, pending_changes = 0 (끝난 뒤 남은 주문·전술 변경이 없다)
--       J6 1턴 이후 주문과 전술 변경이 있다 (25 가 실행됐다 — 매매 없이 지나간 시즌을 PASS 로 두지 않는다)
--       J7 지금 데이터를 22 의 규칙 함수(recon_rules)로 다시 대사해 15규칙이 모두 MATCH (로그 없이 손으로 바꾼 데이터를 잡는다)
-- 판정의 틀 (단독성): 같은 판정 함수(zz_judge)를 양성(현재 상태)과 음성(서브트랜잭션 안에서 일부러 바꾼 상태)에 모두 돌려, 바꾼 결과 "실패하는 판정 집합"이
--       기대와 같은지 본다. 음성 사례는 UPDATE·DELETE 만 쓰고 INSERT 는 하지 않으며 사례마다 되돌린다. 04.1 이 J1–J7 각각에 음성 사례가 있는지(n/n) 확인한다.
--       J6 은 데이터를 지울 수 없어(외래 키) 판정 창(몇 턴부터 보는가)을 path_length+1 로 옮겨 "아무것도 없는" 경우를 만든다.
-- 출력: 건수와 판정만. 가격·수량·금액은 내지 않는다.
-- 흔적: 파일 전체가 한 트랜잭션이고 판정 뒤에 ROLLBACK 한다. 05절이 로그·주문·시즌의 행 수·상태 지문·identity 마지막 값이 실행 전과 같은지 확인한다.
-- 판정 계약: 통과면 출력 끝 두 줄이 `p0_21_check` / `PASS`. 실패면 ON_ERROR_STOP=1 에서는 `P0-21 … 실패` 오류와 종료 코드 3으로 끝나 판정 줄이 없다.
--       끝 두 줄이 `PASS` 가 아니면 실패다. ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 마지막 줄을 본다.
-- 새 문법: 서브트랜잭션 안에서 바꿔 보고 RAISE 로 되돌리며 결과를 예외 메시지로 꺼내기(GET STACKED DIAGNOSTICS), 배열 집합 비교, generate_series 로 턴마다 확인.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

\ir 22_reconcile_season_def.sql
\ir 26_season_report_def.sql

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 00–04절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

-- 흔적 확인용 지문: 행 수·identity 마지막 값, 로그·주문·시즌의 내용 지문. 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE OR REPLACE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    c text;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['season', 'cash_account', 'cash_ledger', 'orders', 'execution', 'holding', 'lineup', 'turn_price', 'formation_change',
                             'reconciliation_log', 'turn', 'turn_feed'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            v := v || t || '=없음;';
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
        SELECT attname INTO c FROM pg_attribute WHERE attrelid = ('public.' || t)::regclass AND attidentity <> '' LIMIT 1;
        s := CASE WHEN c IS NULL THEN '-' ELSE COALESCE(pg_sequence_last_value(pg_get_serial_sequence('public.' || t, c)::regclass)::text, 'null') END;
        v := v || t || '=' || n || '/' || s || ';';
    END LOOP;
    v := v || 'log=' || COALESCE((SELECT md5(string_agg(concat_ws(',', id, season_id, turn_no, attempt_no, check_code, status, mismatch_count, classification), ';' ORDER BY id))
                                  FROM reconciliation_log), '-')
           || ';orders=' || COALESCE((SELECT md5(string_agg(concat_ws(',', id, season_id, turn_no, status), ';' ORDER BY id)) FROM orders), '-')
           || ';season=' || COALESCE((SELECT md5(string_agg(concat_ws(',', id, status, current_turn_no, version), ';' ORDER BY id)) FROM season), '-');
    RETURN v;
END $$;
SET client_min_messages TO warning;
DROP TABLE IF EXISTS pg_temp.zz_before;   -- 정리용 DROP 은 항상 pg_temp. 로 한정한다 (임시 표가 없을 때 public 의 같은 이름 표를 지우지 않게)
RESET client_min_messages;
CREATE TEMP TABLE zz_before AS SELECT pg_temp.zz_snap() AS snap;

-- 판정 함수: 연습 시즌 하나에 대해 J1–J7 을 한 행씩 낸다. p_from_turn 은 J6 이 "몇 턴부터 주문·전술 변경을 보는가"(기본 1)이다.
CREATE OR REPLACE FUNCTION pg_temp.zz_judge(p_sid bigint, p_from_turn smallint DEFAULT 1)
RETURNS TABLE (code text, ok boolean, actual text)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    r_final  boolean;
    r_turns  smallint;
    r_mm     integer;
    r_oor    integer;
    r_po     integer;
    r_pc     integer;
    v_manual text[];
    v_all    text[];
    m        record;
    v_bundles integer;
    v_orders integer;
    v_changes integer;
    v_len    smallint;
    v_cur    smallint;
    v_stat   text;
    v_bad    integer;
    v_oor    integer;
    v_pend_o integer;
    v_pend_c integer;
    v_rep_err boolean := false;
    v_rules  integer;
    v_rules_bad integer;
BEGIN
    SELECT array_agg(c.check_code::text ORDER BY c.check_code) FILTER (WHERE c.manual), array_agg(c.check_code::text ORDER BY c.check_code)
    INTO v_manual, v_all FROM pg_temp.recon_code c;
    SELECT s.path_length, s.current_turn_no, s.status INTO v_len, v_cur, v_stat FROM season s WHERE s.id = p_sid;
    -- 성적표 정의는 끝난 시즌에 마지막 턴의 주문·전술 변경이 있으면 스스로 오류를 낸다(REPORT_INCONSISTENT). 그 경우도 판정 실패로 읽는다.
    BEGIN
        SELECT x.is_final, x.report_turns, x.recon_mismatch_rows, x.recon_out_of_range_rows, x.pending_orders, x.pending_changes
        INTO r_final, r_turns, r_mm, r_oor, r_po, r_pc FROM pg_temp.sr_report(p_sid) x;
    EXCEPTION WHEN raise_exception THEN
        v_rep_err := true;
    END;
    -- 로그와 주문은 성적표와 별개로 표에서 직접 센다 (성적표 값과 같아야 한다)
    SELECT count(*) FILTER (WHERE l.status = 'MISMATCH'), count(*) FILTER (WHERE l.turn_no > v_len) INTO v_bad, v_oor
    FROM reconciliation_log l WHERE l.season_id = p_sid;
    SELECT count(*) INTO v_pend_o FROM orders o WHERE o.season_id = p_sid AND o.turn_no >= v_cur;
    SELECT count(*) INTO v_pend_c FROM formation_change fc WHERE fc.season_id = p_sid AND fc.turn_no >= v_cur;

    -- J1: 15행 묶음 중 id 가 가장 큰 것
    SELECT g.turn_no, g.n, g.bad INTO m
    FROM (SELECT l.turn_no, l.attempt_no, max(l.id) AS last_id, count(*) AS n, count(*) FILTER (WHERE l.status <> 'MATCH') AS bad,
                 array_agg(l.check_code::text ORDER BY l.check_code) AS cs
          FROM reconciliation_log l WHERE l.season_id = p_sid GROUP BY l.turn_no, l.attempt_no) g
    WHERE g.cs = v_manual ORDER BY g.last_id DESC LIMIT 1;
    code := 'J1 마지막 수동 대사 15행 MATCH';
    ok := m.n IS NOT NULL AND m.n = 15 AND m.bad = 0 AND m.turn_no = v_len;
    actual := CASE WHEN m.n IS NULL THEN '수동 묶음 없음' ELSE format('행 %s · 불일치 %s · 턴 %s', m.n, m.bad, m.turn_no) END;
    RETURN NEXT;

    code := 'J2 로그 전체 MISMATCH 0행';
    ok := v_bad = 0 AND (v_rep_err OR r_mm = v_bad);
    actual := format('불일치 행 %s · 성적표 %s', v_bad, COALESCE(r_mm::text, '오류'));
    RETURN NEXT;

    code := 'J3 FINISHED · 마지막 턴 · 성적표 확정';
    ok := v_stat = 'FINISHED' AND v_cur = v_len AND NOT v_rep_err AND r_final AND r_turns = v_len;
    actual := format('%s · 턴 %s/%s · 성적표 %s', v_stat, v_cur, v_len, CASE WHEN v_rep_err THEN '오류' WHEN r_final THEN '확정' ELSE '잠정' END);
    RETURN NEXT;

    SELECT count(*) INTO v_bundles
    FROM generate_series(1, v_len) t
    WHERE EXISTS (SELECT 1 FROM (SELECT array_agg(l.check_code::text ORDER BY l.check_code) AS cs
                                 FROM reconciliation_log l WHERE l.season_id = p_sid AND l.turn_no = t GROUP BY l.attempt_no) g
                  WHERE g.cs = v_all);
    code := 'J4 턴마다 17행 묶음 · 범위 밖 0행';
    ok := v_bundles = v_len AND v_oor = 0 AND (v_rep_err OR r_oor = v_oor);
    actual := format('17행 묶음이 있는 턴 %s/%s · 범위 밖 %s행', v_bundles, v_len, v_oor);
    RETURN NEXT;

    code := 'J5 남은 주문·전술 변경 0';
    ok := v_pend_o = 0 AND v_pend_c = 0 AND (v_rep_err OR (r_po = v_pend_o AND r_pc = v_pend_c));
    actual := format('주문 %s · 전술 변경 %s', v_pend_o, v_pend_c);
    RETURN NEXT;

    SELECT count(*) INTO v_orders FROM orders o WHERE o.season_id = p_sid AND o.turn_no >= p_from_turn;
    SELECT count(*) INTO v_changes FROM formation_change fc WHERE fc.season_id = p_sid AND fc.turn_no >= p_from_turn;
    code := 'J6 1턴 이후 주문·전술 변경 있음';
    ok := v_orders >= 1 AND v_changes >= 1;
    actual := format('%s턴부터 주문 %s · 전술 변경 %s', p_from_turn, v_orders, v_changes);
    RETURN NEXT;

    -- J7: 저장된 로그가 아니라 지금 데이터를 22 의 규칙 함수로 다시 대사한다 (새 계산이 아니라 기존 정의 호출, 쓰기 없음)
    SELECT count(*), count(*) FILTER (WHERE r.status <> 'MATCH') INTO v_rules, v_rules_bad FROM pg_temp.recon_rules(p_sid, v_cur) r;
    code := 'J7 지금 데이터 대사 15규칙 MATCH';
    ok := v_rules = 15 AND v_rules_bad = 0;
    actual := format('규칙 %s · 불일치 %s', v_rules, v_rules_bad);
    RETURN NEXT;
END $$;

-- 음성 사례 실행기: 서브트랜잭션 안에서 p_mutation(UPDATE·DELETE 한 문장)을 실행하고 실패하는 판정 코드 집합(앞 두 글자 J1…)을 돌려준다.
--   마지막에 일부러 예외를 던져 바꾼 것을 되돌리고, 결과는 예외 메시지로 꺼낸다. p_mutation 이 NULL 이면 바꾸지 않고 판정 창만 옮긴다.
CREATE OR REPLACE FUNCTION pg_temp.zz_neg(p_sid bigint, p_mutation text, p_from_turn smallint DEFAULT 1) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v_fail text;
    v_msg  text;
    v_n    bigint;
BEGIN
    BEGIN
        IF p_mutation IS NOT NULL THEN
            EXECUTE p_mutation;
            GET DIAGNOSTICS v_n = ROW_COUNT;
            IF v_n < 1 THEN
                RAISE EXCEPTION 'zz_neg:바꾼 행이 없다';
            END IF;
        END IF;
        SELECT COALESCE(string_agg(left(j.code, 2), ',' ORDER BY j.code), '-') INTO v_fail FROM pg_temp.zz_judge(p_sid, p_from_turn) j WHERE NOT j.ok;
        RAISE EXCEPTION 'zz_neg:%', v_fail;
    EXCEPTION WHEN raise_exception THEN
        GET STACKED DIAGNOSTICS v_msg = MESSAGE_TEXT;
        IF v_msg NOT LIKE 'zz_neg:%' THEN
            RAISE;
        END IF;
        RETURN substr(v_msg, 8);
    END;
END $$;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '00 전제' … '04 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

-- 00 전제 -------------------------------------------------------------------
-- 연습 시즌이 정확히 1개이고, 필요한 표·정의가 있고, 시즌이 FINISHED 여야 한다. 아니면 여기서 결과를 보여 주고 멈춘다.
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', '연습 시즌(이메일 phase0@example.invalid) 개수', '1', n::text, n = 1
FROM (SELECT count(*) AS n FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid')) q;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', '시즌 상태 FINISHED (10턴을 끝낸 뒤)', 'FINISHED', COALESCE(string_agg(s.status, ','), '-'), COALESCE(bool_and(s.status = 'FINISHED'), false)
FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid');
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', '코드 표 recon_code 17행(수동 15)', '17/15', count(*) || '/' || count(*) FILTER (WHERE manual), count(*) = 17 AND count(*) FILTER (WHERE manual) = 15
FROM pg_temp.recon_code;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '00 전제', '수동 대사(코드 15개 묶음)가 1개 이상', '>= 1', n::text, n >= 1
FROM (SELECT count(*) AS n FROM (SELECT l.turn_no, l.attempt_no FROM reconciliation_log l
                                 WHERE l.season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid'))
                                 GROUP BY l.turn_no, l.attempt_no
                                 HAVING array_agg(l.check_code::text ORDER BY l.check_code) = (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.manual)) g) q;

SELECT section, item, expected, actual FROM zz_check_result WHERE section = '00 전제' AND NOT ok ORDER BY seq;
SELECT '00 전제 FAIL 건수' AS item, count(*) AS n FROM zz_check_result WHERE section = '00 전제' AND NOT ok;

DO $$
DECLARE
    v_fail integer;
BEGIN
    SELECT count(*) INTO v_fail FROM zz_check_result WHERE section = '00 전제' AND NOT ok;
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-21 전제 실패: 00절 FAIL % 건 (README 「전체 순서」를 S36 까지 실행했는지 확인한다. 시즌은 FINISHED 여야 한다)', v_fail;
    END IF;
END $$;

SELECT s.id AS sid, s.path_length AS plen
FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = lower('phase0@example.invalid')
\gset

-- 01 양성: 현재 상태가 J1–J7 을 모두 만족한다 ------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '01 양성', j.code, '충족', j.actual, j.ok FROM pg_temp.zz_judge(:sid) j;

-- 02 음성: 일부러 바꾸면 해당 판정이 실패한다 -------------------------------------
-- 마지막 수동 묶음의 한 행만 MISMATCH 로 (status·분류·건수 짝을 함께 바꾼다) → J1 과 J2
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N1 마지막 수동 묶음의 한 행만 MISMATCH', 'J1,J2', x, x = 'J1,J2'
FROM (SELECT pg_temp.zz_neg(:sid, format($m$
    UPDATE reconciliation_log SET status = 'MISMATCH', mismatch_count = 1, classification = 'INVESTIGATE'
    WHERE id = (SELECT max(l.id) FROM reconciliation_log l WHERE l.season_id = %s
                AND (l.turn_no, l.attempt_no) IN (SELECT g.turn_no, g.attempt_no FROM reconciliation_log g WHERE g.season_id = %s GROUP BY g.turn_no, g.attempt_no
                                                  HAVING array_agg(g.check_code::text ORDER BY g.check_code) = (SELECT array_agg(c.check_code::text ORDER BY c.check_code) FROM pg_temp.recon_code c WHERE c.manual)))$m$, :sid, :sid)) AS x) q;
-- 앞 수동 묶음(첫 수동 대사)의 한 행이 MISMATCH 면 마지막은 MATCH 라 J1 은 통과하고 J2 만 실패한다
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N2 앞 수동 묶음(0턴)의 한 행이 MISMATCH', 'J2', x, x = 'J2'
FROM (SELECT pg_temp.zz_neg(:sid, format($m$
    UPDATE reconciliation_log SET status = 'MISMATCH', mismatch_count = 1, classification = 'INVESTIGATE'
    WHERE id = (SELECT min(l.id) FROM reconciliation_log l WHERE l.season_id = %s AND l.turn_no = 0)$m$, :sid)) AS x) q;
-- 턴 전환 묶음(17행)의 한 행이 MISMATCH → J2
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N3 턴 3 전환 묶음의 한 행이 MISMATCH', 'J2', x, x = 'J2'
FROM (SELECT pg_temp.zz_neg(:sid, format($m$
    UPDATE reconciliation_log SET status = 'MISMATCH', mismatch_count = 1, classification = 'INVESTIGATE'
    WHERE id = (SELECT min(l.id) FROM reconciliation_log l WHERE l.season_id = %s AND l.turn_no = 3)$m$, :sid)) AS x) q;
-- 시즌이 아직 진행 중으로 → J3 (마지막 수동 묶음도 시즌의 마지막 턴이 아니게 되는 것은 아니다. 상태만 바꾼다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N4 시즌 상태를 IN_PROGRESS 로', 'J3', x, x = 'J3'
FROM (SELECT pg_temp.zz_neg(:sid, format($m$UPDATE season SET status = 'IN_PROGRESS' WHERE id = %s$m$, :sid)) AS x) q;
-- 턴 3 전환 묶음 17행을 지우면 → J4
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N5 턴 3 전환 묶음 17행 삭제', 'J4', x, x = 'J4'
FROM (SELECT pg_temp.zz_neg(:sid, format($m$DELETE FROM reconciliation_log WHERE season_id = %s AND turn_no = 3$m$, :sid)) AS x) q;
-- 로그 한 행의 턴이 path_length 를 넘으면 → J4 (범위 밖)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N6 로그 한 행의 턴이 범위 밖(path_length+1)', 'J4', x, x = 'J4'
FROM (SELECT pg_temp.zz_neg(:sid, format($m$
    UPDATE reconciliation_log SET turn_no = %s + 1
    WHERE id = (SELECT min(l.id) FROM reconciliation_log l WHERE l.season_id = %s AND l.turn_no = 0)$m$, :plen, :sid)) AS x) q;
-- 끝난 시즌에 마지막 턴 주문이 남아 있으면(전술 변경과 묶이지 않은 주문 하나를 마지막 턴으로) → J5 와, 성적표가 스스로 거절해 J3, 주문 데이터가 달라져 지금 데이터 대사 J7
--   (성적표 정의는 끝난 시즌의 마지막 턴 주문을 REPORT_INCONSISTENT 로 거절한다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N7 주문 하나가 마지막 턴에 남음', 'J3,J5,J7', x, x = 'J3,J5,J7'
FROM (SELECT pg_temp.zz_neg(:sid, format($m$
    UPDATE orders SET turn_no = %s
    WHERE id = (SELECT min(o.id) FROM orders o WHERE o.season_id = %s AND o.turn_no >= 1 AND o.turn_no < %s AND o.formation_change_id IS NULL)$m$, :plen, :sid, :plen)) AS x) q;
-- 판정 창을 path_length+1 로 옮기면 "1턴 이후" 행이 없다 → J6
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N8 판정 창을 path_length+1 로(주문·전술 변경 없음)', 'J6', x, x = 'J6'
FROM (SELECT pg_temp.zz_neg(:sid, NULL, (:plen + 1)::smallint) AS x) q;

-- 로그 없이 데이터만 손으로 바꾸면(체결 수수료 +1) 저장된 로그는 그대로라 J1–J6 은 통과하고, 지금 데이터 대사 J7 이 잡는다
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '02 음성', 'N9 로그 없이 체결 수수료를 손으로 바꿈', 'J7', x, x = 'J7'
FROM (SELECT pg_temp.zz_neg(:sid, format($m$
    UPDATE execution SET fee = fee + 1
    WHERE id = (SELECT min(e.id) FROM execution e JOIN orders o ON o.id = e.order_id WHERE o.season_id = %s)$m$, :sid)) AS x) q;

-- 03 정보: 건수만 (해석은 README 「결과 읽는 법」) --------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '03 정보', '대사 로그 묶음: 턴 전환(17행) · 수동(15행) · 범위 밖 행 · 불일치 행', '(정보)',
       r.recon_turn_bundles || ' · ' || r.recon_manual_bundles || ' · ' || r.recon_out_of_range_rows || ' · ' || r.recon_mismatch_rows, true
FROM pg_temp.sr_report(:sid) r;
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '03 정보', '시즌: 턴 수 · 전술 변경 건수 · 주문 건수', '(정보)',
       :plen || ' · ' || (SELECT count(*) FROM formation_change WHERE season_id = :sid) || ' · ' || (SELECT count(*) FROM orders WHERE season_id = :sid), true;

-- 04 판정 -------------------------------------------------------------------
-- 04.1 커버리지: J1–J7 각각에 음성 사례가 있어 그 판정이 실패하는 것을 실제로 보았다
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '04 판정', '음성 사례 커버리지 (J1–J7 각각 실패를 확인)', '7/7', count(*) FILTER (WHERE hit) || '/' || count(*), count(*) FILTER (WHERE hit) = count(*)
FROM (SELECT j, EXISTS (SELECT 1 FROM zz_check_result n WHERE n.section = '02 음성' AND n.ok AND n.actual LIKE '%' || j || '%') AS hit
      FROM unnest(ARRAY['J1', 'J2', 'J3', 'J4', 'J5', 'J6', 'J7']) AS j) q;

-- 04.2 FAIL 행 목록
SELECT section, item, expected, actual FROM zz_check_result WHERE NOT ok ORDER BY seq;

-- 04.3 집계 (PASS 출력에서도 건수가 보이게)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail FROM zz_check_result GROUP BY section ORDER BY section;
SELECT item, expected, actual FROM zz_check_result WHERE section IN ('03 정보', '04 판정') ORDER BY seq;

-- 04.4 판정을 종료 코드로: FAIL 이 있으면 오류로 끝난다 (ON_ERROR_STOP=1 이면 종료 코드 3).
--      판정 표식은 트랜잭션이 되돌아가도 남는다. 통과해도 2(흔적 확인 대기)까지만 올린다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-21 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 05 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 읽거나 일부러 바꿔 본 표들의 행 수·identity 마지막 값·내용 지문이 실행 전과 같고, public 에 zz_ 객체가 없다.
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
        RAISE EXCEPTION E'P0-21 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    -- 단언을 모두 통과했고 00–04절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_21_check;
