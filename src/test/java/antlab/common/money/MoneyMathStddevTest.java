package antlab.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code MoneyMath.sampleStddev8}·{@code pad40Divide} 의 손 계산 사례. PostgreSQL 과의 대조는 MoneyMathSqlParityTest 가 한다. */
class MoneyMathStddevTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static List<BigDecimal> list(String... v) {
        return java.util.Arrays.stream(v).map(BigDecimal::new).toList();
    }

    @Test
    void 표본_표준편차는_n_빼기_1로_나눈다() {
        // 1,2,3,4 → 평균 2.5, 제곱합 5, 분산 5/3, 표준편차 1.29099445
        assertThat(MoneyMath.sampleStddev8(list("1", "2", "3", "4")).toPlainString()).isEqualTo("1.29099445");
        // 두 값 0, 1 → 분산 0.5 → 0.70710678
        assertThat(MoneyMath.sampleStddev8(list("0", "1")).toPlainString()).isEqualTo("0.70710678");
    }

    @Test
    void 값이_모두_같으면_영이다() {
        assertThat(MoneyMath.sampleStddev8(list("0.05", "0.05", "0.05")).toPlainString()).isEqualTo("0.00000000");
    }

    @Test
    void 값이_둘보다_적으면_널이다() {
        assertThat(MoneyMath.sampleStddev8(list())).isNull();
        assertThat(MoneyMath.sampleStddev8(list("0.1"))).isNull();
    }

    @Test
    void 결과는_늘_소수_8자리다() {
        assertThat(MoneyMath.sampleStddev8(list("0.01", "0.02", "-0.03")).scale()).isEqualTo(8);
        assertThat(MoneyMath.sampleStddev8(list("5", "5")).scale()).isEqualTo(8);
    }

    @Test
    void 널_원소를_거부한다() {
        assertThatThrownBy(() -> MoneyMath.sampleStddev8(java.util.Arrays.asList(bd("1"), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pad40Divide_는_소수_40자리_이상에서_나눈다() {
        // 1/3 → 소수 40자리
        BigDecimal third = MoneyMath.pad40Divide(bd("1"), bd("3"));
        assertThat(third.scale()).isEqualTo(40);
        assertThat(third.toPlainString()).isEqualTo("0." + "3".repeat(40));
        assertThat(MoneyMath.pad40Divide(bd("2"), bd("3")).toPlainString()).isEqualTo("0." + "6".repeat(39) + "7");
        // 분자 scale 이 40보다 크면 그 자리를 따른다
        assertThat(MoneyMath.pad40Divide(bd("1." + "0".repeat(45)), bd("4")).scale()).isEqualTo(45);
        // 분모 scale 이 40보다 크면 그 자리를 따른다
        assertThat(MoneyMath.pad40Divide(bd("1"), bd("4." + "0".repeat(42))).scale()).isEqualTo(42);
    }

    @Test
    void pad40Divide_는_부호와_정확한_절반_올림을_지킨다() {
        assertThat(MoneyMath.pad40Divide(bd("-1"), bd("3")).toPlainString()).isEqualTo("-0." + "3".repeat(40));
        assertThat(MoneyMath.pad40Divide(bd("1"), bd("-3")).toPlainString()).isEqualTo("-0." + "3".repeat(40));
        // (1 + 1e-40) / 2 = 0.5 + 5e-41 — 41번째 자리가 정확히 5, 소수 40자리에서 0에서 먼 쪽으로 올린다
        BigDecimal tie = MoneyMath.pad40Divide(bd("1." + "0".repeat(39) + "1"), bd("2"));
        assertThat(tie.toPlainString()).isEqualTo("0.5" + "0".repeat(38) + "1");
        BigDecimal negativeTie = MoneyMath.pad40Divide(bd("-1." + "0".repeat(39) + "1"), bd("2"));
        assertThat(negativeTie.toPlainString()).isEqualTo("-0.5" + "0".repeat(38) + "1");
    }

    @Test
    void pad40Divide_는_작은_몫에서_자릿수가_늘어난다() {
        // 몫이 1e-24 보다 훨씬 작으면 16 − 4·지수 가 40을 넘는다(PostgreSQL select_div_scale)
        BigDecimal tiny = MoneyMath.pad40Divide(bd("1"), bd("1" + "0".repeat(30)));
        assertThat(tiny.scale()).isGreaterThan(40);
    }

    @Test
    void pad40Divide_음성_영_분모_널() {
        assertThatThrownBy(() -> MoneyMath.pad40Divide(bd("1"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.pad40Divide(null, bd("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.pad40Divide(bd("1"), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
