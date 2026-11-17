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

/**
 * The band is strict: a slot exactly 5 points from its target does not rebalance, one won of price more does. Two slots of one asset each
 * at 50/50, no fees, 5 shares each bought at 100 (cash 1,000). At turn 1 with prices (a, b) the slots are 5a and 5b: with (110, 90)
 * the first slot is 550 of 1,000 = 55 % (exactly 5 points), with (110, 89) it is 550 of 995 = 55.28 %.
 */
class BaselineBandBoundaryTest {

    private static final AssetKey A = new AssetKey("KR", "AAA");
    private static final AssetKey B = new AssetKey("KR", "BBB");
    private static final FeeRates FREE = new FeeRates(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    private static BaselineResult run(long a1, long b1, Integer band) {
        BaselineSpec spec = new BaselineSpec(BaselineKey.FORMATION_FIXED,
                List.of(new Slot("S1", List.of(new Leg(A, 100))), new Slot("S2", List.of(new Leg(B, 100)))), band);
        Map<AssetKey, List<BigDecimal>> prices = Map.of(
                A, List.of(bd(100), bd(a1), bd(a1)),
                B, List.of(bd(100), bd(b1), bd(b1)));
        return BaselineSimulator.simulate(spec, TargetSchedule.constant(50, 50), prices, bd(1_000), FREE);
    }

    private static BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }

    @Test
    void exactly_the_band_does_not_rebalance() {
        BaselineResult result = run(110, 90, 5);
        assertThat(result.rebalanceCount()).isZero();
        assertThat(result.fills()).noneMatch(f -> f.turnNo() > 0);
    }

    @Test
    void the_mirror_image_exactly_at_the_band_does_not_rebalance() {
        assertThat(run(90, 110, 5).rebalanceCount()).isZero();
    }

    @Test
    void one_won_beyond_the_band_rebalances() {
        BaselineResult up = run(111, 90, 5);
        assertThat(up.rebalanceCount()).isEqualTo(1);
        BaselineResult down = run(110, 89, 5);
        assertThat(down.rebalanceCount()).isEqualTo(1);
        // value 995, 497.5 per slot: the first asset (550) sells one share; the second is 52 short, less than one share of 89
        assertThat(down.fills()).filteredOn(f -> f.turnNo() > 0).extracting(f -> f.side().name() + " " + f.asset().ticker() + " " + f.quantity())
                .containsExactly("SELL AAA 1");
        assertThat(down.states().get(2).quantities().get(A)).isEqualTo(4);
        assertThat(down.states().get(2).quantities().get(B)).isEqualTo(5);
        assertThat(down.states().get(2).cash()).isEqualByComparingTo(bd(110));
    }

    @Test
    void a_wider_band_absorbs_the_same_move() {
        assertThat(run(111, 90, 6).rebalanceCount()).isZero();
    }

    @Test
    void no_band_never_rebalances() {
        assertThat(run(200, 50, null).rebalanceCount()).isZero();
    }
}
