package antlab.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.decision.SchemaViolation;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hand-written shape check against the two schema files: for the same document both must say valid or invalid, over a
 * set of cases and thousands of seeded mutations. The check also names pointers and keywords from a fixed vocabulary and
 * never a value. No Spring context.
 */
class MemoryDocumentsTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final Schema REFLECTION = schema("docs/api/season-reflection-1.schema.json");
    private static final Schema NOTE = schema("docs/api/operating-note-1.schema.json");
    private static final Set<String> KEYWORDS = Set.of("type", "required", "additionalProperties", "const", "enum",
            "minLength", "maxLength", "minItems", "maxItems", "encoding");

    private static Schema schema(String file) {
        try {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(MAPPER.readTree(Files.readString(Path.of(file))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String GOOD_REFLECTION = """
            {"schema":"season-reflection/1","good":["a","b","c"],
             "mistakes":[{"type":"PANIC_SELL","text":"sold the dip"}],"ruleCandidates":["keep cash"]}""";
    private static final String GOOD_NOTE = "{\"schema\":\"operating-note/1\",\"rules\":[\"keep cash\"]}";

    private static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    private static List<String> keywordsAt(List<SchemaViolation> violations, String pointer) {
        return violations.stream().filter(v -> v.pointer().equals(pointer)).map(SchemaViolation::keyword).toList();
    }

    @Test
    void a_valid_reflection_and_note_have_no_violation() {
        assertThat(MemoryDocuments.checkReflection(parse(GOOD_REFLECTION))).isEmpty();
        assertThat(MemoryDocuments.checkNote(parse(GOOD_NOTE))).isEmpty();
    }

    @Test
    void lines_that_are_not_strings_are_named_where_the_filter_would_skip_them() {
        List<SchemaViolation> v = MemoryDocuments.checkReflection(parse(
                "{\"schema\":\"season-reflection/1\",\"good\":[{\"x\":\"2008년\"},\"b\",\"c\"],\"mistakes\":[],"
                        + "\"ruleCandidates\":[]}"));

        assertThat(v).containsExactly(new SchemaViolation("/good/0", "type"));
    }

    @Test
    void the_violations_name_pointers_and_keywords_and_never_a_value_or_a_key() {
        List<SchemaViolation> v = MemoryDocuments.checkReflection(parse(
                "{\"schema\":\"nope\",\"good\":[\"a\"],\"mistakes\":[{\"type\":\"2008년\",\"text\":\"\",\"secretKey\":1}],"
                        + "\"ruleCandidates\":[\"1\",\"2\",\"3\",\"4\"],\"2008\":1}"));

        assertThat(v).extracting(SchemaViolation::keyword).isSubsetOf(KEYWORDS);
        assertThat(v).extracting(SchemaViolation::pointer).allMatch(p -> p.matches("(/[A-Za-z]+|/[0-9]+)*"));
        assertThat(keywordsAt(v, "")).containsExactly("additionalProperties");
        assertThat(keywordsAt(v, "/schema")).containsExactly("const");
        assertThat(keywordsAt(v, "/good")).containsExactly("minItems");
        assertThat(keywordsAt(v, "/mistakes/0")).containsExactly("additionalProperties");
        assertThat(keywordsAt(v, "/mistakes/0/type")).containsExactly("enum");
        assertThat(keywordsAt(v, "/mistakes/0/text")).containsExactly("minLength");
        assertThat(keywordsAt(v, "/ruleCandidates")).containsExactly("maxItems");
        assertThat(v.toString()).doesNotContain("2008").doesNotContain("secretKey");
    }

    @Test
    void a_nul_and_an_unpaired_surrogate_are_encoding_violations_and_a_pair_is_fine() {
        String nul = "{\"schema\":\"operating-note/1\",\"rules\":[\"a\\u0000b\"]}";
        String high = "{\"schema\":\"operating-note/1\",\"rules\":[\"a\\ud800b\"]}";
        String low = "{\"schema\":\"operating-note/1\",\"rules\":[\"a\\udc00b\"]}";
        String pair = "{\"schema\":\"operating-note/1\",\"rules\":[\"a\\ud83d\\ude00b\"]}";

        assertThat(MemoryDocuments.checkNote(parse(nul))).containsExactly(new SchemaViolation("/rules/0", "encoding"));
        assertThat(MemoryDocuments.checkNote(parse(high))).containsExactly(new SchemaViolation("/rules/0", "encoding"));
        assertThat(MemoryDocuments.checkNote(parse(low))).containsExactly(new SchemaViolation("/rules/0", "encoding"));
        assertThat(MemoryDocuments.checkNote(parse(pair))).isEmpty();
    }

    @Test
    void lengths_count_code_points_after_nfc() {
        String composed = "가".repeat(120);
        // 120 Hangul syllables written as 2 jamo each are 240 code points raw and 120 after NFC
        String jamo = "\u1100\u1161".repeat(120);
        String over = "x".repeat(121);
        String astral = "😀".repeat(120);
        String doc = "{\"schema\":\"season-reflection/1\",\"good\":[\"%s\",\"%s\",\"%s\"],\"mistakes\":[],\"ruleCandidates\":[]}";

        assertThat(MemoryDocuments.checkReflection(parse(doc.formatted(composed, jamo, astral)))).isEmpty();
        assertThat(MemoryDocuments.checkReflection(parse(doc.formatted(over, "a", "b"))))
                .containsExactly(new SchemaViolation("/good/0", "maxLength"));
        assertThat(MemoryDocuments.checkNote(parse("{\"schema\":\"operating-note/1\",\"rules\":[\"" + "x".repeat(150) + "\"]}")))
                .isEmpty();
        assertThat(MemoryDocuments.checkNote(parse("{\"schema\":\"operating-note/1\",\"rules\":[\"" + "x".repeat(151) + "\"]}")))
                .containsExactly(new SchemaViolation("/rules/0", "maxLength"));
    }

    @Test
    void the_stored_text_is_the_nfc_text() {
        JsonNode stored = MemoryDocuments.nfc(parse("{\"schema\":\"operating-note/1\",\"rules\":[\"\u1100\u1161\"]}"));

        assertThat(stored.get("rules").get(0).asString()).isEqualTo("가");
        assertThat(stored.get("schema").asString()).isEqualTo("operating-note/1");
    }

    @Test
    void the_hand_written_check_and_the_schema_files_agree_on_the_edge_cases() {
        for (String doc : List.of("[]", "null", "\"x\"", "{}", "{\"schema\":\"season-reflection/1\"}", GOOD_REFLECTION,
                GOOD_REFLECTION.replace("\"good\":[\"a\",\"b\",\"c\"]", "\"good\":[\"a\",\"b\"]"),
                GOOD_REFLECTION.replace("\"good\":[\"a\",\"b\",\"c\"]", "\"good\":[\"a\",\"b\",\"c\",\"d\"]"),
                GOOD_REFLECTION.replace("PANIC_SELL", "panic_sell"), GOOD_REFLECTION.replace("\"mistakes\":[", "\"mistakes\":{}["),
                GOOD_REFLECTION.replace("\"ruleCandidates\":[\"keep cash\"]", "\"ruleCandidates\":\"keep cash\""),
                GOOD_REFLECTION.replace("season-reflection/1", "operating-note/1"))) {
            JsonNode node = tryParse(doc);
            if (node != null) {
                assertThat(MemoryDocuments.checkReflection(node).isEmpty()).as(doc).isEqualTo(REFLECTION.validate(node).isEmpty());
            }
        }
        for (String doc : List.of("[]", "{}", "{\"schema\":\"operating-note/1\"}", GOOD_NOTE,
                "{\"schema\":\"operating-note/1\",\"rules\":[]}", "{\"schema\":\"operating-note/1\",\"rules\":[1]}",
                "{\"schema\":\"operating-note/1\",\"rules\":[\"\"]}", GOOD_NOTE.replace("\"rules\"", "\"rule\""),
                "{\"schema\":\"operating-note/1\",\"rules\":[" + "\"r\",".repeat(15) + "\"r\"]}",
                "{\"schema\":\"operating-note/1\",\"rules\":[" + "\"r\",".repeat(14) + "\"r\"]}")) {
            JsonNode node = tryParse(doc);
            if (node != null) {
                assertThat(MemoryDocuments.checkNote(node).isEmpty()).as(doc).isEqualTo(NOTE.validate(node).isEmpty());
            }
        }
    }

    @Test
    void five_thousand_seeded_mutations_get_the_same_verdict_from_the_check_and_the_schema_file() {
        Random random = new Random(20261121L);
        int invalid = 0;
        for (int i = 0; i < 5000; i++) {
            boolean reflection = i % 2 == 0;
            JsonNode doc = parse(reflection ? GOOD_REFLECTION : GOOD_NOTE);
            for (int m = 0, n = 1 + random.nextInt(3); m < n; m++) {
                mutate(doc, random);
            }
            boolean ours = (reflection ? MemoryDocuments.checkReflection(doc) : MemoryDocuments.checkNote(doc)).isEmpty();
            boolean theirs = (reflection ? REFLECTION : NOTE).validate(doc).isEmpty();
            assertThat(ours).as(doc.toString()).isEqualTo(theirs);
            if (!theirs) {
                invalid++;
            }
        }
        assertThat(invalid).as("the mutations break the shape often enough to mean something").isBetween(1500, 4500);
    }

    private static JsonNode tryParse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Replaces, removes or adds one node somewhere in the document; the strings never hold a NUL or a surrogate. */
    private static void mutate(JsonNode root, Random random) {
        List<JsonNode> containers = new ArrayList<>();
        collect(root, containers);
        JsonNode target = containers.get(random.nextInt(containers.size()));
        Object[] values = {null, 1, true, "", "x", "x".repeat(120), "x".repeat(121), "x".repeat(150), "x".repeat(151),
                "RISK_BREACH", "OTHER", "season-reflection/1", "operating-note/1", "2008년", "가"};
        if (target instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            int choice = random.nextInt(4);
            if (choice == 0 && !names.isEmpty()) {
                object.remove(names.get(random.nextInt(names.size())));
            } else if (choice == 1) {
                object.put("extra" + random.nextInt(3), "v");
            } else if (!names.isEmpty()) {
                set(object, names.get(random.nextInt(names.size())), values[random.nextInt(values.length)], random);
            }
        } else if (target instanceof ArrayNode array) {
            int choice = random.nextInt(4);
            if (choice == 0 && array.size() > 0) {
                array.remove(random.nextInt(array.size()));
            } else if (choice == 1) {
                array.add("x");
            } else if (array.size() > 0) {
                int at = random.nextInt(array.size());
                Object value = values[random.nextInt(values.length)];
                if (value == null) {
                    array.set(at, MAPPER.nullNode());
                } else {
                    array.set(at, MAPPER.valueToTree(value));
                }
            }
        }
    }

    private static void set(ObjectNode object, String name, Object value, Random random) {
        if (value == null) {
            object.putNull(name);
        } else if (random.nextInt(8) == 0) {
            object.set(name, MAPPER.createArrayNode().add("x"));
        } else if (random.nextInt(8) == 0) {
            object.set(name, MAPPER.createObjectNode());
        } else {
            object.set(name, MAPPER.valueToTree(value));
        }
    }

    private static void collect(JsonNode node, List<JsonNode> out) {
        if (node.isObject() || node.isArray()) {
            out.add(node);
            node.forEach(child -> collect(child, out));
        }
    }
}
