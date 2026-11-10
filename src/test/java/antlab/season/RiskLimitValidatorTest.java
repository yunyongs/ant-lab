package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RiskLimitValidatorTest {

    private final AllowedRiskLimit.Validator validator = new AllowedRiskLimit.Validator();

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "-0.10", "-0.100000", "-0.2", "-0.20", "-0.3", "-0.4", "-0.40000000"})
    void 허용_네_값은_표기가_달라도_통과한다(String value) {
        assertThat(validator.isValid(new BigDecimal(value), null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.2", "-0.25", "-0.5", "-0.05", "-1", "-0.200000001", "-0.0999"})
    void 목록_밖은_거절한다(String value) {
        assertThat(validator.isValid(new BigDecimal(value), null)).isFalse();
    }

    @Test
    void null은_NotNull이_따로_거절하므로_여기서는_통과한다() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
