package antlab.history;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One {@code asset_month_return} row of one asset: the calendar month and the local-currency return exactly as a
 * NUMERIC(12,8) column returns it. PostgreSQL's division scale depends on the input scale, so the scale is part of the contract:
 * the constructor accepts only what the column can hold (scale 8, at most 12 digits, not below -1, mirroring
 * {@code ck_asset_month_return_local_return}) and rejects anything else instead of rounding it silently.
 */
public record MonthReturn(YearMonth month, BigDecimal localReturn) {

    /** NUMERIC(12,8) keeps four integer digits, so the magnitude stays below 10000. */
    private static final BigDecimal LIMIT = new BigDecimal("10000");
    private static final BigDecimal MINUS_ONE = new BigDecimal("-1");

    public MonthReturn {
        if (month == null || localReturn == null) {
            throw new IllegalArgumentException("month and localReturn are required");
        }
        if (localReturn.scale() != 8) {
            throw new IllegalArgumentException("localReturn must have exactly 8 decimal places (NUMERIC(12,8))");
        }
        if (localReturn.compareTo(MINUS_ONE) < 0 || localReturn.compareTo(LIMIT) >= 0) {
            throw new IllegalArgumentException("localReturn must be at least -1 and below 10000 (NUMERIC(12,8))");
        }
    }
}
