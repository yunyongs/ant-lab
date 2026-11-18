package antlab.collect.series;

import java.time.LocalDate;

/** How often a series is observed — series.frequency. Constants equal the DB CHECK list letter for letter. */
public enum SeriesFrequency {
    DAILY, WEEKLY, MONTHLY;

    /** True when {@code refDate} lies more than two release periods before {@code date}. */
    public boolean olderThanTwoPeriods(LocalDate refDate, LocalDate date) {
        LocalDate limit = switch (this) {
            case DAILY -> refDate.plusDays(2);
            case WEEKLY -> refDate.plusWeeks(2);
            case MONTHLY -> refDate.plusMonths(2);
        };
        return limit.isBefore(date);
    }
}
