package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderSide;
import antlab.common.money.MoneyMath;
import antlab.scoring.BaselineGenerated.Season;
import antlab.scoring.BaselineResult.Fill;
import antlab.scoring.BaselineResult.TurnState;
import antlab.scoring.BaselineSpec.Leg;
import antlab.scoring.BaselineSpec.Slot;
import antlab.scoring.RepresentativeTable.Entry;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Invariants of the simulator on 200 seeded seasons: accounting, band rule, last turn, fees, determinism. */
class BaselineSimulatorPropertyTest {

    private static final int SEASONS = 200;

    @Test
    void cash_never_negative_and_value_is_cash_plus_quantities_times_price() {
        forEachBaseline((season, result) -> {
            for (TurnState state : result.states()) {
                assertThat(state.cash().signum()).isGreaterThanOrEqualTo(0);
                BigDecimal value = state.cash();
                for (Map.Entry<AssetKey, Integer> held : state.quantities().entrySet()) {
                    BigDecimal price = season.prices().get(held.getKey()).get(state.turnNo());
                    value = value.add(price.multiply(BigDecimal.valueOf(held.getValue())));
                }
                assertThat(state.value()).isEqualByComparingTo(value);
            }
        });
    }

    @Test
    void cash_and_quantities_follow_from_the_fills_alone() {
        forEachBaseline((season, result) -> {
            for (TurnState state : result.states()) {
                BigDecimal cash = season.initialCash();
                Map<AssetKey, Integer> held = new TreeMap<>(BaselineSimulator.ASSET_ORDER);
                for (Fill fill : result.fills()) {
                    if (fill.turnNo() > Math.max(state.turnNo() - 1, 0)) {
                        continue;
                    }
                    BigDecimal notional = fill.price().multiply(BigDecimal.valueOf(fill.quantity()));
                    if (fill.side() == OrderSide.BUY) {
                        cash = cash.subtract(notional).subtract(fill.fee());
                        held.merge(fill.asset(), fill.quantity(), Integer::sum);
                    } else {
                        cash = cash.add(notional).subtract(fill.fee());
                        held.merge(fill.asset(), -fill.quantity(), Integer::sum);
                    }
                }
                assertThat(state.cash()).isEqualByComparingTo(cash);
                state.quantities().forEach((asset, quantity) -> assertThat(quantity).isEqualTo(held.getOrDefault(asset, 0)));
            }
        });
    }

    @Test
    void trades_happen_only_on_turns_that_break_the_band_or_change_the_target_and_never_at_the_last_turn() {
        forEachBaseline((season, result) -> {
            BaselineSpec spec = spec(result.key());
            TargetSchedule schedule = schedule(season, result.key());
            int expectedRebalances = 0;
            for (int turn = 1; turn < season.turns(); turn++) {
                boolean forced = schedule.switchesAt(turn);
                boolean broken = broken(spec, schedule, result.states().get(turn), season);
                int atTurn = turn;
                boolean anyFill = result.fills().stream().anyMatch(f -> f.turnNo() == atTurn);
                if (anyFill) {
                    assertThat(forced || broken).as("fill without a reason at turn " + turn).isTrue();
                }
                if (forced || broken) {
                    expectedRebalances++;
                }
            }
            assertThat(result.rebalanceCount()).isEqualTo(expectedRebalances);
            assertThat(result.fills()).noneMatch(f -> f.turnNo() >= season.turns());
        });
    }

    @Test
    void total_fee_is_the_sum_of_the_money_math_fee_of_every_fill() {
        forEachBaseline((season, result) -> {
            BigDecimal sum = BigDecimal.ZERO;
            for (Fill fill : result.fills()) {
                BigDecimal rate = MoneyMath.feeRate(fill.asset().market(), season.fees().kr(), season.fees().us(),
                        season.fees().fxSpread());
                assertThat(fill.fee()).isEqualByComparingTo(MoneyMath.fee(fill.price(), fill.quantity(), rate));
                sum = sum.add(fill.fee());
            }
            assertThat(result.totalFee()).isEqualByComparingTo(sum);
            BigDecimal forced = result.fills().stream().filter(Fill::forced).map(Fill::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(result.forcedChangeFee()).isEqualByComparingTo(forced);
        });
    }

    @Test
    void forced_rebalances_exist_only_for_the_following_baseline() {
        forEachBaseline((season, result) -> {
            if (result.key() == BaselineKey.FORMATION_FOLLOW) {
                assertThat(result.forcedChangeCount()).isEqualTo(season.switches().size());
            } else {
                assertThat(result.forcedChangeCount()).isZero();
                assertThat(result.forcedChangeFee()).isEqualByComparingTo(BigDecimal.ZERO);
            }
        });
    }

    @Test
    void equal_input_gives_equal_output() {
        for (long seed = 0; seed < 20; seed++) {
            Season season = BaselineGenerated.season(seed);
            assertThat(season.play()).isEqualTo(season.play());
        }
    }

    @Test
    void the_generator_reaches_band_and_forced_rebalances() {
        int band = 0;
        int forced = 0;
        for (long seed = 0; seed < SEASONS; seed++) {
            for (BaselineResult result : BaselineGenerated.season(seed).play().values()) {
                band += result.rebalanceCount() - result.forcedChangeCount();
                forced += result.forcedChangeCount();
            }
        }
        assertThat(band).isGreaterThan(100);
        assertThat(forced).isGreaterThan(50);
    }

    // ---- independent restatement of the band rule on recorded states

    private static boolean broken(BaselineSpec spec, TargetSchedule schedule, TurnState state, Season season) {
        if (spec.bandPercent() == null) {
            return false;
        }
        List<Integer> target = schedule.targetAt(state.turnNo());
        BigInteger total = state.value().toBigIntegerExact();
        for (int i = 0; i < spec.slots().size(); i++) {
            Slot slot = spec.slots().get(i);
            BigInteger value = slot.isCash() ? state.cash().toBigIntegerExact() : BigInteger.ZERO;
            for (Leg leg : slot.legs()) {
                BigInteger price = season.prices().get(leg.asset()).get(state.turnNo()).toBigIntegerExact();
                value = value.add(price.multiply(BigInteger.valueOf(state.quantities().get(leg.asset()))));
            }
            BigInteger deviation = BigInteger.valueOf(100).multiply(value)
                    .subtract(BigInteger.valueOf(target.get(i)).multiply(total)).abs();
            if (deviation.compareTo(BigInteger.valueOf(spec.bandPercent()).multiply(total)) > 0) {
                return true;
            }
        }
        return false;
    }

    private static BaselineSpec spec(BaselineKey key) {
        for (Entry entry : RepresentativeTable.REP_V1.resolve(Set.copyOf(BaselineGenerated.POOL)).entries()) {
            if (entry.key() == key) {
                return entry.spec();
            }
        }
        throw new IllegalStateException();
    }

    private static TargetSchedule schedule(Season season, BaselineKey key) {
        return switch (key) {
            case MARKET -> TargetSchedule.constant(100);
            case SIXTY_FORTY -> TargetSchedule.constant(60, 40);
            case FORMATION_FIXED -> new TargetSchedule(season.startFormation(), List.of());
            case FORMATION_FOLLOW -> new TargetSchedule(season.startFormation(), season.switches());
        };
    }

    private interface Check {
        void accept(Season season, BaselineResult result);
    }

    private static void forEachBaseline(Check check) {
        for (long seed = 0; seed < SEASONS; seed++) {
            Season season = BaselineGenerated.season(seed);
            for (BaselineResult result : season.play().values()) {
                check.accept(season, result);
            }
        }
    }
}
