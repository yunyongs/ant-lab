package antlab.season;

/** Result of {@link FormationChangeService#check}: exactly one of a rejection (no state changed) or a plan to apply. */
public final class FormationCheck {

    private final FormationRejection rejection;
    private final FormationPlan plan;

    private FormationCheck(FormationRejection rejection, FormationPlan plan) {
        this.rejection = rejection;
        this.plan = plan;
    }

    static FormationCheck rejected(FormationRejection rejection) {
        return new FormationCheck(rejection, null);
    }

    static FormationCheck passed(FormationPlan plan) {
        return new FormationCheck(null, plan);
    }

    public FormationRejection rejection() {
        return rejection;
    }

    public FormationPlan plan() {
        return plan;
    }
}
