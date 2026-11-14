-- =====================================================================
-- my Fund Manager Phase 0 — 통계 보드 검증 SQL (P0-12)
-- 대상: 통계 보드 정의 db/14_stats_calendar_def.sql(임시 뷰 stat_obs·stat_peak·stat_board …)이 만드는 값
-- 근거: docs/erd.md 5.3·D34, docs/PLAN.md §4.5·§4.11·§5.2, db/13_calc_attention_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/14_stats_calendar_check.sql
--       적재(10 → 11 → 13) 뒤에 실행한다. 정의 파일을 글자 그대로 읽어 변이 시험에 쓰므로 저장소 루트에서만 돈다 (0절이 확인한다).
--       INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아 실습 DB(antlab)에서도 돌릴 수 있다.
-- 판정: 마지막 줄 `p0_12_check` 가 `PASS` 이고 종료 코드가 0이면 통과다.
--       0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고, 그 밖에 하나라도 어긋나면 8절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 8절 뒤에 ROLLBACK 한다. 9절이 행 수·identity 마지막 값이 실행 전과 같은지 확인한다.
-- 절:   0 전제(FAIL 이면 여기서 멈춤) → 1 관측 → 2 집계 → 3 표본 수 → 4 손 계산 → 5 누출 → 6 정점 → 7 타입 → 8 판정 → ROLLBACK → 9 흔적
--       음성 사례(규칙을 일부러 어긴 뒤 잡히는지)는 사례가 속한 규칙의 절에 "음성 [규칙]" 행으로 쌓인다.
-- 규칙 코드 20개 (규칙마다 양성 쿼리 1개 + 음성 사례 1개 이상, 8절이 커버리지 N/N 을 확인한다):
--       OBS_CAL OBS_EVENT OBS_ATTN / BOARD_VALUE / N_HIDE N_LABEL N_BOUNDARY / HAND_MEAN ZERO_NOT_UP FWD_PRODUCT FWD_BREAK ROUND_ONCE /
--       CUTOFF_YEAR PEAK_NO_FUTURE PEAK_MOVED PEAK_TEETH SEASON_ISOLATION NO_PER_OBS / PEAK_RULE / NO_FLOAT_NAN
-- 독립 계산: 정의는 재귀 CTE·내부 조인·GROUP BY 로 만들고, 여기서는 다른 방법으로 다시 만든다.
--       관측 = 달력 전체(month_block)에 시리즈를 펼친 표(zz_dense)에서 윈도 함수 LEAD 로 m + j개월 연속 검사, 정점 = "나보다 지수가 큰 행 수 + 1",
--       집계 = 정수 산술(합 × 10^24 를 div 로 나눠 0에서 먼 쪽 반올림)이라 소수 나눗셈의 반올림에 기대지 않는다. 상수(5, 10, 0.10, {1, 3})는 erd D34 에서 다시 옮겨 적었다.
-- 변이 시험: 정의 파일의 글자를 replace 로 바꿔 이름만 zzm_ 으로 다시 만든다(zz_def 표 + mutate 프로시저). 바꾼 글자가 정의에 없으면 오류라 시험이 헛돌지 않는다.
--       뷰 정의 텍스트(pg_get_viewdef)에 기대는 replace 는 쓰지 않는다. 변이는 서브트랜잭션 안에서만 만들고 되돌린다.
-- 손 계산: 합성 수익률(시세 아님)을 한 종목에 UPDATE 로 덮어쓴 뒤 보드가 손으로 계산한 값을 돌려주는지 본다. 서브트랜잭션에서 되돌린다.
-- 음성 사례는 UPDATE/DELETE 와 변이 뷰만 쓴다. INSERT 는 쓰지 않는다.
-- P0-08·P0-09·P0-11 판정 계약에서 복사한 것: ① pg_temp.zz_verdict ② 결과 표 zz_check_result ④ check_eq + val()
--       ⑤ 0절 뒤 멈춤과 판정(표식 2 또는 1) ⑥ 9절 흔적 확인(표식 0)과 마지막 CASE.
-- 새 문법: psql 백틱(\set x `cat …`)과 :'x' 인용, 프로시저(CALL), EXECUTE 로 만든 SQL, 재귀 카탈로그 질의(pg_depend), EXCEPT ALL,
--       FULL JOIN … USING, div() 정수 나눗셈, make_date, 서브트랜잭션(PL/pgSQL 예외 블록)으로 "바꿔 보고 되돌리기".
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 정의 파일 글자 그대로 (변이 시험용). 저장소 루트가 아니면 비어 있고 0절이 FAIL 한다
\set def_text `cat db/14_stats_calendar_def.sql`

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 1–8절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

-- 흔적 확인용 스냅샷: 읽거나 일부러 바꿔 보는 표들의 행 수와 identity 마지막 값. 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['asset_position', 'month_block', 'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            v := v || t || '=없음;';
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
        s := COALESCE(pg_sequence_last_value(pg_get_serial_sequence('public.' || t, 'id')::regclass)::text, 'null');
        v := v || t || '=' || n || '/' || s || ';';
    END LOOP;
    RETURN v;
END $$;
CREATE TEMP TABLE zz_before AS SELECT pg_temp.zz_snap() AS snap;

BEGIN;

-- 결과 표: 모든 판정이 한 행씩 쌓인다 (세션 임시, 트랜잭션과 함께 사라진다)
CREATE TEMP TABLE zz_check_result (
    seq      integer GENERATED ALWAYS AS IDENTITY,
    section  text    NOT NULL,   -- '0 전제' '1 관측' '2 집계' '3 표본 수' '4 손 계산' '5 누출' '6 정점' '7 타입' '8 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

-- 시험 설정: pfx = 지금 시험하는 뷰 이름 접두사 ('stat' = 정식 정의, 'zzm' = 변이 정의), leak_scale = 샌 순위 변이가 다음 달 지수를 섞는 정도
CREATE TEMP TABLE zz_cfg (pfx text NOT NULL, leak_scale numeric NOT NULL);
INSERT INTO zz_cfg VALUES ('stat', 1);

-- 판정 프로시저 ------------------------------------------------------------

-- 값 비교. NULL끼리는 같다고 본다.
CREATE PROCEDURE pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- 검사 쿼리의 {pfx} 를 지금 시험하는 접두사로 바꾼다
CREATE FUNCTION pg_temp.sub(p_sql text) RETURNS text
LANGUAGE sql AS $$ SELECT replace(p_sql, '{pfx}', (SELECT pfx FROM zz_cfg)) $$;

-- 스칼라 질의 한 개를 실행해 값을 text로 돌려준다. 정확히 한 행이어야 한다. 0행은 NULL, 여러 행은 기대값과 같을 수 없는 'ERROR too_many_rows'.
CREATE FUNCTION pg_temp.val(p_sql text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v_out text;
BEGIN
    EXECUTE pg_temp.sub(p_sql) INTO STRICT v_out;
    RETURN v_out;
EXCEPTION
    WHEN no_data_found THEN RETURN NULL;
    WHEN too_many_rows THEN RETURN 'ERROR too_many_rows';
END $$;

-- 정의 파일 글자 그대로를 담는다 (psql 이 :'def_text' 를 알맞게 인용한다)
CREATE TEMP TABLE zz_def AS SELECT :'def_text'::text AS txt;

-- 변이 프로시저: 정의 글자를 [바꿀 글자, 새 글자, …] 쌍으로 바꾸고 stat_ 을 zzm_ 으로 고쳐 다시 만든 뒤 시험 접두사를 zzm 으로 바꾼다.
--   바꿀 글자가 정의에 없으면 오류 (변이가 헛돌지 않게). 새 글자도 stat_ 이름으로 적는다.
CREATE PROCEDURE pg_temp.mutate(p_pairs text[])
LANGUAGE plpgsql AS $$
DECLARE
    v_txt text := (SELECT txt FROM zz_def);
    i     integer;
BEGIN
    FOR i IN 1 .. array_length(p_pairs, 1) BY 2 LOOP
        IF position(p_pairs[i] IN v_txt) = 0 THEN
            RAISE EXCEPTION 'zz_mut 바꿀 글자가 정의에 없다: %', p_pairs[i];
        END IF;
        v_txt := replace(v_txt, p_pairs[i], p_pairs[i + 1]);
    END LOOP;
    EXECUTE replace(v_txt, 'stat_', 'zzm_');
    UPDATE zz_cfg SET pfx = 'zzm';
END $$;

-- 정의를 읽는다 (임시 뷰 stat_k … stat_board 가 만들어진다)
\ir 14_stats_calendar_def.sql

-- 0절 대조용: cat 으로 읽은 글자를 이름만 zzi_ 로 바꿔 만든다 (\ir 로 읽은 정의와 같은 파일인지 본다)
DO $$ BEGIN IF length((SELECT txt FROM zz_def)) > 1000 THEN EXECUTE replace((SELECT txt FROM zz_def), 'stat_', 'zzi_'); END IF; END $$;

-- 독립 계산 -----------------------------------------------------------------------
-- 시리즈: 기준일 풀의 종목 + 환율 (정의와 다른 문자열 조립)
CREATE TEMP VIEW zz_ser AS
SELECT p.base_date, format('%s:%s', a.market, a.ticker) AS subject, a.id AS asset_id, a.market::text AS market, a.currency::text AS currency
FROM asset_position p JOIN asset a ON a.id = p.asset_id
UNION ALL
SELECT d.base_date, format('FX:%s', f.currency), NULL::bigint, NULL::text, f.currency::text
FROM (SELECT DISTINCT base_date FROM asset_position) d CROSS JOIN (SELECT DISTINCT currency FROM fx_month) f;

-- 펼친 표: 시리즈 × 달력 전체(month_block)의 달 (기준일 해 이전). 수익률이 없는 달은 NULL 로 남는다
CREATE TEMP TABLE zz_dense AS
SELECT s.base_date, s.subject, s.market, b.month_start, r.local_return AS ret
FROM zz_ser s CROSS JOIN month_block b
LEFT JOIN asset_month_return r ON r.asset_id = s.asset_id AND r.month_block_id = b.id
WHERE s.asset_id IS NOT NULL AND b.month_start < make_date(EXTRACT(YEAR FROM s.base_date)::integer, 1, 1)
UNION ALL
SELECT s.base_date, s.subject, NULL, b.month_start, f.fx_change
FROM zz_ser s CROSS JOIN month_block b
LEFT JOIN fx_month f ON f.currency = s.currency AND f.month_block_id = b.id
WHERE s.asset_id IS NULL AND b.month_start < make_date(EXTRACT(YEAR FROM s.base_date)::integer, 1, 1);

-- 앞 창: LEAD 로 m + 1·2·3 번째 행을 끌어온다. 그 행의 달이 정확히 m + j개월이고 수익률이 있어야 창이 완전하다 (끊김이면 달이 어긋난다)
CREATE TEMP TABLE zz_win AS
WITH l AS (
    SELECT d.base_date, d.subject, d.month_start,
           lead(d.month_start, 1) OVER w AS m1, lead(d.ret, 1) OVER w AS r1,
           lead(d.month_start, 2) OVER w AS m2, lead(d.ret, 2) OVER w AS r2,
           lead(d.month_start, 3) OVER w AS m3, lead(d.ret, 3) OVER w AS r3
    FROM zz_dense d
    WINDOW w AS (PARTITION BY d.base_date, d.subject ORDER BY d.month_start)
)
SELECT l.base_date, l.subject, l.month_start AS anchor_month, 1::smallint AS horizon, l.r1 AS ret
FROM l
WHERE l.m1 = (l.month_start + INTERVAL '1 month')::date AND l.r1 IS NOT NULL
UNION ALL
SELECT l.base_date, l.subject, l.month_start, 3::smallint, (1 + l.r1) * (1 + l.r2) * (1 + l.r3) - 1
FROM l
WHERE l.m1 = (l.month_start + INTERVAL '1 month')::date AND l.m2 = (l.month_start + INTERVAL '2 month')::date
  AND l.m3 = (l.month_start + INTERVAL '3 month')::date
  AND l.r1 IS NOT NULL AND l.r2 IS NOT NULL AND l.r3 IS NOT NULL;

-- 정점: 순위 = 1 + (같은 기준일 풀·같은 달에서 지수가 더 큰 행 수), n = 지수 있는 행 수, 정점 = 순위 <= CEIL(0.10 × n)
CREATE TEMP TABLE zz_peak_ind AS
SELECT t.*, (t.rk <= CEIL(0.10 * t.n)) AS is_peak
FROM (SELECT p.base_date, a.month_block_id, mb.month_start, a.asset_id, format('%s:%s', s.market, s.ticker) AS subject, s.market::text AS market,
             a.attention_index,
             (1 + (SELECT count(*) FROM asset_month_attention a2 JOIN asset_position p2 ON p2.asset_id = a2.asset_id AND p2.base_date = p.base_date
                   WHERE a2.month_block_id = a.month_block_id AND a2.attention_index > a.attention_index))::integer AS rk,
             (SELECT count(*) FROM asset_month_attention a2 JOIN asset_position p2 ON p2.asset_id = a2.asset_id AND p2.base_date = p.base_date
              WHERE a2.month_block_id = a.month_block_id AND a2.attention_index IS NOT NULL)::integer AS n
      FROM asset_position p
      JOIN asset s ON s.id = p.asset_id
      JOIN asset_month_attention a ON a.asset_id = p.asset_id
      JOIN month_block mb ON mb.id = a.month_block_id
      WHERE a.attention_index IS NOT NULL
        AND mb.month_start < make_date(EXTRACT(YEAR FROM p.base_date)::integer, 1, 1)) t;

-- 손 계산·경계 시험에 쓰는 종목(풀 첫 종목)과 가장 늦은 기준일
CREATE TEMP VIEW zz_s1 AS
SELECT a.id AS asset_id, format('%s:%s', a.market, a.ticker) AS subject, (SELECT max(base_date) FROM asset_position) AS base_date
FROM asset a WHERE a.id = (SELECT min(asset_id) FROM asset_position);

-- 그 종목의 3개월 창이 완전한 태그 달 (달마다 태그 행 하나). 합성 사건 유형으로 바꿔 쓸 행이다
CREATE TEMP VIEW zz_anchor AS
SELECT DISTINCT ON (w.anchor_month) w.anchor_month AS month_start, e.id AS event_row_id
FROM zz_win w
JOIN zz_s1 s ON s.subject = w.subject AND s.base_date = w.base_date
JOIN month_block b ON b.month_start = w.anchor_month
JOIN month_event e ON e.month_block_id = b.id
WHERE w.horizon = 3
ORDER BY w.anchor_month, e.id;

-- 정점 H8 시험 도구 ------------------------------------------------------------------
-- 기준 달 M 목록 (표본 구조를 아는 값: 지수가 있는 구간의 가운데·끝, 2012-04 끝, 2024 시작, 마지막 달의 직전 달)
CREATE TEMP TABLE zz_pk_m (month_start date PRIMARY KEY);
INSERT INTO zz_pk_m VALUES (DATE '2008-06-01'), (DATE '2010-06-01'), (DATE '2012-03-01'), (DATE '2012-04-01'),
                           (DATE '2024-01-01'), (DATE '2024-06-01'), (DATE '2025-11-01');
CREATE TEMP TABLE zz_pk      AS SELECT * FROM stat_peak WITH NO DATA;
CREATE TEMP TABLE zz_pk_base AS SELECT * FROM stat_peak WITH NO DATA;

-- 기준 달 M 마다 (a) M 뒤 지수를 모두 뒤집음 (b) M 바로 다음 지수 달만 뒤집음 (c) M 뒤 지수 행을 지움, 3종의 변이를 서브트랜잭션에서 하고
-- 지금 시험하는 정점 뷰를 다시 읽어 변이 전 결과(zz_pk_base)와 비교한 뒤 되돌린다.
--   'cut'   : M 이하 행이 변이 전과 다르거나 한쪽에만 있는 수의 합 (0 이어야 한다)
--   'moved' : M 뒤 행이 하나도 달라지지 않은 사례 수 (0 이어야 한다 — 시험이 헛돌지 않는다는 증거)
CREATE FUNCTION pg_temp.pk_count(p_part text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    r       record;
    v_next  date;
    v_cut   bigint;
    v_mov   bigint;
    v_total bigint := 0;
BEGIN
    DELETE FROM zz_pk_base;
    EXECUTE pg_temp.sub('INSERT INTO zz_pk_base SELECT * FROM {pfx}_peak');
    FOR r IN SELECT m.month_start AS m, k.kind
             FROM zz_pk_m m CROSS JOIN (VALUES ('UPD_ALL'), ('UPD_NEXT'), ('DEL_ALL')) AS k(kind)
             ORDER BY m.month_start, k.kind LOOP
        v_cut := NULL; v_mov := NULL;
        BEGIN
            SELECT min(mb.month_start) INTO v_next FROM month_block mb
            WHERE mb.month_start > r.m AND EXISTS (SELECT 1 FROM asset_month_attention a WHERE a.month_block_id = mb.id AND a.attention_index IS NOT NULL);
            IF r.kind = 'UPD_ALL' THEN
                -- 지수를 1 − x 로 뒤집는다 ([−3, 4] 안에 머문다). 순위가 거의 다 바뀐다
                UPDATE asset_month_attention a SET attention_index = 1 - a.attention_index
                FROM month_block mb WHERE mb.id = a.month_block_id AND mb.month_start > r.m AND a.attention_index IS NOT NULL;
            ELSIF r.kind = 'UPD_NEXT' THEN
                UPDATE asset_month_attention a SET attention_index = 1 - a.attention_index
                FROM month_block mb WHERE mb.id = a.month_block_id AND mb.month_start = v_next AND a.attention_index IS NOT NULL;
            ELSE
                DELETE FROM asset_month_attention a USING month_block mb WHERE mb.id = a.month_block_id AND mb.month_start > r.m;
            END IF;
            DELETE FROM zz_pk;
            EXECUTE pg_temp.sub('INSERT INTO zz_pk SELECT * FROM {pfx}_peak');
            IF p_part = 'cut' THEN
                SELECT count(*) INTO v_cut
                FROM (SELECT * FROM zz_pk_base WHERE month_start <= r.m) t
                FULL JOIN (SELECT * FROM zz_pk WHERE month_start <= r.m) v ON v.base_date = t.base_date AND v.month_block_id = t.month_block_id AND v.asset_id = t.asset_id
                WHERE t.asset_id IS NULL OR v.asset_id IS NULL
                   OR (t.attention_index, t.attention_rank, t.n_ranked, t.is_peak) IS DISTINCT FROM (v.attention_index, v.attention_rank, v.n_ranked, v.is_peak);
            ELSE
                SELECT count(*) INTO v_mov
                FROM (SELECT * FROM zz_pk_base WHERE month_start > r.m) t
                FULL JOIN (SELECT * FROM zz_pk WHERE month_start > r.m) v ON v.base_date = t.base_date AND v.month_block_id = t.month_block_id AND v.asset_id = t.asset_id
                WHERE t.asset_id IS NULL OR v.asset_id IS NULL
                   OR (t.attention_index, t.attention_rank, t.n_ranked, t.is_peak) IS DISTINCT FROM (v.attention_index, v.attention_rank, v.n_ranked, v.is_peak);
            END IF;
            RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
        EXCEPTION WHEN OTHERS THEN
            IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
        END;
        IF p_part = 'cut' THEN
            v_total := v_total + v_cut;
        ELSE
            v_total := v_total + CASE WHEN v_mov = 0 THEN 1 ELSE 0 END;
        END IF;
    END LOOP;
    RETURN v_total;
END $$;

-- 샌 정점 변이: 같은 달 지수에 다음 달 지수를 섞어 순위를 매긴다 (미래를 보는 정의). 섞는 정도는 zz_cfg.leak_scale
CREATE PROCEDURE pg_temp.mut_leak()
LANGUAGE plpgsql AS $$
BEGIN
    CALL pg_temp.mutate(ARRAY[
        'ORDER BY a.attention_index DESC) AS attention_rank',
        'ORDER BY a.attention_index + (SELECT leak_scale FROM zz_cfg) * COALESCE((SELECT n2.attention_index FROM asset_month_attention n2 JOIN month_block nb ON nb.id = n2.month_block_id WHERE n2.asset_id = a.asset_id AND nb.month_start = (mb.month_start + INTERVAL ''1 month'')::date), 0) DESC) AS attention_rank']);
END $$;

-- 이빨 확인: 샌 정점 변이를 같은 H8 시험이 잡으면 0, 못 잡으면 1
CREATE FUNCTION pg_temp.peak_teeth() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v bigint;
BEGIN
    BEGIN
        CALL pg_temp.mut_leak();
        v := (pg_temp.pk_count('cut') = 0)::integer;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 순위 대상이 줄어든 달(CEIL / FLOOR 구분, 4절 아닌 6절용): 가장 늦은 기준일 풀의 2024-01 지수 5개를 NULL 로 만들어 순위 대상을 줄이고
-- (z 는 그대로라 index_needs_z CHECK 통과) 정점 뷰의 그 달 is_peak·n_ranked 를 데이터 변경 뒤 다시 계산한 독립 판정과 비교한다. zz_peak_ind 는 낡으므로 쓰지 않는다.
CREATE FUNCTION pg_temp.peak_ceil() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v      bigint := 0;
    v_bd   date;
    v_m    bigint;
    v_n    bigint;
BEGIN
    SELECT max(base_date) INTO v_bd FROM asset_position;
    SELECT id INTO v_m FROM month_block WHERE month_start = DATE '2024-01-01';
    BEGIN
        UPDATE asset_month_attention SET attention_index = NULL
        WHERE month_block_id = v_m AND attention_index IS NOT NULL
          AND asset_id IN (SELECT a.asset_id FROM asset_month_attention a JOIN asset_position p ON p.asset_id = a.asset_id AND p.base_date = v_bd
                           WHERE a.month_block_id = v_m AND a.attention_index IS NOT NULL ORDER BY a.asset_id LIMIT 5);
        GET DIAGNOSTICS v_n = ROW_COUNT;
        v := v + abs(v_n - 5);
        EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_peak WHERE base_date = $1 AND month_block_id = $2 AND n_ranked <> 15') INTO v_n USING v_bd, v_m;
        v := v + v_n;
        EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_peak WHERE base_date = $1 AND month_block_id = $2') INTO v_n USING v_bd, v_m;
        v := v + CASE WHEN v_n = 15 THEN 0 ELSE 1 END;               -- 순위 대상이 실제로 15개가 되었는가 (헛돌지 않게)
        EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_peak o WHERE o.base_date = $1 AND o.month_block_id = $2 AND o.is_peak IS DISTINCT FROM
                  ((1 + (SELECT count(*) FROM asset_month_attention a2 JOIN asset_position p2 ON p2.asset_id = a2.asset_id AND p2.base_date = o.base_date
                         WHERE a2.month_block_id = o.month_block_id AND a2.attention_index > o.attention_index)) <= CEIL(0.10 * 15))') INTO v_n USING v_bd, v_m;
        v := v + v_n;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 보드 한 행이 기대와 같은지. p_cols: 'MEAN' = n·평균, 'UP' = n_up·상승 확률, 'ALL' = 전부 (일화 표시 포함). 같으면 0, 다르면 1
CREATE FUNCTION pg_temp.board_row_bad(p_cols text, p_kind text, p_key text, p_subject text, p_h integer, p_n integer, p_up integer, p_mean numeric, p_prob numeric, p_note text) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
    v integer;
BEGIN
    EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_board WHERE kind = $1 AND cond_key = $2 AND subject = $3 AND horizon = $4 AND n = $5 AND ($10 = ''UP'' OR mean_return = $7) AND ($10 = ''MEAN'' OR (n_up = $6 AND up_prob = $8)) AND sample_note IS NOT DISTINCT FROM $9')
        INTO v USING p_kind, p_key, p_subject, p_h, p_n, p_up, p_mean, p_prob, p_note, p_cols;
    RETURN CASE WHEN v = 1 THEN 0 ELSE 1 END;
END $$;

-- 표본 수 경계(3절): 합성 사건 유형 ZZ_N4·ZZ_N5·ZZ_N9·ZZ_N10 (서로 다른 달의 태그 행을 START 로 바꿔 한 종목에서 n = 4·5·9·10)와
-- 달력 n = 4·5 (그 종목의 3월 수익률 행을 지움)가 보드에서 "없음·일화·일화·표시 없음", "없음·일화" 인가. 어긋난 수를 돌려준다
CREATE FUNCTION pg_temp.bnd_count() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v     bigint := 0;
    v_s1  text;
    v_a   bigint;
    v_upd bigint;
    v_c   bigint;
BEGIN
    SELECT subject, asset_id INTO v_s1, v_a FROM zz_s1;
    BEGIN
        WITH a AS (SELECT row_number() OVER (ORDER BY month_start) AS rn, event_row_id FROM zz_anchor)
        UPDATE month_event e
        SET event_type = CASE WHEN a.rn <= 4 THEN 'ZZ_N4' WHEN a.rn <= 9 THEN 'ZZ_N5' WHEN a.rn <= 18 THEN 'ZZ_N9' ELSE 'ZZ_N10' END,
            phase = 'START'
        FROM a WHERE a.event_row_id = e.id AND a.rn <= 28;
        GET DIAGNOSTICS v_upd = ROW_COUNT;
        v := v + abs(v_upd - 28);                                   -- 바꿀 태그 달 28개가 모두 있었는가
        EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_board WHERE kind = ''EVENT'' AND cond_key = ''ZZ_N4''') INTO v_c;
        v := v + v_c;                                               -- n = 4: 어느 시리즈에도 나오면 안 된다
        EXECUTE pg_temp.sub('SELECT (count(*) FILTER (WHERE n = 5 AND sample_note = ''일화'')) FROM {pfx}_board WHERE kind = ''EVENT'' AND cond_key = ''ZZ_N5'' AND subject = $1 AND horizon = 3') INTO v_c USING v_s1;
        v := v + CASE WHEN v_c = 1 THEN 0 ELSE 1 END;
        EXECUTE pg_temp.sub('SELECT (count(*) FILTER (WHERE n = 9 AND sample_note = ''일화'')) FROM {pfx}_board WHERE kind = ''EVENT'' AND cond_key = ''ZZ_N9'' AND subject = $1 AND horizon = 3') INTO v_c USING v_s1;
        v := v + CASE WHEN v_c = 1 THEN 0 ELSE 1 END;
        EXECUTE pg_temp.sub('SELECT (count(*) FILTER (WHERE n = 10 AND sample_note IS NULL)) FROM {pfx}_board WHERE kind = ''EVENT'' AND cond_key = ''ZZ_N10'' AND subject = $1 AND horizon = 3') INTO v_c USING v_s1;
        v := v + CASE WHEN v_c = 1 THEN 0 ELSE 1 END;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    -- 달력 n = 4: 3월 수익률을 앞의 4개만 남기고 지운다 → 보드에 그 행이 없어야 한다
    BEGIN
        DELETE FROM asset_month_return r USING month_block b
        WHERE r.asset_id = v_a AND b.id = r.month_block_id AND EXTRACT(MONTH FROM b.month_start) = 3
          AND b.month_start < make_date(EXTRACT(YEAR FROM (SELECT base_date FROM zz_s1))::integer, 1, 1)
          AND b.month_start NOT IN (SELECT b2.month_start FROM asset_month_return r2 JOIN month_block b2 ON b2.id = r2.month_block_id
                                    WHERE r2.asset_id = v_a AND EXTRACT(MONTH FROM b2.month_start) = 3 ORDER BY b2.month_start LIMIT 4);
        EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_board WHERE kind = ''CALENDAR'' AND cond_key = ''03'' AND subject = $1') INTO v_c USING v_s1;
        v := v + v_c;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    -- 달력 n = 5: 앞의 5개만 남기면 일화로 나온다
    BEGIN
        DELETE FROM asset_month_return r USING month_block b
        WHERE r.asset_id = v_a AND b.id = r.month_block_id AND EXTRACT(MONTH FROM b.month_start) = 3
          AND b.month_start < make_date(EXTRACT(YEAR FROM (SELECT base_date FROM zz_s1))::integer, 1, 1)
          AND b.month_start NOT IN (SELECT b2.month_start FROM asset_month_return r2 JOIN month_block b2 ON b2.id = r2.month_block_id
                                    WHERE r2.asset_id = v_a AND EXTRACT(MONTH FROM b2.month_start) = 3 ORDER BY b2.month_start LIMIT 5);
        EXECUTE pg_temp.sub('SELECT count(*) FILTER (WHERE n = 5 AND sample_note = ''일화'') FROM {pfx}_board WHERE kind = ''CALENDAR'' AND cond_key = ''03'' AND subject = $1') INTO v_c USING v_s1;
        v := v + CASE WHEN v_c = 1 THEN 0 ELSE 1 END;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 달력 손 계산(4절): 한 종목의 1월·2월 수익률 9개씩을 합성 값(시세 아님)으로 덮어쓰고 보드가 손 계산과 같은지 센다.
--   1월 값: 0.01 −0.02 0.00 0.03 0.01 −0.01 0.02 0.00 0.04 → 합 0.08, 평균 0.08 / 9 = 0.00888889, 오른 달 5개(0 수익률 둘은 상승이 아니다), 5 / 9 = 0.55555556
--   2월 값: 0.00 0.00 0.01 −0.01 0.02 0.00 0.03 −0.02 0.01 → 합 0.04, 평균 0.04 / 9 = 0.00444444, 오른 달 4개(0 수익률 셋), 4 / 9 = 0.44444444
--   p_part = 'MEAN' 은 n·평균, 'ZERO' 는 n_up·상승 확률을 본다. 덮어쓸 달이 9개씩 없으면 어긋남으로 센다
CREATE FUNCTION pg_temp.hand_cal(p_part text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v      bigint := 0;
    v_s1   text;
    v_a    bigint;
    v_cut  date;
    v_upd  bigint;
    v_jan  numeric[] := ARRAY[0.01, -0.02, 0.00, 0.03, 0.01, -0.01, 0.02, 0.00, 0.04];
    v_feb  numeric[] := ARRAY[0.00, 0.00, 0.01, -0.01, 0.02, 0.00, 0.03, -0.02, 0.01];
    v_m    integer;
    v_vals numeric[];
BEGIN
    SELECT subject, asset_id, make_date(EXTRACT(YEAR FROM base_date)::integer, 1, 1) INTO v_s1, v_a, v_cut FROM zz_s1;
    BEGIN
        FOREACH v_m IN ARRAY ARRAY[1, 2] LOOP
            v_vals := CASE WHEN v_m = 1 THEN v_jan ELSE v_feb END;
            WITH t AS (SELECT r.id, row_number() OVER (ORDER BY b.month_start) AS rn
                       FROM asset_month_return r JOIN month_block b ON b.id = r.month_block_id
                       WHERE r.asset_id = v_a AND EXTRACT(MONTH FROM b.month_start) = v_m AND b.month_start < v_cut)
            UPDATE asset_month_return r SET local_return = v_vals[t.rn] FROM t WHERE r.id = t.id AND t.rn <= 9;
            GET DIAGNOSTICS v_upd = ROW_COUNT;
            v := v + abs(v_upd - 9);
        END LOOP;
        v := v + pg_temp.board_row_bad(CASE WHEN p_part = 'MEAN' THEN 'MEAN' ELSE 'UP' END, 'CALENDAR', '01', v_s1, 0, 9, 5, 0.00888889, 0.55555556, '일화');
        v := v + pg_temp.board_row_bad(CASE WHEN p_part = 'MEAN' THEN 'MEAN' ELSE 'UP' END, 'CALENDAR', '02', v_s1, 0, 9, 4, 0.00444444, 0.44444444, '일화');
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 앞 창 손 계산(4절): 한 종목에서 서로 4개월 이상 떨어진 태그 달 5개를 합성 사건 유형의 START 로 바꾸고, 그 뒤 3개월 수익률을 합성 값으로 덮어쓴다.
--   p_code = 'PRODUCT' (유형 ZZ_HAND): 창 5개의 (1월차, 2월차, 3월차) 수익률과 누적 곱
--       (0.10, −0.10, 0.10)  → 1.1 × 0.9 × 1.1 − 1 = 0.089
--       (0.00,  0.05, 0.05)  → 1.0 × 1.05 × 1.05 − 1 = 0.1025
--       (−0.02, −0.02, −0.02) → 0.98³ − 1 = −0.058808
--       (0.01,  0.02, 0.03)  → 1.01 × 1.02 × 1.03 − 1 = 0.061106
--       (0.10,  0.10, 0.10)  → 1.1³ − 1 = 0.331
--     3개월: 합 0.524798, 평균 0.1049596 → 0.10495960, 오른 창 4개 → 0.8.   1개월(1월차만): 0.10 0.00 −0.02 0.01 0.10 → 합 0.19, 평균 0.038, 오른 창 3개 → 0.6
--   p_code = 'ROUND' (유형 ZZ_ROUND): 창 4개 (0.06, 0.0000001, 0) → 1.06 × 1.0000001 − 1 = 0.060000106 (소수 9자리, 반올림하면 0.06000011), 창 1개 (0.06, 0, 0) → 0.06
--     3개월: 합 0.300000424, 평균 0.0600000848 → 한 번 반올림 0.06000008. 관측값을 먼저 반올림하면 (4 × 0.06000011 + 0.06) / 5 = 0.060000088 → 0.06000009 로 달라진다.
--     1개월: 평균 0.06, 모두 상승 → 1.0
CREATE FUNCTION pg_temp.hand_fwd(p_code text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v       bigint := 0;
    v_s1    text;
    v_a     bigint;
    v_bd    date;
    r       record;
    v_last  date;
    v_cnt   integer := 0;
    v_type  text := CASE WHEN p_code = 'PRODUCT' THEN 'ZZ_HAND' ELSE 'ZZ_ROUND' END;
    v_w     numeric[];
    v_upd   bigint;
    j       integer;
    -- 창 5개 × 3개월: 1차원 배열로 평평하게 (창 i 의 j 월차 = v_all[(i − 1) × 3 + j])
    v_all   numeric[] := CASE WHEN p_code = 'PRODUCT'
                              THEN ARRAY[0.10, -0.10, 0.10,  0.00, 0.05, 0.05,  -0.02, -0.02, -0.02,  0.01, 0.02, 0.03,  0.10, 0.10, 0.10]
                              ELSE ARRAY[0.06, 0.0000001, 0,  0.06, 0.0000001, 0,  0.06, 0.0000001, 0,  0.06, 0.0000001, 0,  0.06, 0, 0] END;
BEGIN
    SELECT subject, asset_id, base_date INTO v_s1, v_a, v_bd FROM zz_s1;
    BEGIN
        FOR r IN SELECT month_start, event_row_id FROM zz_anchor ORDER BY month_start LOOP
            IF v_cnt < 5 AND (v_last IS NULL OR r.month_start >= (v_last + INTERVAL '4 month')::date) THEN
                v_cnt := v_cnt + 1;
                v_last := r.month_start;
                UPDATE month_event SET event_type = v_type, phase = 'START' WHERE id = r.event_row_id;
                FOR j IN 1 .. 3 LOOP
                    UPDATE asset_month_return ar SET local_return = v_all[(v_cnt - 1) * 3 + j]
                    FROM month_block b
                    WHERE ar.asset_id = v_a AND b.id = ar.month_block_id AND b.month_start = (r.month_start + make_interval(months => j))::date;
                    GET DIAGNOSTICS v_upd = ROW_COUNT;
                    v := v + abs(v_upd - 1);
                END LOOP;
            END IF;
        END LOOP;
        v := v + abs(v_cnt - 5);                                    -- 서로 떨어진 태그 달 5개가 있었는가
        IF p_code = 'PRODUCT' THEN
            v := v + pg_temp.board_row_bad('ALL', 'EVENT', v_type, v_s1, 3, 5, 4, 0.10495960, 0.80000000, '일화');
            v := v + pg_temp.board_row_bad('ALL', 'EVENT', v_type, v_s1, 1, 5, 3, 0.03800000, 0.60000000, '일화');
        ELSE
            v := v + pg_temp.board_row_bad('ALL', 'EVENT', v_type, v_s1, 3, 5, 5, 0.06000008, 1.00000000, '일화');
            v := v + pg_temp.board_row_bad('ALL', 'EVENT', v_type, v_s1, 1, 5, 5, 0.06000000, 1.00000000, '일화');
        END IF;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 기준일 해 경계(5절 CUTOFF_YEAR): 가장 늦은 기준일을 2025-06-02 로 옮기면(판정 8컬럼은 전부-또는-없음 CHECK 때문에 함께 NULL) 기준일이 속한 해(2025)의 달이 생긴다.
--   그 달들은 보드의 관측에 들어가면 안 된다. 관측의 마지막 창 달(anchor + horizon)이 2025-01-01 이상인 관측 수 + 시험이 헛돌지 않는지(2025 달이 실제로 있고 관측이 있는가)
CREATE FUNCTION pg_temp.cutoff_count() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v      bigint := 0;
    v_bd   date;
    v_c    bigint;
BEGIN
    SELECT max(base_date) INTO v_bd FROM asset_position;
    BEGIN
        UPDATE asset_position
        SET base_date = DATE '2025-06-02', window_start = NULL, window_end = NULL, months_used = NULL,
            up_participation = NULL, down_participation = NULL, volatility = NULL, max_drawdown = NULL, system_position = NULL
        WHERE base_date = v_bd;
        EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_obs WHERE (anchor_month + make_interval(months => horizon::integer))::date >= DATE ''2025-01-01''') INTO v_c;
        v := v + v_c;
        EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_peak WHERE month_start >= DATE ''2025-01-01''') INTO v_c;
        v := v + v_c;
        SELECT count(*) INTO v_c FROM month_block WHERE month_start >= DATE '2025-01-01';
        v := v + CASE WHEN v_c > 0 THEN 0 ELSE 1 END;               -- 2025 달이 DB 에 있어야 시험이 의미가 있다
        EXECUTE pg_temp.sub('SELECT count(*) FROM {pfx}_obs WHERE kind = ''CALENDAR''') INTO v_c;
        v := v + CASE WHEN v_c > 0 THEN 0 ELSE 1 END;
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 정점 뷰가 읽는 표 집합과 지수·풀·종목·달 4개 표의 대칭차 크기 (P0-18 이 이 뷰를 피드 입력으로 쓰므로 같은 달 수익률 같은 지수 밖 입력을 막는다)
CREATE FUNCTION pg_temp.peak_dep_diff() RETURNS bigint
LANGUAGE sql AS $$
    WITH RECURSIVE walk(oid) AS (
        SELECT to_regclass('pg_temp.' || (SELECT pfx FROM zz_cfg) || '_peak')::oid
        UNION
        SELECT d.refobjid
        FROM walk w
        JOIN pg_rewrite rw ON rw.ev_class = w.oid
        JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = rw.oid AND d.refclassid = 'pg_class'::regclass
        WHERE d.refobjid <> w.oid
    ), tbl AS (
        SELECT c.relname::text AS name
        FROM walk w JOIN pg_class c ON c.oid = w.oid
        WHERE c.relkind IN ('r', 'p', 'm', 'f')
    ), want(name) AS (
        VALUES ('asset'), ('asset_position'), ('asset_month_attention'), ('month_block')
    )
    SELECT (SELECT count(*) FROM tbl WHERE name NOT IN (SELECT name FROM want))
         + (SELECT count(*) FROM want WHERE name NOT IN (SELECT name FROM tbl))
$$;

-- 뷰가 읽는 실제 표 집합과 기대 집합(역사 7개)의 대칭차 크기 (시즌·계좌 표를 읽으면 늘어난다). 뷰 → 뷰 → 표로 pg_depend 를 따라간다
CREATE FUNCTION pg_temp.dep_diff() RETURNS bigint
LANGUAGE sql AS $$
    WITH RECURSIVE root AS (
        SELECT to_regclass('pg_temp.' || (SELECT pfx FROM zz_cfg) || '_' || n)::oid AS oid
        FROM unnest(ARRAY['series', 'month', 'peak', 'fwd', 'obs', 'board', 'k']) AS n
    ), walk(oid) AS (
        SELECT oid FROM root
        UNION
        SELECT d.refobjid
        FROM walk w
        JOIN pg_rewrite rw ON rw.ev_class = w.oid
        JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = rw.oid AND d.refclassid = 'pg_class'::regclass
        WHERE d.refobjid <> w.oid
    ), tbl AS (
        SELECT c.relname::text AS name
        FROM walk w JOIN pg_class c ON c.oid = w.oid
        WHERE c.relkind IN ('r', 'p', 'm', 'f')
    ), want(name) AS (
        VALUES ('asset'), ('asset_position'), ('month_block'), ('asset_month_return'), ('fx_month'), ('month_event'), ('asset_month_attention')
    )
    SELECT (SELECT count(*) FROM tbl WHERE name NOT IN (SELECT name FROM want))
         + (SELECT count(*) FROM want WHERE name NOT IN (SELECT name FROM tbl))
$$;

-- 0 전제 -------------------------------------------------------------------
-- 하나라도 FAIL 이면 1절 이후 결과를 믿지 않는다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('7개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '7',
        (SELECT count(*)::text FROM unnest(ARRAY['asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('정의 파일을 글자 그대로 읽었다 (저장소 루트에서 실행했다 — 변이 시험 재료)', 'true', (SELECT (length(txt) > 1000 AND position('CREATE TEMP VIEW stat_board' IN txt) > 0)::text FROM zz_def)),
    ('정의 파일을 \ir 로 읽었다 (임시 뷰 stat_k · stat_series · stat_month · stat_peak · stat_fwd · stat_obs · stat_board 7개)', '7',
        (SELECT count(*)::text FROM unnest(ARRAY['stat_k', 'stat_series', 'stat_month', 'stat_peak', 'stat_fwd', 'stat_obs', 'stat_board']) AS v WHERE to_regclass('pg_temp.' || v) IS NOT NULL)),
    ('cat 으로 읽은 정의와 \ir 로 읽은 정의가 같은 파일이다 (뷰 7개의 정의 글자가 같다 — 다른 체크아웃에서 실행하면 어긋난다)', '7',
        (SELECT count(*)::text FROM unnest(ARRAY['k', 'series', 'month', 'peak', 'fwd', 'obs', 'board']) AS n
         WHERE to_regclass('pg_temp.zzi_' || n) IS NOT NULL
           AND replace(pg_get_viewdef(to_regclass('pg_temp.zzi_' || n), true), 'zzi_', 'stat_') = pg_get_viewdef(to_regclass('pg_temp.stat_' || n), true))),
    ('10 적재가 끝나 있다 (asset_month_return 이 비어 있지 않다)', 'true', (SELECT (count(*) > 0)::text FROM asset_month_return)),
    ('11 적재가 끝나 있다 (month_event 에 START 행이 있다)', 'true', (SELECT (count(*) > 0)::text FROM month_event WHERE phase = 'START')),
    ('13 계산이 끝나 있다 (asset_month_attention 이 비어 있지 않다 — 빈 표로 헛 PASS 하지 않는다)', 'true', (SELECT (count(*) > 0)::text FROM asset_month_attention)),
    ('기준일(asset_position.base_date)이 하나 이상 있다', 'true', (SELECT (count(DISTINCT base_date) >= 1)::text FROM asset_position)),
    ('기준일이 하나다 (표본 구조 전제 — 손 계산·경계·기준일 이동 시험이 기준일을 구분하지 않는다. 기준일이 늘면 이 시험들에 base_date 를 넘기도록 고친다)', '1',
        (SELECT count(DISTINCT base_date)::text FROM asset_position)),
    ('A9 같은 (유형, 달)에 START 가 둘인 경우 0 (조용히 합치지 않는다 — 있으면 사건 n 이 이중으로 센다)', '0',
        (SELECT count(*)::text FROM (SELECT event_type, month_block_id FROM month_event WHERE phase = 'START' GROUP BY 1, 2 HAVING count(*) > 1) d)),
    ('독립 관측이 비어 있지 않다: 달력 / 사건 / 정점이 모두 있다 (빈 결과가 통과로 끝나지 않게)', 'true',
        (SELECT (EXISTS (SELECT 1 FROM zz_dense WHERE ret IS NOT NULL)
                 AND EXISTS (SELECT 1 FROM zz_win w JOIN month_block b ON b.month_start = w.anchor_month JOIN month_event e ON e.month_block_id = b.id AND e.phase = 'START')
                 AND EXISTS (SELECT 1 FROM zz_win w JOIN zz_peak_ind p ON p.subject = w.subject AND p.month_start = w.anchor_month AND p.base_date = w.base_date))::text)),
    ('표본의 달 구간 끝과 시작 (2012-04 ↔ 2023-01 끊김 — 표본 구조 전제, MVP 에서는 이 행을 갱신한다)', '2012-04-01,2023-01-01',
        (SELECT string_agg(d::text, ',' ORDER BY d) FROM
            (SELECT mb.month_start AS d FROM month_block mb
             WHERE (mb.month_start = DATE '2012-04-01' OR mb.month_start = DATE '2023-01-01')
               AND NOT EXISTS (SELECT 1 FROM month_block x WHERE x.month_start > DATE '2012-04-01' AND x.month_start < DATE '2023-01-01')) s)),
    ('손 계산 종목의 1월·2월 수익률이 9개씩 있고 3월은 5개 이상이다 (표본 구조 전제)', '9,9,true',
        (SELECT (SELECT count(*) FROM asset_month_return r JOIN month_block b ON b.id = r.month_block_id JOIN zz_s1 s ON s.asset_id = r.asset_id
                 WHERE EXTRACT(MONTH FROM b.month_start) = 1 AND b.month_start < make_date(EXTRACT(YEAR FROM s.base_date)::integer, 1, 1))::text
             || ',' || (SELECT count(*) FROM asset_month_return r JOIN month_block b ON b.id = r.month_block_id JOIN zz_s1 s ON s.asset_id = r.asset_id
                 WHERE EXTRACT(MONTH FROM b.month_start) = 2 AND b.month_start < make_date(EXTRACT(YEAR FROM s.base_date)::integer, 1, 1))::text
             || ',' || ((SELECT count(*) FROM asset_month_return r JOIN month_block b ON b.id = r.month_block_id JOIN zz_s1 s ON s.asset_id = r.asset_id
                 WHERE EXTRACT(MONTH FROM b.month_start) = 3 AND b.month_start < make_date(EXTRACT(YEAR FROM s.base_date)::integer, 1, 1)) >= 5)::text)),
    ('합성 경계에 쓸 태그 달(그 종목의 3개월 창이 완전한 달) 28개 이상', 'true', (SELECT (count(*) >= 28)::text FROM zz_anchor)),
    ('서로 4개월 이상 떨어진 태그 달 5개 이상 (앞 창 손 계산 재료)', 'true',
        (SELECT (count(*) >= 5)::text FROM
            (SELECT month_start, month_start - lag(month_start) OVER (ORDER BY month_start) AS gap FROM zz_anchor) a WHERE a.gap IS NULL OR a.gap >= 120)),
    ('정점 H8 기준 달이 모두 지수 있는 달이다', (SELECT count(*)::text FROM zz_pk_m),
        (SELECT count(*)::text FROM zz_pk_m m JOIN month_block b ON b.month_start = m.month_start
         WHERE EXISTS (SELECT 1 FROM asset_month_attention a WHERE a.month_block_id = b.id AND a.attention_index IS NOT NULL)))
) AS x(item, expected, actual);

-- 정보 행: 현재 DB 이름 (FAIL 아님)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('0 전제', '현재 DB 이름 (정보)', '(정보)', current_database(), true);

-- 0절 결과를 먼저 보여 주고, FAIL 이 있으면 멈춘다 (표식은 1로 남아 ON_ERROR_STOP 없이도 마지막 줄이 FAIL 이다).
SELECT seq, item, expected, actual, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result
FROM zz_check_result
WHERE section = '0 전제'
ORDER BY seq;

DO $$
DECLARE
    v_fail integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok) INTO v_fail FROM zz_check_result WHERE section = '0 전제';
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-12 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 01 스키마와 10·11·13 적재·계산을 먼저 실행했는지, 저장소 루트인지 확인한다)', v_fail;
    END IF;
END $$;

-- 검사 쿼리 목록: 위반 개수를 돌려주는 쿼리. 양성(0건)과 음성(위반을 만든 뒤 기대한 개수)에 같은 쿼리를 쓴다. {pfx} 는 시험하는 뷰 접두사.
-- 금지 함수 글자 검사(NO_FLOAT_NAN)는 pg_get_viewdef(주석이 없는 뷰 정의)에 하므로 정의 파일 주석에 함수 이름을 써도 걸리지 않는다.
CREATE TEMP TABLE zz_q (code text PRIMARY KEY, section text NOT NULL, item text NOT NULL, sql text NOT NULL);
INSERT INTO zz_q (code, section, item, sql) VALUES
    ('OBS_CAL', '1 관측', '달력 관측(stat_obs CALENDAR)이 독립 계산(펼친 표 zz_dense 의 수익률 있는 달, to_char 로 구한 월)과 다른 행',
     $q$SELECT count(*) FROM (
          (SELECT base_date, cond_key, subject, anchor_month, ret FROM {pfx}_obs WHERE kind = 'CALENDAR' AND horizon = 0
           EXCEPT ALL
           SELECT d.base_date, to_char(d.month_start, 'MM'), d.subject, d.month_start, d.ret FROM zz_dense d WHERE d.ret IS NOT NULL)
          UNION ALL
          (SELECT d.base_date, to_char(d.month_start, 'MM'), d.subject, d.month_start, d.ret FROM zz_dense d WHERE d.ret IS NOT NULL
           EXCEPT ALL
           SELECT base_date, cond_key, subject, anchor_month, ret FROM {pfx}_obs WHERE kind = 'CALENDAR' AND horizon = 0)) d$q$),
    ('OBS_EVENT', '1 관측', '사건 관측(stat_obs EVENT)이 독립 계산(LEAD 로 연속 검사한 앞 창 × START 태그)과 다른 행 (기준 달 자신은 창에 없고, 창이 끊기면 관측이 없다)',
     $q$SELECT count(*) FROM (
          (SELECT base_date, cond_key, subject, horizon::integer, anchor_month, ret FROM {pfx}_obs WHERE kind = 'EVENT'
           EXCEPT ALL
           SELECT w.base_date, e.event_type::text, w.subject, w.horizon::integer, w.anchor_month, w.ret
           FROM zz_win w JOIN month_block b ON b.month_start = w.anchor_month JOIN month_event e ON e.month_block_id = b.id AND e.phase = 'START')
          UNION ALL
          (SELECT w.base_date, e.event_type::text, w.subject, w.horizon::integer, w.anchor_month, w.ret
           FROM zz_win w JOIN month_block b ON b.month_start = w.anchor_month JOIN month_event e ON e.month_block_id = b.id AND e.phase = 'START'
           EXCEPT ALL
           SELECT base_date, cond_key, subject, horizon::integer, anchor_month, ret FROM {pfx}_obs WHERE kind = 'EVENT')) d$q$),
    ('OBS_ATTN', '1 관측', '정점 관측(stat_obs ATTENTION)이 독립 계산(독립 순위 × LEAD 앞 창, 시장별)과 다른 행 (정점·비정점 구분과 시장 묶음 포함)',
     $q$SELECT count(*) FROM (
          (SELECT base_date, cond_key, subject, horizon::integer, anchor_month, ret FROM {pfx}_obs WHERE kind = 'ATTENTION'
           EXCEPT ALL
           SELECT w.base_date, CASE WHEN p.is_peak THEN 'PEAK' ELSE 'NON_PEAK' END, p.market, w.horizon::integer, w.anchor_month, w.ret
           FROM zz_win w JOIN zz_peak_ind p ON p.base_date = w.base_date AND p.subject = w.subject AND p.month_start = w.anchor_month)
          UNION ALL
          (SELECT w.base_date, CASE WHEN p.is_peak THEN 'PEAK' ELSE 'NON_PEAK' END, p.market, w.horizon::integer, w.anchor_month, w.ret
           FROM zz_win w JOIN zz_peak_ind p ON p.base_date = w.base_date AND p.subject = w.subject AND p.month_start = w.anchor_month
           EXCEPT ALL
           SELECT base_date, cond_key, subject, horizon::integer, anchor_month, ret FROM {pfx}_obs WHERE kind = 'ATTENTION')) d$q$),
    ('BOARD_VALUE', '2 집계', '보드(stat_board)가 독립 집계(관측을 n >= 5 그룹으로 묶어 합 × 10^24 의 정수 산술로 0에서 먼 쪽 반올림)와 다른 행: n·n_up·평균·상승 확률·일화 표시, 한쪽에만 있는 조건',
     $q$WITH g AS (
          SELECT base_date, kind, cond_key, subject, horizon, count(*) AS n,
                 sum(CASE WHEN ret > 0 THEN 1 ELSE 0 END) AS n_up, sum(ret) AS s
          FROM {pfx}_obs GROUP BY base_date, kind, cond_key, subject, horizon HAVING count(*) >= 5),
        x AS (
          SELECT g.*,
                 sign(g.s) * div(2 * abs(g.s * 1000000000000000000000000) + g.n::numeric * 10000000000000000, 2 * g.n::numeric * 10000000000000000) / 100000000 AS mean_x,
                 div(2 * g.n_up::numeric * 100000000 + g.n, 2 * g.n::numeric) / 100000000 AS up_x
          FROM g)
        SELECT count(*) FROM x
        FULL JOIN {pfx}_board b USING (base_date, kind, cond_key, subject, horizon)
        WHERE x.n IS NULL OR b.n IS NULL OR b.n IS DISTINCT FROM x.n OR b.n_up IS DISTINCT FROM x.n_up OR b.mean_return IS DISTINCT FROM x.mean_x OR b.up_prob IS DISTINCT FROM x.up_x
           OR b.sample_note IS DISTINCT FROM CASE WHEN x.n < 10 THEN '일화' END$q$),
    ('N_HIDE', '3 표본 수', 'n < 5 인 보드 행 수 + (n >= 5 인 관측 그룹 수 − 보드 행 수의 절댓값) + (n < 5 라 숨긴 조건이 하나도 없으면 1 — 빈 결과가 통과로 끝나지 않게)',
     $q$SELECT (SELECT count(*) FROM {pfx}_board WHERE n < 5)
             + abs((SELECT count(*) FROM (SELECT 1 FROM {pfx}_obs GROUP BY base_date, kind, cond_key, subject, horizon HAVING count(*) >= 5) g)
                   - (SELECT count(*) FROM {pfx}_board))
             + CASE WHEN (SELECT count(*) FROM (SELECT 1 FROM {pfx}_obs GROUP BY base_date, kind, cond_key, subject, horizon HAVING count(*) < 5) h) > 0 THEN 0 ELSE 1 END$q$),
    ('N_LABEL', '3 표본 수', 'sample_note 가 "5 <= n < 10 이면 일화, n >= 10 이면 NULL" 이 아닌 보드 행',
     $q$SELECT count(*) FROM {pfx}_board
        WHERE NOT COALESCE((n >= 5 AND n < 10 AND sample_note IS NOT DISTINCT FROM '일화') OR (n >= 10 AND sample_note IS NULL), false)$q$),
    ('N_BOUNDARY', '3 표본 수', '합성 경계 n = 4·5·9·10 이 각각 "없음·일화·일화·표시 없음"이 아닌 수 (사건 4종 + 달력 n = 4·5)',
     $q$SELECT pg_temp.bnd_count()$q$),
    ('HAND_MEAN', '4 손 계산', '합성 달력 수익률(1월·2월 9개씩)을 덮어쓴 종목의 n·평균·상승 확률이 손 계산과 다른 수 (평균 0.00888889 · 0.00444444)',
     $q$SELECT pg_temp.hand_cal('MEAN')$q$),
    ('ZERO_NOT_UP', '4 손 계산', '0 수익률이 상승으로 세어지는 수: 1월 오른 달 5개(0 둘), 2월 오른 달 4개(0 셋)와 다른 n_up·상승 확률',
     $q$SELECT pg_temp.hand_cal('ZERO')$q$),
    ('FWD_PRODUCT', '4 손 계산', '합성 앞 창 5개의 3개월 누적 곱·1개월 평균이 손 계산과 다른 수 (3개월 평균 0.10495960, 오른 창 4개 · 1개월 평균 0.03800000, 3개)',
     $q$SELECT pg_temp.hand_fwd('PRODUCT')$q$),
    ('FWD_BREAK', '4 손 계산', '앞 창이 달력 끊김(2012-04 ↔ 2023-01)을 넘는 관측 수: 정점 2012-02·2012-03 의 3개월, 2012-04 의 1개월이 없고 2012-01 의 3개월·2012-03 의 1개월은 있다 (헛돌지 않게). 모든 사건·정점 관측의 앞 창 달이 month_block 에 있다',
     $q$SELECT (SELECT count(*) FROM {pfx}_obs WHERE kind = 'ATTENTION' AND ((anchor_month IN (DATE '2012-02-01', DATE '2012-03-01') AND horizon = 3) OR (anchor_month = DATE '2012-04-01' AND horizon = 1)))
             + CASE WHEN (SELECT count(*) FROM {pfx}_obs WHERE kind = 'ATTENTION' AND anchor_month = DATE '2012-01-01' AND horizon = 3) > 0 THEN 0 ELSE 1 END
             + CASE WHEN (SELECT count(*) FROM {pfx}_obs WHERE kind = 'ATTENTION' AND anchor_month = DATE '2012-03-01' AND horizon = 1) > 0 THEN 0 ELSE 1 END
             + (SELECT count(*) FROM {pfx}_obs o
                WHERE o.horizon > 0 AND EXISTS (SELECT 1 FROM generate_series(1, o.horizon::integer) AS j
                                                WHERE NOT EXISTS (SELECT 1 FROM month_block b WHERE b.month_start = (o.anchor_month + make_interval(months => j))::date)))$q$),
    ('ROUND_ONCE', '4 손 계산', '합성 앞 창(관측값 소수 9자리)의 3개월 평균이 "합을 먼저 평균내고 한 번 반올림한 값"(0.06000008)이 아닌 수 (관측값을 먼저 반올림하면 0.06000009), 1개월 평균 0.06000000',
     $q$SELECT pg_temp.hand_fwd('ROUND')$q$),
    ('CUTOFF_YEAR', '5 누출', '관측의 마지막 달(기준 달 + 앞 창)이 기준일이 속한 해의 1월 이상인 관측 수 + 정점 판정이 그 해의 달을 읽은 수 (기준일을 2025-06-02 로 옮긴 시험 포함, 2025 달이 실제로 있는 경우만 의미)',
     $q$SELECT (SELECT count(*) FROM {pfx}_obs WHERE (anchor_month + make_interval(months => horizon::integer))::date >= make_date(EXTRACT(YEAR FROM base_date)::integer, 1, 1))
             + (SELECT count(*) FROM {pfx}_peak WHERE month_start >= make_date(EXTRACT(YEAR FROM base_date)::integer, 1, 1))
             + pg_temp.cutoff_count()$q$),
    ('PEAK_NO_FUTURE', '5 누출', '기준 달 M 뒤 지수를 바꾸거나 지운 상태에서 정점 판정을 다시 했을 때 M 이하 행이 변이 전과 다르거나 한쪽에만 있는 수 (사례 7개 달 × 변이 3종의 합) + 정점 뷰가 지수·풀·종목·달 표 밖을 읽는 수',
     $q$SELECT pg_temp.pk_count('cut') + pg_temp.peak_dep_diff()$q$),
    ('PEAK_MOVED', '5 누출', '기준 달 M 뒤 행이 하나도 달라지지 않은 사례 수 (시험이 헛돌지 않는다 — 변이가 M 뒤 행에 닿는다)',
     $q$SELECT pg_temp.pk_count('moved')$q$),
    ('PEAK_TEETH', '5 누출', '일부러 다음 달 지수를 섞어 순위를 매기는 정의(샌 변이)를 같은 시험이 잡지 못한 경우 (잡으면 0, 못 잡으면 1)',
     $q$SELECT pg_temp.peak_teeth()$q$),
    ('SEASON_ISOLATION', '5 누출', '뷰가 (뷰를 따라가 최종적으로) 읽는 표 집합과 역사 7개 표(asset, asset_position, month_block, asset_month_return, fx_month, month_event, asset_month_attention)의 대칭차 크기 (시즌·계좌 표를 읽으면 늘어난다)',
     $q$SELECT pg_temp.dep_diff()$q$),
    ('NO_PER_OBS', '5 누출', '보드 열이 기대한 10개(base_date·kind·cond_key·subject·horizon·n·n_up·mean_return·up_prob·sample_note)와 타입까지 다르면 1 — 날짜 열은 base_date 하나뿐이고 연도·개별 관측값 열이 없다',
     $q$SELECT ((SELECT string_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod), ',' ORDER BY a.attnum)
                 FROM pg_attribute a WHERE a.attrelid = to_regclass('pg_temp.{pfx}_board') AND a.attnum > 0 AND NOT a.attisdropped)
              IS DISTINCT FROM 'base_date:date,kind:text,cond_key:text,subject:text,horizon:smallint,n:integer,n_up:integer,mean_return:numeric,up_prob:numeric,sample_note:text')::integer$q$),
    ('PEAK_RULE', '6 정점', '정점 뷰(stat_peak)가 독립 정의(나보다 지수가 큰 행 수 + 1, 지수 있는 행 수, 순위 <= CEIL(0.10 × n))와 다른 행: 순위·n_ranked·is_peak·행 집합 (동률은 같은 순위, NULL 지수는 순위 대상이 아님) + 순위 대상 15개로 줄인 달의 CEIL 판정 차이',
     $q$SELECT (SELECT count(*) FROM (
          (SELECT base_date, month_block_id, asset_id, attention_rank, n_ranked, is_peak FROM {pfx}_peak
           EXCEPT ALL
           SELECT base_date, month_block_id, asset_id, rk, n, is_peak FROM zz_peak_ind)
          UNION ALL
          (SELECT base_date, month_block_id, asset_id, rk, n, is_peak FROM zz_peak_ind
           EXCEPT ALL
           SELECT base_date, month_block_id, asset_id, attention_rank, n_ranked, is_peak FROM {pfx}_peak)) d) + pg_temp.peak_ceil()$q$),
    ('NO_FLOAT_NAN', '7 타입', '뷰 열의 float4/float8 수 + 정의에 금지 함수(date_part, percentile_cont, ln, exp, power)·double precision·real 이 든 뷰 수 + NULL·NaN 이 든 행 + 소수 8자리를 넘거나 up_prob 가 [0, 1] 밖이거나 평균 정수부가 4자리 이상인 보드 행',
     $q$SELECT (SELECT count(*) FROM pg_attribute a
                WHERE a.attrelid IN (SELECT to_regclass('pg_temp.{pfx}_' || n) FROM unnest(ARRAY['series', 'month', 'peak', 'fwd', 'obs', 'board', 'k']) AS n)
                  AND a.attnum > 0 AND NOT a.attisdropped AND a.atttypid IN ('float4'::regtype, 'float8'::regtype))
             + (SELECT count(*) FROM unnest(ARRAY['series', 'month', 'peak', 'fwd', 'obs', 'board', 'k']) AS n
                WHERE pg_get_viewdef(to_regclass('pg_temp.{pfx}_' || n), true) ~* '\m(date_part|percentile_cont|ln|exp|power|float4|float8)\M\s*\(|double precision|::real\M')
             + (SELECT count(*) FROM {pfx}_obs WHERE ret = 'NaN'::numeric)
             + (SELECT count(*) FROM {pfx}_board WHERE n_up IS NULL OR mean_return IS NULL OR up_prob IS NULL OR mean_return::numeric = 'NaN'::numeric OR up_prob::numeric = 'NaN'::numeric
                   OR mean_return::numeric <> ROUND(mean_return::numeric, 8) OR up_prob::numeric <> ROUND(up_prob::numeric, 8) OR up_prob NOT BETWEEN 0 AND 1 OR abs(mean_return) >= 10000)$q$);

-- 1~7 양성: 위 쿼리 모두 위반 0건 ---------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT q.section, '[' || q.code || '] ' || q.item || ' — 위반 0건', '0', v.n, v.n IS NOT DISTINCT FROM '0'
FROM zz_q q CROSS JOIN LATERAL (SELECT pg_temp.val(q.sql) AS n OFFSET 0) v   -- 검사 쿼리는 한 번만 실행한다
ORDER BY q.section, q.code;

-- 정보 행 (FAIL 아님): 규모. 건수만 쓰고 수익률·평균·확률 값은 쓰지 않는다
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('1 관측', '관측 수: 달력 / 사건 / 정점 (정보)', '(정보)',
        pg_temp.val($s$SELECT count(*) FROM stat_obs WHERE kind = 'CALENDAR'$s$) || ' / ' || pg_temp.val($s$SELECT count(*) FROM stat_obs WHERE kind = 'EVENT'$s$) || ' / '
        || pg_temp.val($s$SELECT count(*) FROM stat_obs WHERE kind = 'ATTENTION'$s$), true),
       ('3 표본 수', '보드 행 수 / 숨긴 조건 수 (n < 5) / 일화 행 수 (정보)', '(정보)',
        pg_temp.val('SELECT count(*) FROM stat_board') || ' / '
        || pg_temp.val('SELECT count(*) FROM (SELECT 1 FROM stat_obs GROUP BY base_date, kind, cond_key, subject, horizon HAVING count(*) < 5) h') || ' / '
        || pg_temp.val($s$SELECT count(*) FROM stat_board WHERE sample_note IS NOT NULL$s$), true),
       ('3 표본 수', '종류별 보드 행 수: 달력 / 사건 / 정점 (정보)', '(정보)',
        pg_temp.val($s$SELECT count(*) FROM stat_board WHERE kind = 'CALENDAR'$s$) || ' / ' || pg_temp.val($s$SELECT count(*) FROM stat_board WHERE kind = 'EVENT'$s$) || ' / '
        || pg_temp.val($s$SELECT count(*) FROM stat_board WHERE kind = 'ATTENTION'$s$), true);

-- 음성 사례: 일부러 위반을 만들면 그 규칙의 검사 쿼리가 기대한 개수로 잡는지 (서브트랜잭션 안의 UPDATE/DELETE 또는 변이 뷰, 끝에 되돌린다) ----------
-- expect: 기대 위반 수. cmp = 'ge' 이면 그 수 이상이면 통과. alt: 변이가 CHECK 에 막혀 이 오류 코드로 끝나도 통과
CREATE TEMP TABLE zz_neg (n serial, q text NOT NULL, label text NOT NULL, mutate text NOT NULL, expect text NOT NULL DEFAULT '1',
                          cmp text NOT NULL DEFAULT 'eq', alt text);
INSERT INTO zz_neg (q, label, mutate, expect, cmp, alt) VALUES
    ('OBS_CAL', '정의가 달력 월을 한 달 밀어 구하면',
     $m$CALL pg_temp.mutate(ARRAY['lpad(EXTRACT(MONTH FROM m.month_start)::text, 2, ''0'')', 'lpad(EXTRACT(MONTH FROM m.month_start + INTERVAL ''1 month'')::text, 2, ''0'')'])$m$, '1', 'ge', NULL),
    ('OBS_EVENT', '정의의 앞 창이 기준 달 자신부터 시작하면 (m … m + h − 1, 앞 창 0개월 변이)',
     $m$CALL pg_temp.mutate(ARRAY['f.month_start = (b.month_start + INTERVAL ''1 month'')::date', 'f.month_start = (b.month_start + INTERVAL ''0 month'')::date',
                                  'make_interval(months => c.j + 1)', 'make_interval(months => c.j)'])$m$, '1', 'ge', NULL),
    ('OBS_ATTN', '정의가 정점과 비정점 이름을 뒤바꾸면',
     $m$CALL pg_temp.mutate(ARRAY['CASE WHEN p.is_peak THEN ''PEAK'' ELSE ''NON_PEAK'' END', 'CASE WHEN p.is_peak THEN ''NON_PEAK'' ELSE ''PEAK'' END'])$m$, '1', 'ge', NULL),
    ('BOARD_VALUE', '정의가 상승 확률을 소수 7자리로 반올림하면',
     $m$CALL pg_temp.mutate(ARRAY['/ count(*), 8) AS up_prob', '/ count(*), 7) AS up_prob'])$m$, '1', 'ge', NULL),
    ('BOARD_VALUE', '정의가 정점 행의 평균을 NULL 로 내면',
     $m$CALL pg_temp.mutate(ARRAY['ROUND(SUM(o.ret)::numeric(60,40) / count(*), 8) AS mean_return', 'CASE WHEN o.kind = ''ATTENTION'' THEN NULL ELSE ROUND(SUM(o.ret)::numeric(60,40) / count(*), 8) END AS mean_return'])$m$, '1', 'ge', NULL),
    ('N_HIDE', '정의가 n 이 1 이상이면 모두 내면 (HAVING 을 느슨하게)',
     $m$CALL pg_temp.mutate(ARRAY['HAVING count(*) >= (SELECT min_n FROM stat_k)', 'HAVING count(*) >= 1'])$m$, '1', 'ge', NULL),
    ('N_LABEL', '정의가 일화 상한을 9 로 낮추면 (n = 9 인 달력 행에 표시가 빠진다)',
     $m$CALL pg_temp.mutate(ARRAY['count(*) < (SELECT anecdote_n FROM stat_k)', 'count(*) < 9'])$m$, '1', 'ge', NULL),
    ('N_BOUNDARY', '정의가 n = 4 도 내면 (최소 n 을 4 로)',
     $m$CALL pg_temp.mutate(ARRAY['HAVING count(*) >= (SELECT min_n FROM stat_k)', 'HAVING count(*) >= 4'])$m$, '1', 'ge', NULL),
    ('N_BOUNDARY', '정의가 n = 10 에도 일화를 붙이면 (< 를 <= 로)',
     $m$CALL pg_temp.mutate(ARRAY['count(*) < (SELECT anecdote_n FROM stat_k)', 'count(*) <= (SELECT anecdote_n FROM stat_k)'])$m$, '1', 'ge', NULL),
    ('HAND_MEAN', '정의가 평균을 n + 1 로 나누면',
     $m$CALL pg_temp.mutate(ARRAY['::numeric(60,40) / count(*), 8) AS mean_return', '::numeric(60,40) / (count(*) + 1), 8) AS mean_return'])$m$, '1', 'ge', NULL),
    ('ZERO_NOT_UP', '정의가 0 수익률도 상승으로 세면 (> 0 을 >= 0 으로)',
     $m$CALL pg_temp.mutate(ARRAY['FILTER (WHERE o.ret > 0)', 'FILTER (WHERE o.ret >= 0)'])$m$, '1', 'ge', NULL),
    ('FWD_PRODUCT', '정의가 누적 곱 대신 수익률을 더하면',
     $m$CALL pg_temp.mutate(ARRAY['c.acc * (1 + f.ret)', 'c.acc + f.ret'])$m$, '1', 'ge', NULL),
    ('FWD_BREAK', '달력 끊김이 없어지면 (2023-01 이후 달을 128개월 당겨 2012-05 부터 이어 붙인다 — 시험이 끊김 위치의 기대를 실제로 확인한다)',
     $m$UPDATE month_block SET month_start = (month_start - INTERVAL '128 months')::date WHERE month_start >= DATE '2023-01-01'$m$, '1', 'ge', NULL),
    ('ROUND_ONCE', '정의가 관측값을 먼저 8자리로 반올림해 합하면',
     $m$CALL pg_temp.mutate(ARRAY['SUM(o.ret)::numeric(60,40)', 'SUM(ROUND(o.ret, 8))::numeric(60,40)'])$m$, '1', 'ge', NULL),
    ('CUTOFF_YEAR', '정의가 기준일이 속한 해의 달까지 쓰면 (올해 제외를 1년 늦추면)',
     $m$CALL pg_temp.mutate(ARRAY['mb.month_start < date_trunc(''year'', s.base_date::timestamp)::date', 'mb.month_start < (date_trunc(''year'', s.base_date::timestamp) + INTERVAL ''1 year'')::date',
                                  'mb.month_start < date_trunc(''year'', p.base_date::timestamp)::date', 'mb.month_start < (date_trunc(''year'', p.base_date::timestamp) + INTERVAL ''1 year'')::date'])$m$, '1', 'ge', NULL),
    ('PEAK_NO_FUTURE', '정점 순위가 다음 달 지수를 섞으면 (zz_leak: 다음 달 값을 읽는 변이)',
     $m$CALL pg_temp.mut_leak()$m$, '1', 'ge', NULL),
    ('PEAK_NO_FUTURE', '정점 순위가 다음 달 수익률을 섞으면 (지수 밖의 표를 읽는 정점 정의)',
     $m$CALL pg_temp.mutate(ARRAY['ORDER BY a.attention_index DESC) AS attention_rank', 'ORDER BY a.attention_index + COALESCE((SELECT r2.local_return FROM asset_month_return r2 JOIN month_block nb ON nb.id = r2.month_block_id WHERE r2.asset_id = a.asset_id AND nb.month_start = (mb.month_start + INTERVAL ''1 month'')::date), 0) DESC) AS attention_rank'])$m$, '1', 'ge', NULL),
    ('PEAK_MOVED', '기준 달 목록에 마지막 달을 더하면 (뒤에 바뀔 행이 없어 시험이 헛돈다 — 변이 3종 모두)',
     $m$INSERT INTO zz_pk_m SELECT max(month_start) FROM month_block$m$, '3', 'eq', NULL),
    ('PEAK_TEETH', '샌 변이가 다음 달 지수를 섞지 않게 하면 (시험이 잡을 것이 없다)',
     $m$UPDATE zz_cfg SET leak_scale = 0$m$, '1', 'eq', NULL),
    ('SEASON_ISOLATION', '정의가 season 표를 조인하면 (시즌에 연결된 보드)',
     $m$CALL pg_temp.mutate(ARRAY['JOIN asset a ON a.id = p.asset_id', 'JOIN asset a ON a.id = p.asset_id AND (SELECT count(*) FROM season) >= 0'])$m$, '1', 'ge', NULL),
    ('NO_PER_OBS', '정의가 보드에 가장 이른 관측 달 열을 더하면',
     $m$CALL pg_temp.mutate(ARRAY['THEN ''일화'' END AS sample_note', 'THEN ''일화'' END AS sample_note, min(o.anchor_month) AS first_month'])$m$, '1', 'eq', NULL),
    ('PEAK_RULE', '정의의 RANK() 를 DENSE_RANK() 로 바꾸면 (동률 뒤 순위가 달라진다)',
     $m$CALL pg_temp.mutate(ARRAY['RANK() OVER (PARTITION BY p.base_date', 'DENSE_RANK() OVER (PARTITION BY p.base_date'])$m$, '1', 'ge', NULL),
    ('PEAK_RULE', '정의의 NULL 제외 조건을 빼면',
     $m$CALL pg_temp.mutate(ARRAY['WHERE a.attention_index IS NOT NULL', 'WHERE TRUE'])$m$, '1', 'ge', NULL),
    ('PEAK_RULE', '정의의 정점 상한을 FLOOR 로 바꾸면 (순위 대상 15개 달에서 정점이 줄어든다)',
     $m$CALL pg_temp.mutate(ARRAY['CEIL(k.peak_q * t.n_ranked)', 'FLOOR(k.peak_q * t.n_ranked)'])$m$, '1', 'ge', NULL),
    ('PEAK_RULE', '정의의 정점 비율을 0.20 으로 바꾸면',
     $m$CALL pg_temp.mutate(ARRAY['0.10::numeric        AS peak_q', '0.20::numeric        AS peak_q'])$m$, '1', 'ge', NULL),
    ('NO_FLOAT_NAN', '정의가 달 구하기에 date_part(double precision)를 쓰면 (값은 같고 타입만 다르다)',
     $m$CALL pg_temp.mutate(ARRAY['lpad(EXTRACT(MONTH FROM m.month_start)::text, 2, ''0'')', 'lpad(date_part(''month'', m.month_start)::text, 2, ''0'')'])$m$, '1', 'ge', NULL),
    ('NO_FLOAT_NAN', '정의가 평균을 float8 로 내면',
     $m$CALL pg_temp.mutate(ARRAY['ROUND(SUM(o.ret)::numeric(60,40) / count(*), 8)', 'AVG(o.ret::float8)'])$m$, '1', 'ge', NULL);

DO $$
DECLARE
    r record;
    v_sql text;
    v_n   text;
    v_ok  boolean;
BEGIN
    FOR r IN SELECT n.q, n.label, n.mutate, n.expect, n.cmp, n.alt, q.section FROM zz_neg n JOIN zz_q q ON q.code = n.q ORDER BY n.n LOOP
        SELECT sql INTO v_sql FROM zz_q WHERE code = r.q;
        v_n := NULL;
        BEGIN
            EXECUTE r.mutate;
            EXECUTE pg_temp.sub(v_sql) INTO v_n;
            RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
        EXCEPTION WHEN OTHERS THEN
            IF SQLERRM <> 'zz_undo' THEN
                v_n := 'ERROR ' || SQLSTATE || ' ' || left(SQLERRM, 60);
            END IF;
        END;
        v_ok := CASE WHEN r.alt IS NOT NULL AND v_n ~ r.alt THEN true
                     WHEN r.cmp = 'ge' THEN v_n ~ '^[0-9]+$' AND v_n::bigint >= r.expect::bigint
                     ELSE v_n IS NOT DISTINCT FROM r.expect END;
        INSERT INTO zz_check_result (section, item, expected, actual, ok)
        VALUES (r.section, '음성 [' || r.q || '] ' || r.label || ' 위반 ' || CASE WHEN r.cmp = 'ge' THEN r.expect || '건 이상' ELSE r.expect || '건' END,
                CASE WHEN r.cmp = 'ge' THEN '≥ ' || r.expect ELSE r.expect END, v_n, v_ok);
    END LOOP;
END $$;

-- 보고: 절별 결과를 아래 8절이 집계한다. 값(수익률·평균·확률)은 출력하지 않는다.

-- 8 판정 ---------------------------------------------------------------------
-- 8.1 규칙 커버리지: 음성 사례가 통과한 규칙 코드 수 / 규칙 코드 수 (20/20 이어야 한다)
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '8 판정', '규칙 커버리지 (음성 사례가 통과한 규칙 코드 / 전체 규칙 코드)',
       (SELECT count(*) FROM zz_q) || '/' || (SELECT count(*) FROM zz_q),
       cov.n || '/' || (SELECT count(*) FROM zz_q),
       cov.n = (SELECT count(*) FROM zz_q)
FROM (SELECT count(DISTINCT n.q) AS n
      FROM zz_neg n
      JOIN zz_check_result r ON r.item LIKE '음성 [' || n.q || ']%' AND r.ok) cov;

-- 8.2 절별 집계 (total / pass / fail)
SELECT section, count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result
GROUP BY section
ORDER BY section;

-- 8.3 FAIL 행 목록 (0행이어야 정상)
SELECT seq, section, item, expected, actual
FROM zz_check_result
WHERE NOT ok
ORDER BY seq;

-- 8.3b 커버리지 행 (PASS 출력에서도 N/N 이 보이게)
SELECT item, expected, actual FROM zz_check_result WHERE section = '8 판정' ORDER BY seq;

-- 8.4 판정 행
SELECT CASE WHEN count(*) > 0 AND count(*) FILTER (WHERE NOT ok) = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict,
       count(*) AS total, count(*) FILTER (WHERE ok) AS pass, count(*) FILTER (WHERE NOT ok) AS fail
FROM zz_check_result;

-- 8.5 판정을 종료 코드로: FAIL 이 있으면 오류로 끝낸다 (ON_ERROR_STOP=1 이면 종료 코드 3).
--     판정 표식은 트랜잭션이 되돌아가도 남는다. 통과해도 2(흔적 확인 대기)까지만 올린다.
DO $$
DECLARE
    v_fail  integer;
    v_total integer;
BEGIN
    SELECT count(*) FILTER (WHERE NOT ok), count(*) INTO v_fail, v_total FROM zz_check_result;
    PERFORM setval('pg_temp.zz_verdict', CASE WHEN v_fail = 0 AND v_total > 0 THEN 2 ELSE 1 END);
    IF v_fail > 0 THEN
        RAISE EXCEPTION 'P0-12 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 9 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- 읽거나 일부러 바꿔 본 표들의 행 수와 identity 마지막 값이 실행 전과 같고, public 에 zz_ 객체가 없다.
-- 검증은 INSERT 를 하지 않으므로 번호표를 소비하지 않는다.
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
        RAISE EXCEPTION E'P0-12 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    -- 단언을 모두 통과했고 1–8절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_12_check;
