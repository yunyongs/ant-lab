-- 가짜 데이터 — 실제 시세 아님. 턴 전환 시험 전용 합성 수익률·환율 변화 고정본이다.
-- replay_months.sql 이 만든 모든 달(역사 값이 비어 있는 가짜 블록)에 history_pool.sql 의 가짜 종목 4개의 월 수익률과 달러 환율 변화를 넣는다.
-- 값은 달·종목 번호에서 계산한 결정적 합성값이라 어느 경로를 뽑아도 계수가 빠지지 않는다.
-- 소수 8자리의 끝자리가 0이 아니도록 일부러 어긋난 상수를 더해, 매달 반올림하는 식과 마지막에 한 번 반올림하는 식이 다른 칸이 생기게 한다.
INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
SELECT a.id, mb.id,
       ROUND(((extract(month FROM mb.month_start)::int * 7 + extract(year FROM mb.month_start)::int * 3
               + (a.id % 5) * 4) % 19 - 9) / 100.0 + 0.00123457, 8),
       'ACTUAL'
FROM asset a
CROSS JOIN month_block mb
WHERE a.name LIKE '가짜%'
  AND mb.month_start BETWEEN DATE '2006-12-01' AND DATE '2026-01-01'
  AND mb.rate_dir IS NULL AND mb.events_reviewed = FALSE;

INSERT INTO fx_month (month_block_id, currency, fx_change)
SELECT mb.id, 'USD',
       ROUND(((extract(year FROM mb.month_start)::int * 5 + extract(month FROM mb.month_start)::int * 3) % 13 - 6) / 200.0
             + 0.00031579, 8)
FROM month_block mb
WHERE mb.month_start BETWEEN DATE '2006-12-01' AND DATE '2026-01-01'
  AND mb.rate_dir IS NULL AND mb.events_reviewed = FALSE;
