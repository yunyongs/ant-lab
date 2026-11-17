package antlab.ranking;

/** Policy constants of the ranking board (builder values, MVP-12). */
public final class RankingRules {

    /** Rows kept on every list. */
    public static final int BOARD_SIZE = 5;
    /** A streak list only holds assets that moved the same way for at least this many turns. */
    public static final int MIN_STREAK = 2;

    private RankingRules() {
    }
}
