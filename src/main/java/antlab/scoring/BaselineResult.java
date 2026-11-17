package antlab.scoring;

import antlab.account.OrderSide;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Outcome of one baseline over a season. {@code states} has one entry per turn 0..T: the holdings carried into that turn, valued at that
 * turn's price, before any trade of that turn (turn 0 is after the opening purchase). Amounts are whole won.
 *
 * @param rebalanceCount    turns with a rebalance that traded, or was forced by a target switch
 * @param bandNoFillCount   turns where the band was broken but integer shares allowed no trade (a slot that cannot buy one share);
 *                          not counted in {@code rebalanceCount}, so such a slot cannot inflate the count turn after turn
 * @param forcedChangeCount turns with a rebalance forced by a target switch (subset of {@code rebalanceCount})
 * @param forcedChangeFee   fees paid in forced rebalances
 * @param totalFee          fees of every fill, the opening purchase included
 */
public record BaselineResult(
        BaselineKey key,
        List<TurnState> states,
        List<Fill> fills,
        int rebalanceCount,
        int bandNoFillCount,
        int forcedChangeCount,
        BigDecimal forcedChangeFee,
        BigDecimal totalFee) {

    public record TurnState(int turnNo, BigDecimal cash, Map<AssetKey, Integer> quantities, BigDecimal value) {
        public TurnState {
            TreeMap<AssetKey, Integer> sorted = new TreeMap<>(BaselineSimulator.ASSET_ORDER);
            sorted.putAll(quantities);
            quantities = Collections.unmodifiableMap(sorted);
        }
    }

    public record Fill(int turnNo, AssetKey asset, OrderSide side, int quantity, BigDecimal price, BigDecimal fee,
            boolean forced) {
    }

    public BaselineResult {
        states = List.copyOf(states);
        fills = List.copyOf(fills);
    }

    public List<BigDecimal> turnValues() {
        return states.stream().map(TurnState::value).toList();
    }

    public BigDecimal finalValue() {
        return states.get(states.size() - 1).value();
    }

    /** Holdings and cash right after the opening purchase. */
    public TurnState openingState() {
        return states.get(0);
    }

    public TurnState finalState() {
        return states.get(states.size() - 1);
    }
}
