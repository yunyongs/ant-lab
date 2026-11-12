package antlab.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import antlab.api.error.ErrorHttpMapping;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.support.E2eHttp;
import antlab.support.E2eHttp.Reply;
import antlab.support.PostgresTestcontainersConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Every error any {@code /api/v1} operation can answer is a ProblemDetail with a {@code code} the contract knows, over a real
 * port (so errors raised outside the DispatcherServlet's advice would show too). Always on: no sample needed.
 *
 * <p>The operation list is read from the handler mapping, never typed here, and must equal the operations in
 * {@code docs/api/openapi.yaml}. Statuses the yaml documents per operation (400, 404, 503) must be listed for the probed
 * operation. 405, 406 and 415 come from the framework for every path and are not listed per operation in the yaml; they
 * are checked for shape and code only. The dev endpoint is outside the contract and not probed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
class ApiErrorSweepTest {

    private static final String UNKNOWN_SEASON = "987654321";
    private static final Set<Integer> FRAMEWORK_STATUSES = Set.of(405, 406, 415);

    record Operation(String method, String template) {
        String key() {
            return method + " " + template;
        }

        boolean writes() {
            return !method.equals("GET");
        }

        boolean onSeason() {
            return template.contains("{seasonId}");
        }

        boolean hasTurnNo() {
            return template.contains("{turnNo}");
        }

        /** A concrete path: {@code seasonId} and {@code turnNo} filled in. */
        String path(String seasonId, String turnNo) {
            return template.replace("{seasonId}", seasonId).replace("{turnNo}", turnNo);
        }

        String path(String seasonId) {
            return path(seasonId, "1");
        }

        String validBody() {
            if (template.endsWith("/orders")) {
                return "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": 1, \"quantity\": 1,"
                        + " \"declaredPosition\": \"MIDFIELDER\"}";
            }
            if (template.endsWith("/formation-changes")) {
                return "{\"expectedTurnNo\": 1, \"formation\": {\"goalkeeper\": 10, \"defender\": 20,"
                        + " \"midfielder\": 30, \"forward\": 40}}";
            }
            if (template.endsWith("/turns")) {
                return "{\"expectedTurnNo\": 0}";
            }
            return "{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                    + " \"midfielder\": 30, \"forward\": 30}}";
        }
    }

    record Probe(String name, int status, ErrorCode code) {
    }

    @LocalServerPort
    int port;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;
    @Autowired
    DataSource dataSource;
    @MockitoSpyBean
    CurrentMember currentMember;

    @AfterEach
    void cleanup() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/cleanup_season_start.sql")).execute(dataSource);
    }

    /** {@code /api/v1} operations from the mapping (the dev endpoint is {@code @Hidden}, local-only and outside the contract). */
    List<Operation> handlerOperations() {
        List<Operation> out = new ArrayList<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/v1/") || pattern.startsWith("/api/v1/dev/")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    out.add(new Operation(method.name(), pattern));
                }
            }
        }
        out.sort((a, b) -> a.key().compareTo(b.key()));
        return out;
    }

    /** {@code "METHOD template" -> documented status codes} read from the contract file. */
    @SuppressWarnings("unchecked")
    static Map<String, Set<Integer>> yamlOperations() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(Path.of("docs/api/openapi.yaml")));
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, Set<Integer>> out = new java.util.TreeMap<>();
        for (var path : paths.entrySet()) {
            for (var op : ((Map<String, Object>) path.getValue()).entrySet()) {
                Map<String, Object> responses = (Map<String, Object>) ((Map<String, Object>) op.getValue()).get("responses");
                Set<Integer> statuses = new TreeSet<>();
                responses.keySet().forEach(k -> statuses.add(Integer.parseInt(k)));
                out.put(op.getKey().toUpperCase() + " " + path.getKey(), statuses);
            }
        }
        return out;
    }

    List<Probe> probesFor(Operation op) {
        List<Probe> probes = new ArrayList<>();
        probes.add(new Probe("wrong method", 405, ErrorCode.METHOD_NOT_ALLOWED));
        probes.add(new Probe("Accept: application/xml", 406, ErrorCode.NOT_ACCEPTABLE));
        if (op.writes()) {
            probes.add(new Probe("text/plain body", 415, ErrorCode.UNSUPPORTED_MEDIA_TYPE));
            probes.add(new Probe("malformed JSON", 400, ErrorCode.INVALID_ARGUMENT));
            probes.add(new Probe("empty object body", 400, ErrorCode.INVALID_ARGUMENT));
        }
        if (op.onSeason()) {
            probes.add(new Probe("non-numeric season id", 400, ErrorCode.INVALID_ARGUMENT));
            probes.add(new Probe("unknown season", 404, ErrorCode.NOT_FOUND));
            probes.add(new Probe("unknown member", 503, ErrorCode.MEMBER_UNAVAILABLE));
        }
        if (op.hasTurnNo()) {
            probes.add(new Probe("non-numeric turn number", 400, ErrorCode.INVALID_ARGUMENT));
        }
        if (op.template.endsWith("/prices")) {
            probes.add(new Probe("non-numeric query turnNo", 400, ErrorCode.INVALID_ARGUMENT));
        }
        if (op.template.equals("/api/v1/seasons")) {
            probes.add(new Probe("unknown member", 503, ErrorCode.MEMBER_UNAVAILABLE));
        }
        return probes;
    }

    private Reply send(E2eHttp http, Operation op, Probe probe) {
        HttpMethod method = HttpMethod.valueOf(op.method());
        String json = "application/json";
        switch (probe.name()) {
            case "wrong method":
                return http.send(HttpMethod.DELETE, op.path(UNKNOWN_SEASON), null, null, json);
            case "Accept: application/xml":
                return http.send(method, op.path(UNKNOWN_SEASON), op.writes() ? op.validBody() : null,
                        op.writes() ? json : null, "application/xml");
            case "text/plain body":
                return http.send(method, op.path(UNKNOWN_SEASON), "plain", "text/plain", json);
            case "malformed JSON":
                return http.send(method, op.path(UNKNOWN_SEASON), "{", json, json);
            case "empty object body":
                return http.send(method, op.path(UNKNOWN_SEASON), "{}", json, json);
            case "non-numeric season id":
                return http.send(method, op.path("abc"), op.writes() ? op.validBody() : null, op.writes() ? json : null, json);
            case "non-numeric turn number":
                return http.send(method, op.path("1", "abc"), null, null, json);
            case "non-numeric query turnNo":
                return http.send(method, op.path("1") + "?turnNo=abc", null, null, json);
            case "unknown season":
                return http.send(method, op.path(UNKNOWN_SEASON), op.writes() ? op.validBody() : null,
                        op.writes() ? json : null, json);
            case "unknown member":
                DomainException unavailable = new DomainException(ErrorCode.MEMBER_UNAVAILABLE, "no current member");
                doThrow(unavailable).when(currentMember).existingId();
                doThrow(unavailable).when(currentMember).ensureId();
                try {
                    return http.send(method, op.path("1"), op.writes() ? op.validBody() : null, op.writes() ? json : null, json);
                } finally {
                    reset(currentMember);
                }
            default:
                throw new IllegalStateException(probe.name());
        }
    }

    @TestFactory
    Stream<DynamicTest> 모든_연산의_오류_응답은_계약에_있는_코드의_문제_본문이다() throws IOException {
        List<Operation> operations = handlerOperations();
        Map<String, Set<Integer>> documented = yamlOperations();
        E2eHttp http = new E2eHttp(port);

        // vacuity guard: the handlers are exactly the contract's operations
        assertThat(operations.stream().map(Operation::key).toList()).containsExactlyElementsOf(documented.keySet());
        assertThat(operations).hasSize(12);

        List<DynamicTest> tests = new ArrayList<>();
        Set<String> exercised = new TreeSet<>();
        for (Operation op : operations) {
            for (Probe probe : probesFor(op)) {
                exercised.add(probe.name());
                tests.add(dynamicTest(op.key() + " — " + probe.name(), () -> {
                    Reply reply = send(http, op, probe);
                    assertThat(reply.status()).as(reply + " " + reply.body()).isEqualTo(probe.status());
                    assertThat(reply.isProblem()).as(reply + " content type " + reply.contentType()).isTrue();
                    ErrorCode code = ErrorCode.valueOf(reply.code());
                    assertThat(code).as(reply.toString()).isEqualTo(probe.code());
                    assertThat(ErrorHttpMapping.status(code).value()).as("mapping of " + code).isEqualTo(reply.status());
                    assertThat(reply.json().get("status").asInt()).as(reply + " body status").isEqualTo(reply.status());
                    assertThat(reply.json().get("detail").asString()).as(reply + " detail").isNotBlank();
                    assertThat(reply.json().get("instance").asString()).as(reply + " instance").isNotBlank();
                    if (!FRAMEWORK_STATUSES.contains(reply.status())) {
                        assertThat(documented.get(op.key())).as(op.key() + " documents " + reply.status())
                                .contains(reply.status());
                    }
                }));
            }
        }
        // every probe kind ran on at least one operation
        assertThat(exercised).containsExactlyInAnyOrderElementsOf(
                Arrays.asList("wrong method", "Accept: application/xml", "text/plain body", "malformed JSON",
                        "empty object body", "non-numeric season id", "non-numeric turn number", "non-numeric query turnNo",
                        "unknown season", "unknown member"));
        return tests.stream();
    }
}
