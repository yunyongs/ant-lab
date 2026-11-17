package antlab.projection;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads what a projection starts from, from immutable rows only (orders, executions, the cash ledger, turn prices) or
 * from the current holdings. Read-only, one snapshot per call (two statements must see the same commit). A missing
 * price or ledger row is data corruption and stops the call; nothing here rounds or writes.
 */
@Component
public class ProjectionInputQuery {

    /** Current holdings priced at one turn; a missing price stays null so the call refuses instead of dropping the asset. */
    private static final String CURRENT_HOLDINGS = """
            SELECT a.market AS market, a.ticker AS ticker, h.quantity AS quantity, tp.price AS price
            FROM holding h
            JOIN asset a ON a.id = h.asset_id
            LEFT JOIN turn_price tp ON tp.season_id = h.season_id AND tp.asset_id = h.asset_id AND tp.turn_no = :turnNo
            WHERE h.season_id = :seasonId AND h.quantity > 0
            ORDER BY a.id""";

    private static final String CURRENT_CASH = "SELECT balance FROM cash_account WHERE season_id = :seasonId";

    /** Net filled quantity of the turn-0 orders per asset (BUY adds, SELL subtracts), priced at turn 0. */
    private static final String START_HOLDINGS = """
            SELECT a.market AS market, a.ticker AS ticker,
                   sum(CASE o.side WHEN 'BUY' THEN e.quantity ELSE -e.quantity END) AS quantity, tp.price AS price
            FROM orders o
            JOIN execution e ON e.order_id = o.id
            JOIN asset a ON a.id = o.asset_id
            LEFT JOIN turn_price tp ON tp.season_id = o.season_id AND tp.asset_id = o.asset_id AND tp.turn_no = 0
            WHERE o.season_id = :seasonId AND o.turn_no = 0
            GROUP BY a.id, a.market, a.ticker, tp.price
            HAVING sum(CASE o.side WHEN 'BUY' THEN e.quantity ELSE -e.quantity END) > 0
            ORDER BY a.id""";

    /** The balance after the last ledger row of the opening deposit and the turn-0 orders. */
    private static final String START_CASH = """
            SELECT l.balance_after
            FROM cash_ledger l
            WHERE l.season_id = :seasonId
              AND (l.type = 'DEPOSIT'
                   OR l.ref_order_id IN (SELECT o.id FROM orders o WHERE o.season_id = :seasonId AND o.turn_no = 0))
            ORDER BY l.id DESC
            LIMIT 1""";

    private final JdbcClient jdbc;

    public ProjectionInputQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The team as it is now: the holdings and the cash balance, at the prices of {@code turnNo}. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectionBasis current(long seasonId, int turnNo) {
        BigDecimal cash = jdbc.sql(CURRENT_CASH).param("seasonId", seasonId).query(BigDecimal.class).single();
        return new ProjectionBasis(turnNo, cash, holdings(CURRENT_HOLDINGS, seasonId, turnNo));
    }

    /** The team at the end of turn 0 (after the team building), valued at the turn-0 prices. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProjectionBasis start(long seasonId) {
        BigDecimal cash = jdbc.sql(START_CASH).param("seasonId", seasonId).query(BigDecimal.class).single();
        return new ProjectionBasis(0, cash, holdings(START_HOLDINGS, seasonId, 0));
    }

    private List<PricedHolding> holdings(String sql, long seasonId, int turnNo) {
        return jdbc.sql(sql).param("seasonId", seasonId).param("turnNo", turnNo).query((rs, n) -> {
            BigDecimal price = rs.getBigDecimal("price");
            if (price == null) {
                throw new IllegalStateException("projection.missing_price");
            }
            return new PricedHolding(rs.getString("market") + ":" + rs.getString("ticker"), rs.getInt("quantity"), price);
        }).list();
    }
}
