package antlab.agent.decision;

/**
 * Why one unit of a decision was rejected. These are results of model output, not API errors, so they are not
 * {@code ErrorCode} values. The names of the unit checks of the shared order and formation paths must exist here
 * (a test compares them).
 */
public enum DecisionRejectCode {
    SCHEMA_INVALID,
    TURN_ZERO_FORMATION,
    FORMATION_UNCHANGED,
    FORMATION_INVALID,
    ASSET_NOT_IN_POOL,
    QUANTITY_INVALID,
    INSUFFICIENT_HOLDING,
    INSUFFICIENT_CASH,
    SINGLE_ASSET_LIMIT,
    POSITION_REQUIRED,
    POSITION_CONFLICT,
    POSITION_ON_SELL,
    SOURCE_NOT_VISIBLE,
    SOURCE_NOT_ALLOWED,
    THESES_NOT_SUPPORTED
}
