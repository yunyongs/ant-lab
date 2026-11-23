package antlab.agent.detection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.SortedMap;

/**
 * A {@code detection-summary/1} document after its checks: one model's results of one test run over one sample layer, with
 * one entry per window length (1, 3 or 6 months) and the five metrics of each window. Base rates keep 8 decimal places, so
 * two summaries compare equal exactly when their stored rows would.
 */
public record DetectionSummary(String modelId, String testVersion, String sampleLayer, Instant runAt, String sourceSha256,
        SortedMap<Integer, Window> windows) {

    public record Window(int units, Map<DetectionMetric, Score> metrics) {
    }

    public record Score(int hits, int units, BigDecimal baseRate) {
    }
}
