package antlab.scoring;

import antlab.account.OrderSide;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.List;

/**
 * Input of {@link DisciplineCalculator}: whole-won facts of one finished season, read once. No dates, month blocks or
 * seeds; an asset is only its natural key.
 *
 * @param reportTurn  T, the last turn of the season; orders and changes of turns 1..T-1 are judged
 * @param initialCash the value of turn 0, the base of the first team return
 * @param turnValues  stored team value of turns 1..T in turn order (the canonical snapshot)
 * @param changes     formation changes of the season; those outside turns 1..T-1 are ignored
 * @param orders      one row per filled order of turns 1..T-1 (fills summed per order first); a row of turn 0 is ignored
 */
public record DisciplineFacts(int reportTurn, BigDecimal initialCash, List<TurnValue> turnValues,
        List<ChangeFact> changes, List<OrderFact> orders) {

    /** Team value at the start of a turn, before the trades of that turn. */
    public record TurnValue(int turnNo, BigDecimal totalValue) {
    }

    /** One formation change with the forward share before and after it. */
    public record ChangeFact(long id, int turnNo, int beforeForward, int afterForward) {
    }

    /**
     * One filled order. {@code price} and {@code previousPrice} are the asset's turn prices at the order's turn and one
     * turn earlier; both are null only for turn 0, which is not judged.
     */
    public record OrderFact(long orderId, int turnNo, OrderSide side, AssetKey asset, BigDecimal notional,
            Long formationChangeId, BigDecimal price, BigDecimal previousPrice) {
    }
}
