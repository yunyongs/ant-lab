package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.scoring.BaselineGenerated.Season;
import antlab.scoring.BaselineResult.Fill;
import antlab.scoring.SeasonReportSources.AssetKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Prefix property: nothing after turn t, prices or formation changes, can move a baseline's states or fills up to turn t. */
class BaselineLookAheadTest {

    @Test
    void changing_prices_after_turn_t_leaves_everything_up_to_t_unchanged() {
        int checked = 0;
        for (long seed = 0; seed < 60; seed++) {
            Season season = BaselineGenerated.season(seed);
            Map<BaselineKey, BaselineResult> base = season.play();
            for (int t = 0; t < season.turns(); t += 2) {
                Season other = season.withPrices(BaselineGenerated.otherFuture(season, t, seed * 31 + t));
                Map<BaselineKey, BaselineResult> changed = other.play();
                for (BaselineKey key : BaselineKey.values()) {
                    assertSamePrefix(base.get(key), changed.get(key), t);
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(500);
    }

    @Test
    void changing_formation_changes_after_turn_t_leaves_everything_up_to_t_unchanged() {
        int compared = 0;
        for (long seed = 0; seed < 60; seed++) {
            Season season = BaselineGenerated.season(seed);
            BaselineResult base = season.play().get(BaselineKey.FORMATION_FOLLOW);
            for (int t = 0; t < season.turns() - 1; t++) {
                int cut = t;
                List<TargetSchedule.Switch> kept = new ArrayList<>(season.switches().stream().filter(s -> s.turnNo() <= cut).toList());
                int next = t + 1;
                List<Integer> last = kept.isEmpty() ? season.startFormation() : kept.get(kept.size() - 1).target();
                List<Integer> other = last.equals(List.of(100, 0, 0, 0)) ? List.of(0, 100, 0, 0) : List.of(100, 0, 0, 0);
                kept.add(new TargetSchedule.Switch(next, other));
                BaselineResult changed = season.withSwitches(kept).play().get(BaselineKey.FORMATION_FOLLOW);
                assertSamePrefix(base, changed, t);
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(500);
    }

    @Test
    void the_future_does_matter_after_t_so_the_test_can_fail() {
        Season season = BaselineGenerated.season(3);
        Map<AssetKey, List<BigDecimal>> other = BaselineGenerated.otherFuture(season, 0, 99);
        BaselineResult base = season.play().get(BaselineKey.MARKET);
        BaselineResult changed = season.withPrices(other).play().get(BaselineKey.MARKET);
        assertThat(changed.turnValues()).isNotEqualTo(base.turnValues());
    }

    private static void assertSamePrefix(BaselineResult base, BaselineResult changed, int t) {
        assertThat(changed.states().subList(0, t + 1)).isEqualTo(base.states().subList(0, t + 1));
        List<Fill> baseFills = base.fills().stream().filter(f -> f.turnNo() <= t).toList();
        List<Fill> changedFills = changed.fills().stream().filter(f -> f.turnNo() <= t).toList();
        assertThat(changedFills).isEqualTo(baseFills);
    }
}
