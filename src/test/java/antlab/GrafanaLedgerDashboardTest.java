package antlab;

import static antlab.support.GrafanaDashboards.expressions;
import static antlab.support.GrafanaDashboards.panels;
import static antlab.support.GrafanaDashboards.parse;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GrafanaDashboards;
import antlab.support.PromQlNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Static checks of the dashboards that read the ledger exporter (OBS-08): antlab-ai-usage and the fetch panels of antlab-collectors.
 * Job selector on every query, metric names against the allowlist (kept equal to the exporter source), fixed label names only.
 * No Spring context, no Docker. Each check returns its problems so the negative cases can feed it broken input.
 */
class GrafanaLedgerDashboardTest {

    private static final Path LEDGER_ALLOWLIST = Path.of("src/test/resources/monitoring/metric-names-ledger.txt");
    private static final Path EXPORTER = Path.of("ai/src/antlab_ai/ledger_exporter/exporter.py");
    private static final String JOB_MATCHER = "job=\"antlab-ledgers\"";
    /** Label names the exporter may put on a series; a query may group or filter by these only. */
    private static final Set<String> LABELS = Set.of("ledger", "caller_class", "model", "status", "direction", "name", "window", "source", "job");
    private static final Set<String> FORBIDDEN = Set.of("purpose", "call_id", "run_id", "ticker", "target", "caller");

    private static JsonNode aiUsage() throws IOException {
        return GrafanaDashboards.load().get("ai-usage.json");
    }

    private static JsonNode collectors() throws IOException {
        return GrafanaDashboards.load().get("collectors.json");
    }

    private static Set<String> allowed() throws IOException {
        return GrafanaDashboards.allowlist(LEDGER_ALLOWLIST).keySet();
    }

    // ---- checks (return problems) ---------------------------------------------------------------------------------

    static List<String> queryProblems(JsonNode d, Set<String> allowedNames) {
        List<String> problems = new ArrayList<>();
        for (String expr : expressions(d)) {
            PromQlNames.bareIdentifiers(expr).forEach(v -> problems.add(v + " in " + expr));
            for (String name : PromQlNames.names(expr)) {
                if (!allowedNames.contains(name)) {
                    problems.add("unknown metric " + name + " in " + expr);
                }
            }
            for (PromQlNames.Selector s : PromQlNames.selectors(expr)) {
                if (!s.matchers().replace(" ", "").contains(JOB_MATCHER)) {
                    problems.add("no job=\"antlab-ledgers\" filter: " + s);
                }
            }
            Matcher grouping = Pattern.compile("\\b(?:by|without)\\s*\\(([^)]*)\\)").matcher(expr);
            while (grouping.find()) {
                for (String label : grouping.group(1).split(",")) {
                    if (!LABELS.contains(label.strip())) {
                        problems.add("label not exported: " + label.strip() + " in " + expr);
                    }
                }
            }
            for (String word : FORBIDDEN) {
                if (Pattern.compile("\\b" + word + "\\b").matcher(expr).find()) {
                    problems.add("forbidden label " + word + " in " + expr);
                }
            }
        }
        return problems;
    }

    static List<String> sourceProblems(JsonNode d) {
        List<String> problems = new ArrayList<>();
        for (JsonNode panel : panels(d)) {
            if (!"antlab-prometheus".equals(panel.path("datasource").path("uid").asString())) {
                problems.add("panel not on the Prometheus source: " + panel.path("id"));
            }
            for (JsonNode target : panel.path("targets")) {
                if (!target.has("expr") || target.has("rawSql")) {
                    problems.add("target must be a PromQL expr: panel " + panel.path("id"));
                }
            }
        }
        return problems;
    }

    // ---- positive tests -------------------------------------------------------------------------------------------

    @Test
    void AI_사용량_대시보드는_정해진_모양이다() throws IOException {
        JsonNode d = aiUsage();
        assertThat(d.path("uid").asString()).isEqualTo("antlab-ai-usage");
        assertThat(d.path("timezone").asString()).isEqualTo("Asia/Seoul");
        assertThat(panels(d)).hasSize(10);
        assertThat(expressions(d)).hasSize(13);
        assertThat(sourceProblems(d)).isEmpty();
        assertThat(panels(d).stream().map(p -> p.path("title").asString()).toList()).containsExactly(
                "Ledgers readable", "OpenRouter attempts today", "OpenRouter cost today", "Ollama Cloud spend today",
                "Attempts today by caller class against the caps", "Attempts today by model",
                "Attempts in the last minute against the cap", "Outcomes today by status", "Tokens today",
                "Ollama Cloud spend against the stop");
    }

    @Test
    void 수집기_대시보드의_수집_예산_패널은_장부_내보내기를_읽는다() throws IOException {
        JsonNode d = collectors();
        List<JsonNode> prom = panels(d).stream().filter(p -> p.path("id").asInt() >= 8).toList();
        assertThat(prom).hasSize(2);
        for (JsonNode p : prom) {
            assertThat(p.path("datasource").path("uid").asString()).isEqualTo("antlab-prometheus");
        }
        assertThat(expressions(d)).hasSize(3).allSatisfy(e -> assertThat(e).contains(JOB_MATCHER));
        assertThat(String.join("\n", expressions(d))).contains("antlab_fetch_requests_today", "antlab_fetch_daily_cap");
    }

    @Test
    void 두_대시보드의_쿼리는_job_선택자와_허용_이름과_고정_라벨만_쓴다() throws IOException {
        assertThat(queryProblems(aiUsage(), allowed())).isEmpty();
        assertThat(queryProblems(collectors(), allowed())).isEmpty();
    }

    @Test
    void 허용_이름은_내보내기가_실제로_내는_이름과_같다() throws IOException {
        Set<String> exported = new TreeSet<>();
        Matcher m = Pattern.compile("\"(antlab_(?:openrouter|ollama|fetch|ledger)_[a-z_]+)\"").matcher(Files.readString(EXPORTER));
        while (m.find()) {
            exported.add(m.group(1));
        }
        assertThat(allowed()).containsExactlyInAnyOrderElementsOf(exported);
    }

    @Test
    void 개요가_AI_사용량으로_링크한다() throws IOException {
        JsonNode links = GrafanaDashboards.load().get("overview.json").path("links");
        assertThat(links.toString()).contains("\"/d/antlab-ai-usage\"");
    }

    @Test
    void 정지선과_상한은_점선이고_장부_준비_상태는_표로_보인다() throws IOException {
        for (int id : List.of(5, 7, 10)) {
            JsonNode p = panels(aiUsage()).stream().filter(x -> x.path("id").asInt() == id).findFirst().orElseThrow();
            assertThat(p.path("fieldConfig").path("overrides").toString()).contains(".* cap$").contains("dash");
        }
        assertThat(panels(aiUsage()).get(0).path("type").asString()).isEqualTo("table");
    }

    // ---- negative cases: the checks must reject broken input ------------------------------------------------------

    @Test
    void job_선택자_빠짐과_모르는_이름은_거부한다() throws IOException {
        String text = aiUsage().toString();
        assertThat(queryProblems(parse(text.replaceFirst("job=\\\\\"antlab-ledgers\\\\\"", "job=\\\\\"other\\\\\"")), allowed())).isNotEmpty();
        assertThat(queryProblems(parse(text.replace("antlab_openrouter_tokens_today", "antlab_openrouter_token_today")), allowed())).isNotEmpty();
    }

    @Test
    void 내보내지_않는_라벨과_자유_서술_라벨은_거부한다() throws IOException {
        String text = aiUsage().toString();
        assertThat(queryProblems(parse(text.replace("sum by (model)", "sum by (purpose)")), allowed())).isNotEmpty();
        assertThat(queryProblems(parse(text.replace("sum by (model)", "sum by (call_id)")), allowed())).isNotEmpty();
    }

    @Test
    void PostgreSQL_표적이_섞이면_거부한다() throws IOException {
        String text = aiUsage().toString();
        assertThat(sourceProblems(parse(text.replaceFirst("\"expr\"", "\"rawSql\"")))).isNotEmpty();
        assertThat(sourceProblems(parse(text.replaceFirst("antlab-prometheus", "antlab-postgres")))).isNotEmpty();
    }
}
