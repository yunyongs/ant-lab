package antlab.season;

/** Result of the dev resume call: the season's status after the call and whether it really resumed. */
public record DevResumeDto(long seasonId, SeasonStatus status, boolean resumed) {
}
