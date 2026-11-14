package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.E2eHttp.Reply;
import antlab.support.RealPortTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class PrometheusEndpointTest extends RealPortTest {

    private static final List<String> FAMILIES =
            List.of("http_server_requests_seconds_bucket", "jvm_memory_used_bytes", "hikaricp_connections_active");

    private Reply scrape() {
        return management().send(HttpMethod.GET, "/actuator/prometheus", null, null, "text/plain");
    }

    private static List<String> samples(String body, String family) {
        return body.lines().filter(l -> l.startsWith(family + "{")).toList();
    }

    @Test
    void 요청_뒤_관리_포트에서_세_계열을_읽는다() {
        assertThat(api().get("/api/v1/seasons").status()).isEqualTo(200);
        Reply reply = scrape();
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.contentType()).startsWith("text/plain");
        for (String family : FAMILIES) {
            assertThat(samples(reply.body(), family)).as(family).isNotEmpty();
        }
    }

    @Test
    void 엉터리_Authorization_헤더가_있어도_관리_포트_수집은_200이다() {
        Reply reply = management().send(HttpMethod.GET, "/actuator/prometheus", null, null, "text/plain",
                Map.of("Authorization", "Bearer garbage"));
        assertThat(reply.status()).isEqualTo(200);
    }

    @Test
    void 세_계열의_모든_표본에_application_태그가_있다() {
        api().get("/api/v1/seasons");
        Reply reply = scrape();
        for (String family : FAMILIES) {
            List<String> lines = samples(reply.body(), family);
            assertThat(lines).as(family).isNotEmpty().allSatisfy(l -> assertThat(l).contains("application=\"antlab-api\""));
        }
    }

    private static final String SEASON_404_COUNT = "http_server_requests_seconds_count";

    /** Value of the 404 counter for the season-by-id template, or 0 when the series does not exist yet. */
    private static double season404Count(String body) {
        return samples(body, SEASON_404_COUNT).stream()
                .filter(l -> l.contains("method=\"GET\"") && l.contains("status=\"404\"")
                        && l.contains("uri=\"/api/v1/seasons/{seasonId}\""))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1)))
                .sum();
    }

    @Test
    void uri_라벨은_템플릿이고_원시_id는_지표에_없다() {
        double before = season404Count(scrape().body());
        assertThat(api().get("/api/v1/seasons/987654321").status()).isEqualTo(404);
        Reply reply = scrape();
        assertThat(season404Count(reply.body())).isGreaterThan(before);
        assertThat(reply.body()).doesNotContain("987654321");
    }

    @Test
    void 지연_분포는_히스토그램_버킷이고_범위는_5ms에서_10s다() {
        api().get("/api/v1/seasons");
        List<String> buckets = samples(scrape().body(), "http_server_requests_seconds_bucket").stream()
                .filter(l -> l.contains("uri=\"/api/v1/seasons\""))
                .toList();
        assertThat(buckets).isNotEmpty().allSatisfy(l -> assertThat(l).contains("le=\""));
        assertThat(buckets).anySatisfy(l -> assertThat(l).contains("le=\"+Inf\""));
        List<BigDecimal> finite = buckets.stream()
                .map(l -> l.replaceAll(".*le=\"([^\"]+)\".*", "$1"))
                .filter(le -> !le.equals("+Inf"))
                .map(BigDecimal::new)
                .toList();
        assertThat(finite.stream().min(BigDecimal::compareTo).orElseThrow()).isEqualByComparingTo("0.005");
        assertThat(finite.stream().max(BigDecimal::compareTo).orElseThrow()).isEqualByComparingTo("10.0");
    }
}
