package antlab.agent.detection;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.decision.SchemaViolation;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hand-written shape check against {@code docs/api/detection-summary-1.schema.json}: the same verdict over edge cases
 * and thousands of seeded mutations, pointers and keywords from a fixed vocabulary, and the rules after the shape. No
 * Spring context.
 */
class DetectionSummaryDocumentTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final Schema SCHEMA = schema();
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final Set<String> KEYWORDS = Set.of("type", "required", "additionalProperties", "const", "minLength",
            "maxLength", "pattern", "minimum", "maximum", "minProperties");

    static final String GOOD = """
            {"schema":"detection-summary/1","modelId":"nvidia/nemotron-3-ultra:free","testVersion":"detection-month/1",
             "sampleLayer":"P0-08","runAt":"2026-11-23T09:00:00.123Z","sourceSha256":"%s",
             "windows":{"1":{"units":98,"metrics":{
               "YM_EXACT":{"hits":1,"units":98,"baseRate":"0.01000000"},
               "YM_WITHIN_3":{"hits":4,"units":98,"baseRate":"0.07000000"},
               "YEAR":{"hits":10,"units":98,"baseRate":"0.11000000"},
               "KR_NEXT":{"hits":50,"units":97,"baseRate":"0.52000000"},
               "US_NEXT":{"hits":98,"units":98,"baseRate":"1.00000000"}}},
              "6":{"units":88,"metrics":{
               "YM_EXACT":{"hits":0,"units":88,"baseRate":"0.00000000"},
               "YM_WITHIN_3":{"hits":3,"units":88,"baseRate":"0.07000000"},
               "YEAR":{"hits":9,"units":88,"baseRate":"0.11000000"},
               "KR_NEXT":{"hits":44,"units":88,"baseRate":"0.52000000"},
               "US_NEXT":{"hits":45,"units":88,"baseRate":"0.58000000"}}}}}""".formatted("0123456789abcdef".repeat(4));

    private static Schema schema() {
        try {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(MAPPER.readTree(Files.readString(Path.of("docs/api/detection-summary-1.schema.json"))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    private static ObjectNode good() {
        return (ObjectNode) parse(GOOD);
    }

    private static ObjectNode score(ObjectNode doc, String months, String metric) {
        return (ObjectNode) doc.get("windows").get(months).get("metrics").get(metric);
    }

    @Test
    void a_valid_summary_reads_into_exact_values() {
        DetectionSummaryDocument.Result result = DetectionSummaryDocument.read(good(), NOW);

        assertThat(result.violations()).isEmpty();
        DetectionSummary s = result.summary();
        assertThat(s.modelId()).isEqualTo("nvidia/nemotron-3-ultra:free");
        assertThat(s.runAt()).isEqualTo(Instant.parse("2026-11-23T09:00:00.123Z"));
        assertThat(s.windows()).containsOnlyKeys(1, 6);
        assertThat(s.windows().get(1).metrics()).containsOnlyKeys(DetectionMetric.values());
        assertThat(s.windows().get(1).metrics().get(DetectionMetric.KR_NEXT))
                .isEqualTo(new DetectionSummary.Score(50, 97, new BigDecimal("0.52000000")));
        assertThat(s.windows().get(1).metrics().get(DetectionMetric.US_NEXT).baseRate().scale()).isEqualTo(8);
        assertThat(SCHEMA.validate(good())).isEmpty();
    }

    @Test
    void the_violations_name_pointers_and_keywords_and_never_a_value_or_a_key() {
        ObjectNode doc = good();
        doc.put("schema", "detection-summary/2");
        doc.put("secretKey2008", 1);
        doc.put("modelId", " spaced");
        ((ObjectNode) doc.get("windows")).put("2", 1);
        score(doc, "1", "YEAR").put("hits", -1);
        score(doc, "1", "YEAR").put("baseRate", "0.1");
        ((ObjectNode) doc.get("windows").get("6").get("metrics")).remove("US_NEXT");

        List<SchemaViolation> v = DetectionSummaryDocument.checkShape(doc);

        assertThat(v).extracting(SchemaViolation::keyword).isSubsetOf(KEYWORDS);
        assertThat(v).contains(new SchemaViolation("", "additionalProperties"), new SchemaViolation("/schema", "const"),
                new SchemaViolation("/modelId", "pattern"), new SchemaViolation("/windows", "additionalProperties"),
                new SchemaViolation("/windows/1/metrics/YEAR/hits", "minimum"),
                new SchemaViolation("/windows/1/metrics/YEAR/baseRate", "pattern"),
                new SchemaViolation("/windows/6/metrics/US_NEXT", "required"));
        assertThat(v.toString()).doesNotContain("secretKey").doesNotContain("2008").doesNotContain("spaced");
        assertThat(DetectionSummaryDocument.read(doc, NOW).summary()).isNull();
    }

    @Test
    void the_rules_after_the_shape_are_named() {
        ObjectNode doc = good();
        score(doc, "1", "YM_EXACT").put("hits", 99);
        score(doc, "6", "YEAR").put("units", 89);

        assertThat(DetectionSummaryDocument.checkShape(doc)).isEmpty();
        assertThat(DetectionSummaryDocument.read(doc, NOW).violations()).containsExactly(
                new SchemaViolation("/windows/1/metrics/YM_EXACT/hits", "atMostUnits"),
                new SchemaViolation("/windows/6/metrics/YEAR/units", "atMostWindowUnits"));

        ObjectNode future = good();
        future.put("runAt", "2026-11-23T12:00:00.000001Z");
        assertThat(DetectionSummaryDocument.read(future, NOW).violations())
                .containsExactly(new SchemaViolation("/runAt", "notFuture"));
        ObjectNode present = good();
        present.put("runAt", "2026-11-23T12:00:00Z");
        assertThat(DetectionSummaryDocument.read(present, NOW).violations()).isEmpty();
        ObjectNode unreal = good();
        unreal.put("runAt", "2026-02-30T09:00:00Z");
        assertThat(DetectionSummaryDocument.checkShape(unreal)).isEmpty();
        assertThat(DetectionSummaryDocument.read(unreal, NOW).violations())
                .containsExactly(new SchemaViolation("/runAt", "dateTime"));
    }

    @Test
    void a_pattern_must_match_the_whole_string_even_where_a_regex_dollar_would_not_care() {
        ObjectNode doc = good();
        doc.put("testVersion", "detection-month/1\n");

        assertThat(DetectionSummaryDocument.checkShape(doc)).containsExactly(new SchemaViolation("/testVersion", "pattern"));
    }

    @Test
    void the_hand_written_check_and_the_schema_file_agree_on_the_edge_cases() {
        List<String> docs = new ArrayList<>(List.of("[]", "null", "\"x\"", "1", "{}", GOOD,
                GOOD.replace("\"windows\":{\"1\"", "\"windows\":{\"3\""),
                GOOD.replace("\"sampleLayer\":\"P0-08\"", "\"sampleLayer\":\"P0-08-EXTENDED-XX\""),
                GOOD.replace("\"sampleLayer\":\"P0-08\"", "\"sampleLayer\":\"P0-08-EXTENDED-X\""),
                GOOD.replace("\"units\":98,\"metrics\"", "\"units\":100001,\"metrics\""),
                GOOD.replace("\"units\":98,\"metrics\"", "\"units\":100000,\"metrics\""),
                GOOD.replace("\"units\":98,\"metrics\"", "\"units\":98.5,\"metrics\""),
                GOOD.replace("\"units\":98,\"metrics\"", "\"units\":\"98\",\"metrics\""),
                GOOD.replace("\"units\":98,\"metrics\"", "\"units\":0,\"metrics\""),
                GOOD.replace("\"units\":98,\"metrics\"", "\"units\":123456789012345678901234567890,\"metrics\""),
                GOOD.replace("\"hits\":1,", "\"hits\":-1,"),
                GOOD.replace("\"baseRate\":\"0.01000000\"", "\"baseRate\":0.01"),
                GOOD.replace("\"baseRate\":\"0.01000000\"", "\"baseRate\":\"1.00000001\""),
                GOOD.replace("\"baseRate\":\"0.01000000\"", "\"baseRate\":\"0.010000000\""),
                GOOD.replace(".123Z", ".1234567Z"), GOOD.replace(".123Z", "Z"), GOOD.replace(".123Z", "+00:00"),
                GOOD.replace("0123456789abcdef", "0123456789ABCDEF"),
                GOOD.replace("\"modelId\":\"nvidia/nemotron-3-ultra:free\"", "\"modelId\":\"\""),
                GOOD.replace("\"modelId\":\"nvidia/nemotron-3-ultra:free\"", "\"modelId\":\"" + "m".repeat(200) + "\""),
                GOOD.replace("\"modelId\":\"nvidia/nemotron-3-ultra:free\"", "\"modelId\":\"" + "m".repeat(201) + "\""),
                GOOD.replace("\"modelId\":\"nvidia/nemotron-3-ultra:free\"", "\"modelId\":\"모델\""),
                GOOD.replace("\"testVersion\":\"detection-month/1\"", "\"testVersion\":\"detection-month/01\""),
                GOOD.replace("\"testVersion\":\"detection-month/1\"",
                        "\"testVersion\":\"d" + "x".repeat(29) + "/1\""),
                GOOD.replace("\"testVersion\":\"detection-month/1\"",
                        "\"testVersion\":\"d" + "x".repeat(28) + "/1\""),
                GOOD.replace("\"windows\":{", "\"windows\":{\"12\":{},")));
        docs.add("{\"schema\":\"detection-summary/1\",\"modelId\":\"m\",\"testVersion\":\"t/1\",\"sampleLayer\":\"L\","
                + "\"runAt\":\"2026-01-01T00:00:00Z\",\"sourceSha256\":\"" + "a".repeat(64) + "\",\"windows\":{}}");
        for (String doc : docs) {
            JsonNode node = parse(doc);
            assertThat(DetectionSummaryDocument.checkShape(node).isEmpty()).as(doc).isEqualTo(SCHEMA.validate(node).isEmpty());
        }
    }

    @Test
    void five_thousand_seeded_mutations_get_the_same_verdict_from_the_check_and_the_schema_file() {
        Random random = new Random(20261123L);
        int invalid = 0;
        for (int i = 0; i < 5000; i++) {
            JsonNode doc = good();
            for (int m = 0, n = 1 + random.nextInt(3); m < n; m++) {
                mutate(doc, random);
            }
            boolean ours = DetectionSummaryDocument.checkShape(doc).isEmpty();
            boolean theirs = SCHEMA.validate(doc).isEmpty();
            assertThat(ours).as(doc.toString()).isEqualTo(theirs);
            if (!theirs) {
                invalid++;
            }
        }
        assertThat(invalid).as("the mutations break the shape often enough to mean something").isBetween(1500, 4500);
    }

    private static final Map<String, Object[]> FITTING = Map.of(
            "hits", new Object[] {0, 1, 3, 6, 100000}, "units", new Object[] {1, 3, 6, 100000},
            "baseRate", new Object[] {"0.50000000", "1.00000000", "0.00000000"},
            "modelId", new Object[] {"m", "a/b:c", "gemma4:31b"}, "testVersion", new Object[] {"t/1", "detection-month/2"},
            "sampleLayer", new Object[] {"L", "MVP-01"}, "runAt", new Object[] {"2026-11-23T09:00:00Z"},
            "sourceSha256", new Object[] {"a".repeat(64)}, "schema", new Object[] {"detection-summary/1"});

    /** Replaces, removes or adds one member somewhere in the document. Strings never end in a line break. */
    private static void mutate(JsonNode root, Random random) {
        List<ObjectNode> objects = new ArrayList<>();
        collect(root, objects);
        ObjectNode target = objects.get(random.nextInt(objects.size()));
        Object[] values = {null, 0, 1, -1, 3, 6, 100000, 100001, 1.5, true, "", "x", "1", "3", "6", "P0-08", "p0-08",
                "detection-month/1", "detection-summary/1", "2026-11-23T09:00:00Z", "a".repeat(64), "0.50000000",
                "0.5", "YM_EXACT", "가"};
        List<String> names = new ArrayList<>(target.propertyNames());
        int choice = random.nextInt(6);
        if (choice >= 4 && !names.isEmpty()) {
            // a value that may well keep the shape, so valid documents stay common
            String name = names.get(random.nextInt(names.size()));
            Object[] fitting = FITTING.getOrDefault(name, values);
            target.set(name, MAPPER.valueToTree(fitting[random.nextInt(fitting.length)]));
        } else if (choice == 0 && !names.isEmpty()) {
            target.remove(names.get(random.nextInt(names.size())));
        } else if (choice == 1) {
            String[] extra = {"extra", "1", "3", "6", "2", "YEAR", "hits"};
            target.put(extra[random.nextInt(extra.length)], 1);
        } else if (!names.isEmpty()) {
            String name = names.get(random.nextInt(names.size()));
            Object value = values[random.nextInt(values.length)];
            if (value == null) {
                target.putNull(name);
            } else if (random.nextInt(8) == 0) {
                target.set(name, MAPPER.createArrayNode().add("x"));
            } else if (random.nextInt(8) == 0) {
                target.set(name, MAPPER.createObjectNode());
            } else {
                target.set(name, MAPPER.valueToTree(value));
            }
        }
    }

    private static void collect(JsonNode node, List<ObjectNode> out) {
        if (node instanceof ObjectNode object) {
            out.add(object);
            node.forEach(child -> collect(child, out));
        }
    }
}
