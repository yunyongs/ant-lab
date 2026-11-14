-- =====================================================================
-- my Fund Manager Phase 0 — 통계 보드 정의 (P0-12): 달력 월·사건 유형·관심 정점 조건부 통계의 임시 뷰
-- 근거: docs/erd.md 5.3(month_block 연속성·asset_month_return·fx_month·month_event·asset_month_attention)·D18·D19·D33·D34,
--       docs/PLAN.md §4.2(표본 집합 "작년 말까지")·§4.5(사후 정보 배제)·§4.11(통계 보드)·§4.13·§5.2(반올림 한 번)
-- 단독 실행 대상이 아니다. db/14_stats_calendar.sql(출력)과 db/14_stats_calendar_check.sql(검증)이 \ir 로 읽는다.
--       출력과 검증이 같은 정의를 쓰므로 검증이 복사본이 아니라 실제 정의를 시험한다.
-- 만드는 것: 세션 임시 뷰 7개. 트랜잭션 문 없음, 테이블 쓰기 없음, 읽기 전용 정의. 저장하지 않고 읽을 때 계산한다 (PLAN §5.1 "테이블 없음").
--       읽는 표는 역사 7개뿐이다: asset, asset_position, month_block, asset_month_return, fx_month, month_event, asset_month_attention.
--       Rank lookup R is not defined here: this file reads it with \ir from db/13_calc_attention_rank_def.sql (temp view attn_rank, one definition).
--       시즌·계좌 표(erd 6·7절)는 읽지 않는다. 통계 보드는 시즌과 연결되지 않는다 (PLAN §4.11).
-- 뷰 (의존 순서):
--   stat_k       상수 한 줄: 최소 n 5, 일화 상한 10, 앞 창 {1, 3}개월 (정점 비율 q 는 attn_rank 한 곳)
--   stat_series  (base_date, subject, series_kind, asset_id, market, currency)  기준일 풀의 종목 전부 + 환율 'FX:USD'
--   stat_month   (base_date, subject, month_start, ret)  시리즈별 달 수익률 (현지 통화), 기준일이 속한 해 이전의 달만 (A1)
--   stat_peak    (base_date, month_block_id, month_start, asset_id, market, attention_index, attention_rank, n_ranked, is_peak)
--                attn_rank plus the year cut only (this base date's year and later are dropped). Reads the month's own values only (no future column).
--   stat_fwd     (base_date, subject, anchor_month, horizon, ret)  기준 달 뒤 h개월 누적 수익률. 달이 하나라도 비면 관측 없음
--   stat_obs     (base_date, kind, cond_key, subject, horizon, anchor_month, ret)  관측 하나가 한 줄. n 으로 거르지 않는다 [내부 — 화면·DTO 로 내지 않는다]
--   stat_board   (base_date, kind, cond_key, subject, horizon, n, n_up, mean_return, up_prob, sample_note)  n >= 5 만, 반올림 한 번
-- 통계 정의 (docs/erd.md D34):
--   A1 기준일마다 계산한다. 쓰는 달 = month_start < 기준일이 속한 해의 1월 (올해 제외).
--   A2 통계 대상(subject): 기준일 풀의 종목 전부 ('KR:069500' 꼴)와 환율 시리즈 'FX:USD'. 보드의 6개 고르기는 화면 몫.
--   A3 통화: 현지 통화 수익률(local_return). 환율은 따로 한 시리즈. 원화 환산은 MVP 이후.
--   A4 관측값: 달력은 그 달 수익률(horizon 0). 사건·정점은 기준 달 m 뒤 h개월 누적 Π(1 + r) − 1 (m + 1 … m + h, 기준 달 자신은 넣지 않는다).
--      m + 1 … m + h 의 모든 달이 연속으로 있어야 관측이 생긴다 (부분 창 금지 — 2012-04 ↔ 2023-01 끊김을 넘지 않는다). 곱은 정확한 numeric, 중간 반올림 없음.
--   A5 평균: 산술 평균. 기하 평균(ln/exp)은 쓰지 않는다.   A6 상승 확률 = n_up / n, 0 수익률은 상승이 아니다.
--   A8 n 의 단위: 달력 = (시리즈, 월)의 관측 달 수, 사건 = 창이 완전한 START 행 수, 정점 = 창이 완전한 (종목, 기준 달) 쌍 수.
--   A9 사건 조건 = event_type 의 phase = 'START' 달. A10 정점 = 기준일 풀의 그 달 RANK() <= CEIL(q × 순위 대상 수), NON_PEAK = 순위 대상 중 정점이 아닌 행, 시장별로 묶는다.
--   A11 표본 수 규칙은 stat_board 의 GROUP BY … HAVING count(*) >= 5 한 곳 (세 종류에 같은 규칙). 5 <= n < 10 은 sample_note '일화'.
--   A12 반올림은 stat_board 에서 한 번: ROUND(SUM(ret) / n, 8), ROUND(n_up / n, 8). 합은 소수 40자리로 올려 나눈다 (나눗셈이 먼저 반올림하는 이중 반올림 방지). 앞 창을 늘려 관측값이 소수 40자리를 넘으면 자릿수를 함께 늘린다.
-- 타입: 모두 numeric·integer·text·date. float4/float8 을 만드는 함수·캐스트(date_part, percentile_cont, ln, exp, power)를 쓰지 않는다.
--       달은 EXTRACT 로 구한다 (PostgreSQL 14 부터 numeric 을 돌려준다. date_part 는 double precision).
-- 새 문법: 재귀 CTE(WITH RECURSIVE), 집계 FILTER 절, GROUP BY … HAVING, 윈도 함수 RANK() OVER (PARTITION BY …), UNION ALL 로 모양 맞추기,
--       CREATE TEMP VIEW, make_interval(months => n), unnest().
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

DO $$ BEGIN
    -- 의존의 반대 순서로 지운다 (같은 세션에서 다시 읽어도 되도록)
    IF to_regclass('pg_temp.stat_board')  IS NOT NULL THEN DROP VIEW pg_temp.stat_board;  END IF;
    IF to_regclass('pg_temp.stat_obs')    IS NOT NULL THEN DROP VIEW pg_temp.stat_obs;    END IF;
    IF to_regclass('pg_temp.stat_fwd')    IS NOT NULL THEN DROP VIEW pg_temp.stat_fwd;    END IF;
    IF to_regclass('pg_temp.stat_peak')   IS NOT NULL THEN DROP VIEW pg_temp.stat_peak;   END IF;
    IF to_regclass('pg_temp.stat_month')  IS NOT NULL THEN DROP VIEW pg_temp.stat_month;  END IF;
    IF to_regclass('pg_temp.stat_series') IS NOT NULL THEN DROP VIEW pg_temp.stat_series; END IF;
    IF to_regclass('pg_temp.stat_k')      IS NOT NULL THEN DROP VIEW pg_temp.stat_k;      END IF;
END $$;

-- Rank lookup R (temp view attn_rank). Read after the drops above: attn_rank is dropped and recreated there, and stat_peak depends on it.
\ir 13_calc_attention_rank_def.sql

-- 상수 (표본에서 정하지 않은 고정값. 바꾸면 erd D34 도 바꾼다). 검증 파일은 독립 확인용으로 따로 적었다
CREATE TEMP VIEW stat_k AS
SELECT 5                    AS min_n,        -- 이보다 작은 n 은 보드에 내지 않는다 (PLAN §4.11)
       10                   AS anecdote_n,   -- 이보다 작으면 '일화' 표시 (PLAN §13)
       ARRAY[1, 3]          AS horizons;     -- 기준 달 뒤 몇 개월 (재귀 CTE 가 이 값으로 돈다)

-- A2 시리즈: 기준일마다 그 기준일 풀의 종목 + 환율
CREATE TEMP VIEW stat_series AS
SELECT p.base_date, a.market || ':' || a.ticker AS subject, 'ASSET'::text AS series_kind,
       a.id AS asset_id, a.market::text AS market, a.currency::text AS currency
FROM asset_position p
JOIN asset a ON a.id = p.asset_id
UNION ALL
SELECT d.base_date, 'FX:' || f.currency, 'FX', NULL::bigint, NULL::text, f.currency::text
FROM (SELECT DISTINCT base_date FROM asset_position) d
CROSS JOIN (SELECT DISTINCT currency FROM fx_month) f;

-- A1·A3 달 수익률: 기준일이 속한 해의 1월 이전 달만 (date_trunc 는 시간대 영향이 없는 timestamp 로 한다)
CREATE TEMP VIEW stat_month AS
SELECT s.base_date, s.subject, mb.month_start, ar.local_return AS ret
FROM stat_series s
JOIN asset_month_return ar ON ar.asset_id = s.asset_id
JOIN month_block mb ON mb.id = ar.month_block_id
WHERE s.series_kind = 'ASSET'
  AND mb.month_start < date_trunc('year', s.base_date::timestamp)::date
UNION ALL
SELECT s.base_date, s.subject, mb.month_start, f.fx_change
FROM stat_series s
JOIN fx_month f ON f.currency = s.currency
JOIN month_block mb ON mb.id = f.month_block_id
WHERE s.series_kind = 'FX'
  AND mb.month_start < date_trunc('year', s.base_date::timestamp)::date;

-- A10 peak = attn_rank.is_top_q (erd 5.3 rank lookup R) restricted by A1: months before the base date's year only.
--   The rank of a month depends on that month's stored values only, so cutting months afterwards does not change any rank.
CREATE TEMP VIEW stat_peak AS
SELECT r.base_date, r.month_block_id, r.month_start, r.asset_id, r.market, r.attention_index,
       r.attention_rank, r.n_ranked, r.is_top_q AS is_peak
FROM attn_rank r
WHERE r.month_start < date_trunc('year', r.base_date::timestamp)::date;

-- A4 앞 창: 기준 달 m 뒤 j개월까지의 누적 곱을 재귀로 쌓는다. 한 걸음마다 "m + j개월 달이 있는 행"에 내부 조인하므로
--   중간 달이 하나라도 비면 사슬이 거기서 끊겨 그 뒤의 h 는 나오지 않는다 (부분 창 없음).
--   기준 달은 month_block 의 모든 달이다 (기준 달 자신의 수익률은 필요 없다). acc = Π(1 + r), 반올림 없음.
CREATE TEMP VIEW stat_fwd AS
WITH RECURSIVE chain AS (
    SELECT s.base_date, s.subject, b.month_start AS anchor_month, 1 AS j, 1 + f.ret AS acc
    FROM stat_series s
    CROSS JOIN month_block b
    JOIN stat_month f ON f.base_date = s.base_date AND f.subject = s.subject
                     AND f.month_start = (b.month_start + INTERVAL '1 month')::date
    UNION ALL
    SELECT c.base_date, c.subject, c.anchor_month, c.j + 1, c.acc * (1 + f.ret)
    FROM chain c
    JOIN stat_month f ON f.base_date = c.base_date AND f.subject = c.subject
                     AND f.month_start = (c.anchor_month + make_interval(months => c.j + 1))::date
    WHERE c.j < (SELECT max(h) FROM stat_k k, unnest(k.horizons) AS h)
)
SELECT c.base_date, c.subject, c.anchor_month, c.j::smallint AS horizon, c.acc - 1 AS ret
FROM chain c
WHERE c.j IN (SELECT unnest(k.horizons) FROM stat_k k);

-- 관측: 세 종류를 같은 열로 쌓는다 (규칙이 한 곳이면 시험도 한 벌). n 으로 거르지 않는다.
CREATE TEMP VIEW stat_obs AS
SELECT m.base_date, 'CALENDAR'::text AS kind, lpad(EXTRACT(MONTH FROM m.month_start)::text, 2, '0') AS cond_key,
       m.subject, 0::smallint AS horizon, m.month_start AS anchor_month, m.ret
FROM stat_month m
UNION ALL
SELECT f.base_date, 'EVENT', e.event_type::text, f.subject, f.horizon, f.anchor_month, f.ret
FROM stat_fwd f
JOIN month_block b ON b.month_start = f.anchor_month
JOIN month_event e ON e.month_block_id = b.id AND e.phase = 'START'
UNION ALL
SELECT f.base_date, 'ATTENTION', CASE WHEN p.is_peak THEN 'PEAK' ELSE 'NON_PEAK' END, p.market, f.horizon, f.anchor_month, f.ret
FROM stat_peak p
JOIN stat_series s ON s.base_date = p.base_date AND s.asset_id = p.asset_id
JOIN stat_fwd f ON f.base_date = p.base_date AND f.subject = s.subject AND f.anchor_month = p.month_start;

-- 출력: 표본 수 규칙이 여기 한 곳이다. HAVING 은 그룹을 거르고(WHERE 는 행을 거른다), FILTER 는 조건에 맞는 행만 센다.
--   그룹은 n >= 1 이라 0 으로 나누는 식이 없다. 합을 소수 40자리로 올려 나누면 나눗셈 결과의 자릿수가 충분해 ROUND 가 한 번뿐이다.
CREATE TEMP VIEW stat_board AS
SELECT o.base_date, o.kind, o.cond_key, o.subject, o.horizon,
       count(*)::integer AS n,
       (count(*) FILTER (WHERE o.ret > 0))::integer AS n_up,
       ROUND(SUM(o.ret)::numeric(60,40) / count(*), 8) AS mean_return,
       ROUND((count(*) FILTER (WHERE o.ret > 0))::numeric / count(*), 8) AS up_prob,
       CASE WHEN count(*) < (SELECT anecdote_n FROM stat_k) THEN '일화' END AS sample_note
FROM stat_obs o
GROUP BY o.base_date, o.kind, o.cond_key, o.subject, o.horizon
HAVING count(*) >= (SELECT min_n FROM stat_k);
