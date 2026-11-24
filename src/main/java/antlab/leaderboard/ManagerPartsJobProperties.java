package antlab.leaderboard;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the manager parts job. {@code enabled} switches the schedule on (false in tests); {@code delay} is the
 * pause between two ticks (one season per tick); a season that failed waits {@code delay × 2^failures}, at most
 * {@code maxBackoff}, before the job tries it again.
 */
@ConfigurationProperties("antlab.leaderboard.parts-job")
public record ManagerPartsJobProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("60s") Duration delay,
        @DefaultValue("1h") Duration maxBackoff) {

    public ManagerPartsJobProperties {
        if (delay.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("antlab.leaderboard.parts-job.delay must be at least 1s");
        }
        if (maxBackoff.compareTo(delay) < 0) {
            throw new IllegalArgumentException("antlab.leaderboard.parts-job.max-backoff must be at least the delay");
        }
    }
}
