package antlab.feed;

/** Policy constants of rule version {@code p0-18:v2}; the SQL copy is {@code tf_rule_version}, {@code tf_grade} and the row bounds in {@code db/24}. */
public final class FeedRules {

    /** Part of every hash input, so changing it breaks "stored = regenerated" for stored v2 feeds. */
    public static final String RULE_VERSION = "p0-18:v2";
    /** The target row count of a turn is {@code MIN_ROWS + hash % ROW_SPAN}, so 5 to 15. */
    public static final int MIN_ROWS = 5;
    public static final int ROW_SPAN = 11;
    public static final int MAX_ROWS = 15;
    /** A FADE story needs a previous attention rank of at most this. */
    public static final int FADE_TOP = 5;

    private FeedRules() {
    }

    /**
     * Attention grade: top 10 % (ties included) = 3, top half = 2, the rest = 1, no rank = null. Integer ceilings only.
     * The grade is the stored strength and the pick weight (null counts as 1).
     */
    public static Integer grade(Integer rank, int n) {
        if (rank == null || n == 0) {
            return null;
        }
        if (rank <= (n + 9) / 10) {
            return 3;
        }
        return rank <= (n + 1) / 2 ? 2 : 1;
    }
}
