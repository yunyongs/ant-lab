package antlab.api.report;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Filled orders written straight into the tables with a chosen source and fee, for the behaviour tests. */
final class BehaviourFixtures {

    private BehaviourFixtures() {
    }

    /**
     * One filled order with one execution at {@code fillPrice}. A FEED or ALERT order needs a feed row of the season; the first
     * stored row is used, or a NUDGE row is inserted when the season has none.
     */
    static long fill(JdbcTemplate jdbc, long season, long assetId, int turn, String side, String source, int quantity,
            long fillPrice, long fee) {
        Long feed = null;
        if (source.equals("FEED") || source.equals("ALERT")) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM turn_feed WHERE season_id = ? ORDER BY id LIMIT 1", Long.class, season);
            feed = ids.isEmpty() ? jdbc.queryForObject("INSERT INTO turn_feed (season_id, turn_no, seq, kind, template_id, body) "
                    + "VALUES (?, 1, 99, 'NUDGE', 'test', 'test') RETURNING id", Long.class, season) : ids.get(0);
        }
        Long order = jdbc.queryForObject("INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source, "
                + "turn_feed_id) VALUES (?, ?, ?, ?, ?, 'FILLED', ?, ?) RETURNING id", Long.class, season, assetId, turn, side,
                quantity, source, feed);
        jdbc.update("INSERT INTO execution (order_id, fill_price, quantity, fee) VALUES (?, ?, ?, ?)", order, fillPrice, quantity,
                fee);
        return order;
    }
}
