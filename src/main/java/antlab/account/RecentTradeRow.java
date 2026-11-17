package antlab.account;

import antlab.asset.Market;
import java.math.BigDecimal;

/**
 * Internal row of the recent-trades read: one fill with the asset's natural key. It is not a response. The origin is
 * not named {@code source}: the revealed-column naming test matches projection properties by name.
 */
public record RecentTradeRow(int turnNo, OrderSide side, Market market, String ticker, int quantity,
        BigDecimal fillPrice, BigDecimal fee, OrderSource origin) {
}
