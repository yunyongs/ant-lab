package antlab.agent.detection;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The detection result per model for the agent rows: NOT_TESTED with no rows, and TESTED with the newest run's version
 * and layer and, per window, the newest row of that version and layer. Rolled back after each test.
 */
@IntegrationTest
@Transactional
class DetectionReaderTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    DetectionReader reader;
    @Autowired
    DetectionWriter writer;

    /** One window with every metric at the given hits over the window's units and base rate 0.5. */
    private void store(String model, String version, String layer, String runAt, String sha, int hits, int... months) {
        StringBuilder windows = new StringBuilder();
        for (int m : months) {
            StringBuilder metrics = new StringBuilder();
            for (DetectionMetric metric : DetectionMetric.values()) {
                metrics.append(metrics.isEmpty() ? "" : ",").append("\"").append(metric.name())
                        .append("\":{\"hits\":").append(hits).append(",\"units\":8,\"baseRate\":\"0.50000000\"}");
            }
            windows.append(windows.isEmpty() ? "" : ",").append("\"").append(m).append("\":{\"units\":8,\"metrics\":{")
                    .append(metrics).append("}}");
        }
        String json = """
                {"schema":"detection-summary/1","modelId":"%s","testVersion":"%s","sampleLayer":"%s","runAt":"%s",
                 "sourceSha256":"%s","windows":{%s}}""".formatted(model, version, layer, runAt, sha.repeat(64), windows);
        DetectionSummaryDocument.Result read = DetectionSummaryDocument.read(JsonMapper.shared().readTree(json), NOW);
        assertThat(read.violations()).isEmpty();
        writer.insert(read.summary());
    }

    @Test
    void aModelWithoutRowsIsNotTested() {
        Map<String, DetectionResult> results = reader.forModels(List.of("fake/none", "fake/other", "fake/none"));

        assertThat(results).containsOnlyKeys("fake/none", "fake/other");
        assertThat(results.get("fake/none")).isEqualTo(new DetectionResult.NotTested());
        assertThat(results.get("fake/none").status()).isEqualTo(DetectionStatus.NOT_TESTED);
        assertThat(reader.forModels(List.of())).isEmpty();
    }

    @Test
    void theNewestRunFixesVersionAndLayerAndEachWindowTakesItsNewestRow() {
        store("fake/m", "detection-month/1", "P0-08", "2026-01-01T00:00:00Z", "1", 1, 1, 3, 6);
        store("fake/m", "detection-month/1", "P0-08", "2026-02-01T00:00:00Z", "2", 2, 1);
        // an older run of another layer does not mix in; a newer one of another model does not either
        store("fake/m", "detection-month/1", "MVP-01", "2025-12-01T00:00:00Z", "3", 7, 3);
        store("fake/x", "detection-month/2", "P0-08", "2026-03-01T00:00:00Z", "4", 8, 1);

        DetectionResult result = reader.forModel("fake/m");

        assertThat(result.status()).isEqualTo(DetectionStatus.TESTED);
        DetectionResult.Tested tested = (DetectionResult.Tested) result;
        assertThat(tested.testVersion()).isEqualTo("detection-month/1");
        assertThat(tested.sampleLayer()).isEqualTo("P0-08");
        assertThat(tested.runAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(tested.windows()).extracting(DetectionResult.Window::months, w -> w.metrics().get(0).hits(),
                DetectionResult.Window::runAt).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, 2, Instant.parse("2026-02-01T00:00:00Z")),
                org.assertj.core.groups.Tuple.tuple(3, 1, Instant.parse("2026-01-01T00:00:00Z")),
                org.assertj.core.groups.Tuple.tuple(6, 1, Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(tested.windows().get(0).metrics()).extracting(DetectionResult.Metric::metric)
                .containsExactly(DetectionMetric.values());
    }

    @Test
    void aNewerRunOfAnotherLayerReplacesTheWholeResult() {
        store("fake/m", "detection-month/1", "P0-08", "2026-01-01T00:00:00Z", "1", 1, 1, 3);
        store("fake/m", "detection-month/1", "MVP-01", "2026-04-01T00:00:00Z", "2", 5, 6);

        DetectionResult.Tested tested = (DetectionResult.Tested) reader.forModels(List.of("fake/m")).get("fake/m");

        assertThat(tested.sampleLayer()).isEqualTo("MVP-01");
        assertThat(tested.windows()).extracting(DetectionResult.Window::months).containsExactly(6);
    }

    @Test
    void accuracyIsHitsOverUnitsRoundedOnceHalfUpToEightPlaces() {
        assertThat(accuracy(1, 3)).isEqualTo("0.33333333");
        assertThat(accuracy(2, 3)).isEqualTo("0.66666667");
        assertThat(accuracy(1, 16)).isEqualTo("0.06250000");
        assertThat(accuracy(0, 5)).isEqualTo("0.00000000");
        assertThat(accuracy(5, 5)).isEqualTo("1.00000000");
    }

    private static String accuracy(int hits, int units) {
        BigDecimal value = new DetectionResult.Metric(DetectionMetric.YEAR, hits, units, BigDecimal.ONE).accuracy();
        assertThat(value.scale()).isEqualTo(8);
        return value.toPlainString();
    }
}
