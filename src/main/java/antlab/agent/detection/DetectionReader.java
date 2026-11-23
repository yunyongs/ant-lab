package antlab.agent.detection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The detection result per model id, for the agent rows of the leaderboard (AG-06 G2). A model with no imported summary
 * is {@link DetectionResult.NotTested}. Read only.
 */
@Component
public class DetectionReader {

    // The newest row of a model fixes its version and layer; per window the newest row of that version and layer wins.
    private static final String NEWEST = """
            WITH newest AS (
                SELECT DISTINCT ON (model_id) model_id, test_version, sample_layer
                FROM detection_test
                WHERE model_id IN (:models)
                ORDER BY model_id, run_at DESC, id DESC
            ), per_window AS (
                SELECT DISTINCT ON (t.model_id, t.window_months) t.id, t.model_id, t.test_version, t.sample_layer,
                       t.window_months, t.units, t.run_at
                FROM detection_test t
                JOIN newest n ON n.model_id = t.model_id AND n.test_version = t.test_version
                    AND n.sample_layer = t.sample_layer
                ORDER BY t.model_id, t.window_months, t.run_at DESC, t.id DESC
            )
            SELECT p.model_id, p.test_version, p.sample_layer, p.window_months, p.units AS window_units, p.run_at,
                   m.metric, m.hits, m.units, m.base_rate
            FROM per_window p
            JOIN detection_metric m ON m.test_id = p.id
            ORDER BY p.model_id, p.window_months
            """;

    private final JdbcClient jdbc;

    public DetectionReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DetectionResult forModel(String modelId) {
        return forModels(List.of(modelId)).get(modelId);
    }

    /** One entry for every given model id, in the given order (duplicates once). */
    public Map<String, DetectionResult> forModels(Collection<String> modelIds) {
        Map<String, DetectionResult> out = new LinkedHashMap<>();
        if (modelIds.isEmpty()) {
            return out;
        }
        record Row(String modelId, String testVersion, String sampleLayer, int months, int windowUnits, Instant runAt,
                DetectionMetric metric, int hits, int units, BigDecimal baseRate) {
        }
        Set<String> distinct = new TreeSet<>(modelIds);
        List<Row> rows = jdbc.sql(NEWEST).param("models", distinct).query((rs, n) -> new Row(rs.getString("model_id"),
                rs.getString("test_version"), rs.getString("sample_layer"), rs.getInt("window_months"),
                rs.getInt("window_units"), rs.getObject("run_at", OffsetDateTime.class).toInstant(),
                DetectionMetric.valueOf(rs.getString("metric")), rs.getInt("hits"), rs.getInt("units"),
                rs.getBigDecimal("base_rate"))).list();
        Map<String, Map<Integer, List<Row>>> byModel = new LinkedHashMap<>();
        for (Row r : rows) {
            byModel.computeIfAbsent(r.modelId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.months(), k -> new ArrayList<>()).add(r);
        }
        for (String modelId : modelIds) {
            Map<Integer, List<Row>> windows = byModel.get(modelId);
            if (windows == null) {
                out.putIfAbsent(modelId, new DetectionResult.NotTested());
                continue;
            }
            List<DetectionResult.Window> list = new ArrayList<>();
            Instant newest = Instant.MIN;
            Row any = null;
            for (List<Row> metrics : windows.values()) {
                Row head = metrics.get(0);
                any = head;
                newest = head.runAt().isAfter(newest) ? head.runAt() : newest;
                list.add(new DetectionResult.Window(head.months(), head.windowUnits(), head.runAt(), metrics.stream()
                        .sorted(Comparator.comparing(Row::metric))
                        .map(r -> new DetectionResult.Metric(r.metric(), r.hits(), r.units(), r.baseRate())).toList()));
            }
            out.putIfAbsent(modelId, new DetectionResult.Tested(any.testVersion(), any.sampleLayer(), newest, List.copyOf(list)));
        }
        return out;
    }
}
