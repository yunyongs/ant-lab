package antlab.projection;

import java.math.BigDecimal;

/** One held asset at a turn: its engine key ({@code MARKET:TICKER}), the quantity and the turn price in whole won. */
public record PricedHolding(String assetKey, int quantity, BigDecimal price) {
}
