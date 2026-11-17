package antlab.engine.client;

/**
 * The engine cannot answer right now: not configured, not reachable, too slow, or it said so itself (history missing, too
 * busy). The caller may retry later. Until the public code ENGINE_UNAVAILABLE exists this is an unmapped exception; it
 * carries no cause so no URL, header or engine text can leak through it.
 */
public final class EngineUnavailableException extends RuntimeException {

    public enum Reason { NOT_CONFIGURED, UNREACHABLE, TIMEOUT, HISTORY_UNAVAILABLE, BUSY }

    static final String MESSAGE = "The engine is unavailable.";

    private final Reason reason;

    EngineUnavailableException(Reason reason) {
        super(MESSAGE);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
