package antlab.season;

/** A formation change that passed every check and is ready to be written; only {@link FormationChangeService#check} makes one. */
public final class FormationPlan {

    private final Season season;
    private final int turnNo;
    private final Formation before;
    private final Formation after;
    private final String reason;

    FormationPlan(Season season, int turnNo, Formation before, Formation after, String reason) {
        this.season = season;
        this.turnNo = turnNo;
        this.before = before;
        this.after = after;
        this.reason = reason;
    }

    Season season() {
        return season;
    }

    int turnNo() {
        return turnNo;
    }

    Formation before() {
        return before;
    }

    Formation after() {
        return after;
    }

    String reason() {
        return reason;
    }
}
