package antlab.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import antlab.api.error.ErrorHttpMapping;
import antlab.api.security.PublicPaths;
import antlab.common.error.ErrorCode;
import antlab.support.ContractErrorCodes;
import antlab.support.E2eHttp;
import antlab.support.E2eHttp.Reply;
import antlab.support.OpenApiNormalizer;
import antlab.support.PostgresTestcontainersConfig;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * Every error any {@code /api/v1} operation can answer is a ProblemDetail with a {@code code} the contract knows, over a real
 * port (so errors raised outside the DispatcherServlet's advice would show too). Always on: no sample needed.
 *
 * <p>The operation list is read from the handler mapping, never typed here, and must equal the operations in
 * {@code docs/api/openapi.yaml}. Statuses the yaml documents per operation (400, 404) must be listed for the probed
 * operation. 405, 406 and 415 come from the framework for every path and are listed once, in {@code x-framework-errors}.
 * Every observed (operation, status, code) must be listed by the contract's {@code x-error-codes}. The dev endpoint is
 * outside the contract and not probed. Calls carry a valid user token; the 401 probes (no token, a token signed by
 * another key, a token of a member who does not exist) run on every operation that is not public.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestcontainersConfig.class, TestTokens.class, TestMembers.class})
class ApiErrorSweepTest {

    private static final String UNKNOWN_SEASON = "987654321";
    private static final long UNKNOWN_MEMBER = 987_654_321L;

    record Operation(String method, String template, boolean hasBody) {
        String key() {
            return method + " " + template;
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
            if (template.endsWith("/auth/login")) {
                return "{\"email\":\"sweep@example.test\",\"password\":\"correct-horse-battery\"}";
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
    @Autowired
    TestTokens tokens;

    @Autowired
    TestMembers members;

    @AfterEach
    void cleanup() {
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/cleanup_season_start.sql")).execute(dataSource);
    }

    /** {@code /api/v1} operations from the mapping (the dev endpoint is {@code @Hidden}, local-only and outside the contract). */
    List<Operation> handlerOperations() {
        List<Operation> out = new ArrayList<>();
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            boolean hasBody = Arrays.stream(entry.getValue().getMethodParameters())
                    .anyMatch(p -> p.hasParameterAnnotation(RequestBody.class));
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/v1/") || pattern.startsWith("/api/v1/dev/")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    out.add(new Operation(method.name(), pattern, hasBody));
                }
            }
        }
        out.sort((a, b) -> a.key().compareTo(b.key()));
        return out;
    }

    static JsonNode contract() throws IOException {
        return OpenApiNormalizer.fromYaml(Files.readString(Path.of("docs/api/openapi.yaml")));
    }

    List<Probe> probesFor(Operation op) {
        List<Probe> probes = new ArrayList<>();
        probes.add(new Probe("wrong method", 405, ErrorCode.METHOD_NOT_ALLOWED));
        probes.add(new Probe("Accept: application/xml", 406, ErrorCode.NOT_ACCEPTABLE));
        if (op.hasBody()) {
            probes.add(new Probe("text/plain body", 415, ErrorCode.UNSUPPORTED_MEDIA_TYPE));
            probes.add(new Probe("malformed JSON", 400, ErrorCode.INVALID_ARGUMENT));
            probes.add(new Probe("empty object body", 400, ErrorCode.INVALID_ARGUMENT));
        }
        if (op.template.equals("/api/v1/auth/login")) {
            probes.add(new Probe("unknown credentials", 401, ErrorCode.INVALID_CREDENTIALS));
        }
        if (op.template.equals("/api/v1/auth/refresh")) {
            probes.add(new Probe("no refresh cookie", 401, ErrorCode.UNAUTHENTICATED));
        }
        if (!PublicPaths.CONTRACT_PUBLIC.contains(op.template)) {
            probes.add(new Probe("no bearer token", 401, ErrorCode.UNAUTHENTICATED));
            probes.add(new Probe("forged bearer token", 401, ErrorCode.UNAUTHENTICATED));
        }
        if (op.onSeason()) {
            probes.add(new Probe("non-numeric season id", 400, ErrorCode.INVALID_ARGUMENT));
            probes.add(new Probe("unknown season", 404, ErrorCode.NOT_FOUND));
            probes.add(new Probe("unknown member token", 401, ErrorCode.UNAUTHENTICATED));
        }
        if (op.hasTurnNo()) {
            probes.add(new Probe("non-numeric turn number", 400, ErrorCode.INVALID_ARGUMENT));
        }
        if (op.template.endsWith("/prices")) {
            probes.add(new Probe("non-numeric query turnNo", 400, ErrorCode.INVALID_ARGUMENT));
        }
        if (op.template.equals("/api/v1/seasons")) {
            probes.add(new Probe("unknown member token", 401, ErrorCode.UNAUTHENTICATED));
        }
        return probes;
    }

    private Reply send(E2eHttp http, E2eHttp anonymous, E2eHttp forged, E2eHttp strangers, Operation op, Probe probe) {
        HttpMethod method = HttpMethod.valueOf(op.method());
        String json = "application/json";
        switch (probe.name()) {
            case "wrong method":
                return http.send(HttpMethod.DELETE, op.path(UNKNOWN_SEASON), null, null, json);
            case "Accept: application/xml":
                return http.send(method, op.path(UNKNOWN_SEASON), op.hasBody() ? op.validBody() : null,
                        op.hasBody() ? json : null, "application/xml");
            case "text/plain body":
                return http.send(method, op.path(UNKNOWN_SEASON), "plain", "text/plain", json);
            case "malformed JSON":
                return http.send(method, op.path(UNKNOWN_SEASON), "{", json, json);
            case "empty object body":
                return http.send(method, op.path(UNKNOWN_SEASON), "{}", json, json);
            case "no bearer token":
                return anonymous.send(method, op.path("1"), op.hasBody() ? op.validBody() : null,
                        op.hasBody() ? json : null, json);
            case "forged bearer token":
                return forged.send(method, op.path("1"), op.hasBody() ? op.validBody() : null,
                        op.hasBody() ? json : null, json);
            case "unknown credentials":
                return http.send(method, op.path(UNKNOWN_SEASON), op.validBody(), json, json);
            case "no refresh cookie":
                return http.send(method, op.path(UNKNOWN_SEASON), null, null, json);
            case "non-numeric season id":
                return http.send(method, op.path("abc"), op.hasBody() ? op.validBody() : null, op.hasBody() ? json : null, json);
            case "non-numeric turn number":
                return http.send(method, op.path("1", "abc"), null, null, json);
            case "non-numeric query turnNo":
                return http.send(method, op.path("1") + "?turnNo=abc", null, null, json);
            case "unknown season":
                return http.send(method, op.path(UNKNOWN_SEASON), op.hasBody() ? op.validBody() : null,
                        op.hasBody() ? json : null, json);
            case "unknown member token":
                return strangers.send(method, op.path("1"), op.hasBody() ? op.validBody() : null,
                        op.hasBody() ? json : null, json);
            default:
                throw new IllegalStateException(probe.name());
        }
    }

    @TestFactory
    Stream<DynamicTest> 모든_연산의_오류_응답은_계약에_있는_코드의_문제_본문이다() throws IOException {
        List<Operation> operations = handlerOperations();
        JsonNode contract = contract();
        Set<String> documented = ContractErrorCodes.perOperation(contract).keySet();
        E2eHttp http = new E2eHttp(port, Map.of("Authorization", "Bearer " + tokens.user(members.defaultId())));
        E2eHttp anonymous = new E2eHttp(port);
        E2eHttp forged = new E2eHttp(port, Map.of("Authorization",
                "Bearer " + tokens.signedByOtherKey(tokens.header(), tokens.claims(1, "USER"))));
        // a validly signed token whose member has no row: 401 like any other bad token
        E2eHttp strangers = new E2eHttp(port, Map.of("Authorization", "Bearer " + tokens.user(UNKNOWN_MEMBER)));

        // vacuity guard: the handlers are exactly the contract's operations
        assertThat(operations.stream().map(Operation::key).toList()).containsExactlyElementsOf(documented);
        assertThat(operations).hasSize(16);

        List<DynamicTest> tests = new ArrayList<>();
        Set<String> exercised = new TreeSet<>();
        for (Operation op : operations) {
            for (Probe probe : probesFor(op)) {
                exercised.add(probe.name());
                tests.add(dynamicTest(op.key() + " — " + probe.name(), () -> {
                    Reply reply = send(http, anonymous, forged, strangers, op, probe);
                    assertThat(reply.status()).as(reply + " " + reply.body()).isEqualTo(probe.status());
                    assertThat(reply.isProblem()).as(reply + " content type " + reply.contentType()).isTrue();
                    ErrorCode code = ErrorCode.valueOf(reply.code());
                    assertThat(code).as(reply.toString()).isEqualTo(probe.code());
                    assertThat(ErrorHttpMapping.status(code).value()).as("mapping of " + code).isEqualTo(reply.status());
                    assertThat(reply.json().get("status").asInt()).as(reply + " body status").isEqualTo(reply.status());
                    assertThat(reply.json().get("detail").asString()).as(reply + " detail").isNotBlank();
                    assertThat(reply.json().get("instance").asString()).as(reply + " instance").isNotBlank();
                    assertThat(ContractErrorCodes.isListed(contract, op.key(), reply.status(), code.name()))
                            .as(op.key() + " lists " + code + " under " + reply.status()).isTrue();
                }));
            }
        }
        // every probe kind ran on at least one operation
        assertThat(exercised).containsExactlyInAnyOrderElementsOf(
                Arrays.asList("wrong method", "Accept: application/xml", "text/plain body", "malformed JSON",
                        "empty object body", "non-numeric season id", "non-numeric turn number", "non-numeric query turnNo",
                        "unknown season", "unknown member token", "unknown credentials", "no refresh cookie", "no bearer token",
                        "forged bearer token"));
        return tests.stream();
    }
}
