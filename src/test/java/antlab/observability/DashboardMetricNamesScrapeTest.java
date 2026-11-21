package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.E2eHttp.Reply;
import antlab.support.GrafanaDashboards;
import antlab.support.RealPortTest;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Drift guard (MON-04): every metric a dashboard queries is exported by the running API, so a renamed meter breaks the build. */
class DashboardMetricNamesScrapeTest extends RealPortTest {

    private static final String SEASON_ID = "987654321";
    /**
     * The only names that may carry the lazy mark: a fresh JVM registers them after its first GC pause, and the engine client
     * meters exist only after the first call to the engine (the test context has none).
     */
    private static final Pattern LAZY_FAMILY =
            Pattern.compile("^(jvm_gc_pause_seconds_(count|sum|max)|http_client_requests_seconds_(count|sum|max|bucket))$");

    private Set<String> scrapedNames() {
        // Rejected calls make the HTTP histogram and the game counters (registered on first use) appear.
        assertThat(api().post("/api/v1/seasons/" + SEASON_ID + "/turns", "{\"expectedTurnNo\": 0}").status()).isEqualTo(404);
        assertThat(api().post("/api/v1/seasons/" + SEASON_ID + "/orders", "{\"expectedTurnNo\": 0, \"side\": \"BUY\","
                + " \"assetId\": 1, \"quantity\": 1, \"declaredPosition\": \"MIDFIELDER\"}").status()).isEqualTo(404);
        Reply reply = management().send(HttpMethod.GET, "/actuator/prometheus", null, null, "text/plain");
        assertThat(reply.status()).isEqualTo(200);
        Set<String> names = new TreeSet<>();
        reply.body().lines().filter(l -> !l.isBlank() && !l.startsWith("#")).forEach(l -> names.add(l.split("[{ ]", 2)[0]));
        return names;
    }

    @Test
    void 대시보드_지표는_살아있는_scrape에_있다() throws IOException {
        Set<String> scraped = scrapedNames();
        Map<String, Boolean> allowlist = GrafanaDashboards.allowlist();
        // Names of the ledger exporter (OBS-08): ai-usage.json and the ledger panels of collectors.json; API names there stay checked.
        Set<String> ledgerNames = GrafanaDashboards.allowlist(java.nio.file.Path.of("src/test/resources/monitoring/metric-names-ledger.txt")).keySet();
        GrafanaDashboards.load().forEach((file, dashboard) -> {
            if (file.equals("db.json") || file.equals("engine.json") || file.equals("host-resources.json")) {
                return; // exporter and engine metrics (OBS-06, OBS-05) and the host exporter (OBS-13): their own tests and the live smoke prove them
            }
            for (String name : GrafanaDashboards.metricNames(dashboard)) {
                // up is made by Prometheus for every target; the API never exports it (overview, OBS-03).
                if (ledgerNames.contains(name)) {
                    continue;
                }
                if (!name.equals("up") && !Boolean.TRUE.equals(allowlist.get(name))) {
                    assertThat(scraped).as("%s uses %s", file, name).contains(name);
                }
            }
        });
    }

    @Test
    void 허용_목록의_lazy_표시는_정말_늦게_생기는_이름에만_있다() throws IOException {
        Map<String, Boolean> allowlist = GrafanaDashboards.allowlist();
        allowlist.forEach((name, lazy) -> {
            if (lazy) {
                assertThat(name).as("lazy mark on %s", name).matches(LAZY_FAMILY);
            }
        });
        assertThat(allowlist.values()).contains(true);
    }
}
