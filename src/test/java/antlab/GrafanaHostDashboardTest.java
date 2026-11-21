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
 * Static checks of the dashboard that reads the host exporter (OBS-13): antlab-host-resources.
 * Job selector on every query, metric names against the allowlist (kept equal to the exporter source), fixed label names only.
 * No Spring context, no Docker. Each check returns its problems so the negative cases can feed it broken input.
 */
class GrafanaHostDashboardTest {

    private static final Path HOST_ALLOWLIST = Path.of("src/test/resources/monitoring/metric-names-host.txt");
    private static final Path EXPORTER_DIR = Path.of("ai/src/antlab_ai/host_exporter");
    private static final String JOB_MATCHER = "job=\"antlab-host\"";
    /** Label names the exporter may put on a series; a query may group, filter or join by these only. */
    private static final Set<String> LABELS = Set.of("kind", "worktree", "service", "source", "dir", "job");
    /** The exporter never reads command lines into labels; a query naming them would be reading something that does not exist. */
    private static final Set<String> FORBIDDEN = Set.of("command", "argv", "cmd", "path", "pid", "env", "name");

    private static JsonNode host() throws IOException {
        return GrafanaDashboards.load().get("host-resources.json");
    }

    private static Set<String> allowed() throws IOException {
        Set<String> names = new TreeSet<>(GrafanaDashboards.allowlist(HOST_ALLOWLIST).keySet());
        names.add("up"); // made by Prometheus for every target
        return names;
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
                    problems.add("no job=\"antlab-host\" filter: " + s);
                }
            }
            Matcher grouping = Pattern.compile("\\b(?:by|without|on|ignoring)\\s*\\(([^)]*)\\)").matcher(expr);
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
    void 로컬_리소스_대시보드는_정해진_모양이다() throws IOException {
        JsonNode d = host();
        assertThat(d.path("uid").asString()).isEqualTo("antlab-host-resources");
        assertThat(d.path("timezone").asString()).isEqualTo("Asia/Seoul");
        assertThat(sourceProblems(d)).isEmpty();
        assertThat(panels(d).stream().map(p -> p.path("title").asString()).toList()).containsExactly(
                "내보내기 응답", "남은 Testcontainers", "슬롯 점유", "Gradle 데몬·워커 수", "수집 원천 상태",
                "종류별 CPU 사용률", "종류별 메모리 사용량", "오래 남은 고CPU 프로세스", "놀고 있는 데몬",
                "컨테이너 메모리 대비 상한", "수집 소요 시간", "머지된 브랜치의 워크트리", "미커밋 변경이 있는 워크트리", "멈추거나 남은 컨테이너");
    }

    @Test
    void 쿼리는_job_선택자와_허용_이름과_고정_라벨만_쓴다() throws IOException {
        assertThat(queryProblems(host(), allowed())).isEmpty();
    }

    @Test
    void 대시보드와_경보가_쓰는_이름은_모두_허용_목록에_있다() throws IOException {
        Set<String> used = GrafanaDashboards.metricNames(host());
        assertThat(allowed()).containsAll(used);
        assertThat(Files.readString(Path.of("monitoring/grafana/provisioning/alerting/antlab-rules.yaml")))
                .contains("antlab_host_process_stale{job=\\\"antlab-host\\\"}");
    }

    @Test
    void 허용_이름은_내보내기가_실제로_내는_이름과_같다() throws IOException {
        Set<String> exported = new TreeSet<>();
        Pattern name = Pattern.compile("\"(antlab_host_[a-z_]+)\"");
        try (var files = Files.list(EXPORTER_DIR)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".py")).toList()) {
                Matcher m = name.matcher(Files.readString(file));
                while (m.find()) {
                    exported.add(m.group(1));
                }
            }
        }
        assertThat(GrafanaDashboards.allowlist(HOST_ALLOWLIST).keySet()).containsExactlyInAnyOrderElementsOf(exported);
    }

    @Test
    void 개요가_로컬_리소스로_링크한다() throws IOException {
        JsonNode links = GrafanaDashboards.load().get("overview.json").path("links");
        assertThat(links.toString()).contains("\"/d/antlab-host-resources\"");
    }

    @Test
    void 호스트_내보내기는_도커_밖_호스트_루프백_대상이다() throws IOException {
        String prometheus = Files.readString(Path.of("monitoring/prometheus/prometheus.yml"));
        assertThat(prometheus).contains("job_name: antlab-host").contains("host.docker.internal:9191");
        String compose = Files.readString(Path.of("compose.yaml"));
        assertThat(compose).doesNotContain("9191");
        String main = Files.readString(Path.of("ai/src/antlab_ai/host_exporter/__main__.py"));
        assertThat(main).contains("BIND_ADDRESS = \"127.0.0.1\"").doesNotContain("0.0.0.0");
    }

    @Test
    void launchd_예시는_루프백만_쓰고_비밀값을_담지_않는다() throws IOException {
        Path plist = Path.of("monitoring/host-exporter/com.antlab.host-exporter.plist.example");
        String text = Files.readString(plist);
        assertThat(text).contains("com.antlab.host-exporter").contains("antlab_ai.host_exporter").contains("RunAtLoad");
        assertThat(text).doesNotContain(".claude");
        assertThat(host().toString()).doesNotContain(".claude");
        assertThat(text).doesNotContain("0.0.0.0").doesNotContainIgnoringCase("token").doesNotContainIgnoringCase("password");
    }

    // ---- negative cases: the checks must reject broken input ------------------------------------------------------

    @Test
    void job_선택자_빠짐과_모르는_이름은_거부한다() throws IOException {
        String text = host().toString();
        assertThat(queryProblems(parse(text.replaceFirst("job=\\\\\"antlab-host\\\\\"", "job=\\\\\"other\\\\\"")), allowed())).isNotEmpty();
        assertThat(queryProblems(parse(text.replace("antlab_host_process_stale", "antlab_host_process_stall")), allowed())).isNotEmpty();
    }

    @Test
    void 내보내지_않는_라벨과_명령줄_라벨은_거부한다() throws IOException {
        String text = host().toString();
        assertThat(queryProblems(parse(text.replace("sum by (kind)", "sum by (command)")), allowed())).isNotEmpty();
        assertThat(queryProblems(parse(text.replace("sum by (kind)", "sum by (pid)")), allowed())).isNotEmpty();
    }

    @Test
    void PostgreSQL_표적이_섞이면_거부한다() throws IOException {
        String text = host().toString();
        assertThat(sourceProblems(parse(text.replaceFirst("\"expr\"", "\"rawSql\"")))).isNotEmpty();
        assertThat(sourceProblems(parse(text.replaceFirst("antlab-prometheus", "antlab-postgres")))).isNotEmpty();
    }
}
