package antlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The hand-play file {@code docs/api/one-season.http} stays inside the contract: every request line is an operation of
 * {@code docs/api/openapi.yaml}. The one allowed exception is the documented development reconciliation call, which is outside
 * the contract by design. A plain unit test: no Spring, no database.
 */
class RunDocContractTest {

    private static final Path HTTP_FILE = Path.of("docs/api/one-season.http");
    private static final String DEV_CALL = "POST /api/v1/dev/seasons/{}/reconciliation";
    private static final Pattern REQUEST_LINE = Pattern.compile("^(GET|POST|PUT|PATCH|DELETE)\\s+\\{\\{base}}(/[^\\s?]*)");
    private static final Pattern ANY_METHOD_LINE = Pattern.compile("^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(\\S+)");
    private static final String API_PREFIX = "{{base}}/api/v1/";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{[^}]*}}");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^/}]+}");

    /**
     * Request lines as {@code "METHOD /path/{}"} (variables and query removed). A line that starts with an HTTP method but does
     * not continue with {@code {{base}}/api/v1/} is kept as {@code "METHOD <target as written>"}, so it is reported as outside
     * the contract instead of being dropped.
     */
    static List<String> requests(String httpFile) {
        List<String> out = new ArrayList<>();
        for (String line : httpFile.split("\\R")) {
            Matcher m = REQUEST_LINE.matcher(line);
            Matcher any = ANY_METHOD_LINE.matcher(line);
            if (any.find() && !any.group(2).startsWith(API_PREFIX)) {
                out.add(any.group(1) + " " + any.group(2));
            } else if (m.find()) {
                out.add(m.group(1) + " " + VARIABLE.matcher(m.group(2)).replaceAll("{}"));
            }
        }
        return out;
    }

    /** True when {@code request} ("METHOD /path/{}") is the operation {@code operation} ("METHOD /path/{name}"). */
    static boolean matches(String request, String operation) {
        String regex = TEMPLATE_VARIABLE.matcher(operation).replaceAll("[^/]+");
        return request.matches(regex.replace("{}", "[^/]+"));
    }

    /** Requests that match no contract operation (the allowed dev call is not reported). */
    static List<String> outsideContract(Collection<String> requests, Set<String> operations) {
        return requests.stream()
                .filter(r -> !r.equals(DEV_CALL) && operations.stream().noneMatch(op -> matches(r, op)))
                .distinct().toList();
    }

    @SuppressWarnings("unchecked")
    static Set<String> yamlOperations() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(Path.of("docs/api/openapi.yaml")));
        Set<String> out = new TreeSet<>();
        for (var path : ((Map<String, Object>) doc.get("paths")).entrySet()) {
            for (String method : ((Map<String, Object>) path.getValue()).keySet()) {
                out.add(method.toUpperCase() + " " + path.getKey());
            }
        }
        return out;
    }

    @Test
    void 실행_문서의_요청은_모두_계약의_연산이다() throws IOException {
        List<String> requests = requests(Files.readString(HTTP_FILE));
        assertThat(requests).as("the file has requests").hasSizeGreaterThan(20);
        assertThat(outsideContract(requests, yamlOperations())).isEmpty();
        assertThat(requests.stream().filter(r -> r.contains("/dev/")).toList()).as("the dev call appears once")
                .containsExactly(DEV_CALL);
    }

    @Test
    void 한_판에_필요한_연산이_모두_있다() throws IOException {
        List<String> requests = requests(Files.readString(HTTP_FILE));
        List<String> unplayed = yamlOperations().stream()
                .filter(op -> requests.stream().noneMatch(r -> matches(r, op))).toList();
        assertThat(unplayed).as("every contract operation is played at least once").isEmpty();
    }

    @Test
    void 계약에_없는_경로는_잡힌다() {
        Set<String> operations = Set.of("GET /api/v1/pool", "POST /api/v1/seasons/{seasonId}/turns");
        List<String> requests = requests("""
                GET {{base}}/api/v1/pool
                POST {{base}}/api/v1/seasons/{{season}}/turns
                GET {{base}}/api/v1/seasons/{{season}}/standings?x=1
                DELETE {{base}}/api/v1/pool
                GET http://localhost:8080/api/v1/typo
                POST {{base}}/api/v1/dev/seasons/{{season}}/reconciliation
                """);
        assertThat(outsideContract(requests, operations))
                .containsExactly("GET /api/v1/seasons/{}/standings", "DELETE /api/v1/pool",
                        "GET http://localhost:8080/api/v1/typo");
    }
}
