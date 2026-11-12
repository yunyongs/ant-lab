package antlab.season;

import java.time.LocalDate;

/** One turn's real month, as read by the review month query of {@code SeasonPathRepository}. Carries no block id. */
public record RevealedMonthRow(int turnNo, LocalDate monthStart, String eventSummary) {
}
