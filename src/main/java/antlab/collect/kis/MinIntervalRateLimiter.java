package antlab.collect.kis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** At most {@code requestsPerSecond} permits per second: each permit waits until one interval after the previous one. */
final class MinIntervalRateLimiter {

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration interval;
    private Instant nextAllowed;

    MinIntervalRateLimiter(Clock clock, Sleeper sleeper, int requestsPerSecond) {
        if (requestsPerSecond < 1) {
            throw new IllegalArgumentException("requestsPerSecond must be at least 1");
        }
        this.clock = clock;
        this.sleeper = sleeper;
        this.interval = Duration.ofMillis(1000L / requestsPerSecond);
    }

    synchronized void acquire() {
        Instant now = clock.instant();
        if (nextAllowed != null && now.isBefore(nextAllowed)) {
            sleeper.sleep(Duration.between(now, nextAllowed));
            now = clock.instant();
        }
        nextAllowed = now.plus(interval);
    }
}
