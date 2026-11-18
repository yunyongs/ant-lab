package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Hand-computed attention following: top-attention buys against all other buys over the next 6 turns. */
class AttentionFollowingCalculatorTest {

    private static final long TOP = 1;
    private static final long PLAIN = 2;

    private static List<TurnSignals> signals(int reportTurn) {
        List<TurnSignals> out = new ArrayList<>();
        for (int turn = 1; turn < reportTurn; turn++) {
            out.add(new TurnSignals(turn, Set.of(), Set.of(), Set.of(TOP)));
        }
        return out;
    }

    private static BehaviourOrder buy(long id, int turn, long asset, long notional, String price, String after) {
        return new BehaviourOrder(id, turn, OrderSide.BUY, asset, OrderSource.DIRECT, BigDecimal.valueOf(notional),
                BigDecimal.ONE, new BigDecimal(price), after == null ? null : new BigDecimal(after));
    }

    @Test
    void theGroupReturnIsTheMeanOfTheBuysWeightedByAmountAndTheOtherGroupIsSeparate() {
        List<BehaviourOrder> orders = List.of(buy(1, 1, TOP, 100, "1000", "1100"), buy(2, 2, TOP, 300, "1000", "900"),
                buy(3, 3, PLAIN, 200, "500", "600"), buy(4, 1, PLAIN, 600, "500", "400"));
        AttentionFollowingCalculator.Result r = AttentionFollowingCalculator.calculate(10, signals(10), orders);
        assertThat(r.attentionBuys()).isEqualTo(2);
        assertThat(r.attentionBuyNotional().toPlainString()).isEqualTo("400");
        assertThat(r.attentionReturn().toPlainString()).as("(100 x 0.1 + 300 x -0.1) / 400").isEqualTo("-0.05000000");
        assertThat(r.otherBuys()).isEqualTo(2);
        assertThat(r.otherBuyNotional().toPlainString()).isEqualTo("800");
        assertThat(r.otherReturn().toPlainString()).as("(200 x 0.2 + 600 x -0.2) / 800").isEqualTo("-0.10000000");
        assertThat(r.incompleteBuys()).isZero();
    }

    @Test
    void aBuyWhoseWindowEndsAfterTheLastTurnIsCountedAndInNeitherGroupWhileTheBoundaryIsComplete() {
        // T = 10: a buy at turn 4 ends at turn 10 (complete); a buy at turn 5 would end at turn 11 (incomplete)
        List<BehaviourOrder> orders = List.of(buy(1, 4, TOP, 100, "1000", "1200"), buy(2, 5, TOP, 900, "1000", null),
                buy(3, 8, PLAIN, 500, "1000", null));
        AttentionFollowingCalculator.Result r = AttentionFollowingCalculator.calculate(10, signals(10), orders);
        assertThat(r.attentionBuys()).isEqualTo(1);
        assertThat(r.attentionReturn().toPlainString()).isEqualTo("0.20000000");
        assertThat(r.otherBuys()).isZero();
        assertThat(r.incompleteBuys()).isEqualTo(2);
        assertThat(r.attentionBuyNotional().toPlainString()).as("an incomplete buy adds no amount").isEqualTo("100");
    }

    @Test
    void sellsAreNotJudgedAndTheGroupIsDecidedAtTheBuyingTurn() {
        List<TurnSignals> signals = new ArrayList<>(List.of(new TurnSignals(1, Set.of(), Set.of(), Set.of(TOP)),
                new TurnSignals(2, Set.of(), Set.of(), Set.of())));
        for (int turn = 3; turn < 9; turn++) {
            signals.add(new TurnSignals(turn, Set.of(), Set.of(), Set.of()));
        }
        BehaviourOrder sell = new BehaviourOrder(9, 1, OrderSide.SELL, TOP, OrderSource.DIRECT, BigDecimal.valueOf(500),
                BigDecimal.ONE, new BigDecimal("1000"), new BigDecimal("2000"));
        AttentionFollowingCalculator.Result r = AttentionFollowingCalculator.calculate(9, signals,
                List.of(sell, buy(1, 1, TOP, 100, "1000", "1100"), buy(2, 2, TOP, 100, "1000", "1100")));
        assertThat(r.attentionBuys()).as("turn 1: TOP is in the top grade").isEqualTo(1);
        assertThat(r.otherBuys()).as("turn 2: TOP is not in the top grade, so it is 'other'").isEqualTo(1);
        assertThat(r.attentionBuyNotional().toPlainString()).as("the sell adds nothing").isEqualTo("100");
    }

    @Test
    void noBuyGivesNullReturnsAndZeroCounts() {
        AttentionFollowingCalculator.Result r = AttentionFollowingCalculator.calculate(10, signals(10), List.of());
        assertThat(r.attentionReturn()).isNull();
        assertThat(r.otherReturn()).isNull();
        assertThat(r.attentionBuys()).isZero();
        assertThat(r.otherBuys()).isZero();
        assertThat(r.incompleteBuys()).isZero();
        assertThat(r.attentionBuyNotional().toPlainString()).isEqualTo("0");
    }

    @Test
    void aSourceThatContradictsTheSeasonIsRefused() {
        List<TurnSignals> signals = signals(10);
        assertThatThrownBy(() -> AttentionFollowingCalculator.calculate(10, signals, List.of(buy(1, 1, TOP, 100, "1000", null))))
                .as("complete window without the later price").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AttentionFollowingCalculator.calculate(10, signals, List.of(buy(1, 1, TOP, 100, "0", "1000"))))
                .as("zero price").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AttentionFollowingCalculator.calculate(10, signals, List.of(buy(1, 6, TOP, 100, "1000", "1100"))))
                .as("incomplete window with a later price").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AttentionFollowingCalculator.calculate(10, signals, List.of(buy(1, 0, TOP, 100, "1000", "1100"))))
                .as("turn 0 is not judged").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AttentionFollowingCalculator.calculate(10, signals, List.of(buy(1, 10, TOP, 100, "1000", null))))
                .as("the last turn is not judged").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AttentionFollowingCalculator.calculate(10, signals, List.of(buy(1, 1, TOP, -5, "1000", "1100"))))
                .as("negative amount").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AttentionFollowingCalculator.calculate(10, List.of(), List.of(buy(1, 1, TOP, 100, "1000", "1100"))))
                .as("no signals for the buying turn").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theMeanIsRoundedOnceFromTheExactFractionEvenOnAnExactTie() {
        // three buys of 3,000 at +1/3 give exactly 3,000 won of gain; with a flat buy the total is 600,000,000,000, so the mean is
        // exactly 0.000000005 and HALF_UP gives 0.00000001. Summing rounded 34-digit quotients would give 0.00000000.
        List<BehaviourOrder> orders = List.of(buy(1, 1, TOP, 3_000, "3000", "4000"), buy(2, 2, TOP, 3_000, "3000", "4000"),
                buy(3, 3, TOP, 3_000, "3000", "4000"), buy(4, 4, TOP, 600_000_000_000L - 9_000, "1000", "1000"));
        AttentionFollowingCalculator.Result r = AttentionFollowingCalculator.calculate(10, signals(10), orders);
        assertThat(r.attentionReturn().toPlainString()).isEqualTo("0.00000001");
    }
}
