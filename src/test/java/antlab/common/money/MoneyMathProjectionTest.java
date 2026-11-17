package antlab.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Money helpers of the projection: the share rounds down at 12 digits, the won value rounds half up once. */
class MoneyMathProjectionTest {

    @Test
    void 비중은_12자리에서_내린다() {
        assertThat(MoneyMath.shareDown(new BigDecimal("2"), new BigDecimal("3"))).isEqualTo(new BigDecimal("0.666666666666"));
        assertThat(MoneyMath.shareDown(new BigDecimal("1"), new BigDecimal("3"))).isEqualTo(new BigDecimal("0.333333333333"));
        assertThat(MoneyMath.shareDown(new BigDecimal("5"), new BigDecimal("5"))).isEqualTo(new BigDecimal("1.000000000000"));
        assertThat(MoneyMath.shareDown(BigDecimal.ZERO, new BigDecimal("5"))).isEqualTo(new BigDecimal("0.000000000000"));
        assertThat(MoneyMath.shareDown(new BigDecimal("1"), new BigDecimal("999999999999999"))).isEqualTo(new BigDecimal("0.000000000000"));
    }

    @Test
    void 비중_입력이_틀리면_거절한다() {
        assertThatThrownBy(() -> MoneyMath.shareDown(new BigDecimal("4"), new BigDecimal("3")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.shareDown(new BigDecimal("1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.shareDown(new BigDecimal("1.5"), new BigDecimal("3")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.shareDown(new BigDecimal("-1"), new BigDecimal("3")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 원화_값은_배수를_곱해_한_번만_반올림한다() {
        assertThat(MoneyMath.scaleByMultiple(new BigDecimal("10000000"), new BigDecimal("1.05000000")))
                .isEqualTo(new BigDecimal("10500000"));
        assertThat(MoneyMath.scaleByMultiple(new BigDecimal("1234567"), new BigDecimal("0.87319374")))
                .isEqualTo(new BigDecimal("1078016"));
    }

    @Test
    void 정확히_반_원이면_올린다() {
        // 3 x 0.50000000 = 1.5 -> 2 ; 1 x 0.50000000 = 0.5 -> 1 (away from zero on the tie)
        assertThat(MoneyMath.scaleByMultiple(new BigDecimal("3"), new BigDecimal("0.50000000"))).isEqualTo(new BigDecimal("2"));
        assertThat(MoneyMath.scaleByMultiple(new BigDecimal("1"), new BigDecimal("0.50000000"))).isEqualTo(new BigDecimal("1"));
        assertThat(MoneyMath.scaleByMultiple(new BigDecimal("1"), new BigDecimal("0.49999999"))).isEqualTo(new BigDecimal("0"));
    }

    @Test
    void 배수_0은_0원이고_음수_배수는_거절한다() {
        assertThat(MoneyMath.scaleByMultiple(new BigDecimal("777"), new BigDecimal("0.00000000"))).isEqualByComparingTo("0");
        assertThatThrownBy(() -> MoneyMath.scaleByMultiple(new BigDecimal("777"), new BigDecimal("-0.00000001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 총액이_양의_정수가_아니면_거절한다() {
        assertThatThrownBy(() -> MoneyMath.scaleByMultiple(BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.scaleByMultiple(new BigDecimal("1.5"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 범위를_넘는_원화_값은_거절한다() {
        assertThatThrownBy(() -> MoneyMath.scaleByMultiple(new BigDecimal("999999999999999"), new BigDecimal("1000.00000000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
