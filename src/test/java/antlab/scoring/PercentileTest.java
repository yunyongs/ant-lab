package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PercentileTest {

    @ParameterizedTest
    @CsvSource({
            // below, equal, n, mid-rank percentile
            "0, 0, 2000, 0.00000000",
            "2000, 0, 2000, 1.00000000",
            "0, 2000, 2000, 0.50000000",
            "1200, 100, 2000, 0.62500000",
            "1, 0, 3, 0.33333333",
            "2, 0, 3, 0.66666667",
            "0, 1, 3, 0.16666667",
            "4, 3, 7, 0.78571429"})
    void mid_rank_counts_a_tie_as_half(long below, long equal, long n, String expected) {
        assertThat(Percentile.stored(new RankCounts(below, equal, n))).isEqualByComparingTo(expected);
    }

    @Test
    void the_percentile_is_not_the_plain_share_below() {
        // below / n would give 0.6; mid-rank gives 0.625
        assertThat(Percentile.stored(new RankCounts(1200, 100, 2000))).isNotEqualByComparingTo("0.6");
    }

    @ParameterizedTest
    @CsvSource({"-1, 0, 10", "0, -1, 10", "6, 5, 10", "0, 0, 0", "0, 0, -5", "11, 0, 10"})
    void impossible_counts_are_refused(long below, long equal, long n) {
        assertThatThrownBy(() -> new RankCounts(below, equal, n)).isInstanceOf(IllegalArgumentException.class);
    }
}
