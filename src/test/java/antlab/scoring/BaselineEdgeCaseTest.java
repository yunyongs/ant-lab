package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.BaselineSimulator.FeeRates;
import antlab.scoring.BaselineSpec.Leg;
import antlab.scoring.BaselineSpec.Slot;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hand-fixed cases for the cash slot band, the cash cap of a buy, and a slot that cannot buy one share. */
class BaselineEdgeCaseTest {

    private static final AssetKey A = new AssetKey("KR", "AAA");
    private static final AssetKey B = new AssetKey("KR", "BBB");
    private static final FeeRates FREE = new FeeRates(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }

    private static List<BigDecimal> list(long... values) {
        return java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }

    private static BaselineSpec cashAndOne(AssetKey asset) {
        return new BaselineSpec(BaselineKey.FORMATION_FIXED,
                List.of(new Slot("GK", List.of()), new Slot("FW", List.of(new Leg(asset, 100)))), 5);
    }

    /** Cash 1,000, target 20 % cash / 80 % asset at price 100: 8 shares, cash 200. At price a the cash slot is 200 of 200 + 8a. */
    private static BaselineResult cashSlot(long a) {
        return BaselineSimulator.simulate(cashAndOne(A), TargetSchedule.constant(20, 80),
                Map.of(A, list(100, a, a)), bd(1_000), FREE);
    }

    @Test
    void the_cash_slot_exactly_five_points_off_does_not_rebalance() {
        // a = 75: value 800, cash slot 200 = 25 % (exactly 5 points over its 20 %)
        BaselineResult result = cashSlot(75);
        assertThat(result.rebalanceCount()).isZero();
        assertThat(result.bandNoFillCount()).isZero();
    }

    @Test
    void the_cash_slot_one_won_beyond_five_points_breaks_the_band() {
        // a = 74: value 792, cash slot 200 = 25.25 %; the asset would be 633.6 of 792, a 41.6 gap, less than one share of 74,
        // so the band is broken but nothing can be bought: counted apart, not as a rebalance
        BaselineResult result = cashSlot(74);
        assertThat(result.bandNoFillCount()).isEqualTo(1);
        assertThat(result.rebalanceCount()).isZero();
    }

    @Test
    void in_sixty_forty_the_leftover_cash_belongs_to_no_slot_but_counts_in_the_total() {
        // cash 1,000 at 100 each; 60/40 -> 6 and 4 shares, no leftover. Turn 1 prices (a, b) = (120, 80): slots 720 / 320, total 1,040,
        // equity 69.2 %, bond 30.8 % -> broken. With leftover cash the total would still include it: use cash 1,050 -> 6 and 4, cash 50.
        BaselineSpec spec = new BaselineSpec(BaselineKey.SIXTY_FORTY,
                List.of(new Slot("EQUITY", List.of(new Leg(A, 100))), new Slot("BOND", List.of(new Leg(B, 100)))), 5);
        // prices a=100 -> 107, b=100: slots 642 / 400, total 642 + 400 + 50 = 1,092; equity 58.79 % (1.21 off), bond 36.63 % (3.37 off)
        BaselineResult quiet = BaselineSimulator.simulate(spec, TargetSchedule.constant(60, 40),
                Map.of(A, list(100, 107, 107), B, list(100, 100, 100)), bd(1_050), FREE);
        assertThat(quiet.states().get(1).value().toPlainString()).isEqualTo("1092");
        assertThat(quiet.rebalanceCount()).isZero();
        // bond falls to 60: slots 642 / 240, total 642 + 240 + 50 = 932; bond 25.75 % (14.25 off) -> rebalance on the total with cash
        BaselineResult broken = BaselineSimulator.simulate(spec, TargetSchedule.constant(60, 40),
                Map.of(A, list(100, 107, 107), B, list(100, 60, 60)), bd(1_050), FREE);
        assertThat(broken.rebalanceCount()).isEqualTo(1);
    }

    @Test
    void a_buy_is_capped_by_the_cash_left_after_the_sells_and_their_fees() {
        // fee rate 10 %: opening 2,779 buys 7 of A (167) and 10 of B (124), cash 129. At turn 1 (61 / 263) the band breaks; selling
        // frees little cash after fees, so the budget of the last buy is the cash, not the gap to the target. Cash must stay >= 0.
        FeeRates tenPercent = new FeeRates(new BigDecimal("0.1"), BigDecimal.ZERO, BigDecimal.ZERO);
        BaselineSpec spec = new BaselineSpec(BaselineKey.FORMATION_FIXED,
                List.of(new Slot("S1", List.of(new Leg(A, 100))), new Slot("S2", List.of(new Leg(B, 100)))), 5);
        BaselineResult result = BaselineSimulator.simulate(spec, TargetSchedule.constant(50, 50),
                Map.of(A, list(167, 61, 50), B, list(124, 263, 50)), bd(2_779), tenPercent);
        assertThat(result.states().get(1).cash().toPlainString()).isEqualTo("129");
        assertThat(result.states().get(2).cash().toPlainString()).isEqualTo("2");
        assertThat(result.states().get(2).quantities().get(A)).isEqualTo(23);
        assertThat(result.states().get(2).quantities().get(B)).isEqualTo(6);
    }

    @Test
    void a_slot_that_cannot_buy_one_share_is_counted_apart_and_does_not_inflate_the_rebalance_count() {
        // cash 10,000,000, 93 % cash / 7 % asset at 840,000: the 700,000 budget buys nothing, the slot stays 7 points short every turn
        BaselineResult result = BaselineSimulator.simulate(cashAndOne(B), TargetSchedule.constant(93, 7),
                Map.of(B, list(840_000, 840_000, 840_000, 840_000, 840_000)), bd(10_000_000), FREE);
        assertThat(result.fills()).isEmpty();
        assertThat(result.rebalanceCount()).isZero();
        assertThat(result.bandNoFillCount()).isEqualTo(3);
        assertThat(result.totalFee()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
