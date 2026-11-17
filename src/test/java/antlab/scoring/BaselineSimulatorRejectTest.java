package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.scoring.BaselineSimulator.FeeRates;
import antlab.scoring.BaselineSpec.Leg;
import antlab.scoring.BaselineSpec.Slot;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Contradicting inputs stop with a fixed sentence; the sentence carries no digit, so no amount, turn or percentage can leak. */
class BaselineSimulatorRejectTest {

    private static final AssetKey A = new AssetKey("KR", "AAA");
    private static final AssetKey B = new AssetKey("KR", "BBB");
    private static final FeeRates FEES = new FeeRates(new BigDecimal("0.001"), new BigDecimal("0.002"), new BigDecimal("0.004"));
    private static final Pattern DIGIT = Pattern.compile("\\d");

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static List<BigDecimal> series(String... values) {
        List<BigDecimal> out = new ArrayList<>();
        for (String value : values) {
            out.add(bd(value));
        }
        return out;
    }

    private static BaselineSpec twoSlots() {
        return new BaselineSpec(BaselineKey.FORMATION_FIXED,
                List.of(new Slot("S1", List.of(new Leg(A, 100))), new Slot("S2", List.of(new Leg(B, 100)))), 5);
    }

    private static Map<AssetKey, List<BigDecimal>> prices() {
        Map<AssetKey, List<BigDecimal>> map = new HashMap<>();
        map.put(A, series("100", "110", "120", "130"));
        map.put(B, series("200", "210", "220", "230"));
        return map;
    }

    private static void rejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(DIGIT.matcher(e.getMessage()).find()).as("digit in message").isFalse());
    }

    private static BaselineResult simulate(TargetSchedule schedule, Map<AssetKey, List<BigDecimal>> prices, String cash) {
        return BaselineSimulator.simulate(twoSlots(), schedule, prices, bd(cash), FEES);
    }

    @Test
    void the_accepted_baseline_runs() {
        assertThat(simulate(TargetSchedule.constant(50, 50), prices(), "1000000").states()).hasSize(4);
    }

    @Test
    void slot_percents_not_adding_to_the_whole() {
        rejected(() -> TargetSchedule.constant(50, 49));
        rejected(() -> TargetSchedule.constant(60, 50));
        rejected(() -> TargetSchedule.constant(101, -1));
        rejected(() -> simulate(TargetSchedule.constant(100), prices(), "1000000"));
    }

    @Test
    void leg_shares_not_adding_to_the_whole() {
        rejected(() -> new Slot("S", List.of(new Leg(A, 50), new Leg(B, 49))));
        rejected(() -> new Leg(A, 0));
        rejected(() -> new Leg(A, 101));
    }

    @Test
    void structural_mistakes_in_a_spec() {
        rejected(() -> new BaselineSpec(BaselineKey.MARKET, List.of(), null));
        rejected(() -> new BaselineSpec(BaselineKey.MARKET, List.of(new Slot("S", List.of(new Leg(A, 100))),
                new Slot("S", List.of(new Leg(B, 100)))), null));
        rejected(() -> new BaselineSpec(BaselineKey.MARKET, List.of(new Slot("S", List.of(new Leg(A, 100))),
                new Slot("T", List.of(new Leg(A, 100)))), null));
        rejected(() -> new BaselineSpec(BaselineKey.MARKET, List.of(new Slot("C1", List.of()), new Slot("C2", List.of())), null));
        rejected(() -> new BaselineSpec(BaselineKey.MARKET, List.of(new Slot("S", List.of(new Leg(A, 100)))), 0));
        rejected(() -> new BaselineSpec(BaselineKey.MARKET, List.of(new Slot("S", List.of(new Leg(A, 100)))), 100));
    }

    @Test
    void missing_prices() {
        Map<AssetKey, List<BigDecimal>> missing = prices();
        missing.remove(B);
        rejected(() -> simulate(TargetSchedule.constant(50, 50), missing, "1000000"));
        Map<AssetKey, List<BigDecimal>> shorter = prices();
        shorter.put(B, series("200", "210", "220"));
        rejected(() -> simulate(TargetSchedule.constant(50, 50), shorter, "1000000"));
        Map<AssetKey, List<BigDecimal>> onlyTurnZero = new HashMap<>();
        onlyTurnZero.put(A, series("100"));
        onlyTurnZero.put(B, series("200"));
        rejected(() -> simulate(TargetSchedule.constant(50, 50), onlyTurnZero, "1000000"));
    }

    @Test
    void prices_that_are_not_positive_whole_won() {
        for (String bad : List.of("0", "-5", "100.5", "100.0001")) {
            Map<AssetKey, List<BigDecimal>> map = prices();
            map.put(A, series("100", bad, "120", "130"));
            rejected(() -> simulate(TargetSchedule.constant(50, 50), map, "1000000"));
        }
        Map<AssetKey, List<BigDecimal>> withNull = prices();
        List<BigDecimal> hole = series("100", "110", "120", "130");
        hole.set(2, null);
        withNull.put(A, hole);
        rejected(() -> simulate(TargetSchedule.constant(50, 50), withNull, "1000000"));
    }

    @Test
    void whole_won_with_trailing_zeros_is_accepted() {
        Map<AssetKey, List<BigDecimal>> map = prices();
        map.put(A, series("100.0000", "110.0000", "120.0000", "130.0000"));
        assertThat(simulate(TargetSchedule.constant(50, 50), map, "1000000.0000").states()).hasSize(4);
    }

    @Test
    void initial_cash_that_is_not_positive_whole_won() {
        for (String bad : List.of("0", "-1", "1000000.5")) {
            rejected(() -> simulate(TargetSchedule.constant(50, 50), prices(), bad));
        }
    }

    @Test
    void switch_turns_outside_one_to_the_turn_before_the_last() {
        List<Integer> other = List.of(40, 60);
        rejected(() -> new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(0, other))));
        rejected(() -> new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(-1, other))));
        // the prices above run to the last turn 3, so a switch at 3 or later has no turn to rebalance on
        rejected(() -> simulate(new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(3, other))), prices(), "1000000"));
        rejected(() -> simulate(new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(7, other))), prices(), "1000000"));
        assertThat(simulate(new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(2, other))), prices(), "1000000")
                .forcedChangeCount()).isEqualTo(1);
    }

    @Test
    void unsorted_or_repeated_switch_turns() {
        List<Integer> one = List.of(40, 60);
        List<Integer> two = List.of(30, 70);
        rejected(() -> new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(2, one), new TargetSchedule.Switch(1, two))));
        rejected(() -> new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(2, one), new TargetSchedule.Switch(2, two))));
    }

    @Test
    void a_switch_whose_target_has_the_wrong_size_or_total() {
        rejected(() -> new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(1, List.of(100)))));
        rejected(() -> new TargetSchedule(List.of(50, 50), List.of(new TargetSchedule.Switch(1, List.of(60, 60)))));
    }

    @Test
    void exceptions_outside_the_fixed_sentence_contract_are_converted() {
        rejected(() -> BaselineSimulator.simulate(twoSlots(), TargetSchedule.constant(50, 50), prices(), bd("1000000"),
                new FeeRates(null, new BigDecimal("0.002"), new BigDecimal("0.004"))));
        BaselineSpec foreign = new BaselineSpec(BaselineKey.MARKET,
                List.of(new Slot("S", List.of(new Leg(new AssetKey("JP", "AAA"), 100)))), null);
        Map<AssetKey, List<BigDecimal>> jp = Map.of(new AssetKey("JP", "AAA"), series("100", "110", "120"));
        rejected(() -> BaselineSimulator.simulate(foreign, TargetSchedule.constant(100), jp, bd("1000000"), FEES));
        List<Integer> withNull = new ArrayList<>(List.of(50, 50));
        withNull.set(0, null);
        rejected(() -> new TargetSchedule(withNull, List.of()));
        Map<AssetKey, List<BigDecimal>> huge = prices();
        huge.put(A, series("100", "110", "99999999999999999999", "130"));
        rejected(() -> simulate(TargetSchedule.constant(50, 50), huge, "1000000"));
        rejected(() -> Decomposition.of(bd("1"), bd("1"), bd("1"), bd("1"), bd("-5")));
    }
}
