package antlab;

import static antlab.support.GrafanaDashboards.ENGINE_ALLOWLIST;
import static antlab.support.GrafanaDashboards.expressions;
import static antlab.support.GrafanaDashboards.metricNames;
import static antlab.support.GrafanaDashboards.panels;
import static antlab.support.GrafanaDashboards.parse;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GrafanaDashboards;
import antlab.support.PromQlNames;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Static checks of the engine dashboard (OBS-05): data source, the job selector on every query, metric names against the
 * allowlist, and the labels a query may group by (route template, never a raw path). No Spring context, no Docker.
 */
class GrafanaEngineDashboardTest {

    private static final String JOB_MATCHER = "job=\"antlab-engine\"";
    private static final Set<String> ALLOWED_LABELS = Set.of("le", "route", "status", "method");

    private static JsonNode engine() throws IOException {
        return GrafanaDashboards.load().get("engine.json");
    }

    /** The allowlist plus {@code up}, which Prometheus makes for every target (the "Engine up" panel). */
    private static Set<String> allowed() throws IOException {
        Set<String> names = new java.util.TreeSet<>(GrafanaDashboards.allowlist(ENGINE_ALLOWLIST).keySet());
        names.add("up");
        return names;
    }

    static List<String> sourceProblems(JsonNode d) {
        List<String> problems = new ArrayList<>();
        for (JsonNode panel : panels(d)) {
            for (JsonNode target : panel.path("targets")) {
                if (target.has("rawSql") || !target.has("expr")) {
                    problems.add("target must be a PromQL expr: panel " + panel.path("id"));
                }
            }
        }
        problems.addAll(GrafanaDashboardsSettingsTest.dataSourceProblems(d, "antlab-prometheus"));
        return problems;
    }

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
                    problems.add("no job=\"antlab-engine\" filter: " + s);
                }
            }
            var by = java.util.regex.Pattern.compile("\\bby\\s*\\(([^)]*)\\)").matcher(expr);
            while (by.find()) {
                for (String label : by.group(1).split(",")) {
                    if (!ALLOWED_LABELS.contains(label.strip())) {
                        problems.add("label not allowed: " + label.strip() + " in " + expr);
                    }
                }
            }
        }
        return problems;
    }

    @Test
    void 엔진_대시보드는_정해진_모양이다() throws IOException {
        JsonNode d = engine();
        assertThat(d.path("uid").asString()).isEqualTo("antlab-engine");
        assertThat(d.path("title").asString()).isEqualTo("ant-lab \u00b7 엔진");
        assertThat(d.path("tags")).hasSize(1);
        assertThat(d.path("tags").get(0).asString()).isEqualTo("antlab");
        assertThat(d.path("timezone").asString()).isEqualTo("Asia/Seoul");
        assertThat(d.path("templating").path("list")).isEmpty();
        assertThat(panels(d)).hasSize(13);
        assertThat(panels(d).stream().map(p -> p.path("title").asString()).toList()).containsExactly(
                "엔진 가동 상태", "초당 요청 수", "5xx 오류 비율", "사용 중인 투영 슬롯 수", "엔진 메모리 사용량",
                "경로별 초당 요청 수", "5xx 오류 비율 추이", "경로별 p95 지연", "투영 슬롯 사용 추이",
                "초당 국면 평가 수", "국면 평가 처리 시간 p95·평균", "엔진 CPU 사용량", "엔진 메모리 사용량 추이");
        assertThat(GrafanaDashboardsSettingsTest.exportLeftovers(d)).isEmpty();
        assertThat(GrafanaDashboardsSettingsTest.panelProblems(d)).isEmpty();
        assertThat(GrafanaDashboardsSettingsTest.uidProblems(List.copyOf(GrafanaDashboards.load().values()))).isEmpty();
    }

    @Test
    void 모든_쿼리는_Prometheus_표적이고_허용된_이름과_job_선택자만_쓴다() throws IOException {
        JsonNode d = engine();
        assertThat(sourceProblems(d)).isEmpty();
        assertThat(queryProblems(d, allowed())).isEmpty();
        assertThat(GrafanaDashboards.sqlStatements(d)).isEmpty();
        assertThat(expressions(d)).hasSize(15).allSatisfy(e -> assertThat(e).contains(JOB_MATCHER));
        assertThat(metricNames(d)).isSubsetOf(allowed());
        assertThat(GrafanaDashboards.allowlist(ENGINE_ALLOWLIST).keySet()).doesNotContain("up");
    }

    @Test
    void 대시보드가_약속한_지표를_모두_보여_준다() throws IOException {
        assertThat(metricNames(engine())).contains("antlab_engine_http_requests_total",
                "antlab_engine_http_request_duration_seconds_bucket", "antlab_engine_projection_slots_in_use",
                "antlab_engine_regime_evaluation_duration_seconds_bucket", "process_cpu_seconds_total", "process_resident_memory_bytes");
        String all = String.join("\n", expressions(engine()));
        assertThat(all).contains("sum by (route) (rate(antlab_engine_http_requests_total", "histogram_quantile(0.95",
                "status=~\"5..\"");
    }

    @Test
    void 개요_대시보드는_엔진_대시보드로_가는_링크를_가진다() throws IOException {
        boolean linked = false;
        for (JsonNode link : GrafanaDashboards.load().get("overview.json").path("links")) {
            linked |= "link".equals(link.path("type").asString()) && "/d/antlab-engine".equals(link.path("url").asString());
        }
        assertThat(linked).isTrue();
    }

    @Test
    void 허용_목록은_정렬되어_있고_프로메테우스가_만드는_이름을_뺀다() throws IOException {
        List<String> inFile = Files.readAllLines(ENGINE_ALLOWLIST).stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).map(l -> l.split("\\s", 2)[0]).toList();
        assertThat(inFile).isSorted().doesNotHaveDuplicates().doesNotContain("up");
        assertThat(inFile).allSatisfy(n -> assertThat(n).matches("(antlab_engine|process)_[a-z_]+"));
    }

    @Test
    void 엔진_지표_이름과_라벨은_파이썬_소스에_있다() throws IOException {
        String source = Files.readString(java.nio.file.Path.of("engine/src/antlab_engine/metrics.py"));
        for (String name : List.of("antlab_engine_http_requests_total", "antlab_engine_http_request_duration_seconds",
                "antlab_engine_projection_slots_in_use", "antlab_engine_projection_slots_capacity",
                "antlab_engine_regime_evaluation_duration_seconds")) {
            assertThat(source).contains("\"" + name + "\"");
        }
        // The route label comes from the matched route template, never from the raw path.
        assertThat(source).contains("scope.get(\"route\")").doesNotContain("scope[\"path\"]");
    }

    @Test
    void 다른_job과_job_없는_선택자는_거부한다() throws IOException {
        String text = engine().toString();
        assertThat(queryProblems(parse(text.replaceFirst("job=\\\\\"antlab-engine\\\\\"", "job=\\\\\"antlab-api\\\\\"")), allowed())).isNotEmpty();
        assertThat(queryProblems(parse("{\"panels\":[{\"targets\":[{\"expr\":\"antlab_engine_http_requests_total{status=\\\"200\\\"}\"}]}]}"), allowed())).isNotEmpty();
    }

    @Test
    void 허용_목록에_없는_이름과_허용되지_않은_라벨은_거부한다() throws IOException {
        String text = engine().toString().replace("antlab_engine_http_requests_total", "antlab_engine_http_request_total");
        assertThat(queryProblems(parse(text), allowed())).anyMatch(p -> p.startsWith("unknown metric antlab_engine_http_request_total "));
        String raw = engine().toString().replace("sum by (route)", "sum by (path)");
        assertThat(queryProblems(parse(raw), allowed())).anyMatch(p -> p.startsWith("label not allowed: path"));
    }

    @Test
    void SQL_표적과_다른_데이터_원천은_거부한다() throws IOException {
        String text = engine().toString();
        assertThat(sourceProblems(parse(text.replace("\"expr\"", "\"rawSql\"")))).isNotEmpty();
        assertThat(sourceProblems(parse(text.replaceFirst("\"uid\":\"antlab-prometheus\"", "\"uid\":\"antlab-postgres\"")))).isNotEmpty();
    }
}
