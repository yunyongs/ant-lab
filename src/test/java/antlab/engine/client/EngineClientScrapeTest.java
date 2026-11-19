package antlab.engine.client;

import static antlab.engine.client.EngineHarness.FAKE_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.E2eHttp.Reply;
import antlab.support.FakeEngineServer;
import antlab.support.GrafanaDashboards;
import antlab.support.RealPortTest;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * The engine client with the application's own observation registry, against a loopback fake engine: the Prometheus text
 * must carry a fixed uri template per operation and nothing of the URL, the query or the service token.
 */
class EngineClientScrapeTest extends RealPortTest {

    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Set<String> URIS = Set.of(EngineClient.PATHS_URI, EngineClient.PROJECTIONS_URI,
            EngineClient.PERCENTILES_URI, EngineClient.REGIME_URI, EngineClient.INFO_URI);
    private static final Set<String> LABEL_NAMES =
            Set.of("application", "client_name", "error", "exception", "method", "outcome", "status", "uri", "le");

    @Autowired
    ObservationRegistry observations;

    private String scrape() {
        Reply reply = management().send(HttpMethod.GET, "/actuator/prometheus", null, null, "text/plain");
        assertThat(reply.status()).isEqualTo(200);
        return reply.body();
    }

    private List<String> clientSamples(String body) {
        // The shared context may also hold samples of other clients (the collector scrape test); this one is the loopback engine.
        return body.lines().filter(l -> l.startsWith("http_client_requests_seconds") && l.contains("client_name=\"127.0.0.1\"")).toList();
    }

    @Test
    void 엔진_호출은_고정된_uri_템플릿으로_세어지고_토큰과_쿼리는_라벨에_없다() {
        try (FakeEngineServer engine = FakeEngineServer.start()) {
            engine.respondFixture("/v1/paths", 200, "paths-response-m2.json")
                    .respondFixture("/v1/projections", 200, "projection-response.json")
                    .respondFixture("/v1/info", 200, "info-response.json")
                    .respond("/v1/regime/evaluate", 500, "application/json", "{}");
            var properties = new EngineProperties(engine.url(), FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(3),
                    Duration.ofSeconds(15), Duration.ofSeconds(60), EngineProperties.PathSourceMode.JAVA, "m2-sb6-v1", 2000);
            EngineClient client = EngineClientConfig.newClient(properties, observations);

            client.createPath(EngineHarness.pathRequest());
            client.createProjection(EngineHarness.projectionRequest());
            client.info();
            try {
                client.evaluateRegime(EngineHarness.regimeRequest());
            } catch (RuntimeException expected) {
                // the 500 answer is the point: a failed call must be counted too
            }
        }

        String body = scrape();
        List<String> samples = clientSamples(body);
        assertThat(samples).isNotEmpty().allSatisfy(l -> assertThat(l).contains("application=\"antlab-api\""));
        assertThat(samples).anySatisfy(l -> assertThat(l).startsWith("http_client_requests_seconds_count{")
                .contains("uri=\"/v1/paths\"").contains("method=\"POST\"").contains("status=\"200\""));
        assertThat(samples).anySatisfy(l -> assertThat(l).startsWith("http_client_requests_seconds_count{")
                .contains("uri=\"/v1/info\"").contains("method=\"GET\""));
        assertThat(samples).anySatisfy(l -> assertThat(l).startsWith("http_client_requests_seconds_count{")
                .contains("uri=\"/v1/regime/evaluate\"").contains("status=\"500\"").doesNotContain("outcome=\"SUCCESS\""));
        assertThat(samples).anySatisfy(l -> assertThat(l).startsWith("http_client_requests_seconds_bucket{").contains("le="));
        for (String line : samples) {
            Matcher m = LABEL.matcher(line);
            while (m.find()) {
                assertThat(m.group(1)).as(line).isIn(LABEL_NAMES);
                if (m.group(1).equals("uri")) {
                    assertThat(m.group(2)).as(line).isIn(URIS);
                }
            }
            assertThat(line).doesNotContain(FAKE_TOKEN).doesNotContain("?").doesNotContain("http://");
        }
        assertThat(body).doesNotContain(FAKE_TOKEN);
    }

    private static final List<Double> SLO_SECONDS = List.of(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0);

    @Test
    void 엔진_호출_지연은_고정_버킷_열과_정해진_계열_수만_만든다() {
        callEngine();

        List<String> samples = clientSamples(scrape());
        List<String> counts = samples.stream().filter(l -> l.startsWith("http_client_requests_seconds_count{")).toList();
        assertThat(counts).isNotEmpty();
        // per label tuple: ten fixed buckets + the +Inf bucket + count + sum + max
        assertThat(samples).hasSize(counts.size() * 14);
        List<String> paths = samples.stream().filter(l -> l.startsWith("http_client_requests_seconds_bucket{")
                && l.contains("uri=\"/v1/paths\"") && l.contains("status=\"200\"")).toList();
        List<String> expected = new java.util.ArrayList<>(SLO_SECONDS.stream().map(String::valueOf).toList());
        expected.add("+Inf");
        assertThat(paths.stream().map(l -> l.replaceAll(".*le=\"([^\"]*)\".*", "$1")).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void 대시보드가_쓰는_모든_클라이언트_지표가_scrape에_있다() throws IOException {
        callEngine();

        String body = scrape();
        var wanted = GrafanaDashboards.metricNames(GrafanaDashboards.load().get("api.json")).stream()
                .filter(n -> n.startsWith("http_client_requests")).toList();
        assertThat(wanted).contains("http_client_requests_seconds_count", "http_client_requests_seconds_bucket",
                "http_client_requests_seconds_max");
        for (String name : wanted) {
            assertThat(clientSamples(body)).as(name).anyMatch(l -> l.startsWith(name + "{"));
        }
    }

    private void callEngine() {
        try (FakeEngineServer engine = FakeEngineServer.start()) {
            engine.respondFixture("/v1/paths", 200, "paths-response-m2.json");
            var properties = new EngineProperties(engine.url(), FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(3),
                    Duration.ofSeconds(15), Duration.ofSeconds(60), EngineProperties.PathSourceMode.JAVA, "m2-sb6-v1", 2000);
            EngineClientConfig.newClient(properties, observations).createPath(EngineHarness.pathRequest());
        }
    }
}
