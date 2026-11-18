package antlab.support;

/** The JSON the fake engine answers {@code POST /v1/percentiles} with: integer counts only, as the contract says. */
public final class EnginePercentileAnswers {

    public static final String DIGEST = "cd".repeat(32);

    private EnginePercentileAnswers() {
    }

    /** A well-formed answer: the observed end multiple and Sharpe difference have these counts among {@code n} paths. */
    public static String body(int n, int horizon, int endBelow, int endEqual, int diffBelow, int diffEqual) {
        return ("{\"projectionVersion\":\"m2-pj-v1\",\"engineVersion\":\"m2-sb6-v1\",\"n\":%d,\"horizonTurns\":%d,"
                + "\"historyDigest\":\"%s\",\"endMultiple\":{\"below\":%d,\"equal\":%d},"
                + "\"sharpeDiff\":{\"below\":%d,\"equal\":%d}}")
                .formatted(n, horizon, DIGEST, endBelow, endEqual, diffBelow, diffEqual);
    }

    /** The default answer of the score tests: the Sharpe difference sits at the middle (1000 below, 2 equal of 2000). */
    public static String middle(int n, int horizon) {
        return body(n, horizon, n / 4, 0, n / 2 - 1, 2);
    }
}
