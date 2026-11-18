package antlab.collect.ecos;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One value of an ECOS answer: its reference date (the first day for a monthly value) and the exact decimal. */
public record EcosObservation(LocalDate refDate, BigDecimal value) {
}
