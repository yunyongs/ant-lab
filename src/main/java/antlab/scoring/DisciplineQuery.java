package antlab.scoring;

import antlab.account.OrderSide;
import antlab.scoring.DisciplineFacts.OrderFact;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Source of the trading-discipline item: one read-only native statement whatever the number of orders or assets.
 * Fills are summed per order first (the same range and join as the report's {@code po} CTE: turns 1..T-1, orders with at
 * least one fill), then each order meets the traded asset's turn prices at its turn and one turn earlier.
 * A missing price stays null (left join) so that the calculator refuses the season instead of dropping the order.
 * No rounding and no write here.
 */
@Component
public class DisciplineQuery {

    private static final String ORDERS = """
            SELECT o.id AS order_id, o.turn_no AS turn_no, o.side AS side, a.market AS market, a.ticker AS ticker,
                   sum(e.fill_price * e.quantity) AS notional, o.formation_change_id AS formation_change_id,
                   p1.price AS price, p0.price AS previous_price
            FROM orders o
            JOIN execution e ON e.order_id = o.id
            JOIN asset a ON a.id = o.asset_id
            LEFT JOIN turn_price p1 ON p1.season_id = o.season_id AND p1.asset_id = o.asset_id AND p1.turn_no = o.turn_no
            LEFT JOIN turn_price p0 ON p0.season_id = o.season_id AND p0.asset_id = o.asset_id AND p0.turn_no = o.turn_no - 1
            WHERE o.season_id = :seasonId AND o.turn_no >= 1 AND o.turn_no < :reportTurn
            GROUP BY o.id, o.turn_no, o.side, a.market, a.ticker, o.formation_change_id, p1.price, p0.price
            ORDER BY o.turn_no, o.id""";

    private final JdbcClient jdbc;

    public DisciplineQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Filled orders of turns 1..T-1 with their asset's prices at the turn and one turn before, in turn then id order. */
    public List<OrderFact> orders(long seasonId, int reportTurn) {
        return jdbc.sql(ORDERS).param("seasonId", seasonId).param("reportTurn", reportTurn)
                .query((rs, n) -> new OrderFact(rs.getLong("order_id"), rs.getInt("turn_no"),
                        OrderSide.valueOf(rs.getString("side")),
                        new AssetKey(rs.getString("market"), rs.getString("ticker")), rs.getBigDecimal("notional"),
                        rs.getObject("formation_change_id", Long.class), rs.getBigDecimal("price"),
                        rs.getBigDecimal("previous_price")))
                .list();
    }
}
