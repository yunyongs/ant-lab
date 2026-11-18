package antlab.collect.fred;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of a FRED observations answer: the reference date, the value (null where FRED has no value, ".") and the
 * vintage period the value held for ({@code realtimeEnd} is 9999-12-31 while it is current).
 */
public record FredObservation(LocalDate refDate, BigDecimal value, LocalDate realtimeStart, LocalDate realtimeEnd) {
}
