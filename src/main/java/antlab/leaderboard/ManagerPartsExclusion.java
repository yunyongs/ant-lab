package antlab.leaderboard;

/**
 * Why a finished season has no manager parts. Constants match {@code ck_season_manager_parts_exclusion} letter for letter.
 * {@code NOT_STARTED}: a human season abandoned before turn 3 (copied from {@code season_score.rating_exclusion}).
 * {@code REFERENCE_MISMATCH}: the engine's own benchmark or Sharpe for the season differs from Java's (409).
 * {@code NO_REFERENCE}: the engine cannot build or play the season's world (422), or the path length has no reference.
 */
public enum ManagerPartsExclusion {
    NOT_STARTED, REFERENCE_MISMATCH, NO_REFERENCE
}
