-- =====================================================================
-- my Fund Manager Phase 0 — 관심 지수 계산식 정의 (P0-11): 임시 뷰 attn_calc
-- 근거: docs/erd.md 5.3(asset_month_attention 비고)·D33, docs/PLAN.md §4.5(이동 통계만)·§4.13
-- 단독 실행 대상이 아니다. db/13_calc_attention.sql(쓰기)과 db/13_calc_attention_check.sql(검증)이 \ir 로 읽는다.
--       쓰기와 검증이 같은 정의를 쓰므로, 사후 정보 배제(H8) 시험이 복사본이 아니라 실제 계산식을 시험한다.
-- 만드는 것: 세션 임시 뷰 attn_calc 하나. 트랜잭션 문 없음, 테이블 쓰기 없음. 지금 DB 의 asset_month_return 을 읽으므로
--       수익률이 바뀌면 다시 계산된 값이 나온다 (뷰는 저장하지 않고 읽을 때 계산한다).
-- 열:   asset_id, month_block_id, month_start, window_months, sd_raw, z_raw, attention_z, streak, attention_index
--       sd_raw·z_raw 는 반올림 전 값이다 (보고·검증용, 테이블에 넣지 않는다).
-- 규칙 (erd D33): 수익률 r = local_return(현지 통화), x = |r|.
--   F1 연속 구간: 같은 종목의 직전 달 행이 있으면 이어진다. 끊긴 뒤 첫 달이 구간 시작이다.
--   F2 창: 그 달 바로 앞의 연속 달 최대 12개 (그 달은 넣지 않는다). window_months = 창의 달 수 (0–12).
--   F3 창이 12개일 때만 z 를 계산한다. F4 평균·표본 표준편차(n − 1)는 창의 x 에서만 구한다.
--   F5 표준편차(반올림 전) < 0.0001 이면 z 는 NULL.  F6 z 를 ±3 으로 자른 뒤 소수 8자리로 한 번 반올림한다.
--   F7 streak: 부호 있는 연속 방향 달 수 (0 수익률은 0 이고 연속을 끊는다).
--   F8 attention_index = attention_z + 0.25 × (min(|streak|, 5) − 1)  (|streak| ≥ 1, 0 이면 보너스 0). z 가 NULL 이면 NULL.
--   F9 반올림은 z 에서 한 번뿐이다. 보너스는 0.25 의 배수라 더해도 새 반올림이 없다.
-- 정의 안의 상수는 맨 위 CTE k 한 곳에 있다. 단 창 12는 st 의 프레임(ROWS BETWEEN 12 PRECEDING ...)에도 한 번 더 적혀 있다 (창 12, 표준편차 하한 0.0001, 절단 3, 보너스 0.25·상한 5). 쓰기 파일의 사후 단언과 검증 파일은 독립 확인용으로 따로 적었다.
-- 타입: 모두 numeric (임의 정밀도). 부동소수 타입으로 바뀌는 함수·캐스트를 쓰지 않는다.
--       stddev_samp(numeric) 는 numeric 을 돌려준다 (내부 sqrt 도 numeric 경로).
-- New syntax: window functions (OVER, PARTITION BY, ROWS BETWEEN n PRECEDING AND 1 PRECEDING), LAG, ROW_NUMBER, running sums as segment and run ids (gaps and islands), MATERIALIZED CTE,
--       CASE 안에 나누기를 두어 조건이 거짓이면 평가하지 않게 하기, CREATE TEMP VIEW.
-- 명령줄(psql)이 기준이다. \ir 은 psql 메타 명령이라 VS Code 에서는 통합 터미널에서 실행한다.
-- =====================================================================

DO $$ BEGIN
    IF to_regclass('pg_temp.attn_calc') IS NOT NULL THEN DROP VIEW pg_temp.attn_calc; END IF;
END $$;

CREATE TEMP VIEW attn_calc AS
WITH k AS (
    -- 상수 5개 (표본에서 정하지 않은 고정값. 바꾸면 erd D33 도 바꾸고 전 구간을 다시 계산한다)
    SELECT 12              AS win_months,    -- 창 달 수 (F2·F3)
           0.0001::numeric AS sd_floor,      -- 표준편차 하한 (F5)
           3::numeric      AS z_cap,         -- 절단 (F6)
           0.25::numeric   AS bonus_step,    -- 연속 보너스 한 단계 (F8)
           5               AS streak_cap     -- 보너스 상한이 되는 연속 달 수 (F8)
),
r AS MATERIALIZED (
    -- Return rows plus month. Sorted once per asset; brk = 1 when the previous row is not exactly one month earlier (F1).
    SELECT ar.asset_id, ar.month_block_id, mb.month_start,
           abs(ar.local_return)           AS x,
           sign(ar.local_return)::integer AS sg,
           CASE WHEN lag(mb.month_start) OVER (PARTITION BY ar.asset_id ORDER BY mb.month_start)
                     = (mb.month_start - INTERVAL '1 month')::date
                THEN 0 ELSE 1 END         AS brk
    FROM asset_month_return ar
    JOIN month_block mb ON mb.id = ar.month_block_id
),
seg AS MATERIALIZED (
    -- Segment id = running sum of brk. Within a segment the months are consecutive, so a row count is a month count.
    SELECT r.*,
           sum(r.brk) OVER (PARTITION BY r.asset_id ORDER BY r.month_start) AS seg_id
    FROM r
),
chg AS MATERIALIZED (
    -- sg_chg = 1 where the direction differs from the previous row of the segment (zero counts as a direction) (F7).
    SELECT s.*,
           CASE WHEN s.sg = lag(s.sg) OVER (PARTITION BY s.asset_id, s.seg_id ORDER BY s.month_start) THEN 0 ELSE 1 END AS sg_chg
    FROM seg s
),
run AS MATERIALIZED (
    -- Run id = running sum of sg_chg: rows with the same id form one run.
    SELECT c.*,
           sum(c.sg_chg) OVER (PARTITION BY c.asset_id, c.seg_id ORDER BY c.month_start) AS run_id
    FROM chg c
),
st AS (
    -- F2/F4 window: up to 12 preceding rows of the segment (previous month included, this month excluded); 12 rows = 12 months. The 12 in the frame must equal k.win_months (a frame cannot use a column; pinned by AttentionRulesSqlParityTest).
    -- F7 streak: position in the run times the direction; 0 for a zero return.
    SELECT q.asset_id, q.month_block_id, q.month_start, q.x, q.sg,
           count(q.x)       OVER pw AS window_months,
           avg(q.x)         OVER pw AS mu,
           stddev_samp(q.x) OVER pw AS sd_raw,
           (CASE WHEN q.sg = 0 THEN 0
                 ELSE q.sg * row_number() OVER (PARTITION BY q.asset_id, q.seg_id, q.run_id ORDER BY q.month_start)
            END)::integer AS streak
    FROM run q
    WINDOW pw AS (PARTITION BY q.asset_id, q.seg_id ORDER BY q.month_start
                  ROWS BETWEEN 12 PRECEDING AND 1 PRECEDING)
),
zr AS (
    -- F3·F5: 창이 꽉 찼고 표준편차가 하한 이상일 때만 나눈다. 나누기는 CASE 안에 있어 조건이 거짓이면 평가되지 않는다 (0 으로 나누기 오류 방지).
    SELECT s.*,
           CASE WHEN s.window_months = k.win_months AND s.sd_raw >= k.sd_floor
                THEN (s.x - s.mu) / s.sd_raw
           END AS z_raw
    FROM st s CROSS JOIN k
),
zc AS (
    -- F6: ±3 으로 자른 뒤 소수 8자리로 한 번 반올림 (저장하는 z)
    SELECT z.*,
           CASE WHEN z.z_raw IS NOT NULL   -- GREATEST·LEAST 는 NULL 을 무시하므로 NULL 인 z 는 여기서 걸러낸다
                THEN ROUND(GREATEST(-k.z_cap, LEAST(k.z_cap, z.z_raw)), 8)
           END AS attention_z
    FROM zr z CROSS JOIN k
)
-- F8: 보너스는 0.25 의 배수이고 z 는 이미 8자리이므로 정확한 덧셈이다 (F9)
SELECT c.asset_id, c.month_block_id, c.month_start, c.window_months::smallint AS window_months,
       c.sd_raw, c.z_raw, c.attention_z, c.streak::smallint AS streak,
       c.attention_z + CASE WHEN c.streak = 0 THEN 0
                            ELSE k.bonus_step * (LEAST(abs(c.streak), k.streak_cap) - 1)
                       END AS attention_index
FROM zc c CROSS JOIN k;
