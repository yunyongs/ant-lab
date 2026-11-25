package antlab.leaderboard;

/**
 * Why one of the viewer's finished seasons is not in their L. The first three copy {@link ManagerPartsExclusion};
 * {@code PENDING} has no parts row yet and {@code AWAITING_LEAGUE_CLOSE} belongs to a league that is still open.
 */
public enum LeaderboardExclusion {
    NOT_STARTED, REFERENCE_MISMATCH, NO_REFERENCE, PENDING, AWAITING_LEAGUE_CLOSE
}
