-- 가짜 데이터 — 실제 표본 아님. 경로 엔진 시험 전용 합성 고정본이다(달만 있고 수익률·가격은 없다).
-- 사건 편집이 끝난 달(events_reviewed = TRUE)이다: 턴 피드가 편집 안 된 달을 거절한다. 가짜 달의 표지 = 역사 값이 모두 비고 편집 완료.
-- 모양: 범위 밖 1달(2006-12) + 2007-01–2012-04 + 2023-01–2026-01. 시험 트랜잭션이 롤백한다.
INSERT INTO month_block (month_start, events_reviewed)
SELECT d::date, TRUE
FROM generate_series(DATE '2006-12-01', DATE '2006-12-01', INTERVAL '1 month') AS d
UNION ALL
SELECT d::date, TRUE
FROM generate_series(DATE '2007-01-01', DATE '2012-04-01', INTERVAL '1 month') AS d
UNION ALL
SELECT d::date, TRUE
FROM generate_series(DATE '2023-01-01', DATE '2026-01-01', INTERVAL '1 month') AS d;
