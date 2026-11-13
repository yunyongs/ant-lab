package antlab.observability;

import antlab.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Game counters. Counters are in-memory and ignore transactions, so every method must be called where the outcome is
 * already final (after commit or after rollback), never inside a transaction. Tag values come from bounded sets only.
 */
@Component
public class GameMetrics {

    private static final Logger log = LoggerFactory.getLogger(GameMetrics.class);
    static final String COMMITTED = "committed";
    static final String REJECTED = "rejected";
    static final String NONE = "none";
    private static final String SEASON = "antlab.season.start";
    private static final String SEASON_HELP = "Season start requests by outcome";
    private static final String TURN = "antlab.turn.advance";
    private static final String TURN_HELP =
            "Turn advance attempts by outcome; committed counts only after the transaction committed";

    private final MeterRegistry registry;
    private final Counter mismatchRules;

    public GameMetrics(MeterRegistry registry) {
        this.registry = registry;
        outcome(SEASON, SEASON_HELP, COMMITTED, NONE);
        outcome(TURN, TURN_HELP, COMMITTED, NONE);
        this.mismatchRules = Counter.builder("antlab.reconciliation.mismatch.rules")
                .description("Reconciliation rules that mismatched in rolled-back turn advances").register(registry);
    }

    public void seasonStarted() {
        safely(() -> outcome(SEASON, SEASON_HELP, COMMITTED, NONE).increment());
    }

    public void seasonRejected(ErrorCode code) {
        safely(() -> outcome(SEASON, SEASON_HELP, REJECTED, code.name()).increment());
    }

    public void turnCommitted() {
        safely(() -> outcome(TURN, TURN_HELP, COMMITTED, NONE).increment());
    }

    public void turnRejected(ErrorCode code) {
        safely(() -> outcome(TURN, TURN_HELP, REJECTED, code.name()).increment());
    }

    public void mismatchRules(int count) {
        if (count > 0) {
            safely(() -> mismatchRules.increment(count));
        }
    }

    public void orderRejected(ErrorCode code) {
        safely(() -> Counter.builder("antlab.order.rejected").description("Orders rejected by the API, by error code")
                .tag("code", code.name()).register(registry).increment());
    }

    private Counter outcome(String name, String help, String outcome, String code) {
        return Counter.builder(name).description(help).tag("outcome", outcome).tag("code", code).register(registry);
    }

    // A metric failure must never change a response or skip the rollback record.
    private static void safely(Runnable increment) {
        try {
            increment.run();
        } catch (RuntimeException e) {
            log.warn("metric increment failed", e);
        }
    }
}
