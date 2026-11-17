package antlab.scoring;

/**
 * Integer counts of an observed value against n simulated paths: how many paths are strictly below it and how many
 * are equal. Counts travel as integers so no rounded decimal crosses the Java/Python boundary.
 */
public record RankCounts(long below, long equal, long n) {

    public RankCounts {
        if (n < 1 || below < 0 || equal < 0 || below + equal > n) {
            throw new IllegalArgumentException("rank counts must satisfy n >= 1, below >= 0, equal >= 0, below + equal <= n");
        }
    }
}
