package antlab.season;

/** A formation change that passed every check and is ready to be written; only {@link FormationChangeService#check} makes one. */
public record FormationPlan(Season season, int turnNo, Formation before, Formation after, String reason) {
}
