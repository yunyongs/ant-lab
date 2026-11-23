package antlab.agent.detection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Insert-only access to {@code detection_test} and {@code detection_metric}: no statement here updates or deletes, and the
 * application role has no right to (afterMigrate class APPEND). Callers run inside a transaction.
 */
@Repository
public class DetectionWriter {

    private static final String BY_SOURCE = """
            SELECT t.id, t.model_id, t.test_version, t.sample_layer, t.window_months, t.units AS window_units, t.run_at,
                   m.metric, m.hits, m.units, m.base_rate
            FROM detection_test t
            JOIN detection_metric m ON m.test_id = t.id
            WHERE t.source_sha256 = :sha
            ORDER BY t.window_months, m.metric
            """;

    private final JdbcClient jdbc;

    public DetectionWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises imports of one source file until the transaction ends. */
    public void lockSource(String sourceSha256) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "detection_test:" + sourceSha256).query((rs, n) -> 1).single();
    }

    /** The summary already stored under this source hash, rebuilt from its rows. */
    public Optional<DetectionSummary> findBySource(String sourceSha256) {
        record Row(String modelId, String testVersion, String sampleLayer, int months, int windowUnits,
                OffsetDateTime runAt, DetectionMetric metric, int hits, int units, BigDecimal baseRate) {
        }
        List<Row> rows = jdbc.sql(BY_SOURCE).param("sha", sourceSha256).query((rs, n) -> new Row(rs.getString("model_id"),
                rs.getString("test_version"), rs.getString("sample_layer"), rs.getInt("window_months"),
                rs.getInt("window_units"), rs.getObject("run_at", OffsetDateTime.class),
                DetectionMetric.valueOf(rs.getString("metric")), rs.getInt("hits"), rs.getInt("units"),
                rs.getBigDecimal("base_rate"))).list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row first = rows.get(0);
        TreeMap<Integer, DetectionSummary.Window> windows = new TreeMap<>();
        Map<Integer, Map<DetectionMetric, DetectionSummary.Score>> scores = new TreeMap<>();
        for (Row r : rows) {
            scores.computeIfAbsent(r.months(), k -> new EnumMap<>(DetectionMetric.class))
                    .put(r.metric(), new DetectionSummary.Score(r.hits(), r.units(), r.baseRate()));
            windows.put(r.months(), new DetectionSummary.Window(r.windowUnits(), scores.get(r.months())));
        }
        return Optional.of(new DetectionSummary(first.modelId(), first.testVersion(), first.sampleLayer(),
                first.runAt().toInstant(), sourceSha256, windows));
    }

    /** One test row per window and five metric rows under each. */
    public void insert(DetectionSummary summary) {
        OffsetDateTime runAt = summary.runAt().atOffset(ZoneOffset.UTC);
        summary.windows().forEach((months, window) -> {
            long testId = jdbc.sql("""
                    INSERT INTO detection_test (model_id, test_version, sample_layer, window_months, units, source_sha256, run_at)
                    VALUES (:model, :version, :layer, :months, :units, :sha, :runAt)
                    RETURNING id""")
                    .param("model", summary.modelId()).param("version", summary.testVersion())
                    .param("layer", summary.sampleLayer()).param("months", months).param("units", window.units())
                    .param("sha", summary.sourceSha256()).param("runAt", runAt)
                    .query(Long.class).single();
            window.metrics().forEach((metric, score) -> jdbc.sql("""
                    INSERT INTO detection_metric (test_id, metric, hits, units, base_rate)
                    VALUES (:test, :metric, :hits, :units, :rate)""")
                    .param("test", testId).param("metric", metric.name()).param("hits", score.hits())
                    .param("units", score.units()).param("rate", score.baseRate())
                    .update());
        });
    }
}
