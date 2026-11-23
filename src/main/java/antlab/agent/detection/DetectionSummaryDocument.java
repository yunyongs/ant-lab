package antlab.agent.detection;

import antlab.agent.decision.SchemaViolation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads a {@code detection-summary/1} document in two steps. The shape check follows
 * {@code docs/api/detection-summary-1.schema.json} by hand (a parity test holds the two to the same verdicts); it is
 * stricter in one point only: a pattern must match the whole string, so a trailing line break that a regex {@code $} may
 * accept is refused. The rules the schema cannot say come after it: {@code runAt} is a real instant and not in the future,
 * a metric's hits are at most its units, and a metric's units are at most its window's units. A violation is a pointer
 * and a keyword; it never carries a value or a key the sender chose (an unknown key is reported at its parent).
 */
public final class DetectionSummaryDocument {

    public static final String SCHEMA = "detection-summary/1";
    static final int MAX_COUNT = 100_000;
    private static final int MAX_VIOLATIONS = 20;
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+-]*");
    private static final Pattern TEST_VERSION = Pattern.compile("[a-z][a-z0-9-]*/[1-9][0-9]*");
    private static final Pattern SAMPLE_LAYER = Pattern.compile("[A-Z0-9][A-Z0-9-]*");
    private static final Pattern RUN_AT = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BASE_RATE = Pattern.compile("0\\.[0-9]{8}|1\\.00000000");
    private static final List<String> TOP = List.of("schema", "modelId", "testVersion", "sampleLayer", "runAt",
            "sourceSha256", "windows");
    private static final Set<String> WINDOWS = Set.of("1", "3", "6");
    private static final List<String> WINDOW = List.of("units", "metrics");
    private static final List<String> SCORE = List.of("hits", "units", "baseRate");

    /** The checked summary, or null when there is a violation. */
    public record Result(DetectionSummary summary, List<SchemaViolation> violations) {
    }

    private final List<SchemaViolation> violations = new ArrayList<>();

    private DetectionSummaryDocument() {
    }

    /** Shape only: the verdict of the JSON schema file. */
    public static List<SchemaViolation> checkShape(JsonNode doc) {
        DetectionSummaryDocument check = new DetectionSummaryDocument();
        check.root(doc);
        return List.copyOf(check.violations);
    }

    /** Shape, then the rules after it; {@code now} bounds {@code runAt}. */
    public static Result read(JsonNode doc, Instant now) {
        List<SchemaViolation> shape = checkShape(doc);
        if (!shape.isEmpty()) {
            return new Result(null, shape);
        }
        DetectionSummaryDocument check = new DetectionSummaryDocument();
        DetectionSummary summary = check.summary(doc, now);
        return check.violations.isEmpty() ? new Result(summary, List.of()) : new Result(null, List.copyOf(check.violations));
    }

    private void violate(String pointer, String keyword) {
        if (violations.size() < MAX_VIOLATIONS) {
            violations.add(new SchemaViolation(pointer, keyword));
        }
    }

    private void root(JsonNode doc) {
        if (doc == null || !doc.isObject()) {
            violate("", "type");
            return;
        }
        object(doc, "", TOP);
        JsonNode schema = doc.get("schema");
        if (schema != null && !(schema.isString() && SCHEMA.equals(schema.asString()))) {
            violate("/schema", "const");
        }
        string(doc.get("modelId"), "/modelId", 1, 200, MODEL_ID);
        string(doc.get("testVersion"), "/testVersion", 0, 32, TEST_VERSION);
        string(doc.get("sampleLayer"), "/sampleLayer", 0, 16, SAMPLE_LAYER);
        string(doc.get("runAt"), "/runAt", 0, Integer.MAX_VALUE, RUN_AT);
        string(doc.get("sourceSha256"), "/sourceSha256", 0, Integer.MAX_VALUE, SHA256);
        JsonNode windows = doc.get("windows");
        if (windows == null) {
            return;
        }
        if (!windows.isObject()) {
            violate("/windows", "type");
            return;
        }
        if (windows.isEmpty()) {
            violate("/windows", "minProperties");
        }
        for (String name : windows.propertyNames()) {
            if (!WINDOWS.contains(name)) {
                violate("/windows", "additionalProperties");
                break;
            }
        }
        for (String months : List.of("1", "3", "6")) {
            if (windows.has(months)) {
                window(windows.get(months), "/windows/" + months);
            }
        }
    }

    private void window(JsonNode window, String pointer) {
        if (!window.isObject()) {
            violate(pointer, "type");
            return;
        }
        object(window, pointer, WINDOW);
        count(window.get("units"), pointer + "/units", 1);
        JsonNode metrics = window.get("metrics");
        if (metrics == null) {
            return;
        }
        if (!metrics.isObject()) {
            violate(pointer + "/metrics", "type");
            return;
        }
        object(metrics, pointer + "/metrics", names());
        for (DetectionMetric metric : DetectionMetric.values()) {
            JsonNode score = metrics.get(metric.name());
            if (score != null) {
                score(score, pointer + "/metrics/" + metric.name());
            }
        }
    }

    private void score(JsonNode score, String pointer) {
        if (!score.isObject()) {
            violate(pointer, "type");
            return;
        }
        object(score, pointer, SCORE);
        count(score.get("hits"), pointer + "/hits", 0);
        count(score.get("units"), pointer + "/units", 1);
        string(score.get("baseRate"), pointer + "/baseRate", 0, Integer.MAX_VALUE, BASE_RATE);
    }

    /** Unknown keys (reported once, at the object) and the required ones. */
    private void object(JsonNode object, String pointer, List<String> keys) {
        for (String name : object.propertyNames()) {
            if (!keys.contains(name)) {
                violate(pointer, "additionalProperties");
                break;
            }
        }
        for (String key : keys) {
            if (!object.has(key)) {
                violate(pointer + "/" + key, "required");
            }
        }
    }

    private void string(JsonNode node, String pointer, int minLength, int maxLength, Pattern pattern) {
        if (node == null) {
            return;
        }
        if (!node.isString()) {
            violate(pointer, "type");
            return;
        }
        String text = node.asString();
        int length = text.codePointCount(0, text.length());
        if (length < minLength) {
            violate(pointer, "minLength");
        }
        if (length > maxLength) {
            violate(pointer, "maxLength");
        }
        if (!pattern.matcher(text).matches()) {
            violate(pointer, "pattern");
        }
    }

    private void count(JsonNode node, String pointer, int minimum) {
        if (node == null) {
            return;
        }
        if (!node.isIntegralNumber()) {
            violate(pointer, "type");
            return;
        }
        BigInteger value = node.bigIntegerValue();
        if (value.compareTo(BigInteger.valueOf(minimum)) < 0) {
            violate(pointer, "minimum");
        }
        if (value.compareTo(BigInteger.valueOf(MAX_COUNT)) > 0) {
            violate(pointer, "maximum");
        }
    }

    private static List<String> names() {
        List<String> out = new ArrayList<>();
        for (DetectionMetric metric : DetectionMetric.values()) {
            out.add(metric.name());
        }
        return out;
    }

    /** The rules after the shape; the document has the shape here. */
    private DetectionSummary summary(JsonNode doc, Instant now) {
        Instant runAt = null;
        try {
            runAt = Instant.parse(doc.get("runAt").asString());
        } catch (DateTimeParseException e) {
            violate("/runAt", "dateTime");
        }
        if (runAt != null && runAt.isAfter(now)) {
            violate("/runAt", "notFuture");
        }
        TreeMap<Integer, DetectionSummary.Window> windows = new TreeMap<>();
        JsonNode windowNodes = doc.get("windows");
        for (String months : List.of("1", "3", "6")) {
            JsonNode window = windowNodes.get(months);
            if (window == null) {
                continue;
            }
            int windowUnits = window.get("units").intValue();
            Map<DetectionMetric, DetectionSummary.Score> scores = new EnumMap<>(DetectionMetric.class);
            for (DetectionMetric metric : DetectionMetric.values()) {
                JsonNode score = window.get("metrics").get(metric.name());
                String pointer = "/windows/" + months + "/metrics/" + metric.name();
                int hits = score.get("hits").intValue();
                int units = score.get("units").intValue();
                if (hits > units) {
                    violate(pointer + "/hits", "atMostUnits");
                }
                if (units > windowUnits) {
                    violate(pointer + "/units", "atMostWindowUnits");
                }
                scores.put(metric, new DetectionSummary.Score(hits, units, new BigDecimal(score.get("baseRate").asString())));
            }
            windows.put(Integer.parseInt(months), new DetectionSummary.Window(windowUnits, scores));
        }
        return new DetectionSummary(doc.get("modelId").asString(), doc.get("testVersion").asString(),
                doc.get("sampleLayer").asString(), runAt, doc.get("sourceSha256").asString(), windows);
    }
}
