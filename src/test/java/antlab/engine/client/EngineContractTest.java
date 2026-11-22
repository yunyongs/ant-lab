package antlab.engine.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.history.RegimePool;
import antlab.support.FakeEngineServer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java side of the two-sided contract: requests the client builds and the shared fixtures are checked against
 * docs/api/engine.yaml, and the Java records are compared with the yaml schemas by reflection. The Python side reads the
 * same manifest (engine/tests/test_contract_fixtures.py). No Spring context.
 */
class EngineContractTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final Path CONTRACT = Path.of("docs/api/engine.yaml");
    private static final JsonNode YAML = loadYaml();
    private static final JsonNode COMPONENTS = YAML.get("components");

    record Entry(String file, String kind, String operation, int status, boolean valid) {
    }

    private static JsonNode loadYaml() {
        try {
            return MAPPER.valueToTree(new Yaml().load(Files.readString(CONTRACT)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Schema schema(String name) {
        var wrapper = MAPPER.createObjectNode();
        wrapper.put("$ref", "#/components/schemas/" + name);
        wrapper.set("components", COMPONENTS);
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(wrapper);
    }

    static List<Entry> manifest() {
        JsonNode root = MAPPER.readTree(FakeEngineServer.fixture("manifest.json"));
        List<Entry> entries = new ArrayList<>();
        root.get("files").forEach(f -> entries.add(new Entry(f.get("file").asString(), f.get("kind").asString(),
                f.get("operation").isNull() ? null : f.get("operation").asString(), f.path("status").asInt(0),
                f.get("valid").asBoolean())));
        return entries;
    }

    static Stream<Entry> entries() {
        return manifest().stream();
    }

    // --- yaml navigation -------------------------------------------------------------------------------------------

    private static String last(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private static JsonNode operation(String operationId) {
        for (var path : YAML.get("paths").properties()) {
            for (var method : path.getValue().properties()) {
                if (operationId.equals(method.getValue().path("operationId").asString())) {
                    return method.getValue();
                }
            }
        }
        throw new IllegalArgumentException("no operation " + operationId);
    }

    private static String requestSchemaName(String operationId) {
        return last(operation(operationId).at("/requestBody/content/application~1json/schema/$ref").asString());
    }

    /** Schema name of the response the yaml declares for an operation and status, or null when it declares none. */
    private static String responseSchemaName(String operationId, int status) {
        JsonNode response = operation(operationId).get("responses").get(Integer.toString(status));
        if (response == null) {
            return null;
        }
        if (response.has("$ref")) {
            response = COMPONENTS.get("responses").get(last(response.get("$ref").asString()));
        }
        JsonNode content = response.get("content").properties().iterator().next().getValue();
        return last(content.at("/schema/$ref").asString());
    }

    private static String schemaOf(Entry e) {
        if (e.kind().equals("request")) {
            return requestSchemaName(e.operation());
        }
        if (e.operation() == null) {
            return "Problem";
        }
        String name = responseSchemaName(e.operation(), e.status());
        assertThat(name).as("yaml declares %s %s", e.operation(), e.status()).isNotNull();
        return name;
    }

    private static Class<?> javaTypeOf(Entry e) {
        if (e.kind().equals("request")) {
            return switch (e.operation()) {
                case "createPath" -> EnginePathRequest.class;
                case "createProjection" -> EngineProjectionRequest.class;
                case "evaluateRegime" -> EngineRegimeRequest.class;
                default -> throw new AssertionError("no Java request type for " + e.operation());
            };
        }
        if (e.status() != 200) {
            return EngineProblem.class;
        }
        return switch (e.operation()) {
            case "createPath" -> EnginePathResult.class;
            case "createProjection" -> EngineProjectionResult.class;
            case "evaluateRegime" -> EngineRegimeResult.class;
            default -> EngineInfo.class;
        };
    }

    // --- C2, C5: fixtures against the yaml schema and the Java records -----------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    void 픽스처는_yaml_스키마와_자바_레코드가_같은_판정을_낸다(Entry entry) {
        String json = FakeEngineServer.fixture(entry.file());
        JsonNode node = MAPPER.readTree(json);

        boolean schemaAccepts = schema(schemaOf(entry)).validate(node).isEmpty();
        boolean javaAccepts = accepts(javaTypeOf(entry), json);

        assertThat(schemaAccepts).as("yaml schema").isEqualTo(entry.valid());
        assertThat(javaAccepts).as("java records").isEqualTo(entry.valid());
    }

    private static boolean accepts(Class<?> type, String json) {
        try {
            EngineWire.read(type, json.getBytes());
            return true;
        } catch (tools.jackson.core.JacksonException e) {
            return false;
        }
    }

    @Test
    void 매니페스트는_모든_픽스처_파일을_빠짐없이_올리고_상태_조합이_yaml과_같다() throws IOException {
        Set<String> listed = manifest().stream().map(Entry::file).collect(Collectors.toCollection(TreeSet::new));
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> files = Files.list(FakeEngineServer.CONTRACT_DIR)) {
            files.map(p -> p.getFileName().toString()).filter(n -> !n.equals("manifest.json")).forEach(onDisk::add);
        }
        assertThat(listed).isEqualTo(onDisk);

        assertThat(manifestOperations()).as("operations of the yaml the client calls, and the manifest").isEqualTo(clientOperations());
        for (String operationId : clientOperations()) {
            Set<Integer> declared = new TreeSet<>();
            operation(operationId).get("responses").propertyNames().forEach(s -> declared.add(Integer.parseInt(s)));
            Set<Integer> covered = manifest().stream().filter(e -> operationId.equals(e.operation()) && e.kind().equals("response")
                    && e.valid()).map(Entry::status).collect(Collectors.toCollection(TreeSet::new));
            assertThat(covered).as(operationId).isEqualTo(declared);
        }
    }

    /** Yaml operations the Java client never calls (the health probe and the percentile route belong to other callers). */
    private static final Set<String> NOT_CALLED_BY_JAVA = Set.of("healthz", "createPercentile");

    private static Set<String> clientOperations() {
        Set<String> operations = new TreeSet<>();
        for (var path : YAML.get("paths").properties()) {
            for (var method : path.getValue().properties()) {
                operations.add(method.getValue().get("operationId").asString());
            }
        }
        operations.removeAll(NOT_CALLED_BY_JAVA);
        return operations;
    }

    private static Set<String> manifestOperations() {
        return manifest().stream().map(Entry::operation).filter(java.util.Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
    }

    // --- C1: the requests the client builds ---------------------------------------------------------------------------

    @Test
    void 클라이언트가_만드는_요청은_yaml_요청_스키마를_통과하고_요청_픽스처와_같다() {
        Map<String, Object> built = Map.of(
                "paths-request-p0.json", EnginePathRequest.of("p0-boot6-v1", 14, 12, LocalDate.of(2026, 1, 2)),
                "paths-request-m2.json", EnginePathRequest.of("m2-sb6-v1", Long.MIN_VALUE, 12, LocalDate.of(2026, 1, 2)),
                "paths-request-m2c.json", EnginePathRequest.of("m2-sbc6-v1", 20261123, 12, LocalDate.of(2026, 1, 2)),
                "projection-request.json", EngineHarness.projectionRequest(),
                "regime-request.json", EngineHarness.regimeRequest());
        for (Entry e : manifest().stream().filter(x -> x.kind().equals("request")).toList()) {
            JsonNode sent = MAPPER.readTree(EngineWire.write(built.get(e.file())));

            assertThat(schema(schemaOf(e)).validate(sent)).as(e.file()).isEmpty();
            assertThat(sent).as(e.file()).isEqualTo(MAPPER.readTree(FakeEngineServer.fixture(e.file())));
        }
    }

    @Test
    void 시드_극단값과_모든_길이_버전도_요청_스키마를_통과한다() {
        Schema paths = schema("PathRequest");
        for (long seed : new long[] { Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE }) {
            for (String version : EnginePathRequest.VERSIONS) {
                for (int length : EnginePathRequest.LENGTHS) {
                    JsonNode sent = MAPPER.readTree(EngineWire.write(EnginePathRequest.of(version, seed, length, LocalDate.of(2026, 1, 2))));
                    assertThat(paths.validate(sent)).as("%s %d %d".formatted(version, seed, length)).isEmpty();
                }
            }
        }
    }

    @Test
    void 스키마_검사기는_위반을_실제로_잡는다() {
        Schema paths = schema("PathRequest");
        assertThat(paths.validate(MAPPER.readTree("{\"engineVersion\":\"m2-sb6-v1\",\"seed\":14,\"length\":12,\"baseDate\":\"2026-01-02\"}")))
                .isNotEmpty();
        assertThat(paths.validate(MAPPER.readTree("{\"engineVersion\":\"m2-sb6-v1\",\"seed\":\"14\",\"length\":36,\"baseDate\":\"2026-01-02\"}")))
                .isNotEmpty();
        assertThat(paths.validate(MAPPER.readTree("{\"engineVersion\":\"m2-sb6-v1\",\"seed\":\"14\",\"length\":12,\"baseDate\":\"2026-01-02\",\"x\":1}")))
                .isNotEmpty();
    }

    // --- C3: records against the yaml schemas ---------------------------------------------------------------------------

    private static final Map<Class<?>, String> SCHEMAS = Map.ofEntries(
            Map.entry(EnginePathRequest.class, "PathRequest"),
            Map.entry(EnginePathResult.class, "PathResult"),
            Map.entry(EnginePathResult.Block.class, "PathBlock"),
            Map.entry(EngineProjectionRequest.class, "ProjectionRequest"),
            Map.entry(EngineProjectionRequest.Portfolio.class, "ProjectionPortfolio"),
            Map.entry(EngineProjectionRequest.Holding.class, "Holding"),
            Map.entry(EngineProjectionResult.class, "ProjectionResult"),
            Map.entry(EngineProjectionResult.PortfolioResult.class, "ProjectionPortfolioResult"),
            Map.entry(EngineProjectionResult.Band.class, "ProjectionBand"),
            Map.entry(EngineProjectionResult.Summary.class, "ProjectionSummary"),
            Map.entry(EngineProjectionResult.EndQuantiles.class, "EndQuantiles"),
            Map.entry(EngineProjectionResult.MddQuantiles.class, "MddQuantiles"),
            Map.entry(EngineRegimeRequest.class, "RegimeRequest"),
            Map.entry(EngineRegimeResult.class, "RegimeResult"),
            Map.entry(EngineRegimeResult.Month.class, "RegimeMonth"),
            Map.entry(EngineRegimeResult.Axes.class, "Axes"),
            Map.entry(EngineRegimeResult.AxisReading.class, "AxisReading"),
            Map.entry(EngineRegimeResult.Probabilities.class, "Probabilities"),
            Map.entry(EngineRegimeResult.Quality.class, "Quality"),
            Map.entry(EngineRegimeResult.Coverage.class, "RegimeCoverage"),
            Map.entry(EngineInfo.class, "EngineInfo"),
            Map.entry(EngineProblem.class, "Problem"),
            Map.entry(EngineProblem.FieldViolation.class, "FieldViolation"));

    private static final Set<Class<?>> REQUESTS = Set.of(EnginePathRequest.class, EngineProjectionRequest.class,
            EngineProjectionRequest.Portfolio.class, EngineProjectionRequest.Holding.class, EngineRegimeRequest.class);

    /** Optional yaml properties the client deliberately does not send (overlap avoidance, plan O-4). */
    private static final Map<Class<?>, Set<String>> NOT_SENT = Map.of(EnginePathRequest.class, Set.of("recentMonths", "maxOverlap"));

    private static Set<String> requiredOf(Class<?> record) {
        Constructor<?> canonical = canonical(record);
        Set<String> required = new TreeSet<>();
        RecordComponent[] components = record.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            JsonProperty annotation = canonical.getParameters()[i].getAnnotation(JsonProperty.class);
            if (annotation != null && annotation.required()) {
                required.add(components[i].getName());
            }
        }
        return required;
    }

    private static Constructor<?> canonical(Class<?> record) {
        Class<?>[] types = Stream.of(record.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new);
        try {
            return record.getDeclaredConstructor(types);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code anyOf: [X, {type: 'null'}]} is a nullable X: the Java type is the same, so look at X. */
    private static JsonNode withoutNull(JsonNode property) {
        if (!property.has("anyOf")) {
            return property;
        }
        List<JsonNode> branches = new ArrayList<>();
        property.get("anyOf").forEach(branch -> {
            if (!"null".equals(branch.path("type").asString())) {
                branches.add(branch);
            }
        });
        assertThat(branches).as("anyOf is X or null").hasSize(1);
        return branches.getFirst();
    }

    private static void assertKind(String where, Type type, JsonNode declared) {
        JsonNode property = withoutNull(declared);
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
        if (property.has("$ref")) {
            String target = last(property.get("$ref").asString());
            if (SCHEMAS.containsKey(raw)) {
                assertThat(SCHEMAS.get(raw)).as(where + " $ref").isEqualTo(target);
            } else if (raw.isEnum()) {
                assertThat(constants(raw)).as(where + " enum").isEqualTo(enumOf(COMPONENTS.at("/schemas/" + target)));
            } else {
                throw new AssertionError(where + " has an unmapped $ref type " + raw);
            }
            return;
        }
        String yamlType = property.get("type").asString();
        if (raw == String.class || raw == LocalDate.class || raw == BigDecimal.class) {
            assertThat(yamlType).as(where).isEqualTo("string");
        } else if (raw.isEnum()) {
            assertThat(yamlType).as(where).isEqualTo("string");
            assertThat(constants(raw)).as(where + " enum").isEqualTo(enumOf(property));
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            assertThat(yamlType).as(where).isEqualTo("integer");
        } else if (raw == boolean.class || raw == Boolean.class) {
            assertThat(yamlType).as(where).isEqualTo("boolean");
        } else if (raw == List.class) {
            assertThat(yamlType).as(where).isEqualTo("array");
            assertKind(where + "[]", ((ParameterizedType) type).getActualTypeArguments()[0], property.get("items"));
        } else if (raw == Map.class) {
            assertThat(yamlType).as(where).isEqualTo("object");
            assertThat(((ParameterizedType) type).getActualTypeArguments()[0]).as(where + " keys").isEqualTo(String.class);
            assertKind(where + "{}", ((ParameterizedType) type).getActualTypeArguments()[1], property.get("additionalProperties"));
        } else {
            throw new AssertionError(where + " has an unmapped Java type " + raw);
        }
    }

    private static Set<String> constants(Class<?> enumType) {
        Set<String> names = new TreeSet<>();
        for (Object c : enumType.getEnumConstants()) {
            names.add(((Enum<?>) c).name());
        }
        return names;
    }

    private static Set<String> enumOf(JsonNode schema) {
        Set<String> values = new TreeSet<>();
        schema.get("enum").forEach(v -> values.add(v.asString()));
        return values;
    }

    @Test
    void 자바_레코드는_yaml_스키마와_필드_필수_타입이_같다() {
        for (var entry : SCHEMAS.entrySet()) {
            Class<?> record = entry.getKey();
            JsonNode schema = COMPONENTS.at("/schemas/" + entry.getValue());
            Set<String> yamlProperties = new TreeSet<>();
            schema.get("properties").propertyNames().forEach(yamlProperties::add);
            Set<String> yamlRequired = new TreeSet<>();
            schema.path("required").forEach(r -> yamlRequired.add(r.asString()));
            Set<String> javaFields = Stream.of(record.getRecordComponents()).map(RecordComponent::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            String name = record.getSimpleName() + "/" + entry.getValue();

            Set<String> skipped = NOT_SENT.getOrDefault(record, Set.of());
            Set<String> expectedFields = new TreeSet<>(yamlProperties);
            expectedFields.removeAll(skipped);
            assertThat(javaFields).as(name + " fields").isEqualTo(expectedFields);
            assertThat(skipped).as(name + " skipped properties exist in yaml").isSubsetOf(yamlProperties);
            Set<String> javaRequired = requiredOf(record);
            if (REQUESTS.contains(record)) {
                assertThat(javaRequired).as(name + " required (request: at least the yaml set)").containsAll(yamlRequired);
            } else {
                assertThat(javaRequired).as(name + " required").isEqualTo(yamlRequired);
            }
            for (RecordComponent component : record.getRecordComponents()) {
                assertKind(name + "." + component.getName(), component.getGenericType(),
                        schema.get("properties").get(component.getName()));
            }
        }
    }

    @Test
    void 요청_한도와_정규식은_yaml_과_글자_단위로_같다() {
        JsonNode paths = COMPONENTS.at("/schemas/PathRequest/properties");
        assertThat(enumOf(paths.get("engineVersion"))).isEqualTo(new TreeSet<>(EnginePathRequest.VERSIONS));
        Set<Integer> lengths = new TreeSet<>();
        paths.at("/length/enum").forEach(v -> lengths.add(v.asInt()));
        assertThat(lengths).isEqualTo(new TreeSet<>(EnginePathRequest.LENGTHS));
        assertThat(paths.at("/seed/pattern").asString()).isEqualTo(EngineWire.SEED.pattern());
        assertThat(COMPONENTS.at("/schemas/ProjectionRequest/properties/seasonSeed/pattern").asString())
                .isEqualTo(EngineWire.SEED.pattern());
        assertThat(COMPONENTS.at("/schemas/ProjectionRequest/properties/riskLimit/pattern").asString())
                .isEqualTo(EngineWire.RISK_LIMIT.pattern());
        assertThat(COMPONENTS.at("/schemas/ProjectionPortfolio/properties/key/pattern").asString()).isEqualTo(EngineWire.KEY.pattern());
        assertThat(COMPONENTS.at("/schemas/ProjectionPortfolio/properties/cash/pattern").asString()).isEqualTo(EngineWire.SHARE.pattern());
        assertThat(COMPONENTS.at("/schemas/Holding/properties/weight/pattern").asString()).isEqualTo(EngineWire.SHARE.pattern());
        assertThat(COMPONENTS.at("/schemas/Holding/properties/asset/pattern").asString()).isEqualTo(EngineWire.ASSET.pattern());
        assertThat(COMPONENTS.at("/schemas/PathResult/properties/historyDigest/pattern").asString()).isEqualTo(EngineWire.DIGEST.pattern());
        assertThat(COMPONENTS.at("/schemas/ProjectionResult/properties/historyDigest/pattern").asString())
                .isEqualTo(EngineWire.DIGEST.pattern());
        assertThat(COMPONENTS.at("/schemas/PathResult/properties/months/items/pattern").asString()).isEqualTo(EngineWire.MONTH.pattern());

        JsonNode projection = COMPONENTS.at("/schemas/ProjectionRequest/properties");
        assertThat(projection.at("/n/minimum").asInt()).isEqualTo(100);
        assertThat(projection.at("/n/maximum").asInt()).isEqualTo(5000);
        assertThat(projection.at("/portfolios/maxItems").asInt()).isEqualTo(EngineProjectionRequest.MAX_PORTFOLIOS);
        assertThat(projection.at("/summaryTurns/maxItems").asInt()).isEqualTo(EngineProjectionRequest.MAX_SUMMARY_TURNS);
        assertThat(COMPONENTS.at("/schemas/ProjectionPortfolio/properties/weights/maxItems").asInt())
                .isEqualTo(EngineProjectionRequest.MAX_HOLDINGS);
        Set<Integer> horizons = new LinkedHashSet<>();
        projection.at("/horizonTurns/enum").forEach(v -> horizons.add(v.asInt()));
        assertThat(horizons).containsExactlyInAnyOrder(60, 120);
        assertThat(Pattern.compile(EngineWire.SHARE.pattern()).matcher("0.5").matches()).isTrue();
    }

    @Test
    void 국면_요청_한도와_정규식_열거는_yaml_과_글자_단위로_같다() {
        JsonNode request = COMPONENTS.at("/schemas/RegimeRequest/properties");
        assertThat(request.at("/labelVersion/const").asString()).isEqualTo(EngineRegimeRequest.LABEL_VERSION);
        assertThat(request.at("/months/maxItems").asInt()).isEqualTo(EngineRegimeRequest.MAX_MONTHS);
        assertThat(request.at("/months/minItems").asInt()).isEqualTo(1);
        assertThat(request.at("/months/items/pattern").asString()).isEqualTo(EngineRegimeRequest.REQUEST_MONTH.pattern());
        assertThat(COMPONENTS.at("/schemas/RegimeResult/properties/labelVersion/const").asString()).isEqualTo(EngineRegimeRequest.LABEL_VERSION);
        assertThat(COMPONENTS.at("/schemas/RegimeResult/properties/inputDigest/pattern").asString()).isEqualTo(EngineWire.DIGEST.pattern());

        JsonNode month = COMPONENTS.at("/schemas/RegimeMonth/properties");
        assertThat(month.at("/month/pattern").asString()).isEqualTo(EngineRegimeRequest.REQUEST_MONTH.pattern());
        assertThat(enumOf(month.at("/indicators/propertyNames"))).isEqualTo(new TreeSet<>(EngineRegimeResult.INDICATOR_KEYS));
        assertThat(month.at("/indicators/additionalProperties/anyOf/0/pattern").asString())
                .isEqualTo(EngineRegimeResult.INDICATOR_VALUE.pattern());
        assertThat(COMPONENTS.at("/schemas/AxisReading/properties/strength/pattern").asString()).isEqualTo(EngineRegimeResult.STRENGTH.pattern());
        for (String name : List.of("p1", "p2", "p3", "p4")) {
            assertThat(COMPONENTS.at("/schemas/Probabilities/properties/" + name + "/pattern").asString())
                    .isEqualTo(EngineRegimeResult.PROBABILITY.pattern());
        }
        JsonNode coverage = COMPONENTS.at("/schemas/RegimeCoverage/properties");
        assertThat(enumOf(coverage.at("/indicators/propertyNames"))).isEqualTo(new TreeSet<>(EngineRegimeResult.INDICATOR_KEYS));
        assertThat(enumOf(coverage.at("/ruleDatedRows/propertyNames"))).isEqualTo(new TreeSet<>(EngineRegimeResult.RULE_DATED_SERIES));
    }

    @Test
    void 국면_연산의_URI와_상태_조합은_yaml_과_같다() {
        JsonNode regime = YAML.at("/paths/~1v1~1regime~1evaluate/post");
        assertThat(regime.get("operationId").asString()).isEqualTo("evaluateRegime");
        assertThat(EngineClient.REGIME_URI).isEqualTo("/v1/regime/evaluate");
        assertThat(operation("evaluateRegime")).isEqualTo(regime);
    }

    @Test
    void 토큰_헤더_이름은_yaml_보안_스키마와_같다() {
        JsonNode scheme = COMPONENTS.at("/securitySchemes/EngineToken");
        assertThat(scheme.get("type").asString()).isEqualTo("apiKey");
        assertThat(scheme.get("in").asString()).isEqualTo("header");
        assertThat(EngineClient.TOKEN_HEADER).isEqualTo(scheme.get("name").asString()).isEqualTo("X-Antlab-Engine-Token");
        assertThat(YAML.at("/security/0").has("EngineToken")).isTrue();
    }

    // --- C6 ------------------------------------------------------------------------------------------------------------

    @Test
    void 매퍼가_아는_엔진_코드는_yaml_ErrorCode_와_정확히_같다() {
        Set<String> yaml = enumOf(COMPONENTS.at("/schemas/ErrorCode"));

        assertThat(constants(EngineErrorCode.class)).isEqualTo(yaml);
        Set<String> mapped = new TreeSet<>();
        EngineErrorMapper.knownCodes().forEach(code -> mapped.add(code.name()));
        assertThat(mapped).isEqualTo(yaml);
    }

    private record MapProbe(Map<String, String> text, Map<String, Integer> counts, String plain) {
    }

    @Test
    void 계약_검사는_nullable과_Map_드리프트도_잡는다() {
        Type text = MapProbe.class.getRecordComponents()[0].getGenericType();
        Type counts = MapProbe.class.getRecordComponents()[1].getGenericType();
        assertKind("ok", text, MAPPER.readTree("{\"type\":\"object\",\"additionalProperties\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}}"));
        assertKind("ok", counts, MAPPER.readTree("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"}}"));
        assertKind("ok", String.class, MAPPER.readTree("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}"));
        assertThatThrownBy(() -> assertKind("x", text, MAPPER.readTree("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\"}}")))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertKind("x", counts, MAPPER.readTree("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}")))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertKind("x", String.class, MAPPER.readTree("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}")))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertKind("x", RegimePool.class, MAPPER.readTree("{\"type\":\"string\",\"enum\":[\"P1\",\"P2\"]}")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void 계약_검사_자체가_드리프트를_잡는다() {
        assertThatThrownBy(() -> assertKind("x", String.class, MAPPER.readTree("{\"type\":\"integer\"}")))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertKind("x", int.class, MAPPER.readTree("{\"type\":\"string\"}")))
                .isInstanceOf(AssertionError.class);
        assertThat(accepts(EnginePathResult.class, "{}")).isFalse();
    }
}
