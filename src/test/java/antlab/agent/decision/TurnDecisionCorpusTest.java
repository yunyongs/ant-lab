package antlab.agent.decision;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderRejection;
import antlab.account.OrderSide;
import antlab.account.OrderSource;
import antlab.asset.Position;
import antlab.season.FormationRejection;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared fixture corpus ({@code src/test/resources/agent/turn-decision}) against the schema file and the Java shape
 * check: both must give the verdict the manifest states. The runner's tests read the same files, so the two sides cannot
 * drift apart. No Spring context.
 */
class TurnDecisionCorpusTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final Path CORPUS = Path.of("src/test/resources/agent/turn-decision");
    private static final Path SCHEMA_FILE = Path.of("docs/api/turn-decision-1.schema.json");

    private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(MAPPER.readTree(read(SCHEMA_FILE)));

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Entry(String file, boolean valid, boolean schemaFileMayDiffer) {
    }

    static Stream<Entry> entries() {
        JsonNode manifest = MAPPER.readTree(read(CORPUS.resolve("manifest.json")));
        List<Entry> out = new ArrayList<>();
        manifest.get("files").forEach(f -> out.add(new Entry(f.get("file").asString(), f.get("valid").asBoolean(),
                f.path("schemaFileMayDiffer").asBoolean(false))));
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void the_schema_file_and_the_java_check_give_the_manifest_verdict(Entry entry) {
        JsonNode reply = MAPPER.readTree(read(CORPUS.resolve(entry.file())));

        if (!entry.schemaFileMayDiffer()) {
            assertThat(SCHEMA.validate(reply).isEmpty()).as("schema file").isEqualTo(entry.valid());
        }
        TurnDecisionParser.Result java = TurnDecisionParser.parse(reply);
        assertThat(java.valid()).as("java check").isEqualTo(entry.valid());
        if (!entry.valid()) {
            assertThat(java.violations()).isNotEmpty();
            // pointers and keywords come from a fixed vocabulary: nothing the model wrote is repeated
            assertThat(java.violations()).allSatisfy(v -> {
                assertThat(v.pointer()).matches("(/[a-z_0-9]+)*");
                assertThat(v.keyword()).isIn("type", "required", "additionalProperties", "const", "enum", "pattern",
                        "maxLength", "maxItems", "minimum", "maximum");
            });
        }
    }

    @Test
    void the_manifest_lists_every_file_of_the_corpus_and_both_verdicts_occur() throws IOException {
        Set<String> listed = new TreeSet<>();
        entries().forEach(e -> listed.add(e.file()));
        Set<String> onDisk = new TreeSet<>();
        for (String dir : List.of("good", "bad", "envelope")) {
            try (Stream<Path> files = Files.list(CORPUS.resolve(dir))) {
                files.forEach(f -> onDisk.add(dir + "/" + f.getFileName()));
            }
        }
        assertThat(listed).isEqualTo(onDisk);
        assertThat(entries().filter(Entry::valid).count()).isGreaterThanOrEqualTo(10);
        assertThat(entries().filter(e -> !e.valid()).count()).isGreaterThanOrEqualTo(25);
        assertThat(entries().filter(Entry::schemaFileMayDiffer).count()).isEqualTo(2);
        assertThat(entries().filter(e -> e.file().startsWith("good/")).allMatch(Entry::valid)).isTrue();
        assertThat(MAPPER.readTree(read(CORPUS.resolve("manifest.json"))).get("rules")).hasSizeGreaterThanOrEqualTo(5);
        assertThat(entries().filter(e -> e.file().startsWith("bad/")).noneMatch(Entry::valid)).isTrue();
    }

    @Test
    void the_decision_cannot_carry_a_price_a_fee_or_a_fill() {
        JsonNode props = MAPPER.readTree(read(SCHEMA_FILE));
        assertThat(props.get("additionalProperties").asBoolean()).isFalse();
        for (String def : List.of("order", "formationChange", "source")) {
            assertThat(props.get("$defs").get(def).get("additionalProperties").asBoolean()).as(def).isFalse();
        }
        assertThat(props.toString()).doesNotContain("\"price\"").doesNotContain("\"fee\"").doesNotContain("\"fill");
    }

    @Test
    void the_asset_pattern_ends_without_a_dollar_so_python_agrees_on_a_trailing_newline() {
        String pattern = MAPPER.readTree(read(SCHEMA_FILE)).get("$defs").get("order").get("properties").get("asset")
                .get("pattern").asString();
        assertThat(pattern).endsWith("(?![\\s\\S])").doesNotEndWith("$");
        assertThat(java.util.regex.Pattern.compile(pattern).matcher("KR:000660\n").find()).isFalse();
        assertThat(java.util.regex.Pattern.compile(pattern).matcher("KR:000660").find()).isTrue();
    }

    @Test
    void the_schema_enums_are_the_java_enums() {
        JsonNode defs = MAPPER.readTree(read(SCHEMA_FILE)).get("$defs");
        assertThat(names(defs.get("order").get("properties").get("side").get("enum")))
                .isEqualTo(names(OrderSide.class));
        assertThat(names(defs.get("order").get("properties").get("position").get("enum")))
                .isEqualTo(names(Position.class));
        assertThat(names(defs.get("source").get("properties").get("kind").get("enum")))
                .isEqualTo(names(OrderSource.class));
    }

    @Test
    void the_unit_checks_of_the_shared_paths_are_reject_codes_of_the_decision() {
        Set<String> codes = names(DecisionRejectCode.class);
        assertThat(codes).containsAll(names(OrderRejection.class)).containsAll(names(FormationRejection.class));
        assertThat(codes).containsExactlyInAnyOrder("SCHEMA_INVALID", "TURN_ZERO_FORMATION", "FORMATION_UNCHANGED",
                "FORMATION_INVALID", "ASSET_NOT_IN_POOL", "QUANTITY_INVALID", "INSUFFICIENT_HOLDING", "INSUFFICIENT_CASH",
                "SINGLE_ASSET_LIMIT", "POSITION_REQUIRED", "POSITION_CONFLICT", "POSITION_ON_SELL", "SOURCE_NOT_VISIBLE",
                "SOURCE_NOT_ALLOWED", "THESES_NOT_SUPPORTED");
    }

    @Test
    void the_decision_codes_are_not_api_error_codes() {
        Set<String> api = names(antlab.common.error.ErrorCode.class);
        assertThat(names(DecisionRejectCode.class).stream().filter(api::contains).toList())
                .as("codes that exist on both sides are the ones the human path shares")
                .containsExactlyInAnyOrder("ASSET_NOT_IN_POOL", "INSUFFICIENT_HOLDING", "INSUFFICIENT_CASH",
                        "POSITION_REQUIRED", "POSITION_CONFLICT");
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static <E extends Enum<E>> Set<String> names(Class<E> type) {
        Set<String> out = new TreeSet<>();
        Arrays.stream(type.getEnumConstants()).map(Enum::name).forEach(out::add);
        return out;
    }
}
