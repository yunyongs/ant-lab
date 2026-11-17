package antlab.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The single-asset limit compares strictly: exactly on the limit is allowed. */
class OrderServiceLimitTest {

    private static final BigDecimal LIMIT = new BigDecimal("0.10");

    @Test
    void a_value_on_the_limit_is_allowed_and_one_above_it_is_not() {
        BigDecimal total = new BigDecimal("1000000");
        assertThat(OrderService.exceeds(new BigDecimal("100000"), total, LIMIT)).isFalse();
        assertThat(OrderService.exceeds(new BigDecimal("100001"), total, LIMIT)).isTrue();
        assertThat(OrderService.exceeds(new BigDecimal("99999"), total, LIMIT)).isFalse();
    }
}
