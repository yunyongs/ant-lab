package antlab.season;

import antlab.scoring.ReconciliationFailedException;
import antlab.scoring.ReconciliationLogRepository;
import antlab.scoring.ReconciliationLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the failed-attempt log after the turn transaction rolled back and, in the same transaction, halts the season
 * when the failed attempts of that pending turn reach the threshold. The halt rides this transaction because the turn
 * transaction is rolled back by design: a status write there would vanish, and a third transaction could leave
 * "log written, halt lost". The caller has no transaction, so the REQUIRED propagation opens a new one here.
 */
@Service
public class ReconciliationFailureRecorder {

    /** Result of one record call; {@code haltedNow} is true only for the request that stopped the season. */
    public record Outcome(int attemptNo, int failures, boolean haltedNow) {
    }

    private final ReconciliationLogWriter logWriter;
    private final ReconciliationLogRepository logs;
    private final SeasonRepository seasons;
    private final ReconciliationProperties properties;

    public ReconciliationFailureRecorder(ReconciliationLogWriter logWriter, ReconciliationLogRepository logs,
            SeasonRepository seasons, ReconciliationProperties properties) {
        this.logWriter = logWriter;
        this.logs = logs;
        this.seasons = seasons;
        this.properties = properties;
    }

    @Transactional
    public Outcome record(ReconciliationFailedException failed) {
        // The writer locks the season first and joins this transaction, so the row is locked and already managed here:
        // findById answers from the persistence context without a second lock statement.
        int attemptNo = logWriter.record(failed.getSeasonId(), failed.getTurnNo(), failed.getResults());
        Season season = seasons.findById(failed.getSeasonId()).orElseThrow(() -> new IllegalStateException("시즌이 없다"));
        // A failure that lands after another request already advanced the season is history, not a streak.
        boolean pending = season.getCurrentTurnNo() + 1 == failed.getTurnNo()
                && (season.getStatus() == SeasonStatus.IN_PROGRESS || season.getStatus() == SeasonStatus.ABANDONING);
        if (!pending) {
            return new Outcome(attemptNo, 0, false);
        }
        int failures = logs.countFailedAttempts(failed.getSeasonId(), failed.getTurnNo());
        boolean halt = failures >= properties.haltAfterFailures();
        if (halt) {
            season.halt();
        }
        return new Outcome(attemptNo, failures, halt);
    }
}
