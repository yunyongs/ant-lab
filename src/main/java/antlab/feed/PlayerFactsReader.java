package antlab.feed;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link PlayerFacts} of the turn being applied. One statement: executions per trading interval for the intervals before the
 * turn ({@code orders.turn_no < t}); interval k is the orders placed while turn k was current. The cash share comes from the turn's own
 * snapshot, which the caller already holds.
 */
@Component
public class PlayerFactsReader {

    private static final String EXECUTIONS = """
            SELECT o.turn_no AS turn_no, count(*) AS executions
            FROM orders o
            JOIN execution e ON e.order_id = o.id
            WHERE o.season_id = :seasonId AND o.turn_no < :turnNo
            GROUP BY o.turn_no""";

    private final JdbcClient jdbc;

    public PlayerFactsReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PlayerFacts read(long seasonId, int turnNo, BigDecimal cashBalance, BigDecimal totalValue) {
        Map<Integer, Integer> perInterval = new HashMap<>();
        jdbc.sql(EXECUTIONS).param("seasonId", seasonId).param("turnNo", turnNo)
                .query(rs -> {
                    perInterval.put(rs.getInt("turn_no"), rs.getInt("executions"));
                });
        int tradeCount = perInterval.getOrDefault(turnNo - 1, 0);
        int idleTurns = 0;
        for (int interval = turnNo - 1; interval >= 0 && perInterval.getOrDefault(interval, 0) == 0; interval--) {
            idleTurns++;
        }
        BigDecimal cashWeight = totalValue.signum() == 0 ? null : MoneyMath.ratio(cashBalance, totalValue);
        return new PlayerFacts(turnNo, cashWeight, tradeCount, idleTurns);
    }
}
