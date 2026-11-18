package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import antlab.scoring.DefenderGoalCalculator.HeldTurn;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hand-computed defender goals: the exact market-down test and the declared-position filter. */
class DefenderGoalCalculatorTest {

    private static final BigDecimal CASH = new BigDecimal("30000000");

    private static List<BigDecimal> series(long... values) {
        return java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }

    private static HeldTurn held(int turn, long asset, long quantity, long previous, long price, Position declared) {
        return new HeldTurn(turn, asset, quantity, BigDecimal.valueOf(previous), BigDecimal.valueOf(price), declared);
    }

    @Test
    void aRisingDefenderInADownTurnIsAGoalAndEveryOtherCaseIsNot() {
        // turn 2 is the only down turn: the pair falls 10,000 -> 9,000 and 20,000 -> 20,000
        List<BigDecimal> kr = series(10_000, 10_000, 9_000, 9_000);
        List<BigDecimal> us = series(20_000, 20_000, 20_000, 20_000);
        List<HeldTurn> holdings = List.of(
                held(2, 1, 5, 100, 130, Position.DEFENDER),
                held(2, 2, 3, 100, 120, Position.FORWARD_TARGET),
                held(2, 3, 4, 100, 90, Position.DEFENDER),
                held(1, 1, 5, 100, 130, Position.DEFENDER),
                held(3, 1, 5, 100, 130, Position.DEFENDER));
        DefenderGoalCalculator.Result r = DefenderGoalCalculator.calculate(3, CASH, kr, us, holdings);
        assertThat(r.downMarketTurns()).isEqualTo(1);
        assertThat(r.goals()).as("only the rising defender of the down turn").isEqualTo(1);
        assertThat(r.goalWon().toPlainString()).as("5 x (130 - 100)").isEqualTo("150");
        assertThat(r.goalReturn().toPlainString()).isEqualTo("0.00000500");
    }

    @Test
    void aGoalkeeperCountsAndAFlatPriceIsNoGoal() {
        List<BigDecimal> kr = series(10_000, 9_000);
        List<BigDecimal> us = series(20_000, 20_000);
        DefenderGoalCalculator.Result r = DefenderGoalCalculator.calculate(1, CASH, kr, us, List.of(
                held(1, 1, 2, 500, 600, Position.GOALKEEPER), held(1, 2, 2, 500, 500, Position.DEFENDER)));
        assertThat(r.goals()).isEqualTo(1);
        assertThat(r.goalWon().toPlainString()).isEqualTo("200");
    }

    @Test
    void theMarketIsDownOnlyWhenTheWeightedPairFellExactly() {
        // equal bases: +1 % and -1 % cancel exactly, so the pair is flat and the turn is not a down turn
        List<BigDecimal> kr = series(10_000, 10_100);
        List<BigDecimal> us = series(10_000, 9_900);
        assertThat(DefenderGoalCalculator.calculate(1, CASH, kr, us, List.of()).downMarketTurns()).isZero();
        // a tick lower on one leg: down
        assertThat(DefenderGoalCalculator.calculate(1, CASH, series(10_000, 10_100), series(10_000, 9_899), List.of())
                .downMarketTurns()).isEqualTo(1);
        // a tick higher: up
        assertThat(DefenderGoalCalculator.calculate(1, CASH, series(10_000, 10_100), series(10_000, 9_901), List.of())
                .downMarketTurns()).isZero();
        // unequal bases: +50 % on the small leg does not offset -10 % on the large one in the weighted pair
        assertThat(DefenderGoalCalculator.calculate(1, CASH, series(1_000, 1_500), series(100_000, 90_000), List.of())
                .downMarketTurns()).as("0.5 x 1.5 + 0.5 x 0.9 = 1.2").isZero();
        assertThat(DefenderGoalCalculator.calculate(1, CASH, series(1_000, 1_500), series(100_000, 40_000), List.of())
                .downMarketTurns()).as("0.5 x 1.5 + 0.5 x 0.4 = 0.95").isEqualTo(1);
    }

    @Test
    void aFlatMarketOrAnUpMarketGivesNoGoal() {
        List<HeldTurn> rising = List.of(held(1, 1, 5, 100, 130, Position.DEFENDER));
        assertThat(DefenderGoalCalculator.calculate(1, CASH, series(10, 10), series(20, 20), rising).goals()).isZero();
        assertThat(DefenderGoalCalculator.calculate(1, CASH, series(10, 11), series(20, 20), rising).goals()).isZero();
        DefenderGoalCalculator.Result none = DefenderGoalCalculator.calculate(1, CASH, series(10, 10), series(20, 20), List.of());
        assertThat(none.goalWon().toPlainString()).isEqualTo("0");
        assertThat(none.goalReturn().toPlainString()).isEqualTo("0.00000000");
    }

    @Test
    void aSourceThatContradictsTheSeasonIsRefused() {
        List<BigDecimal> kr = series(10, 9);
        List<BigDecimal> us = series(20, 20);
        assertThatThrownBy(() -> DefenderGoalCalculator.calculate(1, CASH, kr, series(20), List.of()))
                .as("pair series too short").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DefenderGoalCalculator.calculate(1, CASH, series(10, 0), us, List.of()))
                .as("zero market price").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DefenderGoalCalculator.calculate(1, BigDecimal.ZERO, kr, us, List.of()))
                .as("no initial cash").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DefenderGoalCalculator.calculate(1, CASH, kr, us, List.of(held(2, 1, 5, 100, 130, Position.DEFENDER))))
                .as("turn outside the season").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DefenderGoalCalculator.calculate(1, CASH, kr, us, List.of(held(1, 1, 0, 100, 130, Position.DEFENDER))))
                .as("empty holding").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DefenderGoalCalculator.calculate(1, CASH, kr, us, List.of(held(1, 1, 5, 100, 130, null))))
                .as("no declared position").isInstanceOf(IllegalStateException.class);
    }
}
