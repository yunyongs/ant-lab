package antlab.observability;

import antlab.common.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumSet;
import java.util.Set;
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

    // Codes each endpoint documents; any other ErrorCode still counts, it just starts at its first event.
    static final Set<ErrorCode> SEASON_REJECTIONS = EnumSet.of(ErrorCode.INVALID_ARGUMENT, ErrorCode.POOL_NOT_LOADED,
            ErrorCode.POOL_NOT_JUDGED, ErrorCode.SEASON_LIMIT, ErrorCode.PATH_TOO_LONG);
    static final Set<ErrorCode> TURN_REJECTIONS = EnumSet.of(ErrorCode.NOT_FOUND, ErrorCode.SEASON_NOT_ACTIVE,
            ErrorCode.TURN_MISMATCH, ErrorCode.RECONCILIATION_FAILED, ErrorCode.SEASON_HALTED);
    static final Set<ErrorCode> ORDER_REJECTIONS = EnumSet.of(ErrorCode.INVALID_ARGUMENT, ErrorCode.NOT_FOUND,
            ErrorCode.SEASON_NOT_ACTIVE, ErrorCode.TURN_MISMATCH, ErrorCode.LEDGER_MISMATCH, ErrorCode.LINEUP_MISMATCH,
            ErrorCode.EVENT_TIME_CONFLICT, ErrorCode.ASSET_NOT_IN_POOL, ErrorCode.SOURCE_NOT_VISIBLE, ErrorCode.INSUFFICIENT_CASH,
            ErrorCode.INSUFFICIENT_HOLDING, ErrorCode.POSITION_CONFLICT, ErrorCode.POSITION_REQUIRED);

    private final MeterRegistry registry;
    private final Counter mismatchRules;
    private final Counter seasonHalted;
    private final Counter seasonResumed;

    public GameMetrics(MeterRegistry registry) {
        this.registry = registry;
        outcome(SEASON, SEASON_HELP, COMMITTED, NONE);
        outcome(TURN, TURN_HELP, COMMITTED, NONE);
        // A series that first appears with its first event is missed by rate() and increase(), so every known pair starts at 0.
        SEASON_REJECTIONS.forEach(code -> outcome(SEASON, SEASON_HELP, REJECTED, code.name()));
        TURN_REJECTIONS.forEach(code -> outcome(TURN, TURN_HELP, REJECTED, code.name()));
        ORDER_REJECTIONS.forEach(this::orderCounter);
        this.mismatchRules = Counter.builder("antlab.reconciliation.mismatch.rules")
                .description("Reconciliation rules that mismatched in rolled-back turn advances").register(registry);
        this.seasonHalted = Counter.builder("antlab.season.halted")
                .description("Seasons stopped after repeated reconciliation failures; counted once per halt").register(registry);
        this.seasonResumed = Counter.builder("antlab.season.resumed")
                .description("Halted seasons resumed by an administrator; counted only when a season really resumed")
                .register(registry);
    }

    public void seasonHalted() {
        safely(seasonHalted::increment);
    }

    public void seasonResumed() {
        safely(seasonResumed::increment);
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
        safely(() -> orderCounter(code).increment());
    }

    private Counter orderCounter(ErrorCode code) {
        return Counter.builder("antlab.order.rejected").description("Orders rejected by the API, by error code")
                .tag("code", code.name()).register(registry);
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
