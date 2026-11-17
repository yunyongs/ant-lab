package antlab.scoring;

import java.math.BigDecimal;

/**
 * Constants and price tests of the trading-discipline item (PLAN §6.2): one place for the thresholds, so that the
 * calculator, the tests and the oracle read the same numbers. All comparisons are exact: a cross-multiplication on
 * won prices, boundaries included, no division and no rounding.
 */
public final class DisciplineRules {

    /** A fall of at least this many percent in the month just shown is a drop (boundary included). */
    public static final int DROP_PERCENT = 8;
    /** A rise of at least this many percent in the month just shown is a rise (boundary included). */
    public static final int RISE_PERCENT = 8;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DisciplineRules() {
    }

    /** {@code 100 * (current - previous) <= -DROP_PERCENT * previous}; a non-positive previous value never drops. */
    public static boolean dropped(BigDecimal previous, BigDecimal current) {
        return previous.signum() > 0 && change(previous, current).compareTo(scaled(-DROP_PERCENT, previous)) <= 0;
    }

    /** {@code 100 * (current - previous) >= RISE_PERCENT * previous}; a non-positive previous value never rises. */
    public static boolean rose(BigDecimal previous, BigDecimal current) {
        return previous.signum() > 0 && change(previous, current).compareTo(scaled(RISE_PERCENT, previous)) >= 0;
    }

    private static BigDecimal change(BigDecimal previous, BigDecimal current) {
        return HUNDRED.multiply(current.subtract(previous));
    }

    private static BigDecimal scaled(int percent, BigDecimal previous) {
        return BigDecimal.valueOf(percent).multiply(previous);
    }
}
