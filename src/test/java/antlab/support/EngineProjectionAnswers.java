package antlab.support;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Builds the JSON the fake engine answers {@code POST /v1/projections} with. The multiples are fixed on purpose so a test
 * can compute the won values by hand: the band of turn t has p05 0.90, p25 0.95, p50 1 + t/1000, p75 1.05, p95 1.10; every
 * summary has end quantiles 0.80 / 0.90 / 1.00 / 1.10 / 1.20, drawdowns -0.30 ... -0.01 and a breach chance of 0.125.
 */
public final class EngineProjectionAnswers {

    public static final String DIGEST = "ab".repeat(32);

    private EngineProjectionAnswers() {
    }

    /** A well-formed answer for one portfolio {@code TEAM} asked with these settings. */
    public static String body(int n, int horizon, List<Integer> summaryTurns) {
        return body(n, horizon, summaryTurns, "TEAM");
    }

    public static String body(int n, int horizon, List<Integer> summaryTurns, String key) {
        return body(n, horizon, summaryTurns, key, horizon);
    }

    /** Like {@link #body(int, int, List, String)} with a chosen number of bands (a broken answer when it is not the horizon). */
    public static String body(int n, int horizon, List<Integer> summaryTurns, String key, int bandCount) {
        List<String> bands = new ArrayList<>();
        for (int turn = 1; turn <= bandCount; turn++) {
            bands.add("{\"turn\":%d,\"p05\":\"0.90000000\",\"p25\":\"0.95000000\",\"p50\":\"%s\",\"p75\":\"1.05000000\",\"p95\":\"1.10000000\"}"
                    .formatted(turn, median(turn)));
        }
        TreeSet<Integer> turns = new TreeSet<>(summaryTurns.stream().filter(turn -> turn <= horizon).toList());
        turns.add(horizon);
        List<String> summaries = turns.stream().map(EngineProjectionAnswers::summary).toList();
        return "{\"projectionVersion\":\"m2-pj-v1\",\"engineVersion\":\"m2-sb6-v1\",\"n\":%d,\"horizonTurns\":%d,\"historyDigest\":\"%s\",\"portfolios\":[{\"key\":\"%s\",\"bands\":[%s],\"summaries\":[%s]}]}"
                .formatted(n, horizon, DIGEST, key, String.join(",", bands), String.join(",", summaries));
    }

    /** An answer whose last band has an absurd p95 multiple (accepted by the wire, far beyond NUMERIC(19,4) as won). */
    public static String bodyWithHugeMultiple(int n, int horizon, List<Integer> summaryTurns) {
        String ok = body(n, horizon, summaryTurns);
        String last = "{\"turn\":%d,\"p05\":\"0.90000000\",\"p25\":\"0.95000000\",\"p50\":\"%s\",\"p75\":\"1.05000000\",\"p95\":\"1.10000000\"}"
                .formatted(horizon, median(horizon));
        assertContains(ok, last);
        return ok.replace(last, last.replace("\"p95\":\"1.10000000\"", "\"p95\":\"999999999999.00000000\""));
    }

    private static void assertContains(String text, String part) {
        if (!text.contains(part)) {
            throw new IllegalStateException("fixture shape changed");
        }
    }

    /** {@code 1 + turn / 1000} with eight decimals. */
    static String median(int turn) {
        return "1.%03d00000".formatted(turn);
    }

    private static String summary(int turn) {
        return ("{\"turn\":%d,\"end\":{\"p05\":\"0.80000000\",\"p25\":\"0.90000000\",\"p50\":\"1.00000000\",\"p75\":\"1.10000000\",\"p95\":\"1.20000000\"},"
                + "\"mdd\":{\"p05\":\"-0.30000000\",\"p10\":\"-0.20000000\",\"p25\":\"-0.10000000\",\"p50\":\"-0.05000000\",\"p75\":\"-0.02000000\",\"p95\":\"-0.01000000\"},"
                + "\"breachCount\":250,\"breachProbability\":\"0.12500000\"}").formatted(turn);
    }
}
