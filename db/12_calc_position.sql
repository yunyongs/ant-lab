-- =====================================================================
-- my Fund Manager Phase 0 — 시스템 판정 포지션 계산 (P0-10): asset_position 의 판정 8컬럼 채우기
-- 근거: docs/erd.md 5.3(asset_position)·D14·D15·D35·9절 P0-10, docs/PLAN.md §4.10·§4.5·§5.2
-- 대상: db/01_schema_history.sql 과 db/10_load_sample.sql 을 실행한 DB (PostgreSQL 17, DB timezone Asia/Seoul)
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/12_calc_position.sql
--       계산식은 db/12_calc_position_def.sql 에 한 곳뿐이고 이 파일이 \ir 로 읽는다.
-- 멱등: 판정이 비어 있는 행(system_position 이 NULL)만 채운다. 다시 실행하면 바뀌는 행이 0이다.
--       이미 판정된 행의 값이 계산값과 다르면(수익률이 바뀌었거나 공식이 바뀐 경우), 또는 판정됐는데 지금은 판정 불능이면 조용히 두지 않고
--       오류로 멈춘다 (값 정정은 하지 않는다). 전부 되돌아간다. 공식·입력이 바뀌면 antlab 을 (b′)로 다시 만들고 10 → 12 순서로 다시 돌린다.
-- 쓰는 곳: asset_position 의 UPDATE 한 문장뿐 (판정 8컬럼). 그 밖의 테이블은 읽기만 한다. 아무것도 지우거나 넣지 않는다.
-- 판정 불능: 최소 달 수(24, 오른 달 5, 빠진 달 5)에 못 미치면 8컬럼을 NULL 로 두고 사유별 건수만 보고한다. 0 대입·절단은 하지 않는다.
--       그 기준일은 erd 검증 규칙 H2 가 잡는다 (시즌 시작 S2 가 막는다).
-- 보고: 건수만 낸다. 참여율·변동성·낙폭·종목별 포지션은 출력하지 않는다 (시세에서 파생된 값을 로그·PR 에 남기지 않기 위해).
-- 구조: BEGIN → 이름 해석·전제·입력 단언 → \ir 계산식 → 계산 결과를 임시 표로 한 번 굳힘 → 차이 검사 → UPDATE 한 문장 → 사후 단언 → 보고 → COMMIT
-- 새 문법: UPDATE … SET (열, …) = (값, …) FROM (행 생성자로 8컬럼을 한 번에, 부분 채움이 문장 구조상 생기지 않는다), 데이터 변경 CTE(WITH … UPDATE … RETURNING),
--       \ir, 임시 표로 굳히기, IS DISTINCT FROM 으로 NULL 안전 비교.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

BEGIN;

-- [이름 해석·전제·입력 단언] 5개 이름이 public 으로 해석되고 10 적재가 끝나 있고 입력이 계산 가능한 모양일 때만 계속한다
DO $$
DECLARE
    v_bad text;
    v_n   bigint;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-10 계산: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), COALESCE(v_bad, '-');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM asset_position) OR NOT EXISTS (SELECT 1 FROM asset_month_return) THEN
        RAISE EXCEPTION 'P0-10 계산: asset_position 또는 asset_month_return 이 비어 있다. db/10_load_sample.sql 을 먼저 실행한다 (10 적재를 먼저)';
    END IF;
    -- 입력 단언: 날짜 산술이 달의 첫날을 전제한다 / NaN 은 계산을 오염시킨다 (01 스키마가 아직 막지 않을 수 있다)
    SELECT count(*) INTO v_n FROM month_block WHERE month_start <> date_trunc('month', month_start::timestamp)::date;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-10 계산: month_block.month_start 가 달의 첫날이 아닌 행이 % 건 있다', v_n;
    END IF;
    SELECT (SELECT count(*) FROM asset_month_return WHERE local_return = 'NaN'::numeric)
         + (SELECT count(*) FROM fx_month WHERE fx_change = 'NaN'::numeric) INTO v_n;
    IF v_n > 0 THEN
        RAISE EXCEPTION 'P0-10 계산: 수익률·환율에 NaN 이 % 건 있다. 계산하지 않는다', v_n;
    END IF;
END $$;

\ir 12_calc_position_def.sql

-- 시장 대용 종목·환율이 창 안 모든 월 블록에 있는지 먼저 전부 읽어 본다 (빠졌으면 여기서 오류로 멈춘다 — 열을 읽어야 평가된다)
DO $$
DECLARE
    v_n bigint;
BEGIN
    SELECT count(r_mkt) INTO v_n FROM pos_mkt;
END $$;

-- 계산 결과를 한 번만 굳힌다 (뷰는 읽을 때마다 다시 계산한다). 이후 차이 검사·UPDATE·보고는 모두 이 표를 본다.
CREATE TEMP TABLE ld_judge ON COMMIT DROP AS
SELECT asset_position_id, base_date, judgeable, reason,
       window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position
FROM pos_judge;

CREATE TEMP TABLE ld_err (msg text) ON COMMIT DROP;
CREATE TEMP TABLE ld_report (seq serial, item text, n bigint) ON COMMIT DROP;

-- [차이 검사] 이미 판정된 행이 계산값과 다르면(또는 지금은 판정 불능이면) 조용히 옛 값을 두지 않고 멈춘다.
INSERT INTO ld_err
SELECT 'asset_position 의 판정이 계산값과 다르다: asset_position_id=' || p.id || ' base_date=' || p.base_date
FROM asset_position p
JOIN ld_judge j ON j.asset_position_id = p.id
WHERE p.system_position IS NOT NULL
  AND (p.window_start, p.window_end, p.months_used, p.up_participation, p.down_participation, p.volatility, p.max_drawdown, p.system_position)
      IS DISTINCT FROM
      (j.window_start, j.window_end, j.months_used, j.up_participation, j.down_participation, j.volatility, j.max_drawdown, j.system_position);

DO $$
DECLARE
    v_n   bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-10 계산: 저장된 판정과 계산값이 어긋난다 (처음 % 건). 값 정정은 하지 않는다. 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- [채우기] 판정이 비어 있고 판정 가능한 행만. 8컬럼을 행 값 하나로 넣으므로 일부만 채워지는 일이 없다 (CHECK ck_asset_position_judgment_all_or_none 도 막는다).
--         NUMERIC(12,8) 범위를 넘는 값은 22003 으로 멈춘다 (절단·대입 없음).
WITH upd AS (
    UPDATE asset_position p
    SET (window_start, window_end, months_used, up_participation, down_participation, volatility, max_drawdown, system_position)
      = (j.window_start, j.window_end, j.months_used, j.up_participation, j.down_participation, j.volatility, j.max_drawdown, j.system_position)
    FROM ld_judge j
    WHERE j.asset_position_id = p.id AND p.system_position IS NULL AND j.judgeable
    RETURNING p.id)
INSERT INTO ld_report (item, n) SELECT '이번에 채운 행 (다시 실행하면 0)', count(*) FROM upd;

-- [사후 단언] 이번 실행이 끝난 DB 기준: 판정된 행의 창이 기준일 달의 바로 전 달에서 끝난다 (H2, 사후 정보 배제) / 창 시작은 35개월 앞 / NaN 0
--   상수(1, 35)는 def 의 pos_k 를 독립 확인용으로 다시 적은 것이다. 바꾸면 def·이 단언·검증 파일·erd D35 를 같이 고친다.
INSERT INTO ld_err
SELECT 'H2 위반(창 끝이 기준일 달의 바로 전 달이 아니다): asset_position_id=' || p.id
FROM asset_position p
WHERE p.system_position IS NOT NULL
  AND (p.window_end <> (date_trunc('month', p.base_date::timestamp) - INTERVAL '1 month')::date
       OR p.window_start <> (date_trunc('month', p.base_date::timestamp) - INTERVAL '36 months')::date)
UNION ALL
SELECT 'NaN 이 저장되었다: asset_position_id=' || p.id
FROM asset_position p
WHERE p.up_participation = 'NaN'::numeric OR p.down_participation = 'NaN'::numeric
   OR p.volatility = 'NaN'::numeric OR p.max_drawdown = 'NaN'::numeric;

DO $$
DECLARE
    v_n   bigint;
    v_msg text;
BEGIN
    SELECT count(*), string_agg(left(msg, 160), E'\n' ORDER BY msg) INTO v_n, v_msg FROM (SELECT msg FROM ld_err ORDER BY msg LIMIT 20) e;
    IF v_n > 0 THEN
        RAISE EXCEPTION E'P0-10 계산: 계산 뒤 단언 실패 (처음 % 건). 전부 되돌린다\n%', v_n, v_msg;
    END IF;
END $$;

-- 보고 1: 대상 행 수·이번에 채운 수·이미 같던 수·아직 비어 있는 수 (다시 실행하면 채운 수는 0)
SELECT (SELECT count(*) FROM asset_position)                                                  AS target_rows,
       (SELECT n FROM ld_report WHERE item LIKE '이번에 채운 행%')                                  AS filled_now,
       (SELECT count(*) FROM ld_judge j JOIN asset_position p ON p.id = j.asset_position_id
         WHERE j.judgeable AND p.system_position IS NOT NULL) - (SELECT n FROM ld_report WHERE item LIKE '이번에 채운 행%') AS already_same,
       (SELECT count(*) FROM asset_position WHERE system_position IS NULL)                     AS still_empty;

-- 보고 2: 판정 불능 사유별 건수 (값은 내지 않는다). 0이 아니면 그 기준일은 H2 위반이다.
SELECT count(*) FILTER (WHERE reason = 'MONTHS')   AS unjudgeable_months,
       count(*) FILTER (WHERE reason = 'N_UP')     AS unjudgeable_n_up,
       count(*) FILTER (WHERE reason = 'N_DOWN')   AS unjudgeable_n_down,
       count(*) FILTER (WHERE reason = 'ZERO_DEN') AS unjudgeable_zero_den
FROM ld_judge;

COMMIT;
