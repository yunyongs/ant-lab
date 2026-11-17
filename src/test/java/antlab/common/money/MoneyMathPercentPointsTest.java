package antlab.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** {@code MoneyMath.percentPoints} — SQL {@code round(ratio * 100, 0)}: ties go away from zero. */
class MoneyMathPercentPointsTest {

    private static String pp(String ratio) {
        return MoneyMath.percentPoints(new BigDecimal(ratio)).toPlainString();
    }

    @Test
    void rounds_half_away_from_zero_to_a_whole_number() {
        assertThat(pp("0.18234567")).isEqualTo("18");
        assertThat(pp("0.005")).isEqualTo("1");
        assertThat(pp("-0.005")).isEqualTo("-1");
        assertThat(pp("0.00499999")).isEqualTo("0");
        assertThat(pp("-0.00499999")).isEqualTo("0");
        assertThat(pp("0.00000000")).isEqualTo("0");
        assertThat(pp("1.2349")).isEqualTo("123");
    }

    @Test
    void a_null_ratio_is_rejected() {
        assertThatThrownBy(() -> MoneyMath.percentPoints(null)).isInstanceOf(RuntimeException.class);
    }
}
