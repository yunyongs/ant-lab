package antlab.engine.client;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;

/**
 * The engine refused a season for a deterministic reason: a retry gives the same answer, so the caller records the
 * refusal instead of trying again. Only {@code POST /v1/manager-parts} answers these. The public answer, should one ever
 * leak to a response, is a plain 500; it carries no cause and no engine text, and {@link #reason()} is for the caller and
 * logs only.
 */
public final class EngineRefusedException extends DomainException {

    /** {@code REFERENCE_MISMATCH} (409): the engine's benchmark or Sharpe differs; the other two (422): no usable world. */
    public enum Reason { REFERENCE_MISMATCH, FEED_INPUT, NO_REFERENCE }

    private final Reason reason;

    EngineRefusedException(Reason reason) {
        super(ErrorCode.INTERNAL_ERROR, EngineErrorMapper.INTERNAL_MESSAGE);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
