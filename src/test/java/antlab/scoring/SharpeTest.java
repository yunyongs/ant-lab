package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharpeTest {

    @Test
    void uses_the_sample_deviation_with_n_minus_one() {
        // returns 0.01 and 0.03: mean 0.02, sample deviation sqrt(0.0002) = 0.0141421356..., ratio 1.41421356
        assertThat(Sharpe.stored(List.of(new BigDecimal("0.01"), new BigDecimal("0.03"))))
                .isEqualByComparingTo("1.41421356");
    }

    @Test
    void equal_returns_give_zero_even_when_the_mean_is_not_representable() {
        BigDecimal third = BigDecimal.ONE.divide(BigDecimal.valueOf(3), 40, java.math.RoundingMode.HALF_UP);

        assertThat(Sharpe.stored(List.of(third, third, third, third))).isEqualByComparingTo("0");
    }

    @Test
    void a_negative_mean_gives_a_negative_ratio() {
        assertThat(Sharpe.stored(List.of(new BigDecimal("-0.01"), new BigDecimal("-0.03"))))
                .isEqualByComparingTo("-1.41421356");
    }

    @Test
    void fewer_than_two_returns_are_refused() {
        assertThatThrownBy(() -> Sharpe.stored(List.of(new BigDecimal("0.01"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sharpe.stored(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
