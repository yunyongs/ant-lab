package antlab.collect.kis;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One session close in the local currency, exactly as KIS reports it: positive, at most 4 decimals. */
public record DailyClose(LocalDate tradeDate, BigDecimal close) {

    public DailyClose {
        if (tradeDate == null || close == null || close.signum() <= 0 || close.scale() > 4) {
            throw new IllegalArgumentException("a close must be positive with at most 4 decimals");
        }
    }
}
