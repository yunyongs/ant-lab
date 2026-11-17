-- Verification S6 (erd 8): orders whose origin does not fit their feed reference. The four clauses are copied from the SOURCE_FEED
-- check of db/25_trade_formation_check.sql with the season as the one parameter. Zero rows are expected.
SELECT count(*) FROM orders o LEFT JOIN turn_feed f ON f.id = o.turn_feed_id
WHERE o.season_id = ?
  AND ((o.source IN ('FEED', 'ALERT')) <> (o.turn_feed_id IS NOT NULL)
       OR (o.turn_feed_id IS NOT NULL AND (f.id IS NULL OR f.season_id <> o.season_id OR (o.source = 'ALERT') <> (f.kind = 'NUDGE') OR f.turn_no > o.turn_no)))
