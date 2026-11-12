package antlab.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code MoneyMath.fractionMean} — 공통 분모로 합쳐 마지막에 한 번 나누는 정확한 평균(db/26 {@code sr_frac_mean} 의 짝, 검증 14절 합성 사례). */
class MoneyMathFractionMeanTest {

    private static List<BigInteger> ints(long... values) {
        return java.util.Arrays.stream(values).mapToObj(BigInteger::valueOf).toList();
    }

    @Test
    void 세_분수가_모두_이분의_일이면_정확히_절반이다() {
        assertThat(MoneyMath.fractionMean(ints(1, 2, 3), ints(2, 4, 6)).toPlainString()).isEqualTo("0.50000000");
    }

    @Test
    void 턴별로_먼저_반올림한_평균과_다른_사례에서_정확한_평균을_낸다() {
        // [1/(2e8), 0/1] 의 평균 = 2.5e-9 → 0.00000000.
        // 분수마다 먼저 8자리로 반올림하면 (0.00000001 + 0) ÷ 2 = 5e-9 → 0.00000001 로 갈린다.
        BigDecimal exact = MoneyMath.fractionMean(ints(1, 0), ints(200_000_000, 1));
        assertThat(exact.toPlainString()).isEqualTo("0.00000000");
        BigDecimal perTurnRounded = MoneyMath.ratio(BigDecimal.ONE, BigDecimal.valueOf(200_000_000))
                .add(MoneyMath.ratio(BigDecimal.ZERO, BigDecimal.ONE))
                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        assertThat(perTurnRounded.toPlainString()).isEqualTo("0.00000001");
        assertThat(exact).isNotEqualByComparingTo(perTurnRounded);
    }

    @Test
    void 분수_하나는_그대로_비율이다() {
        assertThat(MoneyMath.fractionMean(ints(1), ints(3)).toPlainString()).isEqualTo("0.33333333");
        assertThat(MoneyMath.fractionMean(ints(0), ints(5)).toPlainString()).isEqualTo("0.00000000");
    }

    @Test
    void 약분하지_않은_분수도_같은_값이다() {
        assertThat(MoneyMath.fractionMean(ints(1, 2), ints(3, 6)))
                .isEqualByComparingTo(MoneyMath.fractionMean(ints(1, 1), ints(3, 3)));
    }

    @Test
    void 음성_빈_목록_길이_불일치_영_분모_널을_거부한다() {
        assertThatThrownBy(() -> MoneyMath.fractionMean(ints(), ints())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.fractionMean(ints(1, 2), ints(3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.fractionMean(ints(1, 2), ints(3, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.fractionMean(null, ints(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.fractionMean(java.util.Arrays.asList((BigInteger) null), ints(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
