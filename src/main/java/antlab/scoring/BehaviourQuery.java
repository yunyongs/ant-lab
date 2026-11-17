package antlab.scoring;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Source of the behaviour items (noise reaction, per-source platform revenue): one read-only native statement whatever the number of
 * orders. Fills are summed per order first (the same range and join as the report's {@code po} CTE, with turn 0 included for the team
 * fee), then each order meets its asset's price at its turn and {@link BehaviourRules#WINDOW_TURNS} turns later. A missing price stays
 * null so that the calculators can refuse the season. No rounding and no write here.
 */
@Component
public class BehaviourQuery {

    private static final String ORDERS = """
            SELECT o.id AS order_id, o.turn_no AS turn_no, o.side AS side, o.asset_id AS asset_id, o.source AS source,
                   sum(e.fill_price * e.quantity) AS notional, sum(e.fee) AS fee, p1.price AS price, p2.price AS price_after
            FROM orders o
            JOIN execution e ON e.order_id = o.id
            LEFT JOIN turn_price p1 ON p1.season_id = o.season_id AND p1.asset_id = o.asset_id AND p1.turn_no = o.turn_no
            LEFT JOIN turn_price p2 ON p2.season_id = o.season_id AND p2.asset_id = o.asset_id
                                   AND p2.turn_no = o.turn_no + :window AND o.turn_no + :window <= :reportTurn
            WHERE o.season_id = :seasonId AND o.turn_no < :reportTurn
            GROUP BY o.id, o.turn_no, o.side, o.asset_id, o.source, p1.price, p2.price
            ORDER BY o.turn_no, o.id""";

    private final JdbcClient jdbc;

    public BehaviourQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Filled orders of turns 0..T-1 in turn then id order. */
    public List<BehaviourOrder> orders(long seasonId, int reportTurn) {
        return jdbc.sql(ORDERS).param("seasonId", seasonId).param("reportTurn", reportTurn)
                .param("window", BehaviourRules.WINDOW_TURNS)
                .query((rs, n) -> new BehaviourOrder(rs.getLong("order_id"), rs.getInt("turn_no"),
                        OrderSide.valueOf(rs.getString("side")), rs.getLong("asset_id"),
                        OrderSource.valueOf(rs.getString("source")), rs.getBigDecimal("notional"), rs.getBigDecimal("fee"),
                        rs.getBigDecimal("price"), rs.getBigDecimal("price_after")))
                .list();
    }
}
