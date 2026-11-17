package antlab.engine.client;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;

/**
 * The engine cannot answer right now: not configured, not reachable, too slow, or it said so itself (history missing, too
 * busy). The caller may retry later; the public answer is 503 ENGINE_UNAVAILABLE. It carries no cause so no URL, header or
 * engine text can leak through it, and {@link #reason()} is for logs and tests only, never for a response.
 */
public final class EngineUnavailableException extends DomainException {

    public enum Reason { NOT_CONFIGURED, UNREACHABLE, TIMEOUT, HISTORY_UNAVAILABLE, BUSY }

    static final String MESSAGE = "The engine is unavailable.";

    private final Reason reason;

    EngineUnavailableException(Reason reason) {
        super(ErrorCode.ENGINE_UNAVAILABLE, MESSAGE);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
