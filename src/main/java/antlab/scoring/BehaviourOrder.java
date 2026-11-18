package antlab.scoring;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import java.math.BigDecimal;

/**
 * One filled order of a finished season with its fills summed (MVP-19). Whole-won sums, no rounding. The calculators judge turns
 * 1..T-1; turn 0 (team building) is carried only so that the platform's fee total can be split into team and per-source parts.
 *
 * @param price      the asset's price at the order's turn
 * @param priceAfter the asset's price six turns later, null when that turn is after the last one
 */
public record BehaviourOrder(long orderId, int turnNo, OrderSide side, long assetId, OrderSource source, BigDecimal notional,
        BigDecimal fee, BigDecimal price, BigDecimal priceAfter) {
}
