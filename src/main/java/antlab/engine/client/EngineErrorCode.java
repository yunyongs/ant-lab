package antlab.engine.client;

/** The problem codes of docs/api/engine.yaml (schema ErrorCode). A code outside this list is contract drift. */
public enum EngineErrorCode {
    INVALID_ARGUMENT,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNAUTHENTICATED,
    INTERNAL_ERROR,
    PATH_TOO_LONG,
    POOL_NOT_FOUND,
    HISTORY_UNAVAILABLE,
    ENGINE_BUSY,
    REGIME_DATA_INVALID,
    REGIME_LABELS_MISSING,
    REFERENCE_MISMATCH,
    FEED_INPUT,
    NO_REFERENCE
}
