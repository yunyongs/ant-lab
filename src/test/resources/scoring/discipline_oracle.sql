-- Independent recomputation of the trading-discipline amounts of one finished season (test oracle, read-only).
-- Written from the definition alone, with window functions over the stored rows; it shares no code with the Java calculator.
-- Parameter :seasonId. One row: five won amounts and two change counts (orders and changes of turns 1..T-1 only).
WITH s AS (
    SELECT id, initial_cash, current_turn_no AS last_turn FROM season WHERE id = :seasonId
), team AS (
    -- team value of each turn next to the value before it (turn 0 = initial cash)
    SELECT t.turn_no, t.total_value,
           COALESCE(lag(t.total_value) OVER (ORDER BY t.turn_no), s.initial_cash) AS before_value
    FROM turn t JOIN s ON s.id = t.season_id
), turn_forward AS (
    -- forward target at the start (first change by id, its "before") and at the end (last change by id, its "after") of a turn
    SELECT c.turn_no,
           (array_agg(c.before_forward ORDER BY c.id ASC))[1] AS first_before,
           (array_agg(c.after_forward ORDER BY c.id DESC))[1] AS last_after,
           count(*) AS change_rows
    FROM formation_change c JOIN s ON s.id = c.season_id
    WHERE c.turn_no BETWEEN 1 AND s.last_turn - 1
    GROUP BY c.turn_no
), panic_turn AS (
    SELECT f.turn_no, f.change_rows
    FROM turn_forward f JOIN team ON team.turn_no = f.turn_no
    WHERE team.before_value > 0
      AND 100 * (team.total_value - team.before_value) <= -8 * team.before_value
      AND f.last_after < f.first_before
), judged AS (
    -- one row per filled order with its asset's price now and one turn before
    SELECT o.id, o.side, o.formation_change_id, sum(e.fill_price * e.quantity) AS notional,
           p1.price AS price_now, p0.price AS price_before
    FROM orders o
    JOIN s ON s.id = o.season_id
    JOIN execution e ON e.order_id = o.id
    JOIN turn_price p1 ON p1.season_id = o.season_id AND p1.asset_id = o.asset_id AND p1.turn_no = o.turn_no
    JOIN turn_price p0 ON p0.season_id = o.season_id AND p0.asset_id = o.asset_id AND p0.turn_no = o.turn_no - 1
    WHERE o.turn_no BETWEEN 1 AND s.last_turn - 1
    GROUP BY o.id, o.side, o.formation_change_id, p1.price, p0.price
)
SELECT COALESCE(sum(j.notional) FILTER (WHERE j.side = 'SELL'), 0) AS sell_notional,
       COALESCE(sum(j.notional) FILTER (WHERE j.side = 'SELL' AND (
               100 * (j.price_now - j.price_before) <= -8 * j.price_before
               OR EXISTS (SELECT 1 FROM formation_change c JOIN panic_turn p ON p.turn_no = c.turn_no
                          WHERE c.id = j.formation_change_id))), 0) AS panic_sell_notional,
       COALESCE(sum(j.notional) FILTER (WHERE j.side = 'BUY'), 0) AS buy_notional,
       COALESCE(sum(j.notional) FILTER (WHERE j.side = 'BUY'
               AND 100 * (j.price_now - j.price_before) >= 8 * j.price_before), 0) AS chase_buy_notional,
       COALESCE(sum(j.notional), 0) AS trade_notional,
       (SELECT count(*) FROM formation_change c JOIN s ON s.id = c.season_id
        WHERE c.turn_no BETWEEN 1 AND s.last_turn - 1) AS formation_changes,
       (SELECT COALESCE(sum(change_rows), 0) FROM panic_turn) AS panic_formation_changes
FROM judged j
