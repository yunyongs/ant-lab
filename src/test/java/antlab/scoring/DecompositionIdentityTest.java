package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.scoring.BaselineGenerated.Season;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The three-way split telescopes exactly in won; ratios are one division each; with no formation change the tactic effect is zero. */
class DecompositionIdentityTest {

    private static BigDecimal won(long value) {
        return BigDecimal.valueOf(value);
    }

    @Test
    void golden_digits_of_a_hand_case() {
        // initial 30,000,000; user 31,234,567; follow 30,876,543; fixed 30,111,111; market 29,999,999
        Decomposition d = Decomposition.of(won(31_234_567), won(30_876_543), won(30_111_111), won(29_999_999), won(30_000_000));
        assertThat(d.selectionWon().toPlainString()).isEqualTo("358024");
        assertThat(d.tacticWon().toPlainString()).isEqualTo("765432");
        assertThat(d.structureWon().toPlainString()).isEqualTo("111112");
        assertThat(d.totalWon().toPlainString()).isEqualTo("1234568");
        assertThat(d.selectionReturn().toPlainString()).isEqualTo("0.01193413");
        assertThat(d.tacticReturn().toPlainString()).isEqualTo("0.02551440");
        assertThat(d.structureReturn().toPlainString()).isEqualTo("0.00370373");
        // one division: 1,234,568 / 30,000,000; the difference of the two rounded returns would end in 26
        assertThat(d.totalReturn().toPlainString()).isEqualTo("0.04115227");
    }

    @Test
    void the_won_identity_is_exact_and_the_ratios_agree_within_two_units_of_the_eighth_decimal() {
        Random random = new Random(7);
        BigDecimal tolerance = new BigDecimal("0.00000002");
        for (int i = 0; i < 500; i++) {
            BigDecimal initial = won(3_000_000L + random.nextInt(97_000_000));
            Decomposition d = Decomposition.of(won(random.nextInt(200_000_000)), won(random.nextInt(200_000_000)),
                    won(random.nextInt(200_000_000)), won(random.nextInt(200_000_000)), initial);
            assertThat(d.selectionWon().add(d.tacticWon()).add(d.structureWon())).isEqualByComparingTo(d.totalWon());
            BigDecimal sum = d.selectionReturn().add(d.tacticReturn()).add(d.structureReturn());
            assertThat(sum.subtract(d.totalReturn()).abs()).isLessThanOrEqualTo(tolerance);
        }
    }

    @Test
    void on_generated_seasons_the_split_telescopes_through_the_real_baselines() {
        for (long seed = 0; seed < 200; seed++) {
            Season season = BaselineGenerated.season(seed);
            Map<BaselineKey, BaselineResult> played = season.play();
            BigDecimal user = season.initialCash().add(won(new Random(seed).nextInt(2_000_000) - 1_000_000));
            BigDecimal market = played.get(BaselineKey.MARKET).finalValue();
            Decomposition d = Decomposition.of(user, played.get(BaselineKey.FORMATION_FOLLOW).finalValue(),
                    played.get(BaselineKey.FORMATION_FIXED).finalValue(), market, season.initialCash());
            assertThat(d.selectionWon().add(d.tacticWon()).add(d.structureWon())).isEqualByComparingTo(user.subtract(market));
            assertThat(d.totalWon()).isEqualByComparingTo(user.subtract(market));
        }
    }

    @Test
    void without_a_formation_change_following_equals_fixed_on_every_turn_and_the_tactic_effect_is_zero() {
        int checked = 0;
        for (long seed = 0; seed < 200; seed++) {
            Season season = BaselineGenerated.season(seed).withSwitches(List.of());
            Map<BaselineKey, BaselineResult> played = season.play();
            BaselineResult follow = played.get(BaselineKey.FORMATION_FOLLOW);
            BaselineResult fixed = played.get(BaselineKey.FORMATION_FIXED);
            assertThat(follow.turnValues()).isEqualTo(fixed.turnValues());
            assertThat(follow.fills()).isEqualTo(fixed.fills());
            Decomposition d = Decomposition.of(follow.finalValue(), follow.finalValue(), fixed.finalValue(),
                    played.get(BaselineKey.MARKET).finalValue(), season.initialCash());
            assertThat(d.tacticWon()).isEqualByComparingTo(BigDecimal.ZERO);
            checked++;
        }
        assertThat(checked).isEqualTo(200);
    }

    @Test
    void a_switch_that_leaves_the_target_unchanged_is_refused() {
        assertThatThrownBy(() -> new TargetSchedule(List.of(20, 30, 30, 20),
                List.of(new TargetSchedule.Switch(2, List.of(20, 30, 30, 20))))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TargetSchedule(List.of(20, 30, 30, 20), List.of(
                new TargetSchedule.Switch(2, List.of(10, 40, 30, 20)),
                new TargetSchedule.Switch(3, List.of(10, 40, 30, 20))))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void following_differs_from_fixed_once_a_change_happens_in_the_golden_fixture() {
        BaselineVectors vectors = BaselineVectors.load();
        BigDecimal follow = vectors.simulate(BaselineKey.FORMATION_FOLLOW).finalValue();
        BigDecimal fixed = vectors.simulate(BaselineKey.FORMATION_FIXED).finalValue();
        assertThat(follow).isNotEqualByComparingTo(fixed);
    }
}
