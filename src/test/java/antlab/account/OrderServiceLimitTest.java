package antlab.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** The single-asset limit compares strictly: exactly on the limit is allowed. The quote's largest buy stays on that edge. */
class OrderServiceLimitTest {

    private static final BigDecimal LIMIT = new BigDecimal("0.10");

    @Test
    void a_value_on_the_limit_is_allowed_and_one_above_it_is_not() {
        BigDecimal total = new BigDecimal("1000000");
        assertThat(OrderService.exceeds(new BigDecimal("100000"), total, LIMIT)).isFalse();
        assertThat(OrderService.exceeds(new BigDecimal("100001"), total, LIMIT)).isTrue();
        assertThat(OrderService.exceeds(new BigDecimal("99999"), total, LIMIT)).isFalse();
    }

    private static int max(String balance, String reserved, String price, String rate, int held, String others,
            BigDecimal limit) {
        return OrderService.maxBuyQuantity(new BigDecimal(balance), new BigDecimal(reserved), new BigDecimal(price),
                new BigDecimal(rate), held, new BigDecimal(others), limit);
    }

    @Test
    void the_largest_buy_is_the_last_quantity_whose_amount_and_rounded_fee_fit_the_cash() {
        assertThat(max("30000000", "0", "150000", "0.00015000", 0, "0", null)).isEqualTo(199);
        assertThat(max("30000000", "0", "648000", "0.00650000", 0, "0", null)).isEqualTo(45);
        // reserved cash is not available
        assertThat(max("30000000", "1000000", "150000", "0.00015000", 0, "0", null)).isEqualTo(193);
        assertThat(max("149999", "0", "150000", "0.00015000", 0, "0", null)).isZero();
        assertThat(max("0", "0", "150000", "0.00015000", 0, "0", null)).isZero();
    }

    @Test
    void the_search_agrees_with_a_plain_scan_where_fee_rounding_decides_the_edge() {
        // price 333, rate 0.0015: the fee is 0.4995 per share, so half-up rounding moves the edge share by share
        BigDecimal price = new BigDecimal("333");
        BigDecimal rate = new BigDecimal("0.00150000");
        for (int cash = 0; cash <= 20_000; cash += 7) {
            int expected = 0;
            for (int q = 1; ; q++) {
                BigDecimal notional = price.multiply(BigDecimal.valueOf(q));
                BigDecimal fee = notional.multiply(rate).setScale(0, RoundingMode.HALF_UP);
                if (notional.add(fee).compareTo(BigDecimal.valueOf(cash)) > 0) {
                    break;
                }
                expected = q;
            }
            assertThat(max(Integer.toString(cash), "0", "333", "0.00150000", 0, "0", null)).as("cash " + cash)
                    .isEqualTo(expected);
        }
    }

    @Test
    void with_a_limit_the_largest_buy_stops_where_the_limit_check_would_refuse() {
        // total = 1,000,000 cash; 10 shares of 10,000 are exactly 10 % and allowed
        assertThat(max("1000000", "0", "10000", "0", 0, "0", LIMIT)).isEqualTo(10);
        // a 1 % fee lowers the total: 10 shares = 100,000 > 10 % of 999,000, so 9
        assertThat(max("1000000", "0", "10000", "0.01000000", 0, "0", LIMIT)).isEqualTo(9);
        // shares already held count toward the asset; other holdings raise the total
        assertThat(max("1000000", "0", "10000", "0", 4, "0", LIMIT)).isEqualTo(6);
        assertThat(max("1000000", "0", "10000", "0", 0, "1000000", LIMIT)).isEqualTo(20);
        // already above the limit: not one more share
        assertThat(max("100000", "0", "10000", "0", 10, "0", LIMIT)).isZero();
    }
}
