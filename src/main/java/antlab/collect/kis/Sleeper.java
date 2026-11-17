package antlab.collect.kis;

import java.time.Duration;

/** Waiting behind an interface so tests run on a fake clock without real sleeping. */
@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = duration -> {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KisException("KIS_INTERRUPTED", "interrupted while waiting for the KIS rate limit");
        }
    };

    void sleep(Duration duration);
}
