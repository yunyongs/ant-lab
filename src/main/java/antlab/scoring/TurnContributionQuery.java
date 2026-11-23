package antlab.scoring;

import antlab.asset.Position;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read-only sources of the turn contribution: two native statements whose count does not depend on the number of
 * holdings. No rounding, no writes, no locks.
 * <ul>
 *   <li>{@link #lines} — quantity held through the move into turn k = buys − sells of the fills with {@code turn_no < k}
 *       (the same rule as the report's {@code sr_hold}), with the turn prices of k − 1 and k and the declared spans
 *       open at k ({@code start_turn < k <= end_turn}, null end = open). Non-zero quantities only.</li>
 *   <li>{@link #tradingCost} — fees and taxes of the fills of the orders placed at turn k − 1.</li>
 * </ul>
 */
@Component
public class TurnContributionQuery {

    private static final String LINES = """
            WITH q AS (
                SELECT o.asset_id, sum(CASE WHEN o.side = 'BUY' THEN e.quantity ELSE -e.quantity END) AS quantity
                FROM orders o
                JOIN execution e ON e.order_id = o.id
                WHERE o.season_id = :seasonId AND o.turn_no < :turnNo
                GROUP BY o.asset_id
            )
            SELECT q.asset_id AS asset_id, q.quantity AS quantity, p0.price AS previous_price, p1.price AS price,
                   l.spans AS spans, l.declared_position AS declared_position
            FROM q
            LEFT JOIN turn_price p0 ON p0.season_id = :seasonId AND p0.asset_id = q.asset_id AND p0.turn_no = :previousTurnNo
            LEFT JOIN turn_price p1 ON p1.season_id = :seasonId AND p1.asset_id = q.asset_id AND p1.turn_no = :turnNo
            CROSS JOIN LATERAL (
                SELECT count(*) AS spans, min(s.declared_position) AS declared_position
                FROM lineup s
                WHERE s.season_id = :seasonId AND s.asset_id = q.asset_id AND s.start_turn < :turnNo
                  AND (s.end_turn IS NULL OR s.end_turn >= :turnNo)
            ) l
            WHERE q.quantity <> 0
            ORDER BY q.asset_id""";

    private static final String TRADING_COST = """
            SELECT COALESCE(sum(e.fee + e.tax), 0)
            FROM orders o
            JOIN execution e ON e.order_id = o.id
            WHERE o.season_id = :seasonId AND o.turn_no = :previousTurnNo""";

    private final JdbcClient jdbc;

    public TurnContributionQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Holdings through the move into {@code turnNo}, ascending asset id. Prices are null when a turn price is missing. */
    public List<TurnContributionCalculator.Line> lines(long seasonId, int turnNo) {
        return jdbc.sql(LINES).param("seasonId", seasonId).param("turnNo", turnNo).param("previousTurnNo", turnNo - 1)
                .query((rs, n) -> new TurnContributionCalculator.Line(rs.getLong("asset_id"), rs.getLong("quantity"),
                        rs.getBigDecimal("previous_price"), rs.getBigDecimal("price"), rs.getInt("spans"),
                        position(rs.getString("declared_position"))))
                .list();
    }

    /** Fees and taxes of the orders placed at turn {@code turnNo − 1}, in won. */
    public BigDecimal tradingCost(long seasonId, int turnNo) {
        return jdbc.sql(TRADING_COST).param("seasonId", seasonId).param("previousTurnNo", turnNo - 1)
                .query(BigDecimal.class).single();
    }

    private static Position position(String name) {
        return name == null ? null : Position.valueOf(name);
    }
}
