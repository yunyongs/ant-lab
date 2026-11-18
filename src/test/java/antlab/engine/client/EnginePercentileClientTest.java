package antlab.engine.client;

import static antlab.engine.client.EngineHarness.BASE;
import static antlab.engine.client.EngineHarness.BASE_DATE;
import static antlab.engine.client.EngineHarness.FAKE_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java side of the percentile contract (MVP-18): the shared fixtures in docs/api/fixtures/engine are checked against
 * docs/api/engine.yaml and the Java records, the records are compared with the yaml schemas, and the client's mapping of
 * the engine's refusals is pinned. No Spring context, no network.
 */
class EnginePercentileClientTest {

    private static final Path FIXTURES = Path.of("docs/api/fixtures/engine");
    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final JsonNode YAML = loadYaml();
    private static final JsonNode COMPONENTS = YAML.get("components");
    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");
    private static final String PERCENTILES = BASE + "/v1/percentiles";

    private final EngineHarness h = new EngineHarness();

    record Entry(String file, String kind, boolean valid) {
    }

    private static JsonNode loadYaml() {
        try {
            return MAPPER.valueToTree(new Yaml().load(Files.readString(Path.of("docs/api/engine.yaml"))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fixture(String name) {
        try {
            return Files.readString(FIXTURES.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String engineProblem(String file) {
        try {
            return Files.readString(Path.of("src/test/resources/engine/contract").resolve(file));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Entry> manifest() {
        List<Entry> out = new ArrayList<>();
        MAPPER.readTree(fixture("manifest.json")).get("files")
                .forEach(f -> out.add(new Entry(f.get("file").asString(), f.get("kind").asString(), f.get("valid").asBoolean())));
        return out;
    }

    private static Schema schema(String name) {
        var wrapper = MAPPER.createObjectNode();
        wrapper.put("$ref", "#/components/schemas/" + name);
        wrapper.set("components", COMPONENTS);
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(wrapper);
    }

    private static boolean javaAccepts(Entry entry, String json) {
        try {
            if (entry.kind().equals("request")) {
                EngineWire.read(EnginePercentileRequest.class, json.getBytes());
            } else {
                EngineWire.read(EnginePercentileResult.class, json.getBytes());
            }
            return true;
        } catch (tools.jackson.core.JacksonException e) {
            return false;
        }
    }

    /** The request of the shared fixture, built the way the score service builds one. */
    static EnginePercentileRequest fixtureRequest() {
        return new EnginePercentileRequest("14", BASE_DATE, 60, 2000,
                new EnginePercentileRequest.Portfolio("0.1", List.of(new EngineProjectionRequest.Holding("KR:069500", "0.5"),
                        new EngineProjectionRequest.Holding("US:SPY", "0.4"))),
                new EnginePercentileRequest.Portfolio("0", List.of(new EngineProjectionRequest.Holding("KR:069500", "0.5"),
                        new EngineProjectionRequest.Holding("US:SPY", "0.5"))),
                new EnginePercentileRequest.Observed("1.234567890123", "-0.012345678901"));
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("manifest")
    void 픽스처는_엔진_모델처럼_자바_레코드도_받거나_거절한다(Entry entry) {
        String json = fixture(entry.file());
        String schemaName = entry.kind().equals("request") ? "PercentileRequest" : "PercentileResult";

        assertThat(javaAccepts(entry, json)).as("java records").isEqualTo(entry.valid());
        if (entry.valid()) {
            assertThat(schema(schemaName).validate(MAPPER.readTree(json))).as("yaml schema").isEmpty();
        }
    }

    @Test
    void 합계가_n을_넘는_응답은_스키마로는_통과하지만_자바_레코드가_거절한다() {
        String json = fixture("bad-percentile-response-overcount.json");

        assertThat(schema("PercentileResult").validate(MAPPER.readTree(json))).isEmpty();
        assertThat(javaAccepts(new Entry("x", "response", false), json)).isFalse();
    }

    @Test
    void 클라이언트가_만드는_요청은_스키마를_통과하고_요청_픽스처와_같다() {
        JsonNode sent = MAPPER.readTree(EngineWire.write(fixtureRequest()));

        assertThat(schema("PercentileRequest").validate(sent)).isEmpty();
        assertThat(sent).isEqualTo(MAPPER.readTree(fixture("percentile-request.json")));
    }

    // ---- records against the yaml schema -------------------------------------------------------------------------

    private static Set<String> requiredOf(Class<?> record) {
        Class<?>[] types = Stream.of(record.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Constructor<?> canonical;
        try {
            canonical = record.getDeclaredConstructor(types);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
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

    private static void sameShape(Class<?> record, String schemaName, boolean request) {
        JsonNode schema = COMPONENTS.at("/schemas/" + schemaName);
        Set<String> yamlProperties = new TreeSet<>();
        schema.get("properties").propertyNames().forEach(yamlProperties::add);
        Set<String> yamlRequired = new TreeSet<>();
        schema.path("required").forEach(r -> yamlRequired.add(r.asString()));
        Set<String> javaFields = Stream.of(record.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(javaFields).as(schemaName + " fields").isEqualTo(yamlProperties);
        if (request) {
            // the client always sends the optional n, so it may require more than the yaml does, never less
            assertThat(requiredOf(record)).as(schemaName + " required (request: at least the yaml set)")
                    .containsAll(yamlRequired);
        } else {
            assertThat(requiredOf(record)).as(schemaName + " required").isEqualTo(yamlRequired);
        }
    }

    @Test
    void 자바_레코드는_yaml_스키마와_필드와_필수가_같다() {
        sameShape(EnginePercentileRequest.class, "PercentileRequest", true);
        sameShape(EnginePercentileRequest.Portfolio.class, "PercentilePortfolio", true);
        sameShape(EnginePercentileRequest.Observed.class, "PercentileObserved", true);
        sameShape(EnginePercentileResult.class, "PercentileResult", false);
        sameShape(EnginePercentileResult.Counts.class, "RankCounts", false);
    }

    @Test
    void 한도와_정규식은_yaml과_글자_단위로_같다() {
        JsonNode request = COMPONENTS.at("/schemas/PercentileRequest/properties");
        assertThat(request.at("/seasonSeed/pattern").asString()).isEqualTo(EngineWire.SEED.pattern());
        assertThat(request.at("/n/minimum").asInt()).isEqualTo(100);
        assertThat(request.at("/n/maximum").asInt()).isEqualTo(5000);
        Set<Integer> horizons = new TreeSet<>();
        request.at("/horizonTurns/enum").forEach(v -> horizons.add(v.asInt()));
        assertThat(horizons).isEqualTo(new TreeSet<>(EnginePercentileRequest.HORIZONS));
        assertThat(COMPONENTS.at("/schemas/PercentilePortfolio/properties/cash/pattern").asString())
                .isEqualTo(EngineWire.SHARE.pattern());
        assertThat(COMPONENTS.at("/schemas/PercentilePortfolio/properties/weights/maxItems").asInt())
                .isEqualTo(EnginePercentileRequest.MAX_HOLDINGS);
        assertThat(COMPONENTS.at("/schemas/PercentileObserved/properties/endMultiple/pattern").asString())
                .isEqualTo(EnginePercentileRequest.END_MULTIPLE.pattern());
        assertThat(COMPONENTS.at("/schemas/PercentileObserved/properties/sharpeDiff/pattern").asString())
                .isEqualTo(EnginePercentileRequest.SHARPE_DIFF.pattern());
        assertThat(COMPONENTS.at("/schemas/PercentileResult/properties/historyDigest/pattern").asString())
                .isEqualTo(EngineWire.DIGEST.pattern());
        assertThat(COMPONENTS.at("/schemas/PercentileResult/properties/n/minimum").asInt()).isEqualTo(100);
        assertThat(COMPONENTS.at("/schemas/PercentileResult/properties/n/maximum").asInt()).isEqualTo(5000);
        assertThat(COMPONENTS.at("/schemas/RankCounts/properties/below/maximum").asInt()).isEqualTo(5000);
    }

    @Test
    void 연산은_엔진_yaml에_있고_오류_상태_조합이_매퍼_규칙과_맞는다() {
        JsonNode operation = YAML.at("/paths/~1v1~1percentiles/post");
        assertThat(operation.get("operationId").asString()).isEqualTo("createPercentile");
        Set<String> statuses = new TreeSet<>();
        operation.get("responses").propertyNames().forEach(statuses::add);
        assertThat(statuses).containsExactlyInAnyOrder("200", "400", "401", "404", "422", "500", "503");
        assertThat(EngineClient.PERCENTILES_URI).isEqualTo("/v1/percentiles");
    }

    // ---- client behaviour ----------------------------------------------------------------------------------------

    private AtomicReference<String> expectOk(String body) {
        return expectOk(h, body);
    }

    private static AtomicReference<String> expectOk(EngineHarness h, String body) {
        AtomicReference<String> sent = new AtomicReference<>();
        h.projections.expect(once(), requestTo(PERCENTILES)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(header(EngineClient.TOKEN_HEADER, FAKE_TOKEN))
                .andExpect(request -> sent.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        return sent;
    }

    private void expectProblem(int status, String body) {
        h.projections.expect(once(), requestTo(PERCENTILES))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(PROBLEM).body(body));
    }

    @Test
    void 요청은_토큰_하나와_십진_문자열_본문으로_나가고_정수_개수를_읽는다() {
        AtomicReference<String> sent = expectOk(fixture("percentile-response.json"));

        EnginePercentileResult result = h.client.createPercentile(fixtureRequest());

        h.projections.verify();
        assertThat(MAPPER.readTree(sent.get())).isEqualTo(MAPPER.readTree(fixture("percentile-request.json")));
        assertThat(sent.get()).doesNotContain("\"observed\":{\"endMultiple\":1");
        assertThat(result.n()).isEqualTo(2000);
        assertThat(result.horizonTurns()).isEqualTo(60);
        assertThat(result.endMultiple().below()).isEqualTo(1204);
        assertThat(result.endMultiple().equal()).isEqualTo(1);
        assertThat(result.sharpeDiff().below()).isEqualTo(987);
        assertThat(result.sharpeDiff().equal()).isZero();
    }

    @Test
    void 경로가_모자란_이력은_PATH_TOO_LONG_으로_옮겨진다() {
        expectProblem(422, engineProblem("problem-path-too-long.json"));

        assertThatThrownBy(() -> h.client.createPercentile(fixtureRequest())).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PATH_TOO_LONG));
    }

    @Test
    void 바쁘거나_이력이_없는_엔진은_ENGINE_UNAVAILABLE_로_옮겨진다() {
        expectProblem(503, engineProblem("problem-engine-busy.json"));

        assertThatThrownBy(() -> h.client.createPercentile(fixtureRequest())).isInstanceOf(EngineUnavailableException.class);
    }

    @Test
    void 엔진의_400과_404는_PATH_TOO_LONG도_ENGINE_UNAVAILABLE도_아니고_내부_오류다() {
        expectProblem(400, engineProblem("problem-invalid-argument.json"));
        expectProblem(404, engineProblem("problem-pool-not-found.json"));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> h.client.createPercentile(fixtureRequest()))
                    .isNotInstanceOf(EngineUnavailableException.class).isInstanceOfSatisfying(DomainException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
    }

    @Test
    void 합계가_n을_넘거나_n이_0이거나_키가_더_있는_200은_나쁜_응답이다() {
        String overcount = fixture("bad-percentile-response-overcount.json");
        String nZero = fixture("bad-percentile-response-n-zero.json");
        String extra = fixture("percentile-response.json").replaceFirst("\\{", "{\"perPath\":[1],");
        for (String body : List.of(overcount, nZero, extra)) {
            EngineHarness local = new EngineHarness();
            expectOk(local, body);
            assertThatThrownBy(() -> local.client.createPercentile(fixtureRequest())).isInstanceOfSatisfying(DomainException.class,
                    e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        }
    }

    // ---- request rules -------------------------------------------------------------------------------------------

    @Test
    void 요청_레코드는_잘못된_값을_만들_때_거절한다() {
        var team = fixtureRequest().team();
        var observed = fixtureRequest().observed();
        assertThatThrownBy(() -> new EnginePercentileRequest("14", BASE_DATE, 36, 2000, team, team, observed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePercentileRequest("14", BASE_DATE, 12, 99, team, team, observed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePercentileRequest("14", BASE_DATE, 12, 5001, team, team, observed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePercentileRequest("not-a-seed", BASE_DATE, 12, 2000, team, team, observed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePercentileRequest.Portfolio("0.5", team.weights()))
                .as("cash plus weights is 0.9").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePercentileRequest.Portfolio("0", List.of(
                new EngineProjectionRequest.Holding("KR:069500", "0.5"), new EngineProjectionRequest.Holding("KR:069500", "0.5"))))
                .as("an asset twice").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePercentileRequest.Observed("1.5", "1000000.0"))
                .as("seven integer digits").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePercentileRequest.Observed("-1.5", "0.1"))
                .as("a negative end multiple").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnginePercentileRequest.Observed("1.5", "1.1234567890123"))
                .as("thirteen decimals").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 요청의_문자열_표현에는_시드도_날짜도_값도_없다() {
        assertThat(fixtureRequest().toString()).doesNotContain("14,").doesNotContain("2026").doesNotContain("KR:")
                .doesNotContain("1.2345");
    }
}
