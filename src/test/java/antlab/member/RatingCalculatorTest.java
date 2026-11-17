package antlab.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Scores are listed oldest first; the newest season carries weight 5. */
class RatingCalculatorTest {

    private static List<BigDecimal> scores(String... values) {
        return Stream.of(values).map(BigDecimal::new).toList();
    }

    @Test
    void one_season_is_its_own_rating() {
        RatingCalculator.Rating r = RatingCalculator.rate(scores("64.20"));

        assertThat(r.rating()).isEqualByComparingTo("64.20");
        assertThat(r.seasonsUsed()).isEqualTo(1);
    }

    @Test
    void two_seasons_weigh_newest_five_and_older_four() {
        // (60 x 4 + 80 x 5) / 9 = 71.111...
        RatingCalculator.Rating r = RatingCalculator.rate(scores("60", "80"));

        assertThat(r.rating()).isEqualByComparingTo("71.11");
        assertThat(r.seasonsUsed()).isEqualTo(2);
    }

    @Test
    void three_seasons_use_weights_three_four_five() {
        // (50 x 3 + 60 x 4 + 70 x 5) / 12 = 61.666...
        assertThat(RatingCalculator.rate(scores("50", "60", "70")).rating()).isEqualByComparingTo("61.67");
    }

    @Test
    void four_seasons_use_weights_two_three_four_five() {
        // (40 x 2 + 50 x 3 + 60 x 4 + 70 x 5) / 14 = 58.5714...
        assertThat(RatingCalculator.rate(scores("40", "50", "60", "70")).rating()).isEqualByComparingTo("58.57");
    }

    @Test
    void five_seasons_use_weights_one_to_five() {
        // (30 x 1 + 40 x 2 + 50 x 3 + 60 x 4 + 70 x 5) / 15 = 56.666...
        RatingCalculator.Rating r = RatingCalculator.rate(scores("30", "40", "50", "60", "70"));

        assertThat(r.rating()).isEqualByComparingTo("56.67");
        assertThat(r.seasonsUsed()).isEqualTo(5);
    }

    @Test
    void a_sixth_season_drops_the_oldest() {
        List<BigDecimal> window = scores("30", "40", "50", "60", "70");
        List<BigDecimal> withOlder = new ArrayList<>(scores("99"));
        withOlder.addAll(window);

        RatingCalculator.Rating r = RatingCalculator.rate(withOlder);

        assertThat(r.rating()).isEqualByComparingTo(RatingCalculator.rate(window).rating());
        assertThat(r.seasonsUsed()).isEqualTo(5);
    }

    @Test
    void a_bad_newest_season_lowers_the_rating() {
        BigDecimal before = RatingCalculator.rate(scores("70", "70", "70", "70", "70")).rating();
        BigDecimal after = RatingCalculator.rate(scores("70", "70", "70", "70", "70", "10")).rating();

        assertThat(after).isLessThan(before);
        // (70 x 1 + 70 x 2 + 70 x 3 + 70 x 4 + 10 x 5) / 15 = 50
        assertThat(after).isEqualByComparingTo("50.00");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 9})
    void equal_scores_give_the_same_rating_whatever_the_count(int count) {
        assertThat(RatingCalculator.rate(Collections.nCopies(count, new BigDecimal("66.66"))).rating())
                .isEqualByComparingTo("66.66");
    }

    @Test
    void the_rating_is_rounded_once_half_up() {
        // (0 x 4 + 0.01 x 5) / 9 = 0.00555... -> 0.01 ; (0.01 x 4 + 0 x 5) / 9 = 0.00444... -> 0.00
        assertThat(RatingCalculator.rate(scores("0", "0.01")).rating()).isEqualByComparingTo("0.01");
        assertThat(RatingCalculator.rate(scores("0.01", "0")).rating()).isEqualByComparingTo("0.00");
    }

    @Test
    void the_rating_is_shown_from_the_third_counted_season() {
        assertThat(RatingCalculator.isShown(1)).isFalse();
        assertThat(RatingCalculator.isShown(2)).isFalse();
        assertThat(RatingCalculator.isShown(3)).isTrue();
        assertThat(RatingCalculator.isShown(7)).isTrue();
    }

    @Test
    void an_empty_list_is_refused() {
        assertThatThrownBy(() -> RatingCalculator.rate(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RatingCalculator.rate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scores_outside_zero_to_one_hundred_or_null_are_refused() {
        assertThatThrownBy(() -> RatingCalculator.rate(scores("100.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RatingCalculator.rate(scores("-0.01"))).isInstanceOf(IllegalArgumentException.class);
        List<BigDecimal> withNull = new ArrayList<>(scores("50"));
        withNull.add(null);
        assertThatThrownBy(() -> RatingCalculator.rate(withNull)).isInstanceOf(IllegalArgumentException.class);
    }
}
