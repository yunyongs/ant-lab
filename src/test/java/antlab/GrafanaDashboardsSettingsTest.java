package antlab;

import static antlab.support.GrafanaDashboards.ALLOWLIST;
import static antlab.support.GrafanaDashboards.DASHBOARD_DIR;
import static antlab.support.GrafanaDashboards.PROVISIONING;
import static antlab.support.GrafanaDashboards.allowlist;
import static antlab.support.GrafanaDashboards.expressions;
import static antlab.support.GrafanaDashboards.metricNames;
import static antlab.support.GrafanaDashboards.panels;
import static antlab.support.GrafanaDashboards.parse;
import static antlab.support.GrafanaDashboards.variableQuery;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.GrafanaDashboards;
import antlab.support.PromQlNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Static checks of the provisioned Grafana dashboards (MON-04): provider file, identity, data source, PromQL names against the
 * committed allowlist. No Spring context, no Docker. Each check returns its problems so the negative cases can feed it broken input.
 */
class GrafanaDashboardsSettingsTest {

    private static final String CONTAINER_PROVISIONING = "/etc/grafana/provisioning";
    private static final Map<String, String> UIDS = Map.of(
            "api.json", "antlab-api", "jvm-db.json", "antlab-jvm-db", "game.json", "antlab-game");
    private static final Pattern UID_FORMAT = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final Set<String> PANEL_TYPES = Set.of("timeseries", "stat", "row");
    private static final String APPLICATION_FILTER = "application=~\"$application\"";
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3]");

    @SuppressWarnings("unchecked")
    private static String dataSourceUid() throws IOException {
        Map<String, Object> file = (Map<String, Object>) new Yaml().load(Files.readString(Path.of(PROVISIONING, "datasources/prometheus.yaml")));
        return (String) ((List<Map<String, Object>>) file.get("datasources")).get(0).get("uid");
    }

    // ---- checks (return problems) ---------------------------------------------------------------------------------

    static List<String> uidProblems(List<JsonNode> dashboards) {
        List<String> problems = new ArrayList<>();
        Set<String> seenUids = new java.util.HashSet<>();
        Set<String> seenTitles = new java.util.HashSet<>();
        for (JsonNode d : dashboards) {
            String uid = d.path("uid").asString("");
            if (!UID_FORMAT.matcher(uid).matches()) {
                problems.add("bad uid format: '" + uid + "'");
            }
            if (!seenUids.add(uid)) {
                problems.add("duplicate uid: " + uid);
            }
            if (!seenTitles.add(d.path("title").asString(""))) {
                problems.add("duplicate title: " + d.path("title").asString(""));
            }
        }
        return problems;
    }

    static List<String> exportLeftovers(JsonNode d) {
        List<String> problems = new ArrayList<>();
        if (!d.path("editable").isBoolean() || d.path("editable").asBoolean(true)) {
            problems.add("editable must be false");
        }
        if (d.has("id") && !d.get("id").isNull()) {
            problems.add("numeric id present");
        }
        for (String key : List.of("__inputs", "__requires", "version")) {
            if (d.has(key)) {
                problems.add("export leftover: " + key);
            }
        }
        if (d.toString().contains("${DS_")) {
            problems.add("data source placeholder ${DS_...}");
        }
        return problems;
    }

    static List<String> dataSourceProblems(JsonNode d, String uid) {
        List<String> problems = new ArrayList<>();
        List<JsonNode> refs = new ArrayList<>();
        for (JsonNode panel : panels(d)) {
            if (!"row".equals(panel.path("type").asString())) {
                refs.add(panel.path("datasource"));
            }
            for (JsonNode target : panel.path("targets")) {
                refs.add(target.path("datasource"));
            }
        }
        for (JsonNode variable : d.path("templating").path("list")) {
            refs.add(variable.path("datasource"));
        }
        for (JsonNode ref : refs) {
            if (!"prometheus".equals(ref.path("type").asString()) || !uid.equals(ref.path("uid").asString())) {
                problems.add("data source is not prometheus/" + uid + ": " + ref);
            }
        }
        return problems;
    }

    static List<String> variableProblems(JsonNode d) {
        List<String> problems = new ArrayList<>();
        JsonNode variable = null;
        for (JsonNode v : d.path("templating").path("list")) {
            if ("application".equals(v.path("name").asString())) {
                variable = v;
            }
        }
        if (variable == null) {
            return List.of("variable 'application' missing");
        }
        if (!"query".equals(variable.path("type").asString())) {
            problems.add("application: type must be query");
        }
        if (!"jvm_info".equals(PromQlNames.variableMetric(variableQuery(variable)))
                || !variableQuery(variable).replace(" ", "").endsWith(",application)")) {
            problems.add("application: query must be label_values(jvm_info, application)");
        }
        if (variable.path("multi").asBoolean(true) || variable.path("includeAll").asBoolean(true)) {
            problems.add("application: multi and includeAll must be false");
        }
        return problems;
    }

    static List<String> queryProblems(JsonNode d) {
        List<String> problems = new ArrayList<>();
        for (String expr : expressions(d)) {
            if (expr.isBlank()) {
                problems.add("blank expr");
            }
            PromQlNames.bareIdentifiers(expr).forEach(v -> problems.add(v + " in " + expr));
            PromQlNames.selectorsWithoutApplicationFilter(expr).forEach(v -> problems.add("no application filter: " + v));
            if (!PromQlNames.selectors(expr).isEmpty() && PromQlNames.names(expr).isEmpty()) {
                problems.add("no metric name in " + expr);
            }
        }
        return problems;
    }

    static List<String> unknownNames(JsonNode d, Set<String> allowed) {
        return metricNames(d).stream().filter(n -> !allowed.contains(n)).toList();
    }

    static List<String> panelProblems(JsonNode d) {
        List<String> problems = new ArrayList<>();
        Set<Integer> ids = new java.util.HashSet<>();
        for (JsonNode panel : panels(d)) {
            String type = panel.path("type").asString("");
            String label = "panel " + panel.path("id") + " '" + panel.path("title").asString("") + "'";
            if (!ids.add(panel.path("id").asInt(-1))) {
                problems.add("duplicate or missing panel id: " + label);
            }
            if (!PANEL_TYPES.contains(type)) {
                problems.add("panel type not allowed: " + type + " in " + label);
            }
            if (type.equals("row")) {
                continue;
            }
            if (panel.path("title").asString("").isBlank()) {
                problems.add("blank title: " + label);
            }
            String description = panel.path("description").asString("");
            if (!description.contains(" / ") || !HANGUL.matcher(description).find()) {
                problems.add("description must be English / Korean: " + label);
            }
            JsonNode grid = panel.path("gridPos");
            if (grid.path("w").asInt(0) <= 0 || grid.path("x").asInt(-1) < 0 || grid.path("x").asInt() + grid.path("w").asInt() > 24) {
                problems.add("gridPos out of the 24-column grid: " + label);
            }
            if (!panel.path("targets").isArray() || panel.path("targets").isEmpty()) {
                problems.add("no targets: " + label);
            }
            for (JsonNode target : panel.path("targets")) {
                if (target.path("expr").asString("").isBlank() || target.path("refId").asString("").isBlank()) {
                    problems.add("target without expr or refId: " + label);
                }
            }
            if (panel.path("fieldConfig").path("defaults").path("unit").asString("").isBlank()) {
                problems.add("no unit: " + label);
            }
        }
        return problems;
    }

    static List<String> formatProblems(String text, int panelCount) {
        List<String> problems = new ArrayList<>();
        if (text.contains("\t")) {
            problems.add("tab character");
        }
        if (!text.endsWith("\n")) {
            problems.add("no trailing newline");
        }
        for (String line : text.split("\n")) {
            int indent = line.length() - line.stripLeading().length();
            if (indent % 2 != 0) {
                problems.add("odd indent: " + line);
                break;
            }
        }
        if (text.lines().count() < panelCount * 10L) {
            problems.add("looks minified");
        }
        return problems;
    }

    // ---- positive tests -------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void 대시보드_provider는_마운트된_antlab_폴더를_읽기_전용으로_읽는다() throws IOException {
        Map<String, Object> file = (Map<String, Object>) new Yaml().load(Files.readString(Path.of(PROVISIONING, "dashboards/antlab.yaml")));
        assertThat(file.get("apiVersion")).isEqualTo(1);
        var providers = (List<Map<String, Object>>) file.get("providers");
        assertThat(providers).hasSize(1);
        var provider = providers.get(0);
        assertThat(provider.get("name")).isEqualTo("antlab");
        assertThat(provider.get("orgId")).isEqualTo(1);
        assertThat(provider.get("type")).isEqualTo("file");
        assertThat(provider.get("folder")).isEqualTo("ant-lab");
        assertThat(provider.get("folderUid")).isEqualTo("antlab");
        assertThat(provider.get("disableDeletion")).isEqualTo(true);
        assertThat(provider.get("allowUiUpdates")).isEqualTo(false);
        var options = (Map<String, Object>) provider.get("options");
        assertThat(options.get("foldersFromFilesStructure")).isEqualTo(false);
        String path = (String) options.get("path");
        assertThat(path).startsWith(CONTAINER_PROVISIONING + "/");
        assertThat(Path.of(PROVISIONING, path.substring(CONTAINER_PROVISIONING.length() + 1))).isEqualTo(DASHBOARD_DIR).isDirectory();
        var compose = (Map<String, Object>) new Yaml().load(Files.readString(Path.of("compose.yaml")));
        var grafana = ((Map<String, Map<String, Object>>) compose.get("services")).get("grafana");
        assertThat((List<String>) grafana.get("volumes")).contains("./monitoring/grafana/provisioning:/etc/grafana/provisioning:ro");
    }

    @Test
    void 대시보드는_정해진_uid_세_개다() throws IOException {
        Map<String, JsonNode> dashboards = GrafanaDashboards.load();
        assertThat(dashboards.keySet()).containsExactlyInAnyOrderElementsOf(UIDS.keySet());
        dashboards.forEach((file, d) -> assertThat(d.path("uid").asString()).as(file).isEqualTo(UIDS.get(file)));
        assertThat(uidProblems(List.copyOf(dashboards.values()))).isEmpty();
    }

    @Test
    void 대시보드는_편집할_수_없고_내보내기_흔적이_없다() throws IOException {
        GrafanaDashboards.load().forEach((file, d) -> assertThat(exportLeftovers(d)).as(file).isEmpty());
    }

    @Test
    void 모든_패널과_쿼리는_antlab_prometheus_데이터_원천을_쓴다() throws IOException {
        String uid = dataSourceUid();
        assertThat(uid).isEqualTo("antlab-prometheus");
        GrafanaDashboards.load().forEach((file, d) -> assertThat(dataSourceProblems(d, uid)).as(file).isEmpty());
    }

    @Test
    void application_변수가_있고_모든_선택자가_그것으로_거른다() throws IOException {
        GrafanaDashboards.load().forEach((file, d) -> {
            assertThat(variableProblems(d)).as(file).isEmpty();
            assertThat(queryProblems(d)).as(file).isEmpty();
            assertThat(expressions(d)).as(file).isNotEmpty().allSatisfy(e -> assertThat(e).contains(APPLICATION_FILTER));
        });
    }

    @Test
    void 쿼리의_지표_이름은_모두_실제_scrape에_있다() throws IOException {
        Set<String> allowed = allowlist().keySet();
        GrafanaDashboards.load().forEach((file, d) -> assertThat(unknownNames(d, allowed)).as(file).isEmpty());
    }

    @Test
    void 패널은_id가_겹치지_않고_허용된_종류만_쓴다() throws IOException {
        GrafanaDashboards.load().forEach((file, d) -> assertThat(panelProblems(d)).as(file).isEmpty());
    }

    @Test
    void 대시보드가_약속한_지표를_모두_보여_준다() throws IOException {
        Map<String, JsonNode> dashboards = GrafanaDashboards.load();
        String api = String.join("\n", expressions(dashboards.get("api.json")));
        assertThat(api).contains("http_server_requests_seconds_bucket", "http_server_requests_seconds_count");
        for (String q : List.of("0.5", "0.95", "0.99")) {
            assertThat(api).contains("histogram_quantile(" + q + ",");
        }
        Set<String> jvm = metricNames(dashboards.get("jvm-db.json"));
        assertThat(jvm).contains("jvm_memory_used_bytes", "jvm_threads_live_threads", "hikaricp_connections_active",
                "hikaricp_connections_idle", "hikaricp_connections_pending", "hikaricp_connections_acquire_seconds_sum",
                "hikaricp_connections_acquire_seconds_count", "hikaricp_connections_acquire_seconds_max");
        assertThat(jvm).anyMatch(n -> n.startsWith("jvm_gc_"));
        assertThat(metricNames(dashboards.get("game.json"))).contains("antlab_season_start_total", "antlab_turn_advance_total",
                "antlab_reconciliation_mismatch_rules_total", "antlab_order_rejected_total");
    }

    @Test
    void JSON이_아닌_파일은_읽지_않는다(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.json"), "{\"panels\": []}");
        Files.write(dir.resolve(".DS_Store"), new byte[] {0, 1, 2, (byte) 0xff});

        assertThat(GrafanaDashboards.load(dir)).containsOnlyKeys("a.json");
    }

    @Test
    void JSON은_사람이_읽는_형식이다() throws IOException {
        try (Stream<Path> files = Files.list(DASHBOARD_DIR)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                String text = Files.readString(file);
                assertThat(formatProblems(text, panels(parse(text)).size())).as(file.toString()).isEmpty();
            }
        }
    }

    @Test
    void 허용_목록은_정렬되어_있고_이름_형식이다() throws IOException {
        Map<String, Boolean> names = allowlist();
        assertThat(names.keySet()).isNotEmpty().allSatisfy(n -> assertThat(n).matches("[a-zA-Z_:][a-zA-Z0-9_:]*"));
        List<String> inFile = Files.readAllLines(ALLOWLIST).stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).map(l -> l.split("\\s", 2)[0]).toList();
        assertThat(inFile).isSorted().doesNotHaveDuplicates();
        assertThat(names.keySet()).noneMatch(n -> n.equals("up") || n.startsWith("scrape_"));
    }

    // ---- negative cases: the checks must reject broken input ------------------------------------------------------

    private static final String GOOD = "sum(rate(x_total{application=~\"$application\"}[$__rate_interval]))";

    @Test
    void 맨_지표_이름은_거부한다() {
        assertThat(PromQlNames.bareIdentifiers(GOOD)).isEmpty();
        assertThat(PromQlNames.bareIdentifiers("rate(jvm_info[5m])")).isNotEmpty();
        assertThat(PromQlNames.bareIdentifiers("sum(jvm_info) by (application)")).isNotEmpty();
    }

    @Test
    void application_필터_없는_선택자는_거부한다() {
        assertThat(PromQlNames.selectorsWithoutApplicationFilter(GOOD)).isEmpty();
        assertThat(PromQlNames.selectorsWithoutApplicationFilter("sum(jvm_info{area=\"heap\"})")).isNotEmpty();
        assertThat(PromQlNames.selectorsWithoutApplicationFilter("sum(jvm_info{application=\"antlab-api\"})")).isNotEmpty();
    }

    @Test
    void 오타_이름은_허용_목록에서_빠진다() throws IOException {
        Set<String> allowed = allowlist().keySet();
        assertThat(allowed).contains("http_server_requests_seconds_bucket").doesNotContain("http_server_request_seconds_bucket");
        JsonNode d = GrafanaDashboards.load().get("api.json");
        String text = d.toString().replace("http_server_requests_seconds_bucket", "http_server_request_seconds_bucket");
        assertThat(unknownNames(parse(text), allowed)).containsExactly("http_server_request_seconds_bucket");
    }

    @Test
    void by_라벨과_범위와_문자열은_이름으로_보지_않는다() {
        String expr = "sum by (le, uri) (rate(x{application=~\"$application\",status=~\"4..|5..\"}[$__rate_interval])) or vector(0)";
        assertThat(PromQlNames.names(expr)).containsExactly("x");
        assertThat(PromQlNames.bareIdentifiers(expr)).isEmpty();
        assertThat(PromQlNames.bareIdentifiers("histogram_quantile(0.95, " + expr.substring(0, expr.indexOf(" or ")) + ")")).isEmpty();
    }

    @Test
    void 이름_없는_중괄호_선택자는_거부한다() {
        assertThat(PromQlNames.bareIdentifiers("{__name__=~\"jvm.*\",application=~\"$application\"}")).isNotEmpty();
    }

    @Test
    void 다른_데이터_원천_uid는_거부한다() throws IOException {
        JsonNode d = GrafanaDashboards.load().get("api.json");
        assertThat(dataSourceProblems(d, "antlab-prometheus")).isEmpty();
        JsonNode foreign = parse(d.toString().replaceFirst("\"uid\":\"antlab-prometheus\"", "\"uid\":\"other\""));
        assertThat(dataSourceProblems(foreign, "antlab-prometheus")).isNotEmpty();
        JsonNode wrongType = parse(d.toString().replaceFirst("\"type\":\"prometheus\"", "\"type\":\"loki\""));
        assertThat(dataSourceProblems(wrongType, "antlab-prometheus")).isNotEmpty();
    }

    @Test
    void 겹치는_uid는_거부한다() throws IOException {
        JsonNode api = GrafanaDashboards.load().get("api.json");
        JsonNode game = GrafanaDashboards.load().get("game.json").deepCopy();
        ((ObjectNode) game).put("uid", api.path("uid").asString());
        assertThat(uidProblems(List.of(api, game))).anyMatch(p -> p.startsWith("duplicate uid"));
        JsonNode sameTitle = GrafanaDashboards.load().get("game.json").deepCopy();
        ((ObjectNode) sameTitle).put("title", api.path("title").asString());
        assertThat(uidProblems(List.of(api, sameTitle))).anyMatch(p -> p.startsWith("duplicate title"));
    }

    @Test
    void editable_true는_거부한다() throws IOException {
        JsonNode d = GrafanaDashboards.load().get("api.json").deepCopy();
        ((ObjectNode) d).put("editable", true);
        assertThat(exportLeftovers(d)).contains("editable must be false");
        ((ObjectNode) d).remove("editable");
        assertThat(exportLeftovers(d)).contains("editable must be false");
    }

    @Test
    void DS_자리표시자는_거부한다() throws IOException {
        JsonNode d = parse(GrafanaDashboards.load().get("api.json").toString().replace("\"uid\":\"antlab-prometheus\"", "\"uid\":\"${DS_PROMETHEUS}\""));
        assertThat(exportLeftovers(d)).contains("data source placeholder ${DS_...}");
        assertThat(dataSourceProblems(d, "antlab-prometheus")).isNotEmpty();
    }

    @Test
    void 내보내기_흔적과_숫자_id는_거부한다() throws IOException {
        ObjectNode d = (ObjectNode) GrafanaDashboards.load().get("api.json").deepCopy();
        d.put("id", 7);
        d.putObject("__inputs");
        d.put("version", 3);
        assertThat(exportLeftovers(d)).contains("numeric id present", "export leftover: __inputs", "export leftover: version");
    }

    @Test
    void 겹치는_패널_id와_허용되지_않은_종류는_거부한다() throws IOException {
        String text = GrafanaDashboards.load().get("api.json").toString();
        assertThat(panelProblems(parse(text.replaceFirst("\"type\":\"stat\"", "\"type\":\"piechart\"")))).isNotEmpty();
        JsonNode d = parse(text);
        assertThat(panelProblems(d)).isEmpty();
        ((ObjectNode) d.path("panels").get(1)).put("id", d.path("panels").get(0).path("id").asInt());
        assertThat(panelProblems(d)).anyMatch(p -> p.startsWith("duplicate or missing panel id"));
    }

    @Test
    void 지표_이름_변수와_쿼리_변수_이름도_허용_목록으로_본다() {
        JsonNode d = parse("{\"templating\":{\"list\":[{\"name\":\"application\",\"query\":{\"query\":\"label_values(jvm_inf, application)\"}}]}}");
        assertThat(metricNames(d)).containsExactly("jvm_inf");
        assertThat(unknownNames(d, Set.of("jvm_info"))).containsExactly("jvm_inf");
    }
}
