package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** {@link RuleResult#of} 의 갈래 — 합성 값(시세 아님). */
class RuleResultTest {

    private static RuleRow row(String expected, String actual, Integer count, String detail) {
        return new RuleRow() {
            public BigDecimal getExpectedValue() {
                return expected == null ? null : new BigDecimal(expected);
            }

            public BigDecimal getActualValue() {
                return actual == null ? null : new BigDecimal(actual);
            }

            public Integer getMismatchCount() {
                return count;
            }

            public String getDetail() {
                return detail;
            }
        };
    }

    @Test
    void 스칼라_일치는_분류와_detail이_없고_차이는_0이다() {
        RuleResult r = RuleResult.of(2, row("100.0000", "100.0000", 0, null));
        assertThat(r.status()).isEqualTo(ReconciliationStatus.MATCH);
        assertThat(r.checkCode()).isEqualTo(CheckCode.CASH_SUM);
        assertThat(r.diffValue()).isEqualByComparingTo("0");
        assertThat(r.classification()).isNull();
        assertThat(r.detail()).isNull();
        assertThat(r.mismatchCount()).isZero();
    }

    @Test
    void 스칼라_불일치는_차이가_실제_빼기_기대이고_자릿수를_보존한다() {
        RuleResult r = RuleResult.of(2, row("100.0000", "101.5000", 1, "{\"items\":[]}"));
        assertThat(r.status()).isEqualTo(ReconciliationStatus.MISMATCH);
        assertThat(r.diffValue()).isEqualByComparingTo("1.5").extracting(BigDecimal::scale).isEqualTo(4);
        assertThat(r.classification()).isEqualTo(ReconciliationClassification.INVESTIGATE);
        assertThat(r.detail()).isEqualTo("{\"items\":[]}");
    }

    @Test
    void 비스칼라_불일치는_세_값이_없고_차이도_없다() {
        RuleResult r = RuleResult.of(3, row(null, null, 2, "{\"total\":2}"));
        assertThat(r.expectedValue()).isNull();
        assertThat(r.actualValue()).isNull();
        assertThat(r.diffValue()).isNull();
        assertThat(r.status()).isEqualTo(ReconciliationStatus.MISMATCH);
        assertThat(r.classification()).isEqualTo(ReconciliationClassification.INVESTIGATE);
    }

    @Test
    void 비교_대상이_없는_스칼라는_세_값이_모두_없고_불일치_1이다() {
        RuleResult r = RuleResult.of(9, row(null, null, 1, "{\"total\":1}"));
        assertThat(r.diffValue()).isNull();
        assertThat(r.mismatchCount()).isEqualTo(1);
        assertThat(r.status()).isEqualTo(ReconciliationStatus.MISMATCH);
    }

    @Test
    void 항목이_상쇄돼_차이가_0이어도_건수가_있으면_불일치다() {
        RuleResult r = RuleResult.of(4, row("-500.0000", "-500.0000", 1, "{\"total\":1}"));
        assertThat(r.diffValue()).isEqualByComparingTo("0");
        assertThat(r.status()).isEqualTo(ReconciliationStatus.MISMATCH);
        assertThat(r.classification()).isEqualTo(ReconciliationClassification.INVESTIGATE);
    }

    @Test
    void 일치면_행에_detail이_있어도_버린다() {
        assertThat(RuleResult.of(7, row(null, null, 0, "{\"x\":1}")).detail()).isNull();
    }

    @Test
    void 없는_규칙_번호는_거절한다() {
        assertThatThrownBy(() -> RuleResult.of(5, row(null, null, 0, null))).isInstanceOf(IllegalArgumentException.class);
    }
}
