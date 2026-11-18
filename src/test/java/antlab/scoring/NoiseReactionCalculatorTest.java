package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.account.OrderSide;
import antlab.account.OrderSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Hand-computed noise reaction: a buy follows an up signal, a sell follows a down signal, the season value is the per-turn mean. */
class NoiseReactionCalculatorTest {

    private static final long GAINER = 1;
    private static final long LOSER = 2;
    private static final long PLAIN = 3;
    private static final long TOP = 4;

    private static TurnSignals signals(int turn, Set<Long> up, Set<Long> down, Set<Long> top) {
        return new TurnSignals(turn, up, down, top);
    }

    private static BehaviourOrder order(long id, int turn, OrderSide side, long asset, long notional) {
        return new BehaviourOrder(id, turn, side, asset, OrderSource.DIRECT, BigDecimal.valueOf(notional), BigDecimal.ONE,
                BigDecimal.TEN, null);
    }

    @Test
    void theSeasonValueIsTheMeanOfTheTradingTurnsAndTurnsWithoutTradeDoNotCount() {
        List<TurnSignals> signals = List.of(signals(1, Set.of(GAINER), Set.of(LOSER), Set.of()),
                signals(2, Set.of(GAINER), Set.of(LOSER), Set.of()), signals(3, Set.of(GAINER), Set.of(LOSER), Set.of()));
        List<BehaviourOrder> orders = List.of(order(1, 1, OrderSide.BUY, GAINER, 600), order(2, 1, OrderSide.BUY, PLAIN, 400),
                order(3, 2, OrderSide.SELL, LOSER, 300), order(4, 2, OrderSide.SELL, PLAIN, 100));
        NoiseReactionCalculator.Result r = NoiseReactionCalculator.calculate(4, signals, orders);
        assertThat(r.noiseShare().toPlainString()).as("(0.6 + 0.75) / 2, not pooled and not diluted by turn 3").isEqualTo("0.67500000");
        assertThat(r.tradingTurns()).isEqualTo(2);
        assertThat(r.followedBuyNotional().toPlainString()).isEqualTo("600");
        assertThat(r.followedSellNotional().toPlainString()).isEqualTo("300");
        assertThat(r.tradeNotional().toPlainString()).isEqualTo("1400");
    }

    @Test
    void buyingALoserSellingAGainerAndSellingATopAttentionAssetDoNotFollow() {
        List<TurnSignals> signals = List.of(signals(1, Set.of(GAINER), Set.of(LOSER), Set.of(TOP)));
        List<BehaviourOrder> orders = List.of(order(1, 1, OrderSide.BUY, LOSER, 100), order(2, 1, OrderSide.SELL, GAINER, 100),
                order(3, 1, OrderSide.SELL, TOP, 100), order(4, 1, OrderSide.BUY, TOP, 100));
        NoiseReactionCalculator.Result r = NoiseReactionCalculator.calculate(2, signals, orders);
        assertThat(r.noiseShare().toPlainString()).isEqualTo("0.00000000");
        assertThat(r.tradingTurns()).isEqualTo(1);
        assertThat(r.followedBuyNotional()).isEqualByComparingTo("0");
        assertThat(r.followedSellNotional()).isEqualByComparingTo("0");
    }

    @Test
    void anAssetInBothSetsCountsItsBuyAndItsSell() {
        List<TurnSignals> signals = List.of(signals(1, Set.of(GAINER), Set.of(GAINER), Set.of()));
        List<BehaviourOrder> orders = List.of(order(1, 1, OrderSide.BUY, GAINER, 100), order(2, 1, OrderSide.SELL, GAINER, 300));
        NoiseReactionCalculator.Result r = NoiseReactionCalculator.calculate(2, signals, orders);
        assertThat(r.noiseShare().toPlainString()).isEqualTo("1.00000000");
        assertThat(r.followedBuyNotional()).isEqualByComparingTo("100");
        assertThat(r.followedSellNotional()).isEqualByComparingTo("300");
    }

    @Test
    void theMeanIsRoundedOnceAtTheEnd() {
        // turn values 2/3, 1/3 and 1/7: the exact mean is 8/21
        List<TurnSignals> signals = List.of(signals(1, Set.of(GAINER), Set.of(), Set.of()),
                signals(2, Set.of(GAINER), Set.of(), Set.of()), signals(3, Set.of(GAINER), Set.of(), Set.of()));
        List<BehaviourOrder> orders = List.of(order(1, 1, OrderSide.BUY, GAINER, 2), order(2, 1, OrderSide.BUY, PLAIN, 1),
                order(3, 2, OrderSide.BUY, GAINER, 1), order(4, 2, OrderSide.BUY, PLAIN, 2),
                order(5, 3, OrderSide.BUY, GAINER, 1), order(6, 3, OrderSide.BUY, PLAIN, 6));
        assertThat(NoiseReactionCalculator.calculate(4, signals, orders).noiseShare().toPlainString()).isEqualTo("0.38095238");
    }

    @Test
    void noTradeInTheJudgedTurnsGivesNullAndZeroAmounts() {
        NoiseReactionCalculator.Result r = NoiseReactionCalculator.calculate(3,
                List.of(signals(1, Set.of(), Set.of(), Set.of()), signals(2, Set.of(), Set.of(), Set.of())), List.of());
        assertThat(r.noiseShare()).isNull();
        assertThat(r.tradingTurns()).isZero();
        assertThat(r.followedBuyNotional().toPlainString()).isEqualTo("0");
        assertThat(r.followedSellNotional().toPlainString()).isEqualTo("0");
        assertThat(r.tradeNotional().toPlainString()).isEqualTo("0");
    }

    @Test
    void anOrderOfTurnZeroOrOfTheLastTurnIsRefused() {
        List<TurnSignals> signals = List.of(signals(1, Set.of(), Set.of(), Set.of()), signals(2, Set.of(), Set.of(), Set.of()));
        assertThatThrownBy(() -> NoiseReactionCalculator.calculate(3, signals, List.of(order(1, 0, OrderSide.BUY, GAINER, 100))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("보고 범위 밖의 주문");
        assertThatThrownBy(() -> NoiseReactionCalculator.calculate(3, signals, List.of(order(1, 3, OrderSide.BUY, GAINER, 100))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("보고 범위 밖의 주문");
    }

    @Test
    void aNonPositiveOrFractionalAmountIsRefused() {
        List<TurnSignals> signals = List.of(signals(1, Set.of(), Set.of(), Set.of()));
        for (long bad : new long[] {-100, 0}) {
            assertThatThrownBy(() -> NoiseReactionCalculator.calculate(2, signals, List.of(order(1, 1, OrderSide.BUY, GAINER, bad))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("양의 원 단위 정수");
        }
        BehaviourOrder fraction = new BehaviourOrder(1, 1, OrderSide.BUY, GAINER, OrderSource.DIRECT, new BigDecimal("100.5"),
                BigDecimal.ONE, BigDecimal.TEN, null);
        assertThatThrownBy(() -> NoiseReactionCalculator.calculate(2, signals, List.of(fraction)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOrderWithoutSignalsOrSignalsOutsideTheRangeAreRefused() {
        assertThatThrownBy(() -> NoiseReactionCalculator.calculate(3, List.of(signals(1, Set.of(), Set.of(), Set.of())),
                List.of(order(1, 2, OrderSide.BUY, GAINER, 100)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("신호가 없다");
        assertThatThrownBy(() -> NoiseReactionCalculator.calculate(2, List.of(signals(2, Set.of(), Set.of(), Set.of())), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NoiseReactionCalculator.calculate(3,
                List.of(signals(1, Set.of(), Set.of(), Set.of()), signals(1, Set.of(), Set.of(), Set.of())), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
