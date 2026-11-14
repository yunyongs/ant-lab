-- =====================================================================
-- my Fund Manager Phase 0 — 관심 지수 계산 검증 SQL (P0-11)
-- 대상: db/13_calc_attention.sql 이 채운 asset_month_attention 과 계산식 db/13_calc_attention_def.sql(임시 뷰 attn_calc)
-- 근거: docs/erd.md 5.3(asset_month_attention 비고)·5.5(검증 규칙 H8)·D33, docs/PLAN.md §4.5·§4.13, db/11_load_events_check.sql 의 판정 계약
-- 실행: 저장소 루트에서  psql -X -q -v ON_ERROR_STOP=1 -d <DB이름> -f db/13_calc_attention_check.sql
--       적재(db/10_load_sample.sql → db/13_calc_attention.sql) 뒤에 실행한다. INSERT 를 하지 않으므로 identity(번호표)를 소비하지 않아
--       실습 DB(antlab)에서도 돌릴 수 있다 (01_schema_history_check.sql 은 검증용 DB 전용 — 차이).
-- 판정: 마지막 줄 `p0_11_check` 가 `PASS` 이고 종료 코드가 0이면 통과다.
--       0절 전제가 FAIL 이면 결과를 보여 준 뒤 멈추고, 그 밖에 하나라도 어긋나면 8절이 FAIL 행을 보여 주고 오류로 끝난다 (종료 코드 3).
--       ON_ERROR_STOP 없이 돌리면 종료 코드가 0일 수 있으니 종료 코드가 아니라 마지막 줄을 본다.
-- 흔적: 파일 전체가 한 트랜잭션이고 8절 뒤에 ROLLBACK 한다. 9절이 행 수·identity 마지막 값이 실행 전과 같은지 확인한다.
-- 절:   0 전제(FAIL 이면 여기서 멈춤) → 1 행 수 → 2 독립 계산 → 3 규칙 → 4 손 계산 → 5 사후 정보 배제(H8) → 6 순위·그룹 → 8 판정 → ROLLBACK → 9 흔적
--       음성 사례(규칙을 일부러 어긴 뒤 잡히는지)는 사례가 속한 규칙의 절에 "음성 [규칙]" 행으로 쌓인다.
-- 규칙 코드 18개 (규칙마다 양성 쿼리 1개 + 음성 사례 1개 이상, 8절이 커버리지 N/N 을 확인한다):
--       ROWS_COVER ROWS_EXTRA / IND_WINDOW IND_Z IND_STREAK IND_INDEX / NULL_RULE INDEX_NULL Z_RANGE INDEX_FORMULA SEG_START NO_NAN /
--       HAND / H8_CUT H8_MOVED H8_TEETH / RANK_RULE GROUP_AVG
-- 독립 계산(2절): 윈도 함수(LAG, 누적 SUM, 프레임 ROWS BETWEEN 12 PRECEDING AND 1 PRECEDING)와 합·제곱합 식으로 분산을 구한다.
--       계산식 정의(자기 조인·상관 서브쿼리·stddev_samp)와 다른 방법이라 서로를 검증한다. 상수(12, 0.0001, 3, 0.25, 5)는 erd D33 에서 다시 옮겨 적었다.
-- 손 계산(4절): 합성 수익률(시세 아님)을 한 구간에 덮어쓴 뒤 attn_calc 가 손으로 계산한 값을 돌려주는지 본다. 서브트랜잭션에서 되돌린다.
-- 사후 정보 배제(5절): 기준 달 M 의 뒤 수익률을 바꾸거나 지운 상태에서 같은 계산식(attn_calc)으로 다시 계산해도 M 까지의 행이 변이 전 같은 계산식의 결과(그리고 저장값)와 같아야 한다.
--       H8 은 그 달 뒤를 보는지만 시험한다. 그 달 자신을 창에 넣는 실수는 2절 독립 계산·4절 손 계산이 잡는다. 변이는 asset_month_return 만 바꾸므로
--       계산식이 다른 표(fx_month, month_event 등)를 읽게 바뀌면 변이도 그 표로 넓힌다.
--       시험이 헛돌지 않게 M 뒤 행은 하나 이상 달라져야 하고(H8_MOVED), 일부러 다음 달을 보는 뷰(zz_leak)에서는 시험이 위반을 잡아야 한다(H8_TEETH).
-- 순위·그룹(6절): 정본 쿼리 텍스트가 이 절의 zz_canon 에 있다 (순위는 RANK(), NULL 제외, 동률은 같은 순위, 표시 순서의 마지막 키는 market, ticker).
-- P0-08·P0-09 판정 계약에서 복사한 것: ① pg_temp.zz_verdict ② 결과 표 zz_check_result ④ check_eq + val()
--       ⑤ 0절 뒤 멈춤과 판정(표식 2 또는 1) ⑥ 9절 흔적 확인(표식 0)과 마지막 CASE.
--       갱신 규칙: val() 은 정확히 한 행, 기대값을 NULL 로 쓰지 않는다, NULL 안전 비교(IS NOT DISTINCT FROM), 사례 하나에 조건 하나.
-- 음성 사례: 서브트랜잭션 안에서 DELETE/UPDATE 로 위반을 만든 뒤 그 규칙의 검사 쿼리가 기대한 개수로 잡는지 센 뒤 되돌린다. INSERT 는 쓰지 않는다.
--       사례마다 단언하는 규칙 코드는 하나뿐이다. 01 스키마가 더 강한 CHECK 를 가지면(예: NaN 거부) 변이가 23514 로 막히는 사례는 그 오류도 통과로 본다.
-- 새 문법: 윈도 함수와 프레임, LAG, 누적 합으로 구간 번호 매기기(gaps-and-islands), RANK(), FULL JOIN 으로 두 결과 대조,
--       PL/pgSQL 예외 블록(서브트랜잭션)으로 "바꿔 보고 되돌리기", EXECUTE … USING, 쿼리 텍스트를 표에 담아 실행하기.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 판정 표식: 트랜잭션이 되돌아가도 남는 임시 시퀀스 (1 = 미완료 또는 실패, 2 = 1–8절 통과·흔적 확인 대기, 0 = 최종 통과)
CREATE TEMP SEQUENCE IF NOT EXISTS zz_verdict MINVALUE 0 START 1;
DO $$ BEGIN
    IF to_regclass('pg_temp.zz_verdict') IS NULL THEN RAISE EXCEPTION 'zz_verdict is not a temp sequence'; END IF;
    PERFORM setval('pg_temp.zz_verdict', 1);
END $$;

-- 흔적 확인용 스냅샷: asset_month_attention·asset_month_return 의 행 수와 asset_month_attention 의 identity 마지막 값.
-- 트랜잭션 밖(BEGIN 앞)에서 찍어 ROLLBACK 뒤에도 남는다
CREATE FUNCTION pg_temp.zz_snap() RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v text := '';
    t text;
    n bigint;
    s text;
BEGIN
    FOREACH t IN ARRAY ARRAY['asset_month_attention', 'asset_month_return'] LOOP
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
    section  text    NOT NULL,   -- '0 전제' '1 행 수' '2 독립 계산' '3 규칙' '4 손 계산' '5 H8' '6 순위·그룹' '8 판정'
    item     text    NOT NULL,   -- 사례 설명 또는 대조 항목
    expected text,               -- 기대값
    actual   text,               -- 실제값
    ok       boolean NOT NULL
);

-- 판정 프로시저 ------------------------------------------------------------

-- 값 비교. NULL끼리는 같다고 본다.
CREATE PROCEDURE pg_temp.check_eq(p_section text, p_item text, p_expected text, p_actual text)
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zz_check_result (section, item, expected, actual, ok)
    VALUES (p_section, p_item, p_expected, p_actual, p_expected IS NOT DISTINCT FROM p_actual);
END $$;

-- 스칼라 질의 한 개를 실행해 값을 text로 돌려준다. CALL 인자에는 서브쿼리를 쓸 수 없어 check_eq의 실제값을 만드는 데 쓴다.
-- 정확히 한 행이어야 한다. 0행은 NULL, 여러 행은 기대값과 같을 수 없는 'ERROR too_many_rows'를 돌려 FAIL 행으로 남긴다.
CREATE FUNCTION pg_temp.val(p_sql text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v_out text;
BEGIN
    EXECUTE p_sql INTO STRICT v_out;
    RETURN v_out;
EXCEPTION
    WHEN no_data_found THEN RETURN NULL;
    WHEN too_many_rows THEN RETURN 'ERROR too_many_rows';
END $$;

-- 계산식 정의를 읽는다 (쓰기 파일과 같은 파일. 임시 뷰 attn_calc 가 만들어진다)
\ir 13_calc_attention_def.sql

-- 검증 도구 -----------------------------------------------------------------------

-- 시험 설정: H8 시험이 어느 뷰를 보는지, 샌 뷰가 다음 달 값을 얼마나 섞는지 (음성 사례가 값을 바꿔 시험의 이빨을 확인한다)
CREATE TEMP TABLE zz_cfg (h8_view text NOT NULL, leak_scale numeric NOT NULL);
INSERT INTO zz_cfg VALUES ('attn_calc', 1);

-- H8 기준 달 M 목록 (표본 구조를 아는 값: 구간 끝 2012-04, 구간 시작 2023-01, 창이 막 찬 달 등. 마지막 줄은 마지막 달의 직전 달)
CREATE TEMP TABLE zz_h8_m (month_start date PRIMARY KEY);
INSERT INTO zz_h8_m
SELECT d FROM (VALUES (DATE '2007-12-01'), (DATE '2009-06-01'), (DATE '2012-03-01'), (DATE '2012-04-01'),
                      (DATE '2023-01-01'), (DATE '2024-01-01'),
                      ((SELECT (max(month_start) - INTERVAL '1 month')::date FROM month_block))) AS v(d);

-- 독립 계산 뷰: 윈도 함수로 구간·창·연속을 만든다.
--   구간 번호 = 직전 달이 이어지지 않는 달에서 +1 하는 누적 합 (gaps-and-islands)
--   창 = 같은 구간에서 "12 PRECEDING AND 1 PRECEDING" 프레임 (그 달 제외)
--   분산 = (n·Σx² − (Σx)²) / (n·(n−1))  — 나누기 한 번뿐인 합·제곱합 식. 하한은 분산 < 0.0001² = 1e-8 로 비교한다 (sqrt 없이)
--   z = (n·x − Σx) / (n·√분산)
--   연속 = 방향이 바뀌거나 0 이면 새 연속, 연속 안의 순번 × 방향
CREATE TEMP VIEW zz_ind AS
WITH b AS (
    SELECT ar.asset_id, ar.month_block_id, mb.month_start, abs(ar.local_return) AS x, sign(ar.local_return)::integer AS sg,
           CASE WHEN lag(mb.month_start) OVER (PARTITION BY ar.asset_id ORDER BY mb.month_start)
                     = (mb.month_start - INTERVAL '1 month')::date THEN 0 ELSE 1 END AS brk
    FROM asset_month_return ar JOIN month_block mb ON mb.id = ar.month_block_id
),
s AS (
    SELECT b.*, sum(brk) OVER (PARTITION BY asset_id ORDER BY month_start) AS seg FROM b
),
w AS (
    SELECT s.*,
           count(x) OVER wf                AS n,
           COALESCE(sum(x) OVER wf, 0)     AS sx,
           COALESCE(sum(x * x) OVER wf, 0) AS sxx,
           CASE WHEN sg = 0 OR lag(sg) OVER ws IS DISTINCT FROM sg THEN 1 ELSE 0 END AS newrun
    FROM s
    WINDOW wf AS (PARTITION BY asset_id, seg ORDER BY month_start ROWS BETWEEN 12 PRECEDING AND 1 PRECEDING),
           ws AS (PARTITION BY asset_id, seg ORDER BY month_start)
),
q AS (
    SELECT w.*,
           sum(newrun) OVER (PARTITION BY asset_id, seg ORDER BY month_start) AS run,
           CASE WHEN n = 12 THEN (n * sxx - sx * sx) / (n * (n - 1)) END AS var
    FROM w
),
z AS (
    SELECT q.*,
           sg * row_number() OVER (PARTITION BY asset_id, seg, run ORDER BY month_start) AS streak_i,
           CASE WHEN n = 12 AND var >= 0.00000001 THEN GREATEST(-3, LEAST(3, (n * x - sx) / (n * sqrt(var)))) END AS z_c
    FROM q
)
SELECT asset_id, month_block_id, month_start, n, var, z_c,
       ROUND(z_c, 8) AS z_clip,
       streak_i,
       ROUND(z_c, 8) + CASE WHEN streak_i = 0 THEN 0 ELSE 0.25 * (LEAST(abs(streak_i), 5) - 1) END AS idx_i
FROM z;

-- 샌 뷰(H8 시험의 이빨 확인용): attn_calc 의 지수에 "다음 달 |수익률|"을 섞는다. 계산식 복사가 아니라 attn_calc 위에 얹은 얇은 변이다.
CREATE TEMP VIEW zz_leak AS
SELECT c.asset_id, c.month_block_id, c.month_start, c.window_months, c.attention_z, c.streak,
       c.attention_index + (SELECT leak_scale FROM zz_cfg)
           * COALESCE((SELECT abs(n.local_return)
                       FROM asset_month_return n JOIN month_block nm ON nm.id = n.month_block_id
                       WHERE n.asset_id = c.asset_id AND nm.month_start = (c.month_start + INTERVAL '1 month')::date), 0) AS attention_index
FROM attn_calc c;

-- H8 시험 도구: 기준 달 M 마다 (a) M 뒤 수익률 전부를 다른 값으로 바꿈 (b) M 바로 다음 달만 바꿈 (c) M 뒤 수익률 행을 지움,
-- 이렇게 3종의 변이를 서브트랜잭션에서 하고 p_view 를 다시 계산해 변이 전 같은 뷰의 결과(zz_h8_base)와 비교한 뒤 되돌린다.
--   'cut'   : M 이하 행에서 변이 전 결과와 다르거나 한쪽에만 있는 행 수의 합 (0 이어야 한다). p_view 가 attn_calc 이면 변이 전 결과 = 저장값도 한 번 확인해 더한다
--   'moved' : M 뒤 행이 하나도 달라지지 않은 사례 수 (0 이어야 한다 — 시험이 헛돌지 않는다는 증거)
CREATE TEMP TABLE zz_h8 (asset_id bigint, month_block_id bigint, month_start date, window_months smallint,
                         attention_z numeric, streak smallint, attention_index numeric);
-- 기준값: 변이 전에 같은 뷰(p_view)가 낸 결과. 변이 뒤 결과를 이것과 비교한다 (샌 뷰는 변이 전부터 저장값과 달라서 저장값과 비교하면 이빨이 헛돈다)
CREATE TEMP TABLE zz_h8_base (LIKE zz_h8);
CREATE FUNCTION pg_temp.h8_count(p_view text, p_part text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    r       record;
    v_next  date;
    v_cut   bigint;
    v_mov   bigint;
    v_total bigint := 0;
BEGIN
    DELETE FROM zz_h8_base;
    EXECUTE format('INSERT INTO zz_h8_base SELECT asset_id, month_block_id, month_start, window_months, attention_z, streak, attention_index FROM %s', p_view);
    -- 'cut' + 정식 계산식: 저장값 = 변이 전 계산 결과도 함께 확인한다 (기준값 스냅샷이 저장값을 대신하므로 이 확인을 잃지 않게)
    IF p_part = 'cut' AND p_view = 'attn_calc' THEN
        SELECT count(*) INTO v_total
        FROM asset_month_attention t
        FULL JOIN zz_h8_base b ON b.asset_id = t.asset_id AND b.month_block_id = t.month_block_id
        WHERE t.asset_id IS NULL OR b.asset_id IS NULL
           OR (t.window_months, t.attention_z, t.streak, t.attention_index) IS DISTINCT FROM (b.window_months, b.attention_z, b.streak, b.attention_index);
    END IF;
    FOR r IN SELECT m.month_start AS m, k.kind
             FROM zz_h8_m m CROSS JOIN (VALUES ('UPD_ALL'), ('UPD_NEXT'), ('DEL_ALL')) AS k(kind)
             ORDER BY m.month_start, k.kind LOOP
        v_cut := NULL; v_mov := NULL;
        BEGIN
            SELECT min(mb.month_start) INTO v_next FROM month_block mb
            WHERE mb.month_start > r.m AND EXISTS (SELECT 1 FROM asset_month_return ar WHERE ar.month_block_id = mb.id);
            IF r.kind = 'UPD_ALL' THEN
                -- 방향을 뒤집고 크기도 바꾼다 (0 은 +7%). CHECK(local_return ≥ −1) 안의 값
                UPDATE asset_month_return ar SET local_return = CASE WHEN ar.local_return > 0 THEN -0.07 ELSE 0.07 END
                FROM month_block mb WHERE mb.id = ar.month_block_id AND mb.month_start > r.m;
            ELSIF r.kind = 'UPD_NEXT' THEN
                UPDATE asset_month_return ar SET local_return = CASE WHEN ar.local_return > 0 THEN -0.07 ELSE 0.07 END
                FROM month_block mb WHERE mb.id = ar.month_block_id AND mb.month_start = v_next;
            ELSE
                DELETE FROM asset_month_return ar USING month_block mb WHERE mb.id = ar.month_block_id AND mb.month_start > r.m;
            END IF;
            DELETE FROM zz_h8;
            EXECUTE format('INSERT INTO zz_h8 SELECT asset_id, month_block_id, month_start, window_months, attention_z, streak, attention_index FROM %s', p_view);
            IF p_part = 'cut' THEN
            SELECT count(*) INTO v_cut
            FROM (SELECT * FROM zz_h8_base WHERE month_start <= r.m) t
            FULL JOIN (SELECT * FROM zz_h8 WHERE month_start <= r.m) v ON v.asset_id = t.asset_id AND v.month_block_id = t.month_block_id
            WHERE t.asset_id IS NULL OR v.asset_id IS NULL
               OR (t.window_months, t.attention_z, t.streak, t.attention_index) IS DISTINCT FROM (v.window_months, v.attention_z, v.streak, v.attention_index);
            ELSE
            SELECT count(*) INTO v_mov
            FROM (SELECT * FROM zz_h8_base WHERE month_start > r.m) t
            FULL JOIN (SELECT * FROM zz_h8 WHERE month_start > r.m) v ON v.asset_id = t.asset_id AND v.month_block_id = t.month_block_id
            WHERE t.asset_id IS NULL OR v.asset_id IS NULL
               OR (t.window_months, t.attention_z, t.streak, t.attention_index) IS DISTINCT FROM (v.window_months, v.attention_z, v.streak, v.attention_index);
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

-- 손 계산 사례(4절): 합성 수익률. arank = 종목 순번(번호 순), k = 구간 시작(그 종목의 첫 달)부터 센 달 순번(0–12).
--   ret   : 13개 달의 합성 수익률 (시세 아님)   streaks: 13개 달의 기대 streak
--   z12 / idx12 : 13번째 달(k = 12, 창이 12개)의 기대 attention_z / attention_index. k < 12 인 달은 창이 모자라 z·지수가 NULL, window_months = k.
--   손 계산: 창 x 가 0.01 여섯 개·0.03 여섯 개 → 평균 0.02, 표본 표준편차 0.01·√(12/11). x = 0.04 → z = 2·√(11/12) = 1.9148542155… → 1.91485422
CREATE TEMP TABLE zz_hand_case (case_code text PRIMARY KEY, arank integer NOT NULL, ret numeric[] NOT NULL, streaks integer[] NOT NULL,
                                z12 numeric, idx12 numeric, note text NOT NULL);
INSERT INTO zz_hand_case VALUES
    ('H1', 1, ARRAY[0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,-0.01,0.03,0.03,0.04],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,-1,1,2,3],
              1.91485422, 2.41485422, '정상 z (방향이 섞인 창이라 |r| 로 계산하지 않으면 어긋난다), 3개월 연속 → 보너스 0.50'),
    ('H2', 2, ARRAY[0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,-0.01,0.03,0.03,0.06],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,-1,1,2,3],
              3.00000000, 3.50000000, 'z_raw = 4·√(11/12) = 3.83 → +3 절단'),
    ('H3', 3, ARRAY[0.10,-0.10,0.10,-0.10,0.10,-0.10,0.10,-0.10,0.10,-0.10,0.10,0.11,0.00],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,-1,1,2,0],
              -3.00000000, -3.00000000, '0 수익률 → streak 0, 보너스 0, z_raw ≈ −35 → −3 절단'),
    ('H4', 4, ARRAY[0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.02,0.05],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,-1,1,-1,1],
              NULL, NULL, '창이 12개 모두 |r| = 0.02 (부호만 섞임) → 표준편차 0 → z NULL, 0 으로 나누기 오류 없음'),
    ('H5', 5, ARRAY[0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.0203,0.05],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,-1,1,-1,1],
              NULL, NULL, '표준편차 = 0.0003/√12 = 8.7e-5 < 하한 0.0001 → z NULL (0 이 아닌 작은 표준편차)'),
    ('H6', 6, ARRAY[0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.02,0.02,-0.0204,0.05],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,-1,1,-1,1],
              3.00000000, 3.00000000, '표준편차 = 0.0004/√12 = 1.15e-4 ≥ 하한 → z 계산 (z_raw ≈ 259 → +3 절단), streak 1 → 보너스 0'),
    ('H7', 7, ARRAY[0.01,0.03,0.01,0.03,0.01,0.03,0.01,0.03,0.01,0.01,0.03,0.03,0.04],
              ARRAY[1,2,3,4,5,6,7,8,9,10,11,12,13],
              1.91485422, 2.91485422, '13개월 연속 상승 → 보너스 상한 1.00 (|streak| 5 이상)'),
    ('H8', 8, ARRAY[0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,-0.01,0.03,0.03,-0.04],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,-1,1,2,-1],
              1.91485422, 1.91485422, '연속이 끊겨 streak −1 → 보너스 0'),
    ('H9', 9, ARRAY[0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,0.01,0.03,-0.03,-0.04],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,2,3,-1,-2],
              1.91485422, 2.16485422, '2개월 연속 하락 → 보너스 0.25 (방향과 무관)'),
    ('H10', 10, ARRAY[0.01,-0.03,0.01,-0.03,0.01,-0.03,0.01,-0.03,-0.01,0.01,0.03,0.03,0.04],
              ARRAY[1,-1,1,-1,1,-1,1,-1,-2,1,2,3,4],
              1.91485422, 2.66485422, '4개월 연속 상승 → 보너스 0.75'),
    ('H11', 11, ARRAY[0.0202,-0.0198,0.0201,-0.0199,0.02005,-0.01995,0.02005,-0.01995,0.02,-0.02,0.02,-0.02,0.0202],
              ARRAY[1,-1,1,-1,1,-1,1,-1,1,-1,1,-1,1],
              2.00000000, 2.00000000, '표준편차가 정확히 하한 0.0001 (편차 ±0.0002·±0.0001·±0.00005 두 쌍·0 네 개, 제곱합 1.1e-7 ÷ 11) → z 계산 (하한은 미만만 NULL)'),
    ('H12', 12, ARRAY[0.02,0.00,0.02,0.00,0.00,-0.02,0.00,0.02,0.00,-0.02,0.00,-0.02,-0.03],
              ARRAY[1,0,1,0,0,-1,0,1,0,-1,0,-1,-2],
              1.91485422, 2.16485422, '0 수익률 다음 달은 ±1 부터 다시 센다 (창 x 가 0 여섯 개·0.02 여섯 개 → H1 과 같은 z)');
CREATE TEMP TABLE zz_hand (arank integer, k integer, ret numeric, PRIMARY KEY (arank, k));
INSERT INTO zz_hand SELECT c.arank, (u.ord - 1)::integer, u.v FROM zz_hand_case c CROSS JOIN LATERAL unnest(c.ret) WITH ORDINALITY AS u(v, ord);
CREATE TEMP TABLE zz_hand_exp (arank integer, k integer, window_months smallint, z numeric, streak smallint, idx numeric, PRIMARY KEY (arank, k));
INSERT INTO zz_hand_exp
SELECT c.arank, (u.ord - 1)::integer, LEAST(u.ord - 1, 12)::smallint,
       CASE WHEN u.ord = 13 THEN c.z12 END, u.st::smallint, CASE WHEN u.ord = 13 THEN c.idx12 END
FROM zz_hand_case c CROSS JOIN LATERAL unnest(c.streaks) WITH ORDINALITY AS u(st, ord);

-- 종목 순번(번호 순)과 그 종목 안의 달 순번 k (0 부터)
CREATE TEMP VIEW zz_pos AS
SELECT ar.id AS ret_id, ar.asset_id, ar.month_block_id, mb.month_start,
       dense_rank() OVER (ORDER BY ar.asset_id) AS arank,
       (row_number() OVER (PARTITION BY ar.asset_id ORDER BY mb.month_start) - 1)::integer AS k
FROM asset_month_return ar JOIN month_block mb ON mb.id = ar.month_block_id;

-- 손 계산 시험: 위 합성 수익률을 덮어쓰고 attn_calc 가 기대값과 다른 행 수를 센다 (서브트랜잭션, 되돌림)
CREATE FUNCTION pg_temp.hand_count() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_upd bigint;
    v_bad bigint := 0;
BEGIN
    BEGIN
        UPDATE asset_month_return ar SET local_return = h.ret
        FROM zz_pos p JOIN zz_hand h ON h.arank = p.arank AND h.k = p.k
        WHERE ar.id = p.ret_id;
        GET DIAGNOSTICS v_upd = ROW_COUNT;
        v_bad := v_bad + abs(v_upd - (SELECT count(*) FROM zz_hand));      -- 덮어쓸 달이 모두 있었는가
        v_bad := v_bad + (SELECT count(*)
                          FROM zz_hand_exp e
                          JOIN zz_pos p ON p.arank = e.arank AND p.k = e.k
                          LEFT JOIN attn_calc c ON c.asset_id = p.asset_id AND c.month_block_id = p.month_block_id
                          WHERE c.asset_id IS NULL
                             OR (c.window_months, c.attention_z, c.streak, c.attention_index)
                                IS DISTINCT FROM (e.window_months, e.z, e.streak, e.idx));
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v_bad;
END $$;

-- 정본 쿼리 텍스트(6절): 순위 R 과 그룹 조회 G. $1 = 시즌 기준일(asset_position.base_date). 저장하지 않고 읽을 때 계산한다.
CREATE TEMP TABLE zz_canon (name text PRIMARY KEY, sql text NOT NULL);
INSERT INTO zz_canon VALUES
    ('RANK', $c$
SELECT t.*, t.attention_rank <= CEIL(0.10 * t.n_ranked) AS is_top
FROM (SELECT $1::date AS base_date, a.month_block_id, a.asset_id, a.attention_index,
             RANK() OVER (PARTITION BY a.month_block_id ORDER BY a.attention_index DESC) AS attention_rank,
             COUNT(*) OVER (PARTITION BY a.month_block_id) AS n_ranked,
             ROW_NUMBER() OVER (PARTITION BY a.month_block_id
                                ORDER BY a.attention_index DESC, s.market COLLATE "C", s.ticker COLLATE "C") AS display_order
      FROM asset_month_attention a
      JOIN asset_position p ON p.asset_id = a.asset_id AND p.base_date = $1
      JOIN asset s ON s.id = a.asset_id
      WHERE a.attention_index IS NOT NULL) t
$c$),
    ('GROUP', $c$
SELECT $1::date AS base_date, m.id AS month_block_id, a.asset_group_id,
       ROUND(AVG(t.attention_index), 8) AS group_attention,
       COUNT(t.attention_index) AS n_members,
       COUNT(*) AS n_pool
FROM asset_position p
JOIN asset a ON a.id = p.asset_id
CROSS JOIN month_block m
LEFT JOIN asset_month_attention t ON t.asset_id = a.id AND t.month_block_id = m.id
WHERE p.base_date = $1
GROUP BY m.id, a.asset_group_id
$c$);

CREATE TEMP TABLE zz_rank_out (base_date date, month_block_id bigint, asset_id bigint, attention_index numeric,
                               attention_rank bigint, n_ranked bigint, display_order bigint, is_top boolean);
CREATE TEMP TABLE zz_group_out (base_date date, month_block_id bigint, asset_group_id bigint, group_attention numeric,
                                n_members bigint, n_pool bigint);

-- 정본 쿼리를 기준일마다 실행해 출력 표에 담는다
CREATE FUNCTION pg_temp.canon_load(p_name text, p_out regclass) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    d date;
BEGIN
    EXECUTE format('DELETE FROM %s', p_out);
    FOR d IN SELECT DISTINCT base_date FROM asset_position ORDER BY base_date LOOP
        EXECUTE format('INSERT INTO %s %s', p_out, (SELECT sql FROM zz_canon WHERE name = p_name)) USING d;
    END LOOP;
END $$;

-- 순위 R 이 독립 정의와 다른 행 수: 순위 = 1 + (같은 달·같은 풀에서 지수가 더 큰 행 수), n_ranked = 지수 있는 행 수,
-- 표시 순서 = 지수 내림차순 뒤 market, ticker (바이트 순서), 상위 q 표시 = 순위 ≤ CEIL(0.10 × n), 행 집합 = 풀 ∩ 지수 있음
CREATE FUNCTION pg_temp.rank_diff() RETURNS bigint
LANGUAGE sql AS $$
    SELECT (SELECT count(*)
            FROM zz_rank_out o
            JOIN asset so ON so.id = o.asset_id
            WHERE o.attention_rank IS DISTINCT FROM
                  1 + (SELECT count(*) FROM asset_month_attention t2
                       JOIN asset_position p2 ON p2.asset_id = t2.asset_id AND p2.base_date = o.base_date
                       WHERE t2.month_block_id = o.month_block_id AND t2.attention_index > o.attention_index)
               OR o.n_ranked IS DISTINCT FROM
                  (SELECT count(*) FROM asset_month_attention t2
                   JOIN asset_position p2 ON p2.asset_id = t2.asset_id AND p2.base_date = o.base_date
                   WHERE t2.month_block_id = o.month_block_id AND t2.attention_index IS NOT NULL)
               OR o.display_order IS DISTINCT FROM
                  1 + (SELECT count(*) FROM asset_month_attention t2
                       JOIN asset_position p2 ON p2.asset_id = t2.asset_id AND p2.base_date = o.base_date
                       JOIN asset s2 ON s2.id = t2.asset_id
                       WHERE t2.month_block_id = o.month_block_id AND t2.attention_index IS NOT NULL
                         AND (t2.attention_index > o.attention_index
                              OR (t2.attention_index = o.attention_index
                                  AND (s2.market COLLATE "C", s2.ticker COLLATE "C") < (so.market COLLATE "C", so.ticker COLLATE "C"))))
               OR o.attention_index IS DISTINCT FROM
                  (SELECT t.attention_index FROM asset_month_attention t WHERE t.asset_id = o.asset_id AND t.month_block_id = o.month_block_id)
               OR o.is_top IS DISTINCT FROM
                  (1 + (SELECT count(*) FROM asset_month_attention t2
                        JOIN asset_position p2 ON p2.asset_id = t2.asset_id AND p2.base_date = o.base_date
                        WHERE t2.month_block_id = o.month_block_id AND t2.attention_index > o.attention_index)
                   <= CEIL(0.10 * (SELECT count(*) FROM asset_month_attention t2
                                   JOIN asset_position p2 ON p2.asset_id = t2.asset_id AND p2.base_date = o.base_date
                                   WHERE t2.month_block_id = o.month_block_id AND t2.attention_index IS NOT NULL))))
         + (SELECT count(*) FROM (
               (SELECT base_date, month_block_id, asset_id FROM zz_rank_out
                EXCEPT ALL
                SELECT p.base_date, t.month_block_id, t.asset_id FROM asset_month_attention t JOIN asset_position p ON p.asset_id = t.asset_id
                WHERE t.attention_index IS NOT NULL)
               UNION ALL
               (SELECT p.base_date, t.month_block_id, t.asset_id FROM asset_month_attention t JOIN asset_position p ON p.asset_id = t.asset_id
                WHERE t.attention_index IS NOT NULL
                EXCEPT ALL
                SELECT base_date, month_block_id, asset_id FROM zz_rank_out)) d)
$$;

-- 순위 규칙 위반 수의 합: (1) 정본 쿼리 출력이 독립 정의와 다른 행 (2) 출력에 NULL 지수가 든 행
-- (3) 합성 동률: 한 달의 두 종목 지수를 같은 값(최댓값 4)으로 만들면 같은 순위, 표시 순서는 market, ticker 순이어야 한다
CREATE FUNCTION pg_temp.rank_count() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v      bigint := 0;
    v_m    bigint;
    v_lo   bigint;
    v_hi   bigint;
    v_mk   text;
    v_bd   date;
BEGIN
    PERFORM pg_temp.canon_load('RANK', 'zz_rank_out'::regclass);
    v := v + (SELECT count(*) FROM zz_rank_out WHERE attention_index IS NULL) + pg_temp.rank_diff();
    -- 합성 동률을 만들 (기준일, 달): 지수가 있는 종목이 가장 많은 한 풀의 한 달 (기준일이 여럿이어도 한 풀 안에서 짝을 짓는다)
    SELECT o.base_date, o.month_block_id INTO v_bd, v_m FROM zz_rank_out o WHERE o.attention_index IS NOT NULL
    GROUP BY o.base_date, o.month_block_id ORDER BY count(*) DESC, o.base_date, o.month_block_id LIMIT 1;
    -- 같은 시장에서 ticker 가 가장 앞선 종목과 가장 뒤인 종목 (시장이 같아야 ticker 순서가 표시 순서를 가른다). 지수가 있는 종목이 가장 많은 시장을 고른다
    SELECT s.market INTO v_mk FROM zz_rank_out o JOIN asset s ON s.id = o.asset_id
    WHERE o.base_date = v_bd AND o.month_block_id = v_m AND o.attention_index IS NOT NULL
    GROUP BY s.market ORDER BY count(*) DESC, s.market COLLATE "C" LIMIT 1;
    SELECT o.asset_id INTO v_lo FROM zz_rank_out o JOIN asset s ON s.id = o.asset_id
    WHERE o.base_date = v_bd AND o.month_block_id = v_m AND o.attention_index IS NOT NULL AND s.market = v_mk ORDER BY s.ticker COLLATE "C" LIMIT 1;
    SELECT o.asset_id INTO v_hi FROM zz_rank_out o JOIN asset s ON s.id = o.asset_id
    WHERE o.base_date = v_bd AND o.month_block_id = v_m AND o.attention_index IS NOT NULL AND s.market = v_mk ORDER BY s.ticker COLLATE "C" DESC LIMIT 1;
    IF v_lo IS NULL OR v_hi IS NULL OR v_lo = v_hi THEN
        v := v + 1;   -- 같은 풀·같은 시장에 서로 다른 두 종목이 없어 합성 동률을 만들 수 없다 (시험이 헛돈다)
    END IF;
    BEGIN
        UPDATE asset_month_attention SET attention_index = 4
        WHERE month_block_id = v_m AND asset_id IN (v_lo, v_hi);
        PERFORM pg_temp.canon_load('RANK', 'zz_rank_out'::regclass);
        v := v + pg_temp.rank_diff();
        v := v + (SELECT CASE WHEN count(*) = 1 AND bool_and(a.attention_rank = b.attention_rank AND a.display_order < b.display_order) THEN 0 ELSE 1 END
                  FROM zz_rank_out a JOIN zz_rank_out b ON b.base_date = a.base_date AND b.month_block_id = a.month_block_id
                  WHERE a.base_date = v_bd AND a.month_block_id = v_m AND a.asset_id = v_lo AND b.asset_id = v_hi);
        RAISE EXCEPTION 'zz_undo' USING ERRCODE = 'P0001';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM <> 'zz_undo' THEN RAISE; END IF;
    END;
    RETURN v;
END $$;

-- 그룹 조회 G 위반 수: 독립 계산(직접 SUM / COUNT, 나누기 한 번 뒤 반올림 한 번)과 다른 (달, 그룹), n_members > n_pool,
-- 그룹 지수 NULL ⇔ n_members = 0 이 아닌 행, 행 집합이 (풀의 그룹 × 달)과 다른 수
CREATE FUNCTION pg_temp.group_count() RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v bigint := 0;
BEGIN
    PERFORM pg_temp.canon_load('GROUP', 'zz_group_out'::regclass);
    v := v + (SELECT count(*)
              FROM zz_group_out o
              WHERE o.group_attention IS DISTINCT FROM
                    (SELECT ROUND(SUM(t.attention_index) / NULLIF(COUNT(t.attention_index), 0), 8)
                     FROM asset_month_attention t JOIN asset a2 ON a2.id = t.asset_id
                     JOIN asset_position p2 ON p2.asset_id = a2.id AND p2.base_date = o.base_date
                     WHERE a2.asset_group_id = o.asset_group_id AND t.month_block_id = o.month_block_id)
                 OR o.n_members IS DISTINCT FROM
                    (SELECT COUNT(t.attention_index)
                     FROM asset_month_attention t JOIN asset a2 ON a2.id = t.asset_id
                     JOIN asset_position p2 ON p2.asset_id = a2.id AND p2.base_date = o.base_date
                     WHERE a2.asset_group_id = o.asset_group_id AND t.month_block_id = o.month_block_id)
                 OR o.n_pool IS DISTINCT FROM
                    (SELECT COUNT(*) FROM asset a2 JOIN asset_position p2 ON p2.asset_id = a2.id AND p2.base_date = o.base_date
                     WHERE a2.asset_group_id = o.asset_group_id)
                 OR o.n_members > o.n_pool
                 OR (o.group_attention IS NULL) <> (o.n_members = 0));
    v := v + (SELECT count(*) FROM (
                (SELECT base_date, month_block_id, asset_group_id FROM zz_group_out
                 EXCEPT ALL
                 SELECT g.base_date, mb.id, g.asset_group_id
                 FROM (SELECT DISTINCT p.base_date, a.asset_group_id FROM asset_position p JOIN asset a ON a.id = p.asset_id) g CROSS JOIN month_block mb)
                UNION ALL
                (SELECT g.base_date, mb.id, g.asset_group_id
                 FROM (SELECT DISTINCT p.base_date, a.asset_group_id FROM asset_position p JOIN asset a ON a.id = p.asset_id) g CROSS JOIN month_block mb
                 EXCEPT ALL
                 SELECT base_date, month_block_id, asset_group_id FROM zz_group_out)) d);
    RETURN v;
END $$;

-- 0 전제 -------------------------------------------------------------------
-- 하나라도 FAIL 이면 1절 이후 결과를 믿지 않는다.

INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT '0 전제', x.item, x.expected, x.actual, x.expected IS NOT DISTINCT FROM x.actual
FROM (VALUES
    ('서버 주 버전 (PostgreSQL 17)', '17', substr(current_setting('server_version_num'), 1, 2)),
    ('DB 인코딩', 'UTF8', (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database())),
    ('TimeZone (erd 2절 날짜·시각)', 'Asia/Seoul', current_setting('TimeZone')),
    ('session_replication_role (FK 트리거 우회 차단)', 'origin', current_setting('session_replication_role')),
    ('4개 이름이 모두 public 으로 해석된다 (search_path 앞쪽에 같은 이름이 없다)', '4',
        (SELECT count(*)::text FROM unnest(ARRAY['asset', 'month_block', 'asset_month_return', 'asset_month_attention']) AS t
         WHERE to_regclass(t) IS NOT NULL AND to_regclass(t) = to_regclass('public.' || t))),
    ('public 의 zz_ 객체 (테이블·프로시저)', '0',
        ((SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relname LIKE 'zz\_%')
       + (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = 'public' AND p.proname LIKE 'zz\_%'))::text),
    ('계산식 정의 파일을 읽었다 (임시 뷰 attn_calc 가 있다)', 'true', (to_regclass('pg_temp.attn_calc') IS NOT NULL)::text),
    ('10 적재가 끝나 있다 (asset_month_return 이 비어 있지 않다)', 'true', (SELECT (count(*) > 0)::text FROM asset_month_return)),
    ('13 계산이 끝나 있다 (asset_month_attention 이 비어 있지 않다 — 빈 표로 헛 PASS 하지 않는다)', 'true', (SELECT (count(*) > 0)::text FROM asset_month_attention)),
    ('H8 기준 달이 모두 month_block 에 있다', (SELECT count(*)::text FROM zz_h8_m),
        (SELECT count(*)::text FROM zz_h8_m h JOIN month_block m ON m.month_start = h.month_start)),
    ('표본의 구간 시작 달 (직전 달 행이 없는 달) 집합 — 표본 구조 전제 (MVP 에서는 이 행을 갱신한다)', '2007-01-01,2023-01-01',
        (SELECT string_agg(d::text, ',' ORDER BY d) FROM
            (SELECT DISTINCT mb.month_start AS d FROM asset_month_return ar JOIN month_block mb ON mb.id = ar.month_block_id
             WHERE NOT EXISTS (SELECT 1 FROM asset_month_return p JOIN month_block pm ON pm.id = p.month_block_id
                               WHERE p.asset_id = ar.asset_id AND pm.month_start = (mb.month_start - INTERVAL '1 month')::date)) s)),
    ('손 계산 사례가 쓰는 종목 12개가 있고 각자 첫 13개 달이 이어진다 (k = 0–12)', '12',
        (SELECT count(*)::text FROM (SELECT p.arank FROM zz_pos p WHERE p.arank <= 12 AND p.k <= 12
                                     GROUP BY p.arank HAVING count(*) = 13
                                        AND max(p.month_start) = (min(p.month_start) + INTERVAL '12 months')::date) g))
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
        RAISE EXCEPTION 'P0-11 전제 실패: 0절 FAIL % 건 (위의 0절 결과를 본다. 01 스키마와 10 적재, 13 계산을 먼저 실행했는지, 저장소 루트인지 확인한다)', v_fail;
    END IF;
END $$;

-- 검사 쿼리 목록: 위반 개수를 돌려주는 쿼리. 양성(0건)과 음성(위반을 만든 뒤 기대한 개수)에 같은 쿼리를 쓴다.
CREATE TEMP TABLE zz_q (code text PRIMARY KEY, section text NOT NULL, item text NOT NULL, sql text NOT NULL);
INSERT INTO zz_q (code, section, item, sql) VALUES
    ('ROWS_COVER', '1 행 수', '수익률 (종목, 달)에 관심 지수 행이 없는 수 (행이 1:1 로 채워졌는가 — 빠진 행)',
     $q$SELECT count(*) FROM asset_month_return r
        WHERE NOT EXISTS (SELECT 1 FROM asset_month_attention t WHERE t.asset_id = r.asset_id AND t.month_block_id = r.month_block_id)$q$),
    ('ROWS_EXTRA', '1 행 수', '수익률 행이 없는 관심 지수 행 수 (남는 행)',
     $q$SELECT count(*) FROM asset_month_attention t
        WHERE NOT EXISTS (SELECT 1 FROM asset_month_return r WHERE r.asset_id = t.asset_id AND r.month_block_id = t.month_block_id)$q$),
    ('IND_WINDOW', '2 독립 계산', 'window_months 가 윈도 함수 독립 계산(프레임 12 PRECEDING AND 1 PRECEDING 의 행 수)과 다른 행',
     $q$SELECT count(*) FROM asset_month_attention t
        LEFT JOIN zz_ind i ON i.asset_id = t.asset_id AND i.month_block_id = t.month_block_id
        WHERE i.asset_id IS NULL OR t.window_months <> i.n$q$),
    ('IND_Z', '2 독립 계산', 'attention_z 가 합·제곱합 식 독립 계산(±3 절단, 8자리 반올림)과 다른 행',
     $q$SELECT count(*) FROM asset_month_attention t
        LEFT JOIN zz_ind i ON i.asset_id = t.asset_id AND i.month_block_id = t.month_block_id
        WHERE i.asset_id IS NULL OR t.attention_z IS DISTINCT FROM i.z_clip$q$),
    ('IND_STREAK', '2 독립 계산', 'streak 이 누적 합 연속 번호 독립 계산과 다른 행',
     $q$SELECT count(*) FROM asset_month_attention t
        LEFT JOIN zz_ind i ON i.asset_id = t.asset_id AND i.month_block_id = t.month_block_id
        WHERE i.asset_id IS NULL OR t.streak IS DISTINCT FROM i.streak_i$q$),
    ('IND_INDEX', '2 독립 계산', 'attention_index 가 독립 계산(z + 연속 보너스)과 다른 행',
     $q$SELECT count(*) FROM asset_month_attention t
        LEFT JOIN zz_ind i ON i.asset_id = t.asset_id AND i.month_block_id = t.month_block_id
        WHERE i.asset_id IS NULL OR t.attention_index IS DISTINCT FROM i.idx_i$q$),
    ('NULL_RULE', '3 규칙', 'attention_z 가 NULL ⇔ (창 < 12 또는 분산 < 1e-8, 즉 표준편차 < 0.0001) 이 아닌 행',
     $q$SELECT count(*) FROM asset_month_attention t JOIN zz_ind i ON i.asset_id = t.asset_id AND i.month_block_id = t.month_block_id
        WHERE (t.attention_z IS NULL) <> (i.n < 12 OR i.var < 0.00000001)$q$),
    ('INDEX_NULL', '3 규칙', 'attention_index 가 NULL ⇔ attention_z 가 NULL 이 아닌 행',
     $q$SELECT count(*) FROM asset_month_attention WHERE (attention_index IS NULL) <> (attention_z IS NULL)$q$),
    ('Z_RANGE', '3 규칙', '|attention_z| > 3 이거나 attention_index 가 [−3, 4] 밖인 행',
     $q$SELECT count(*) FROM asset_month_attention WHERE abs(attention_z) > 3 OR attention_index < -3 OR attention_index > 4$q$),
    ('INDEX_FORMULA', '3 규칙', 'attention_index ≠ 저장된 z + 보너스, 또는 ≠ 반올림 전 절단 z 에 보너스를 더해 한 번 반올림한 값 (F9 반올림 한 번)',
     $q$SELECT count(*) FROM asset_month_attention t JOIN zz_ind i ON i.asset_id = t.asset_id AND i.month_block_id = t.month_block_id
        WHERE t.attention_z IS NOT NULL
          AND (t.attention_index IS DISTINCT FROM t.attention_z + CASE WHEN t.streak = 0 THEN 0 ELSE 0.25 * (LEAST(abs(t.streak), 5) - 1) END
               OR t.attention_index IS DISTINCT FROM ROUND(i.z_c + CASE WHEN t.streak = 0 THEN 0 ELSE 0.25 * (LEAST(abs(t.streak), 5) - 1) END, 8))$q$),
    ('SEG_START', '3 규칙', '구간 시작 달(직전 달 수익률 행이 없는 달)인데 창이 0 이 아니거나 |streak| > 1 이거나 z 가 있는 행 (2012-04 와 2023-01 이 이어지지 않는다)',
     $q$SELECT count(*) FROM asset_month_attention t
        WHERE NOT EXISTS (SELECT 1 FROM asset_month_return p JOIN month_block pm ON pm.id = p.month_block_id
                          JOIN month_block cm ON cm.id = t.month_block_id
                          WHERE p.asset_id = t.asset_id AND pm.month_start = (cm.month_start - INTERVAL '1 month')::date)
          AND (t.window_months <> 0 OR abs(t.streak) > 1 OR t.attention_z IS NOT NULL)$q$),
    ('NO_NAN', '3 규칙', 'NaN 이 저장된 행 + attn_calc 결과(반올림 전 값 포함)에 NaN 이 든 행',
     $q$SELECT (SELECT count(*) FROM asset_month_attention WHERE attention_z = 'NaN'::numeric OR attention_index = 'NaN'::numeric)
             + (SELECT count(*) FROM attn_calc WHERE attention_z = 'NaN'::numeric OR attention_index = 'NaN'::numeric OR z_raw = 'NaN'::numeric OR sd_raw = 'NaN'::numeric)$q$),
    ('HAND', '4 손 계산', '합성 수익률을 덮어쓴 구간에서 attn_calc 가 손 계산 기대값(리터럴)과 다른 행 + 덮어쓰지 못한 달',
     $q$SELECT pg_temp.hand_count()$q$),
    ('H8_CUT', '5 H8', '기준 달 M 뒤 수익률을 바꾸거나 지운 상태에서 다시 계산했을 때 M 이하 행이 변이 전 같은 뷰의 결과와 다르거나 한쪽에만 있는 수 + (정식 계산이면) 변이 전 결과와 저장값의 차이 (사례 7개 달 × 변이 3종의 합)',
     $q$SELECT pg_temp.h8_count((SELECT h8_view FROM zz_cfg), 'cut')$q$),
    ('H8_MOVED', '5 H8', 'M 뒤 행이 하나도 달라지지 않은 사례 수 (시험이 헛돌지 않는다 — 변이가 M 뒤 행에 닿는다)',
     $q$SELECT pg_temp.h8_count((SELECT h8_view FROM zz_cfg), 'moved')$q$),
    ('H8_TEETH', '5 H8', '일부러 다음 달을 보는 뷰(zz_leak)를 같은 시험이 잡지 못한 경우 (잡으면 0, 못 잡으면 1)',
     $q$SELECT (pg_temp.h8_count('zz_leak', 'cut') = 0)::integer$q$),
    ('RANK_RULE', '6 순위·그룹', '순위 R(zz_canon RANK)이 규칙과 다른 수: RANK() 독립 정의, 동률은 같은 순위, NULL 지수 제외, 표시 순서(market, ticker), 상위 q, 합성 동률',
     $q$SELECT pg_temp.rank_count()$q$),
    ('GROUP_AVG', '6 순위·그룹', '그룹 조회 G(zz_canon GROUP)가 독립 계산(직접 SUM/COUNT)과 다른 수: 평균·n_members·n_pool, 지수 없는 그룹·달은 NULL',
     $q$SELECT pg_temp.group_count()$q$);

-- 1~6 양성: 위 쿼리 모두 위반 0건 ---------------------------------------------------
INSERT INTO zz_check_result (section, item, expected, actual, ok)
SELECT q.section, '[' || q.code || '] ' || q.item || ' — 위반 0건', '0', v.n, v.n IS NOT DISTINCT FROM '0'
FROM zz_q q CROSS JOIN LATERAL (SELECT pg_temp.val(q.sql) AS n OFFSET 0) v   -- 검사 쿼리는 한 번만 실행한다
ORDER BY q.section, q.code;

-- 정보 행 (FAIL 아님): 규모와 NULL 이유·절단·구간 시작의 건수. 값은 쓰지 않는다
INSERT INTO zz_check_result (section, item, expected, actual, ok)
VALUES ('1 행 수', 'asset_month_attention 행 수 = asset_month_return 행 수 (정보)', '(정보)',
        pg_temp.val('SELECT count(*) FROM asset_month_attention') || ' = ' || pg_temp.val('SELECT count(*) FROM asset_month_return'), true),
       ('3 규칙', 'z NULL 이유별 건수: 창 < 12 / 표준편차 하한 미만 (정보)', '(정보)',
        pg_temp.val('SELECT count(*) FROM asset_month_attention WHERE window_months < 12') || ' / '
        || pg_temp.val('SELECT count(*) FROM asset_month_attention WHERE window_months = 12 AND attention_z IS NULL'), true),
       ('3 규칙', '±3 절단 건수: +3 / −3 (정보, attn_calc 의 반올림 전 z 기준)', '(정보)',
        pg_temp.val('SELECT count(*) FROM attn_calc WHERE z_raw > 3') || ' / ' || pg_temp.val('SELECT count(*) FROM attn_calc WHERE z_raw < -3'), true),
       ('3 규칙', '구간 시작 달 행 수 (창 0, 정보)', '(정보)', pg_temp.val('SELECT count(*) FROM asset_month_attention WHERE window_months = 0'), true);

-- 음성 사례: 일부러 위반을 만들면 그 규칙의 검사 쿼리가 기대한 개수로 잡는지 (서브트랜잭션 안의 DELETE/UPDATE, 끝에 되돌린다) ----------
-- expect: 기대 위반 수. cmp = 'ge' 이면 그 수 이상이면 통과. alt: 변이가 01 스키마의 CHECK 에 막혀 이 오류 코드로 끝나도 통과
CREATE TEMP TABLE zz_neg (n serial, q text NOT NULL, label text NOT NULL, mutate text NOT NULL, expect text NOT NULL DEFAULT '1',
                          cmp text NOT NULL DEFAULT 'eq', alt text);
INSERT INTO zz_neg (q, label, mutate, expect, cmp, alt) VALUES
    ('ROWS_COVER', '관심 지수 행 하나를 지우면',
     $m$DELETE FROM asset_month_attention WHERE id = (SELECT min(id) FROM asset_month_attention)$m$, '1', 'eq', NULL),
    ('ROWS_EXTRA', '수익률 행 하나를 지우면 (그 달의 관심 지수 행이 남는다)',
     $m$DELETE FROM asset_month_return WHERE id = (SELECT min(id) FROM asset_month_return)$m$, '1', 'eq', NULL),
    ('IND_WINDOW', '창 12 인 행의 window_months 를 11 로 바꾸면',
     $m$UPDATE asset_month_attention SET window_months = 11 WHERE id = (SELECT min(id) FROM asset_month_attention WHERE window_months = 12)$m$, '1', 'eq', NULL),
    ('IND_Z', 'attention_z 를 1e-8 바꾸면 (8자리 저장값 일치를 요구한다)',
     $m$UPDATE asset_month_attention SET attention_z = attention_z + 0.00000001 WHERE id = (SELECT min(id) FROM asset_month_attention WHERE attention_z BETWEEN -2 AND 2)$m$, '1', 'eq', NULL),
    ('IND_STREAK', 'streak 의 부호를 바꾸면',
     $m$UPDATE asset_month_attention SET streak = -streak WHERE id = (SELECT min(id) FROM asset_month_attention WHERE streak <> 0)$m$, '1', 'eq', NULL),
    ('IND_INDEX', 'attention_index 를 1e-8 바꾸면',
     $m$UPDATE asset_month_attention SET attention_index = attention_index + 0.00000001 WHERE id = (SELECT min(id) FROM asset_month_attention WHERE attention_index IS NOT NULL)$m$, '1', 'eq', NULL),
    ('NULL_RULE', '창이 찬 행의 z 와 지수를 NULL 로 만들면 (DDL 은 통과)',
     $m$UPDATE asset_month_attention SET attention_z = NULL, attention_index = NULL WHERE id = (SELECT min(id) FROM asset_month_attention WHERE window_months = 12 AND attention_z IS NOT NULL)$m$, '1', 'eq', NULL),
    ('INDEX_NULL', 'z 가 있는 행의 지수만 NULL 로 만들면 (DDL 은 통과)',
     $m$UPDATE asset_month_attention SET attention_index = NULL WHERE id = (SELECT min(id) FROM asset_month_attention WHERE attention_z IS NOT NULL)$m$, '1', 'eq', NULL),
    ('Z_RANGE', 'attention_z 를 3.5 로 만들면 (DDL 은 범위를 막지 않는다)',
     $m$UPDATE asset_month_attention SET attention_z = 3.5 WHERE id = (SELECT min(id) FROM asset_month_attention WHERE attention_z IS NOT NULL)$m$, '1', 'eq', NULL),
    ('INDEX_FORMULA', 'attention_index 를 1e-8 바꾸면',
     $m$UPDATE asset_month_attention SET attention_index = attention_index + 0.00000001 WHERE id = (SELECT min(id) FROM asset_month_attention WHERE attention_index IS NOT NULL)$m$, '1', 'eq', NULL),
    ('SEG_START', '2023-01(표본이 끊긴 뒤 첫 달) 행의 창을 3 으로 만들면 (z 는 NULL 이라 DDL 은 통과)',
     $m$UPDATE asset_month_attention SET window_months = 3
        WHERE id = (SELECT min(t.id) FROM asset_month_attention t JOIN month_block m ON m.id = t.month_block_id WHERE m.month_start = DATE '2023-01-01')$m$, '1', 'eq', NULL),
    ('NO_NAN', 'attention_z 를 NaN 으로 만들면 (01 스키마가 NaN 을 막으면 23514 로 막힌 것도 통과)',
     $m$UPDATE asset_month_attention SET attention_z = 'NaN'::numeric WHERE id = (SELECT min(id) FROM asset_month_attention WHERE attention_z IS NOT NULL)$m$, '1', 'eq', '^ERROR 23514'),
    ('HAND', '기대값 한 칸(H1 의 z)을 1e-8 바꾸면',
     $m$UPDATE zz_hand_exp SET z = z + 0.00000001 WHERE arank = 1 AND k = 12$m$, '1', 'eq', NULL),
    ('H8_CUT', 'H8 시험을 샌 뷰(zz_leak)에 돌리면 (다음 달을 보는 계산은 M 이하 행이 달라진다)',
     $m$UPDATE zz_cfg SET h8_view = 'zz_leak'$m$, '1', 'ge', NULL),
    ('H8_MOVED', '기준 달 목록에 마지막 달을 더하면 (뒤에 바뀔 행이 없어 시험이 헛돈다 — 변이 3종 모두)',
     $m$INSERT INTO zz_h8_m SELECT max(month_start) FROM month_block$m$, '3', 'eq', NULL),
    ('H8_TEETH', '샌 뷰가 다음 달 값을 섞지 않게 하면 (시험이 잡을 것이 없다)',
     $m$UPDATE zz_cfg SET leak_scale = 0$m$, '1', 'eq', NULL),
    ('RANK_RULE', '정본 쿼리의 RANK() 를 DENSE_RANK() 로 바꾸면 (동률 뒤 순위가 달라진다)',
     $m$UPDATE zz_canon SET sql = replace(sql, 'RANK() OVER', 'DENSE_RANK() OVER') WHERE name = 'RANK'$m$, '1', 'ge', NULL),
    ('RANK_RULE', '정본 쿼리의 NULL 제외 조건을 빼면',
     $m$UPDATE zz_canon SET sql = replace(sql, 'WHERE a.attention_index IS NOT NULL', 'WHERE TRUE') WHERE name = 'RANK'$m$, '1', 'ge', NULL),
    ('RANK_RULE', '정본 쿼리의 표시 순서에서 ticker 를 내림차순으로 바꾸면 (합성 동률의 순서가 뒤집힌다)',
     $m$UPDATE zz_canon SET sql = replace(sql, 's.ticker COLLATE "C") AS display_order', 's.ticker COLLATE "C" DESC) AS display_order') WHERE name = 'RANK'$m$, '1', 'ge', NULL),
    ('GROUP_AVG', '정본 쿼리가 NULL 지수를 0 으로 평균내면',
     $m$UPDATE zz_canon SET sql = replace(sql, 'AVG(t.attention_index)', 'AVG(COALESCE(t.attention_index, 0))') WHERE name = 'GROUP'$m$, '1', 'ge', NULL),
    ('GROUP_AVG', '정본 쿼리가 n_pool 을 지수 있는 종목 수로 세면',
     $m$UPDATE zz_canon SET sql = replace(sql, 'COUNT(*) AS n_pool', 'COUNT(t.attention_index) AS n_pool') WHERE name = 'GROUP'$m$, '1', 'ge', NULL);

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
            EXECUTE v_sql INTO v_n;
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

-- 보고: 절별 결과를 아래 8절이 집계한다. 값(지수·수익률)은 출력하지 않는다.

-- 8 판정 ---------------------------------------------------------------------
-- 8.1 규칙 커버리지: 음성 사례가 통과한 규칙 코드 수 / 규칙 코드 수 (18/18 이어야 한다)
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
        RAISE EXCEPTION 'P0-11 검증 실패: FAIL % 건 (위의 FAIL 행 목록을 본다)', v_fail;
    END IF;
END $$;

ROLLBACK;

-- 9 흔적 확인 (ROLLBACK 뒤) ------------------------------------------------------
-- asset_month_attention·asset_month_return 의 행 수와 identity 마지막 값이 실행 전과 같고, public 에 zz_ 객체가 없다.
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
        RAISE EXCEPTION E'P0-11 흔적 확인 실패: 실행 전 [%]\n실행 후 [%], public 의 zz_ 객체 % (기대 0)', v_before, v_after, v_zz;
    END IF;
    -- 단언을 모두 통과했고 1–8절도 통과(2)였을 때만 최종 통과(0)로 바꾼다.
    IF (SELECT last_value FROM pg_temp.zz_verdict) = 2 THEN
        PERFORM setval('pg_temp.zz_verdict', 0);
    END IF;
END $$;

SELECT CASE WHEN (SELECT last_value FROM pg_temp.zz_verdict) = 0 THEN 'PASS' ELSE 'FAIL' END AS p0_11_check;
