package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S and L by hand: exact S, trimming only at m = 10, sample sd, one rounding at the end. */
class CareerScoreTest {

    static final ManagerScoreFormula S69 = new ManagerScoreFormula("s69-v1", ManagerScoreFormulaStatus.PROVISIONAL,
            new BigDecimal("0.30"), new BigDecimal("0.30"), new BigDecimal("0.15"), new BigDecimal("0.25"));

    private static List<BigDecimal> values(String... v) {
        return Arrays.stream(v).map(BigDecimal::new).toList();
    }

    @Test
    void S는_가중합을_반올림하지_않는다() {
        BigDecimal s = CareerScore.seasonScore(S69, new BigDecimal("12.34567891"), new BigDecimal("98.76543211"),
                new BigDecimal("50.00000001"), new BigDecimal("33.33"));
        // 3.703703673 + 29.629629633 + 7.5000000015 + 8.3325
        assertThat(s).isEqualTo(new BigDecimal("49.1658333075"));
    }

    @Test
    void 열_시즌_미만은_자르지_않고_표본_표준편차를_뺀다() {
        CareerScore.Result r = CareerScore.of(values("40", "50", "60"));
        assertThat(r.trimEachSide()).isZero();
        assertThat(r.trimmedMean()).isEqualByComparingTo("50");
        assertThat(r.sampleSd()).isEqualByComparingTo("10");
        assertThat(r.l()).isEqualTo(new BigDecimal("45.00"));
        assertThat(CareerScore.of(values("10", "20", "30", "40", "50", "60", "70", "80", "90")).trimEachSide()).isZero();
    }

    @Test
    void 열_시즌이면_양쪽에서_하나씩_자른다() {
        CareerScore.Result r = CareerScore.of(values("0", "10", "20", "30", "40", "50", "60", "70", "80", "1000"));
        assertThat(r.trimEachSide()).isEqualTo(1);
        assertThat(r.trimmedMean()).isEqualByComparingTo("45");
    }

    @Test
    void 모두_같으면_표준편차는_0이다() {
        CareerScore.Result r = CareerScore.of(values("61.25", "61.25", "61.25", "61.25"));
        assertThat(r.sampleSd()).isEqualByComparingTo("0");
        assertThat(r.l()).isEqualTo(new BigDecimal("61.25"));
    }

    @Test
    void L은_끝에서_한_번만_반올림하고_반은_올린다() {
        assertThat(CareerScore.of(values("50.005", "50.005", "50.005")).l()).isEqualTo(new BigDecimal("50.01"));
        // mean 50.005, sd 0.00346410…: exact L 50.00326795 → 50.00 (rounding the mean first would give 50.01)
        CareerScore.Result r = CareerScore.of(values("50.003", "50.003", "50.009"));
        assertThat(r.lExact()).isLessThan(new BigDecimal("50.005"));
        assertThat(r.l()).isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    void L은_음수일_수_있다() {
        assertThat(CareerScore.of(values("0", "0", "0", "0", "100")).l().signum()).isNegative();
    }

    @Test
    void 시즌_수가_범위를_벗어나면_거절한다() {
        assertThatThrownBy(() -> CareerScore.of(values("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CareerScore.of(values("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
