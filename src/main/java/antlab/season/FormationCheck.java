package antlab.season;

/** Result of {@link FormationChangeService#check}: exactly one of a rejection (no state changed) or a plan to apply. */
public record FormationCheck(FormationRejection rejection, FormationPlan plan) {

    static FormationCheck rejected(FormationRejection rejection) {
        return new FormationCheck(rejection, null);
    }

    static FormationCheck passed(FormationPlan plan) {
        return new FormationCheck(null, plan);
    }
}
