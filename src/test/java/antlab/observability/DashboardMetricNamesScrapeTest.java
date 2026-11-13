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
    /** The only names that may carry the lazy mark: a fresh JVM registers them after its first GC pause. */
    private static final Pattern LAZY_FAMILY = Pattern.compile("^jvm_gc_pause_seconds_(count|sum|max)$");

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
        GrafanaDashboards.load().forEach((file, dashboard) -> {
            for (String name : GrafanaDashboards.metricNames(dashboard)) {
                if (!Boolean.TRUE.equals(allowlist.get(name))) {
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
