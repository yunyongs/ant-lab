package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.BaselineSimulator.FeeRates;
import antlab.scoring.BaselineSpec.Leg;
import antlab.scoring.BaselineSpec.Slot;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.TargetSchedule.Change;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed effect of a formation change. The baseline has a cash slot and one asset slot, no fees and no band, so only the
 * forced rebalance of a change trades: 1,000,000 won, the asset at 1,000, shares bought with the slot's share of the value.
 */
class ChangeEffectCalculatorTest {

    private static final AssetKey ASSET = new AssetKey("KR", "AAA");
    private static final BigDecimal CASH = new BigDecimal("1000000");
    private static final FeeRates NO_FEES = new FeeRates(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    private static final BaselineSpec SPEC = new BaselineSpec(BaselineKey.FORMATION_FOLLOW,
            List.of(new Slot("CASH", List.of()), new Slot("ASSET", List.of(new Leg(ASSET, 100)))), null);

    private static Map<AssetKey, List<BigDecimal>> prices(long... values) {
        return Map.of(ASSET, java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList());
    }

    private static List<ChangeEffectCalculator.Effect> effects(List<Integer> start, List<Change> changes, int reportTurn,
            long... prices) {
        return ChangeEffectCalculator.calculate(SPEC, start, changes, prices(prices), CASH, NO_FEES, reportTurn);
    }

    @Test
    void oneChangeWhoseWindowReachesTheSeasonEndHasTheTacticOfTheSplitAsItsEffect() {
        // T = 4: 500 shares at 1,000, then the price rises to 1,200 at turn 2. The change at turn 1 moves everything into the asset.
        long[] p = {1_000, 1_000, 1_200, 1_200, 1_200};
        List<Integer> start = List.of(50, 50);
        List<Change> changes = List.of(new Change(1, 7, List.of(0, 100)));
        List<ChangeEffectCalculator.Effect> out = effects(start, changes, 4, p);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).turnNo()).isEqualTo(1);
        assertThat(out.get(0).windowTurns()).as("min(6, T - k) = 3").isEqualTo(3);
        assertThat(out.get(0).effectWon().toPlainString()).as("1,200,000 - 1,100,000").isEqualTo("100000");
        assertThat(out.get(0).effectReturn().toPlainString()).isEqualTo("0.10000000");
        // the same as the split's tactic: following baseline minus the fixed baseline, both simulated directly
        BigDecimal follow = BaselineSimulator.simulate(SPEC, TargetSchedule.fromChanges(start,
                List.of(new TargetSchedule.Change(1, 7, List.of(0, 100)))), prices(p), CASH, NO_FEES).finalValue();
        BigDecimal fixed = BaselineSimulator.simulate(SPEC, TargetSchedule.constant(50, 50), prices(p), CASH, NO_FEES).finalValue();
        assertThat(out.get(0).effectWon()).isEqualByComparingTo(follow.subtract(fixed));
    }

    @Test
    void theSecondChangeIsComparedWithTheScheduleThatHoldsOnlyTheFirst() {
        // T = 10, start all cash. Change 1 at turn 1 -> 50 % asset; change 2 at turn 3 -> 100 % asset.
        // the price moves again after the last measuring turn (9), so a value read at the season end would differ
        long[] p = {1_000, 1_000, 1_000, 2_000, 2_000, 3_000, 3_000, 3_000, 3_000, 3_000, 4_000};
        List<Change> changes = List.of(new Change(3, 2, List.of(0, 100)), new Change(1, 1, List.of(50, 50)));
        List<ChangeEffectCalculator.Effect> out = effects(List.of(100, 0), changes, 10, p);
        assertThat(out).extracting(ChangeEffectCalculator.Effect::turnNo).as("in id order").containsExactly(1, 3);
        assertThat(out).extracting(ChangeEffectCalculator.Effect::windowTurns).containsExactly(6, 6);
        // change 1 at turn 7: with 500 shares 500,000 + 500 x 3,000 = 2,000,000 against 1,000,000 held as cash
        assertThat(out.get(0).effectWon().toPlainString()).isEqualTo("1000000");
        // change 2 at turn 9: 750 shares x 3,000 = 2,250,000 against the first change alone, 2,000,000 (not against all cash)
        assertThat(out.get(1).effectWon().toPlainString()).isEqualTo("250000");
        assertThat(out.get(1).effectReturn().toPlainString()).isEqualTo("0.25000000");
    }

    @Test
    void twoChangesInOneTurnAreTwoRowsInIdOrder() {
        // T = 5, start all cash; both changes at turn 1: first to 25 % asset, then to 100 %
        long[] p = {1_000, 1_000, 1_000, 2_000, 2_000, 2_000};
        List<Change> changes = List.of(new Change(1, 6, List.of(0, 100)), new Change(1, 5, List.of(75, 25)));
        List<ChangeEffectCalculator.Effect> out = effects(List.of(100, 0), changes, 5, p);
        assertThat(out).extracting(ChangeEffectCalculator.Effect::turnNo).containsExactly(1, 1);
        assertThat(out).extracting(ChangeEffectCalculator.Effect::windowTurns).containsExactly(4, 4);
        assertThat(out.get(0).effectWon().toPlainString()).as("250 shares: 750,000 + 500,000 - 1,000,000").isEqualTo("250000");
        assertThat(out.get(1).effectWon().toPlainString()).as("1,000 shares 2,000,000 - 1,250,000").isEqualTo("750000");
    }

    @Test
    void noChangeGivesAnEmptyListAndChangesAtTurnZeroOrTheLastTurnAreNotListed() {
        long[] p = {1_000, 1_000, 1_100, 1_100};
        assertThat(effects(List.of(50, 50), List.of(), 3, p)).isEmpty();
        assertThat(effects(List.of(50, 50), List.of(new Change(0, 1, List.of(0, 100)), new Change(3, 2, List.of(0, 100))), 3, p))
                .as("turn 0 and turn T are outside the judged turns").isEmpty();
    }

    @Test
    void theEffectIsNetOfTheSimulatedFees() {
        // start all cash, change at turn 1 to all asset with a 1 % rate: floor(1,000,000 / 1,010) = 990 shares, fee 9,900, cash 100
        FeeRates onePercent = new FeeRates(new BigDecimal("0.01000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        List<ChangeEffectCalculator.Effect> out = ChangeEffectCalculator.calculate(SPEC, List.of(100, 0),
                List.of(new Change(1, 1, List.of(0, 100))), prices(1_000, 1_000, 1_000, 2_000, 2_000), CASH, onePercent, 4);
        assertThat(out.get(0).effectWon().toPlainString()).as("100 + 990 x 2,000 - 1,000,000").isEqualTo("980100");
        assertThat(out.get(0).effectReturn().toPlainString()).isEqualTo("0.98010000");
    }
}
