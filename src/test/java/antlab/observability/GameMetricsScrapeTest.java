package antlab.observability;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.error.ErrorCode;
import antlab.support.E2eHttp.Reply;
import antlab.support.RealPortTest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class GameMetricsScrapeTest extends RealPortTest {

    private static final String SEASON_ID = "987654321";
    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private String scrape() {
        Reply reply = management().send(HttpMethod.GET, "/actuator/prometheus", null, null, "text/plain");
        assertThat(reply.status()).isEqualTo(200);
        return reply.body();
    }

    private static List<String> gameSamples(String body) {
        // antlab_collect_* belongs to the collectors (CollectMetricsScrapeTest), not to the game.
        return body.lines().filter(l -> l.startsWith("antlab_") && !l.startsWith("antlab_collect_")).toList();
    }

    @Test
    void 게임_계열이_HELP와_application_태그와_함께_나온다() {
        assertThat(api().post("/api/v1/seasons/" + SEASON_ID + "/turns", "{\"expectedTurnNo\": 0}").status()).isEqualTo(404);
        assertThat(api().post("/api/v1/seasons/" + SEASON_ID + "/orders", "{\"expectedTurnNo\": 0, \"side\": \"BUY\","
                + " \"assetId\": 1, \"quantity\": 1, \"declaredPosition\": \"MIDFIELDER\"}").status()).isEqualTo(404);
        String body = scrape();
        for (String family : List.of("antlab_season_start_total", "antlab_turn_advance_total",
                "antlab_reconciliation_mismatch_rules_total", "antlab_order_rejected_total")) {
            assertThat(body).as(family).contains("# HELP " + family + " ");
            assertThat(gameSamples(body)).filteredOn(l -> l.startsWith(family)).isNotEmpty()
                    .allSatisfy(l -> assertThat(l).contains("application=\"antlab-api\""));
        }
        assertThat(gameSamples(body)).anySatisfy(l -> assertThat(l).startsWith("antlab_turn_advance_total{")
                .contains("code=\"NOT_FOUND\"").contains("outcome=\"rejected\""));
    }

    @Test
    void 라벨_이름과_값은_허용_목록_안이고_시즌_번호는_없다() {
        api().post("/api/v1/seasons/" + SEASON_ID + "/turns", "{\"expectedTurnNo\": 0}");
        Set<String> codes = Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());
        codes.add("none");
        List<String> lines = gameSamples(scrape());
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            Matcher m = LABEL.matcher(line);
            while (m.find()) {
                switch (m.group(1)) {
                    case "application" -> assertThat(m.group(2)).isEqualTo("antlab-api");
                    case "outcome" -> assertThat(m.group(2)).isIn("committed", "rejected");
                    case "code" -> assertThat(m.group(2)).isIn(codes);
                    case "not_started" -> assertThat(m.group(2)).isIn("true", "false");
                    default -> throw new AssertionError("label not allowed: " + m.group(1) + " in " + line);
                }
            }
            assertThat(line).doesNotContain(SEASON_ID);
        }
    }

    @Test
    void HTTP_지표의_error_code_태그는_닫힌_집합이다() {
        api().post("/api/v1/seasons/" + SEASON_ID + "/turns", "{\"expectedTurnNo\": 0}");
        api().post("/api/v1/seasons/" + SEASON_ID + "/turns", "not json");
        api().get("/api/v1/no-such-path-" + System.nanoTime());
        assertThat(api().get("/livez").status()).isEqualTo(200);
        Set<String> allowed = Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());
        allowed.addAll(Set.of("none", "other"));
        List<String> lines = scrape().lines().filter(l -> l.startsWith("http_server_requests_seconds_count")).toList();
        assertThat(lines).isNotEmpty();
        Set<String> seen = new java.util.HashSet<>();
        for (String line : lines) {
            Matcher m = LABEL.matcher(line);
            String value = null;
            while (m.find()) {
                if (m.group(1).equals("error_code")) {
                    value = m.group(2);
                }
            }
            assertThat(value).as(line).isNotNull().isIn(allowed);
            seen.add(value);
        }
        assertThat(seen).contains("NOT_FOUND", "none");
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("status=\"404\"").contains("error_code=\"NOT_FOUND\""));
    }
}
