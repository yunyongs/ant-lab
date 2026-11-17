package antlab.projection;

/**
 * The moment a client asks for: the stored START and END, or the team as it is now (computed on request, never stored).
 * Not an entity field, so it does not have to match a CHECK list.
 */
public enum ProjectionMoment {
    CURRENT, START, END;

    /** The stored phase of this moment, or null for CURRENT. */
    public ProjectionPhase stored() {
        return switch (this) {
            case CURRENT -> null;
            case START -> ProjectionPhase.START;
            case END -> ProjectionPhase.END;
        };
    }
}
