package antlab.agent.memory;

/**
 * The fixed reasons a season's reflection is discarded. The filter codes name the rule the second attempt still broke;
 * {@code OVERSIZE} is the length cap, {@code SCHEMA} a reply that never had the shape, {@code BUDGET} the owner's choice to
 * stop for lack of calls. The names are stored in {@code agent_reflection.discard_codes}.
 */
public enum MemoryDiscardCode {
    YEAR,
    YEAR_MONTH,
    EVENT_TERM,
    FINGERPRINT,
    TURN_RETURN,
    PRICE,
    OVERSIZE,
    SCHEMA,
    BUDGET
}
