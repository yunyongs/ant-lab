-- =====================================================================
-- my Fund Manager Phase 0 — 관심 지수 계산·적재 (P0-11): asset_month_return → asset_month_attention
-- 근거: docs/erd.md 5.3(asset_month_attention 비고)·D33·9절 P0-11, docs/PLAN.md §4.5·§4.13, docs/SPRING_JPA.md 7절
-- 대상: db/01_schema_history.sql 과 db/10_load_sample.sql 을 실행한 DB (PostgreSQL 17, DB timezone Asia/Seoul)
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/13_calc_attention.sql
--       계산식은 db/13_calc_attention_def.sql 에 한 곳뿐이고 이 파일이 \ir 로 읽는다.
-- 멱등: 이미 있는 행은 건드리지 않는다. 없는 (종목, 달)만 INSERT 하므로 다시 실행하면 새 행이 없다.
--       이미 있는 행의 값이 계산값과 다르면(수익률이 바뀌었거나 공식이 바뀐 경우), 그리고 DB 에만 있는 행이 있으면 조용히 두지 않고
--       오류로 멈춘다 (값 정정은 하지 않는다). 전부 되돌아간다. 공식·입력이 바뀌면 antlab 을 (b′)로 다시 만들고 10 → 11 → 13 순서로 다시 돌린다.
-- 쓰는 곳: asset_month_attention 의 INSERT 만. 그 밖의 테이블은 읽기만 한다. 아무것도 지우거나 고치지 않는다.
-- 계산: 각 행은 그 종목의 "그 달까지의" 수익률만으로 계산한다 (PLAN §4.5 사후 정보 배제). 공식은 def 파일 머리글과 erd D33 참조.
-- 보고: 건수만 낸다. 지수·수익률 값은 출력하지 않는다 (시세에서 파생된 값을 로그·PR 에 남기지 않기 위해).
-- 구조: BEGIN → 이름 해석·전제·입력 단언 → \ir 계산식 → 계산 결과를 임시 표로 한 번 굳힘 → INSERT … WHERE NOT EXISTS
--       → 차이 검사 → 사후 단언 → 보고 → COMMIT
-- 새 문법: \ir (포함하는 파일 기준 상대 경로), CREATE TEMP VIEW 를 \ir 로 공유, 임시 표로 굳히기(한 번만 계산),
--       IS DISTINCT FROM 으로 NULL 안전 비교.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

BEGIN;

-- [이름 해석·전제·입력 단언] 4개 이름이 public 으로 해석되고 10 적재가 끝나 있고 입력이 계산 가능한 모양일 때만 계속한다
DO $$
DECLARE
    v_bad text;
    v_n   bigint;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['asset', 'month_block', 'asset_month_return', 'asset_month_attention']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-11 계산: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM asset_month_return) THEN
        RAISE EXCEPTION 'P0-11 계산: asset_month_return 이 비어 있다. db/10_load_sample.sql 을 먼저 실행한다 (10 적재를 먼저)';
    END IF;
    -- 입력 단언: 날짜 산술이 달의 첫날을 전제한다 / NaN 은 계산을 오염시킨다 (01 스키마가 아직 막지 않을 수 있다)
    SELECT count(*) INTO v_n FROM month_block WHERE month_start <> date_trunc('month', month_start)::date;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-11 계산: month_block.month_start 가 달의 첫날이 아닌 행이 % 건 있다', v_n;
    END IF;
    SELECT count(*) INTO v_n FROM asset_month_return WHERE local_return = 'NaN'::numeric;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-11 계산: asset_month_return.local_return 에 NaN 이 % 건 있다. 계산하지 않는다', v_n;
    END IF;
END $$;

\ir 13_calc_attention_def.sql

-- 계산 결과를 한 번만 굳힌다 (뷰는 읽을 때마다 다시 계산한다). 이후 INSERT·차이 검사·보고는 모두 이 표를 본다.
CREATE TEMP TABLE ld_calc ON COMMIT DROP AS
SELECT asset_id, month_block_id, month_start, window_months, sd_raw, z_raw, attention_z, streak, attention_index
FROM attn_calc;

CREATE TEMP TABLE ld_err (msg text) ON COMMIT DROP;
CREATE TEMP TABLE ld_report (seq serial, item text, n bigint) ON COMMIT DROP;

-- [삽입] WHERE NOT EXISTS 로 이미 있는 행을 먼저 거른다 (ON CONFLICT 만 쓰면 충돌 행도 identity 번호를 소비한다).
--        ORDER BY 로 같은 입력이면 같은 번호가 붙게 한다.
WITH ins AS (
    INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak, attention_index)
    SELECT c.asset_id, c.month_block_id, c.window_months, c.attention_z, c.streak, c.attention_index
    FROM ld_calc c
    WHERE NOT EXISTS (SELECT 1 FROM asset_month_attention t
                      WHERE t.asset_id = c.asset_id AND t.month_block_id = c.month_block_id)
    ORDER BY c.asset_id, c.month_start
    RETURNING 1)
INSERT INTO ld_report (item, n) SELECT '이번에 넣은 행 (다시 실행하면 0)', count(*) FROM ins;

-- [차이 검사] 이미 있던 행이 계산값과 다르면 조용히 옛 값을 두지 않고 멈춘다. 방금 넣은 행도 같은 쿼리를 지나므로 삽입 자체의 오류도 잡는다.
--             DB 에만 있는 행(수익률 행이 사라진 (종목, 달))도 멈춘다.
INSERT INTO ld_err
SELECT 'asset_month_attention 값이 계산값과 다르다: asset_id=' || c.asset_id || ' month_start=' || c.month_start
FROM ld_calc c
JOIN asset_month_attention t ON t.asset_id = c.asset_id AND t.month_block_id = c.month_block_id
WHERE (t.window_months, t.attention_z, t.streak, t.attention_index)
      IS DISTINCT FROM
      (c.window_months, c.attention_z, c.streak, c.attention_index)
UNION ALL
SELECT 'DB 에만 있는 행(수익률 행이 없다): asset_id=' || t.asset_id || ' month_block_id=' || t.month_block_id
FROM asset_month_attention t
WHERE NOT EXISTS (SELECT 1 FROM ld_calc c WHERE c.asset_id = t.asset_id AND c.month_block_id = t.month_block_id);

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-11 계산: 계산값과 DB 가 어긋난다 (처음 % 건). 값 정정은 하지 않는다. 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- [사후 단언] 이번 실행이 끝난 DB 기준
--   행 수 = 수익률 행 수 / z NULL ⇔ (창 < 12 또는 표준편차 하한 미만) / index NULL ⇔ z NULL / NaN 0 / |z| ≤ 3 / index = z + 보너스
--   상수(12, 0.0001, 3, 0.25, 5)는 def 의 CTE k 를 독립 확인용으로 다시 적은 것이다. 바꾸면 def·이 단언·검증 파일·erd D33 을 같이 고친다.
INSERT INTO ld_err
SELECT '행 수가 수익률 행 수와 다르다: 수익률 ' || (SELECT count(*) FROM asset_month_return) || ' 관심 ' || (SELECT count(*) FROM asset_month_attention)
WHERE (SELECT count(*) FROM asset_month_return) <> (SELECT count(*) FROM asset_month_attention)
UNION ALL
SELECT 'z NULL 규칙 위반: asset_id=' || t.asset_id || ' month_block_id=' || t.month_block_id
FROM asset_month_attention t JOIN ld_calc c ON c.asset_id = t.asset_id AND c.month_block_id = t.month_block_id
WHERE (t.attention_z IS NULL) <> (c.window_months < 12 OR c.sd_raw IS NULL OR c.sd_raw < 0.0001)
UNION ALL
SELECT 'index NULL ⇔ z NULL 위반: asset_id=' || t.asset_id || ' month_block_id=' || t.month_block_id
FROM asset_month_attention t WHERE (t.attention_index IS NULL) <> (t.attention_z IS NULL)
UNION ALL
SELECT 'NaN 이 저장되었다: asset_id=' || t.asset_id || ' month_block_id=' || t.month_block_id
FROM asset_month_attention t WHERE t.attention_z = 'NaN'::numeric OR t.attention_index = 'NaN'::numeric
UNION ALL
SELECT '|z| > 3: asset_id=' || t.asset_id || ' month_block_id=' || t.month_block_id
FROM asset_month_attention t WHERE abs(t.attention_z) > 3
UNION ALL
SELECT 'index ≠ z + 보너스: asset_id=' || t.asset_id || ' month_block_id=' || t.month_block_id
FROM asset_month_attention t
WHERE t.attention_z IS NOT NULL
  AND t.attention_index IS DISTINCT FROM t.attention_z + CASE WHEN t.streak = 0 THEN 0 ELSE 0.25 * (LEAST(abs(t.streak), 5) - 1) END;

DO $$
DECLARE
    v_n bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-11 계산: 계산 뒤 단언 실패 (처음 % 건). 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- 보고 1: 대상 행 수·이번에 넣은 수·이미 같던 수 (다시 실행하면 넣은 수는 0, 같던 수는 대상 행 수)
SELECT (SELECT count(*) FROM ld_calc)                                                   AS target_rows,
       (SELECT n FROM ld_report WHERE item LIKE '이번에 넣은 행%')                          AS inserted_now,
       (SELECT count(*) FROM ld_calc) - (SELECT n FROM ld_report WHERE item LIKE '이번에 넣은 행%') AS already_same;

-- 보고 2: NULL 이유별·절단·구간 시작 건수 (값은 내지 않는다)
SELECT count(*) FILTER (WHERE window_months < 12)                                   AS null_window_short,
       count(*) FILTER (WHERE window_months = 12 AND attention_z IS NULL)           AS null_sd_floor,
       count(*) FILTER (WHERE z_raw > 3)                                           AS clipped_plus3,
       count(*) FILTER (WHERE z_raw < -3)                                          AS clipped_minus3,
       count(*) FILTER (WHERE window_months = 0)                                    AS segment_starts
FROM ld_calc;

COMMIT;
