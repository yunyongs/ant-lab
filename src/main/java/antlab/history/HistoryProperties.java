package antlab.history;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * History settings (MVP-06, B13). {@code replayStart} is the first month a season path may draw (erd S1); the end is January 1
 * of the base-date year. Months before it are warm-up only. The default is the Phase 0 sample start; the measured value from the
 * coverage report replaces it when the full history is loaded. {@code db/20} reads the same value through the psql variable
 * {@code replay_start}.
 */
@ConfigurationProperties("antlab.history")
public record HistoryProperties(@DefaultValue("2007-01-01") LocalDate replayStart) {

    public static final LocalDate DEFAULT_REPLAY_START = LocalDate.of(2007, 1, 1);

    public HistoryProperties {
        Objects.requireNonNull(replayStart, "antlab.history.replay-start");
        if (replayStart.getDayOfMonth() != 1) {
            throw new IllegalArgumentException("antlab.history.replay-start " + replayStart + " must be the first day of a month");
        }
    }

    public static HistoryProperties defaults() {
        return new HistoryProperties(DEFAULT_REPLAY_START);
    }

    /** The replay start must lie before January 1 of the base-date year (the end of the replay range). */
    public void requireBefore(LocalDate baseDate) {
        LocalDate upper = LocalDate.of(Objects.requireNonNull(baseDate, "baseDate").getYear(), 1, 1);
        if (!replayStart.isBefore(upper)) {
            throw new IllegalArgumentException("antlab.history.replay-start " + replayStart + " must be before " + upper
                    + " (January 1 of the base-date year of " + baseDate + ")");
        }
    }
}
