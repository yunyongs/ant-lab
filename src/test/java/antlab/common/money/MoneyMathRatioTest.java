package antlab.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** {@code MoneyMath.ratio} — 정수 ÷ 정수를 소수 8자리로 한 번, 0에서 먼 쪽 HALF_UP(db/26 sr_div8 의 짝). */
class MoneyMathRatioTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 나누어떨어지지_않으면_여덟_자리에서_반올림한다() {
        assertThat(MoneyMath.ratio(bd("1"), bd("3")).toPlainString()).isEqualTo("0.33333333");
        assertThat(MoneyMath.ratio(bd("2"), bd("3")).toPlainString()).isEqualTo("0.66666667");
        assertThat(MoneyMath.ratio(bd("1"), bd("1")).toPlainString()).isEqualTo("1.00000000");
        assertThat(MoneyMath.ratio(bd("0"), bd("7")).toPlainString()).isEqualTo("0.00000000");
    }

    @Test
    void 동률은_양수도_음수도_0에서_먼_쪽이다() {
        // 1 ÷ 200,000,000 = 0.000000005 (정확히 아홉째 자리 5)
        assertThat(MoneyMath.ratio(bd("1"), bd("200000000")).toPlainString()).isEqualTo("0.00000001");
        assertThat(MoneyMath.ratio(bd("-1"), bd("200000000")).toPlainString()).isEqualTo("-0.00000001");
        assertThat(MoneyMath.ratio(bd("1"), bd("-200000000")).toPlainString()).isEqualTo("-0.00000001");
        assertThat(MoneyMath.ratio(bd("-1"), bd("-200000000")).toPlainString()).isEqualTo("0.00000001");
        assertThat(MoneyMath.ratio(bd("3"), bd("200000000")).toPlainString()).isEqualTo("0.00000002");
    }

    @Test
    void 이미_정수인_scale_4_입력도_받는다() {
        assertThat(MoneyMath.ratio(bd("1000.0000"), bd("3000.0000")).toPlainString()).isEqualTo("0.33333333");
        assertThat(MoneyMath.ratio(bd("-5E+2"), bd("2000")).toPlainString()).isEqualTo("-0.25000000");
    }

    @Test
    void 영으로_나누거나_소수부가_있거나_null이면_예외다() {
        assertThatThrownBy(() -> MoneyMath.ratio(bd("1"), bd("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.ratio(bd("1"), bd("0.0000"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.ratio(bd("1.5"), bd("3"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.ratio(bd("1"), bd("2.0001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.ratio(null, bd("3"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.ratio(bd("1"), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
