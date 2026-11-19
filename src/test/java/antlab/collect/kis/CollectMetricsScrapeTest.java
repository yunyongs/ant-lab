package antlab.collect.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.PriceBasis;
import antlab.collect.ecos.EcosClientFactory;
import antlab.collect.ecos.EcosCycle;
import antlab.collect.ecos.EcosException;
import antlab.collect.ecos.EcosProperties;
import antlab.collect.fred.FredClientFactory;
import antlab.collect.fred.FredException;
import antlab.collect.fred.FredProperties;
import antlab.observability.CollectMetrics;
import antlab.support.E2eHttp.Reply;
import antlab.support.RealPortTest;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Stubbed FRED, ECOS and KIS calls through the application's own meter registry. The clients are given an observed
 * RestClient on purpose (production builds them without one): if anyone turns client observation on for the collectors,
 * the scrape must still carry no key, token or query string. The test lives in the kis package for the factory constructor.
 */
class CollectMetricsScrapeTest extends RealPortTest {

    private static final String FRED_KEY = "FAKE-FRED-KEY-SCRAPE-01";
    private static final String ECOS_KEY = "FAKEECOSKEYSCRAPE01";
    private static final String APP_KEY = "FAKE-APP-KEY-SCRAPE-01";
    private static final String APP_SECRET = "FAKE-APP-SECRET-SCRAPE-01";
    private static final String TOKEN = "FAKE-ACCESS-TOKEN-0001";
    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Set<String> SECRETS = Set.of(FRED_KEY, ECOS_KEY, APP_KEY, APP_SECRET, TOKEN);
    private static final Set<String> LABEL_NAMES = Set.of("application", "client_name", "error", "exception", "method", "outcome", "status",
            "uri", "le", "source", "job");

    @Autowired
    ObservationRegistry observations;

    @Autowired
    CollectMetrics metrics;

    private RestClient.Builder observed() {
        return RestClient.builder().observationRegistry(observations);
    }

    private String scrape() {
        Reply reply = management().send(HttpMethod.GET, "/actuator/prometheus", null, null, "text/plain");
        assertThat(reply.status()).isEqualTo(200);
        return reply.body();
    }

    /** An observed call with a real uri template, as the engine client leaves it, so the test holds in any class order. */
    private void engineSample() {
        try (antlab.support.FakeEngineServer engine = antlab.support.FakeEngineServer.start()) {
            engine.respond("/v1/info", 200, "application/json", "{}");
            observed().baseUrl(engine.url()).build().get().uri("/v1/info").retrieve().toBodilessEntity();
        }
    }

    private void callFred() {
        RestClient.Builder builder = observed();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(startsWith("https://api.stlouisfed.org/fred/series/observations?")))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"count\":1,\"observations\":[{\"realtime_start\":"
                        + "\"2026-11-18\",\"realtime_end\":\"2026-11-18\",\"date\":\"2025-11-17\",\"value\":\"3.6\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith("https://api.stlouisfed.org/fred/series/observations?")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("oops echo api_key=" + FRED_KEY));
        var props = new FredProperties(true, FRED_KEY, 2, 30, 100000, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2));
        var client = FredClientFactory.create(builder, props, Clock.systemUTC(), metrics).newClient();
        client.observations("DGS2", LocalDate.of(2025, 11, 1), false);
        try {
            client.observations("DGS2", LocalDate.of(2025, 11, 1), false);
        } catch (FredException expected) {
            // a failed request is counted too
        }
        server.verify();
    }

    private void callEcos() {
        RestClient.Builder builder = observed();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(startsWith("https://ecos.bok.or.kr/api/StatisticSearch/" + ECOS_KEY + "/")))
                .andRespond(withSuccess("{\"StatisticSearch\":{\"list_total_count\":1,\"row\":[{\"STAT_CODE\":\"817Y002\","
                        + "\"ITEM_CODE1\":\"010200000\",\"TIME\":\"20251103\",\"DATA_VALUE\":\"2.7\"}]}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith("https://ecos.bok.or.kr/api/StatisticSearch/" + ECOS_KEY + "/")))
                .andRespond(withSuccess("{\"RESULT\":{\"CODE\":\"INFO-100\",\"MESSAGE\":\"key " + ECOS_KEY + "\"}}",
                        MediaType.APPLICATION_JSON));
        var props = new EcosProperties(true, ECOS_KEY, 2, 20, 100000, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2));
        var client = EcosClientFactory.create(builder, props, Clock.systemUTC(), metrics).newClient();
        client.observations("817Y002", "010200000", EcosCycle.D, LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 18));
        try {
            client.observations("817Y002", "010200000", EcosCycle.D, LocalDate.of(2025, 11, 3), LocalDate.of(2025, 11, 18));
        } catch (EcosException expected) {
            // a failed request is counted too
        }
        server.verify();
    }

    private void callKis() {
        RestClient.Builder builder = observed();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(KisHarness.BASE + KisEndpoint.TOKEN.path())).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(KisHarness.fixture("auth_ok"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith(KisHarness.BASE + KisEndpoint.DOMESTIC_DAILY.path() + "?")))
                .andRespond(withSuccess(KisHarness.fixture("kr_daily_p2"), MediaType.APPLICATION_JSON));
        var props = new KisProperties(true, APP_KEY, APP_SECRET, "", 2, 60);
        var factory = new KisQuoteClientFactory(builder, KisEndpoint.BASE_URI, props, Clock.systemUTC(), Sleeper.SYSTEM, metrics);
        factory.newClient().domesticDaily("900001", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9), PriceBasis.RAW);
        server.verify();
    }

    private static final List<String> HOSTS = List.of("api.stlouisfed.org", "ecos.bok.or.kr", "openapi.koreainvestment.com");

    private static double hostCount(String body, String host) {
        return body.lines().filter(l -> l.startsWith("http_client_requests_seconds_count{") && l.contains("client_name=\"" + host + "\""))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).sum();
    }

    @Test
    void 세_수집기_호출_뒤_scrape의_어떤_라벨에도_키_토큰_쿼리가_없다() {
        String before = scrape();
        engineSample();
        callFred();
        callEcos();
        callKis();

        String body = scrape();
        // each host must show new HTTP samples, or the label check below would pass without looking at any client label
        for (String host : HOSTS) {
            assertThat(hostCount(body, host) - hostCount(before, host)).as(host).isGreaterThanOrEqualTo(2);
            assertThat(body.lines().filter(l -> l.startsWith("http_client_requests_seconds_count{")
                    && l.contains("client_name=\"" + host + "\""))).as(host).isNotEmpty();
        }
        // only the collector hosts' client samples: the shared context may also hold engine samples with real uri templates
        List<String> samples = body.lines().filter(l -> l.startsWith("antlab_collect_")
                || l.startsWith("http_client_requests") && HOSTS.stream().anyMatch(h -> l.contains("client_name=\"" + h + "\""))).toList();
        assertThat(samples).isNotEmpty();
        for (String line : samples) {
            Matcher m = LABEL.matcher(line);
            while (m.find()) {
                assertThat(m.group(1)).as(line).isIn(LABEL_NAMES);
                for (String secret : SECRETS) {
                    assertThat(m.group(2)).as(line).doesNotContain(secret);
                }
                assertThat(m.group(2)).as(line).doesNotContain("?").doesNotContain("api_key").doesNotContain("appkey")
                        .doesNotContain("authorization");
                if (m.group(1).equals("uri")) {
                    assertThat(m.group(2)).as("no collector URL is a uri template").isEqualTo("none");
                }
            }
        }
        for (String secret : SECRETS) {
            assertThat(body).as("scrape body").doesNotContain(secret);
        }
    }

    @Test
    void 호출_결과는_출처와_결과_고정_값으로만_세어진다() {
        callFred();
        callEcos();
        callKis();

        Set<String> allOutcomes = new java.util.HashSet<>();
        for (CollectMetrics.Source source : CollectMetrics.Source.values()) {
            allOutcomes.addAll(CollectMetrics.outcomes(source));
        }
        List<String> lines = scrape().lines().filter(l -> l.startsWith("antlab_collect_request_total{")).toList();
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("source=\"FRED\"").contains("outcome=\"OK\"").doesNotContain(" 0.0"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("source=\"FRED\"").contains("outcome=\"FRED_HTTP\"").doesNotContain(" 0.0"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("source=\"ECOS\"").contains("outcome=\"OK\"").doesNotContain(" 0.0"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("source=\"ECOS\"").contains("outcome=\"ECOS_AUTH\"").doesNotContain(" 0.0"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("source=\"KIS\"").contains("outcome=\"OK\"").doesNotContain(" 0.0"));
        for (String line : lines) {
            Matcher m = LABEL.matcher(line);
            while (m.find()) {
                if (m.group(1).equals("source")) {
                    assertThat(m.group(2)).isIn("KIS", "FRED", "ECOS");
                }
                if (m.group(1).equals("outcome")) {
                    assertThat(m.group(2)).isIn(allOutcomes);
                }
            }
        }
    }
}
