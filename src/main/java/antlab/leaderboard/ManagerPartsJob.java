package antlab.leaderboard;

import antlab.leaderboard.ManagerPartsService.Attempt;
import antlab.leaderboard.ManagerPartsService.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One tick of the manager parts schedule: one pending season, earliest finish first, skipping seasons that failed lately.
 * A failure doubles that season's wait (capped); a stored row or an exclusion clears it. An engine outage stops the tick
 * and adds no wait, so the same season is tried first next time. The backoff lives in memory only: a restart tries every
 * season again, which is safe because a stored row is never replaced.
 */
public class ManagerPartsJob {

    private static final Logger LOG = LoggerFactory.getLogger(ManagerPartsJob.class);

    private record Backoff(int failures, Instant until) {
    }

    private final ManagerPartsService service;
    private final Clock clock;
    private final Duration delay;
    private final Duration maxBackoff;
    private final Map<Long, Backoff> backoff = new ConcurrentHashMap<>();

    public ManagerPartsJob(ManagerPartsService service, Clock clock, ManagerPartsJobProperties properties) {
        this.service = service;
        this.clock = clock;
        this.delay = properties.delay();
        this.maxBackoff = properties.maxBackoff();
    }

    /** Never throws: a scheduled task that throws is logged by the scheduler with the exception text. */
    public void tick() {
        try {
            Instant now = clock.instant();
            List<Long> waiting = backoff.entrySet().stream().filter(e -> e.getValue().until().isAfter(now))
                    .map(Map.Entry::getKey).toList();
            ManagerPartsService.Run run = service.run(1, waiting);
            for (Attempt attempt : run.attempts()) {
                if (attempt.outcome() == Outcome.FAILED) {
                    backoff.merge(attempt.seasonId(), new Backoff(1, now.plus(wait(1))),
                            (old, first) -> new Backoff(old.failures() + 1, now.plus(wait(old.failures() + 1))));
                } else {
                    backoff.remove(attempt.seasonId());
                }
            }
        } catch (RuntimeException e) {
            LOG.error("manager_parts.tick_failed reason={}", e.getClass().getSimpleName());
        }
    }

    /** {@code delay × 2^failures}, at most {@code maxBackoff}: the first failure skips the next tick. */
    Duration wait(int failures) {
        Duration wait = delay;
        for (int i = 0; i < failures && wait.compareTo(maxBackoff) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }

    /** Seasons the job waits on now (tests). */
    int waitingSeasons() {
        Instant now = clock.instant();
        return (int) backoff.values().stream().filter(b -> b.until().isAfter(now)).count();
    }
}
