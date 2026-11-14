-- Fake data cleanup: committing season and order tests delete only the rows they made (children before parents).
-- Targets: seasons of the default test member (player@example.invalid; the member row stays for the context) and the fake rows of history_pool.sql and replay_months.sql.
DELETE FROM reconciliation_log WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
DELETE FROM turn        WHERE season_id IN (SELECT s.id FROM season s JOIN member m ON m.id = s.member_id WHERE lower(m.email) = 'player@example.invalid');
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
DELETE FROM asset_month_attention WHERE asset_id IN (SELECT id FROM asset WHERE name LIKE '가짜%');
DELETE FROM asset_month_return WHERE asset_id IN (SELECT id FROM asset WHERE name LIKE '가짜%');
DELETE FROM asset_position WHERE asset_id IN (SELECT id FROM asset WHERE name LIKE '가짜%');
DELETE FROM month_event WHERE event_key = 'FAKE_EVENT_01';
DELETE FROM fx_month WHERE month_block_id IN (SELECT id FROM month_block WHERE month_start = DATE '2001-02-01')
   OR month_block_id IN (SELECT id FROM month_block WHERE month_start BETWEEN DATE '2006-12-01' AND DATE '2026-01-01' AND rate_dir IS NULL AND events_reviewed = FALSE);
DELETE FROM asset WHERE name LIKE '가짜%';
DELETE FROM asset_group WHERE code IN ('FAKE_SEMI', 'FAKE_INDEX');
DELETE FROM month_block WHERE month_start IN (DATE '2001-02-01', DATE '2001-03-01')
   OR (month_start BETWEEN DATE '2006-12-01' AND DATE '2026-01-01' AND rate_dir IS NULL AND events_reviewed = FALSE);
