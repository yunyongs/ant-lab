-- 가짜 데이터 정리 — 커밋하는 시즌·주문 시험이 자기가 만든 행만 지운다(자식 → 부모 순서).
-- 대상: 고정 회원(player@example.invalid)의 시즌, history_pool.sql·replay_months.sql 의 가짜 행.
DELETE FROM execution   WHERE order_id IN (SELECT id FROM orders WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid'));
DELETE FROM cash_ledger WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM holding     WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM lineup      WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM orders      WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM formation_change WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM turn_price  WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM season_path WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM cash_account WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM season WHERE member_id IN (SELECT id FROM member WHERE lower(email) = 'player@example.invalid');
DELETE FROM member WHERE lower(email) = 'player@example.invalid';
DELETE FROM asset_month_attention WHERE asset_id IN (SELECT id FROM asset WHERE name LIKE '가짜%');
DELETE FROM asset_month_return WHERE asset_id IN (SELECT id FROM asset WHERE name LIKE '가짜%');
DELETE FROM asset_position WHERE asset_id IN (SELECT id FROM asset WHERE name LIKE '가짜%');
DELETE FROM month_event WHERE event_key = 'FAKE_EVENT_01';
DELETE FROM fx_month WHERE month_block_id IN (SELECT id FROM month_block WHERE month_start = DATE '2001-02-01');
DELETE FROM asset WHERE name LIKE '가짜%';
DELETE FROM asset_group WHERE code IN ('FAKE_SEMI', 'FAKE_INDEX');
DELETE FROM month_block WHERE month_start IN (DATE '2001-02-01', DATE '2001-03-01')
   OR (month_start BETWEEN DATE '2006-12-01' AND DATE '2026-01-01' AND rate_dir IS NULL AND events_reviewed = FALSE);
