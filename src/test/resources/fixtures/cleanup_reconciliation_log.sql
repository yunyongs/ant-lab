-- 대사 기록 정리 — cleanup_season_start.sql 보다 먼저 돌려야 한다(대사 기록이 시즌을 참조한다).
-- 대상: 고정 회원(player@example.invalid)의 시즌의 reconciliation_log.
DELETE FROM reconciliation_log WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
