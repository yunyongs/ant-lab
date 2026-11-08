-- =====================================================================
-- my Fund Manager Phase 0 — 통계 보드 출력 (P0-12): 달력 월·사건 유형·관심 정점 조건부 평균·상승 확률·표본 수
-- 근거: docs/erd.md 5.3·D34, docs/PLAN.md §4.11·§4.13·§5.2, 정의는 db/14_stats_calendar_def.sql 한 곳 (이 파일이 \ir 로 읽는다)
-- 대상: db/01_schema_history.sql → db/10_load_sample.sql → db/11_load_events.sql → db/13_calc_attention.sql 을 실행한 DB (PostgreSQL 17)
-- 실행: 저장소 루트에서  psql -X -v ON_ERROR_STOP=1 -d <DB이름> -f db/14_stats_calendar.sql
--       기준일을 고르려면  -v base_date=2026-01-02  (asset_position.base_date 중 하나. 없으면 가장 늦은 기준일)
-- 쓰기: 없다. 임시 뷰만 만들고 마지막에 ROLLBACK 한다. 그래서 실습 DB(antlab)에서도 몇 번이든 돌릴 수 있다.
-- 출력: ① 종류별 건수 표 (값 없음) ② 통계 보드 (n >= 5 인 조건만. 열: base_date, kind, cond_key, subject, horizon, n, n_up, mean_return, up_prob, sample_note)
--   kind     CALENDAR(달력 월 1–12) / EVENT(사건 유형의 START 뒤) / ATTENTION(관심 정점 뒤)
--   cond_key CALENDAR '01'–'12', EVENT event_type, ATTENTION 'PEAK'(정점)·'NON_PEAK'(비교용: 순위 대상 중 정점이 아닌 행)
--   subject  CALENDAR·EVENT 는 시리즈('KR:069500', 'FX:USD' 꼴), ATTENTION 은 시장('KR'·'US')
--   horizon  CALENDAR 0, 그 밖에는 기준 달 뒤 1 또는 3개월
--   n 이 5 미만인 조건은 나오지 않고, 5 이상 10 미만이면 sample_note 가 '일화' 이다 (PLAN §4.11).
-- 값은 시세에서 나온 통계다. 터미널에서만 보고 PR·문서·이슈에 붙이지 않는다 (db/sample/README "추적하지 않는 이유").
-- 새 문법: \if 로 psql 변수 기본값 정하기, ROLLBACK 으로 임시 뷰만 쓰고 지우기, GROUP BY … HAVING, 집계 FILTER.
-- 명령줄(psql)이 기준이다. VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

-- 기준일 변수: 주지 않으면 빈 문자열 (아래에서 가장 늦은 기준일로 바꾼다)
\if :{?base_date}
\else
\set base_date ''
\endif

BEGIN;

-- [전제] 10·11·13 적재가 끝나 있고 기준일이 있어야 한다. 같은 (유형, 달)에 START 가 둘이면 멈춘다 (A9). 이름은 public 의 테이블이어야 한다.
DO $$
DECLARE
    v_bad text;
BEGIN
    SELECT string_agg(n, ', ') INTO v_bad
    FROM unnest(ARRAY['asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month', 'month_event', 'asset_month_attention']) AS n
    WHERE to_regclass(n) IS NULL OR to_regclass(n) IS DISTINCT FROM to_regclass('public.' || n);
    IF current_schema() IS DISTINCT FROM 'public' OR v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'P0-12 통계: 이름이 public 의 테이블을 가리키지 않는다 (current_schema=%, 어긋난 이름: %). 01 스키마를 먼저 실행했는지, search_path 를 확인한다',
            current_schema(), v_bad;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM asset_position) OR NOT EXISTS (SELECT 1 FROM asset_month_return) THEN
        RAISE EXCEPTION 'P0-12 통계: 10 적재를 먼저 실행한다 (asset_position 또는 asset_month_return 이 비어 있다)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM month_event) THEN
        RAISE EXCEPTION 'P0-12 통계: 11 사건 태그 적재를 먼저 실행한다 (month_event 가 비어 있다)';
    END IF;
    IF EXISTS (SELECT 1 FROM month_event WHERE phase = 'START'
               GROUP BY event_type, month_block_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'P0-12 통계: 같은 (유형, 달)에 START 가 둘 이상이다 — 사건 n 을 이중으로 세므로 멈춘다 (erd D34·events/README 에피소드 규칙). events/tags.csv 를 확인한다';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM asset_month_attention) THEN
        RAISE EXCEPTION 'P0-12 통계: 13 관심 지수 계산을 먼저 실행한다 (asset_month_attention 이 비어 있다)';
    END IF;
END $$;

-- 정의를 읽는다 (임시 뷰 7개가 만들어진다)
\ir 14_stats_calendar_def.sql

-- 고른 기준일 (한 줄로 굳혀 두고 아래 두 표가 같은 값을 쓴다)
CREATE TEMP TABLE pick AS
SELECT COALESCE(NULLIF(:'base_date', '')::date, (SELECT max(base_date) FROM asset_position)) AS base_date;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM asset_position a JOIN pick p ON p.base_date = a.base_date) THEN
        RAISE EXCEPTION 'P0-12 통계: 기준일이 asset_position 에 없다 (-v base_date=… 를 확인한다)';
    END IF;
END $$;

-- ① 종류별 건수 (값 없음): shown = 보드에 나온 조건 수, hidden = n < 5 라 숨긴 조건 수, anecdote = 그중 '일화' 조건 수, obs = 관측 수
--   Phase 0 표본에서 사건(EVENT) 조건은 유형별 START 가 적어 전부 숨겨진다. 규칙이 의도대로 동작한 결과다.
SELECT g.kind,
       count(*) FILTER (WHERE g.n >= k.min_n)                       AS shown,
       count(*) FILTER (WHERE g.n < k.min_n)                        AS hidden,
       count(*) FILTER (WHERE g.n >= k.min_n AND g.n < k.anecdote_n) AS anecdote,
       sum(g.n)                                                     AS obs
FROM (SELECT o.kind, o.cond_key, o.subject, o.horizon, count(*) AS n
      FROM stat_obs o JOIN pick p ON p.base_date = o.base_date
      GROUP BY o.kind, o.cond_key, o.subject, o.horizon) g
CROSS JOIN stat_k k
GROUP BY g.kind
ORDER BY g.kind;

-- ② 통계 보드 (n >= 5). 같은 입력이면 같은 출력이 되도록 정렬한다
SELECT b.base_date, b.kind, b.cond_key, b.subject, b.horizon, b.n, b.n_up, b.mean_return, b.up_prob, b.sample_note
FROM stat_board b JOIN pick p ON p.base_date = b.base_date
ORDER BY b.kind, b.cond_key, b.subject COLLATE "C", b.horizon;

ROLLBACK;
