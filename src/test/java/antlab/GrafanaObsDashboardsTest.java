package antlab;

import static antlab.support.GrafanaDashboards.expressions;
import static antlab.support.GrafanaDashboards.panels;
import static antlab.support.GrafanaDashboards.parse;
import static antlab.support.GrafanaDashboards.sqlStatements;
import static antlab.support.GrafanaDashboards.variableQuery;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.collect.JobName;
import antlab.support.GrafanaDashboards;
import antlab.support.PromQlNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Static checks of the dashboards that read PostgreSQL (OBS-03): data source per target, the relations a panel may read,
 * Grafana macros, money and float casts, and the job names and caps against the collector code and configuration.
 * No Spring context, no Docker. Each check returns its problems so the negative cases can feed it broken input.
 */
class GrafanaObsDashboardsTest {

    private static final String PG_TYPE = "grafana-postgresql-datasource";
    private static final String PG_UID = "antlab-postgres";
    private static final String PROM_TYPE = "prometheus";
    private static final String PROM_UID = "antlab-prometheus";
    private static final Path VIEWS_MIGRATION = Path.of("src/main/resources/db/migration/V13__obs_views.sql");
    private static final Set<String> PUBLIC_TABLES = Set.of("public.series", "public.series_observation");
    private static final Set<String> MACROS = Set.of("$__timeFilter", "$__timeGroupAlias");
    private static final Pattern RELATION = Pattern.compile("\\b(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_.]*)(\\s*\\()?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CTE = Pattern.compile("(?:\\bWITH|,)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\([^)]*\\))?\\s+AS\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMA_JOIN = Pattern.compile("\\bFROM\\s+[\\w.]+(\\s+(?:AS\\s+)?\\w+)?\\s*,", Pattern.CASE_INSENSITIVE);
    private static final Pattern MACRO_USE = Pattern.compile("\\$__\\w+");
    private static final Pattern FLOAT_TYPE = Pattern.compile("\\b(float\\d*|double|real|money)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_VALUE = Pattern.compile("\\('([A-Z_]+)'(?:,\\s*(\\d+))?\\)");

    // ---- checks (return problems) ---------------------------------------------------------------------------------

    static List<String> sourceProblems(JsonNode d, boolean allowPrometheus) {
        List<String> problems = new ArrayList<>();
        for (JsonNode panel : panels(d)) {
            if ("row".equals(panel.path("type").asString())) {
                continue;
            }
            JsonNode panelSource = panel.path("datasource");
            checkSource(panelSource, allowPrometheus, "panel " + panel.path("id"), problems);
            for (JsonNode target : panel.path("targets")) {
                checkSource(target.path("datasource"), allowPrometheus, "panel " + panel.path("id") + " target", problems);
                if (!target.path("datasource").equals(panelSource)) {
                    problems.add("target data source differs from its panel: panel " + panel.path("id"));
                }
                boolean sql = target.has("rawSql");
                boolean prom = target.has("expr");
                if (sql == prom) {
                    problems.add("target must have exactly one of rawSql and expr: panel " + panel.path("id"));
                }
                boolean pgSource = PG_UID.equals(target.path("datasource").path("uid").asString());
                if (sql != pgSource) {
                    problems.add("rawSql needs the PostgreSQL source and expr the Prometheus source: panel " + panel.path("id"));
                }
            }
        }
        for (JsonNode variable : d.path("templating").path("list")) {
            checkSource(variable.path("datasource"), allowPrometheus, "variable " + variable.path("name").asString(), problems);
            if (!PROM_UID.equals(variable.path("datasource").path("uid").asString())) {
                problems.add("variable must use the Prometheus source: " + variable.path("name").asString());
            }
        }
        return problems;
    }

    private static void checkSource(JsonNode ref, boolean allowPrometheus, String where, List<String> problems) {
        String type = ref.path("type").asString("");
        String uid = ref.path("uid").asString("");
        boolean pg = PG_TYPE.equals(type) && PG_UID.equals(uid);
        boolean prom = PROM_TYPE.equals(type) && PROM_UID.equals(uid);
        if (!(pg || (allowPrometheus && prom))) {
            problems.add("data source not allowed (" + where + "): " + ref);
        }
    }

    /** Relations a panel may read: views of schema obs (names from the migration) and the two public macro tables. */
    static List<String> relationProblems(String sql, Set<String> obsViews) {
        List<String> problems = new ArrayList<>();
        Set<String> ctes = new TreeSet<>();
        Matcher cte = CTE.matcher(sql);
        while (cte.find()) {
            ctes.add(cte.group(1).toLowerCase());
        }
        if (COMMA_JOIN.matcher(sql).find()) {
            problems.add("comma join (use JOIN): " + sql);
        }
        Matcher m = RELATION.matcher(sql);
        while (m.find()) {
            if (m.group(2) != null) {
                continue; // a function call such as FROM generate_series(...)
            }
            String name = m.group(1).toLowerCase();
            boolean ok = name.startsWith("obs.") ? obsViews.contains(name.substring(4))
                    : PUBLIC_TABLES.contains(name) || ctes.contains(name);
            if (!ok) {
                problems.add("relation not allowed: " + m.group(1));
            }
        }
        if (sql.toLowerCase().contains("information_schema") || sql.toLowerCase().contains("pg_catalog")
                || sql.toLowerCase().matches("(?s).*\\bpg_(?!database_size\\b)\\w+.*")) {
            problems.add("catalog access");
        }
        return problems;
    }

    static List<String> typeProblems(String sql) {
        List<String> problems = new ArrayList<>();
        if (FLOAT_TYPE.matcher(sql).find()) {
            problems.add("float or money type in " + sql);
        }
        if (sql.contains("::")) {
            problems.add("cast with :: in " + sql);
        }
        if (Pattern.compile("\\bcast\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            problems.add("CAST in " + sql);
        }
        return problems;
    }

    static List<String> macroProblems(JsonNode target) {
        List<String> problems = new ArrayList<>();
        String sql = target.path("rawSql").asString("");
        Matcher m = MACRO_USE.matcher(sql);
        while (m.find()) {
            if (!MACROS.contains(m.group())) {
                problems.add("unknown macro " + m.group() + " in " + sql);
            }
        }
        boolean timeSeries = "time_series".equals(target.path("format").asString());
        if (timeSeries && !sql.contains("$__timeFilter(")) {
            problems.add("time series without $__timeFilter: " + sql);
        }
        if (sql.contains("$__timeGroupAlias") && !sql.contains("$__timeFilter(")) {
            problems.add("$__timeGroupAlias without $__timeFilter: " + sql);
        }
        if (!timeSeries && !"table".equals(target.path("format").asString())) {
            problems.add("format must be time_series or table: " + sql);
        }
        return problems;
    }

    static List<String> sqlProblems(JsonNode d, Set<String> obsViews) {
        List<String> problems = new ArrayList<>();
        for (JsonNode panel : panels(d)) {
            for (JsonNode target : panel.path("targets")) {
                if (!target.has("rawSql")) {
                    continue;
                }
                String sql = target.path("rawSql").asString("");
                problems.addAll(relationProblems(sql, obsViews));
                problems.addAll(typeProblems(sql));
                problems.addAll(macroProblems(target));
                if (!sql.stripLeading().toUpperCase().startsWith("SELECT") && !sql.stripLeading().toUpperCase().startsWith("WITH")) {
                    problems.add("not a SELECT: " + sql);
                }
            }
        }
        return problems;
    }

    /** Prometheus targets of the overview: names from the allowlist, plus {@code up}; the application filter on every selector except up. */
    static List<String> overviewPrometheusProblems(JsonNode d, Set<String> allowedNames) {
        List<String> problems = new ArrayList<>();
        for (String expr : expressions(d)) {
            PromQlNames.bareIdentifiers(expr).forEach(v -> problems.add(v + " in " + expr));
            for (String name : PromQlNames.names(expr)) {
                if (!allowedNames.contains(name) && !name.equals("up")) {
                    problems.add("unknown metric " + name + " in " + expr);
                }
            }
            for (PromQlNames.Selector s : PromQlNames.selectors(expr)) {
                boolean up = s.name().equals("up");
                if (up && !s.matchers().replace(" ", "").equals("job=\"antlab-api\"")) {
                    problems.add("up must select job=\"antlab-api\": " + expr);
                }
                if (!up && !s.matchers().contains("application=~\"$application\"")) {
                    problems.add("no application filter: " + s);
                }
            }
        }
        return problems;
    }

    static List<String> jobProblems(JsonNode d, Map<String, Integer> caps) {
        List<String> problems = new ArrayList<>();
        Set<String> enumNames = new TreeSet<>();
        Arrays.stream(JobName.values()).forEach(j -> enumNames.add(j.name()));
        for (String sql : sqlStatements(d)) {
            if (!sql.contains("VALUES (") || !sql.contains("job_name")) {
                continue;
            }
            Matcher m = JOB_VALUE.matcher(sql);
            Map<String, Integer> found = new TreeMap<>();
            while (m.find()) {
                if (enumNames.contains(m.group(1))) {
                    found.put(m.group(1), m.group(2) == null ? null : Integer.valueOf(m.group(2)));
                }
            }
            if (!found.keySet().equals(enumNames) && !found.isEmpty()) {
                problems.add("job names " + found.keySet() + " differ from JobName " + enumNames);
            }
            found.forEach((job, cap) -> {
                if (cap != null && !cap.equals(caps.get(job))) {
                    problems.add("cap of " + job + " is " + cap + " but application.yml says " + caps.get(job));
                }
            });
        }
        return problems;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static Set<String> obsViews() throws IOException {
        Set<String> views = new TreeSet<>();
        Matcher m = Pattern.compile("CREATE VIEW obs\\.(\\w+)").matcher(Files.readString(VIEWS_MIGRATION));
        while (m.find()) {
            views.add(m.group(1));
        }
        return views;
    }

    @SuppressWarnings("unchecked")
    private static Object find(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
            for (Object value : map.values()) {
                Object found = find(value, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Integer> configuredCaps() throws IOException {
        Map<String, Integer> caps = new TreeMap<>();
        for (Object document : new Yaml().loadAll(Files.readString(Path.of("src/main/resources/application.yml")))) {
            Object collect = find(document, "collect");
            if (collect instanceof Map<?, ?> c) {
                caps.put("KIS_DAILY_PRICE", (Integer) ((Map<String, Object>) c.get("kis")).get("max-requests-per-run"));
                caps.put("FRED_SERIES", (Integer) ((Map<String, Object>) c.get("fred")).get("max-requests-per-run"));
                caps.put("ECOS_SERIES", (Integer) ((Map<String, Object>) c.get("ecos")).get("max-requests-per-run"));
            }
        }
        return caps;
    }

    private static JsonNode collectors() throws IOException {
        return GrafanaDashboards.load().get("collectors.json");
    }

    private static JsonNode overview() throws IOException {
        return GrafanaDashboards.load().get("overview.json");
    }

    private static JsonNode panel(JsonNode d, int id) {
        return panels(d).stream().filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow();
    }

    // ---- positive tests -------------------------------------------------------------------------------------------

    @Test
    void 수집기_대시보드는_PostgreSQL_패널과_장부_내보내기_패널로_정해진_모양이다() throws IOException {
        JsonNode d = collectors();
        assertThat(d.path("uid").asString()).isEqualTo("antlab-collectors");
        assertThat(sourceProblems(d, true)).isEmpty();
        assertThat(panels(d)).hasSize(9);
        assertThat(sqlStatements(d)).hasSize(8);
        // The two Prometheus targets of the fetch panels (OBS-08) are checked in GrafanaLedgerDashboardTest.
        assertThat(expressions(d)).hasSize(3);
        assertThat(panels(d).stream().map(p -> p.path("title").asString()).toList()).containsExactly(
                "잡별 마지막 실행", "상태별 실행 추이", "실행당 요청 수와 상한",
                "추가·정정·변동 없음 행", "실패 대상과 오류 코드", "시계열별 최신 관측",
                "시장별 최신 일봉", "오늘 수집 요청 수와 일일 상한", "수집 장부 읽기");
    }

    @Test
    void 개요_대시보드는_두_데이터_원천을_쓰고_Prometheus_쿼리는_허용_이름만_쓴다() throws IOException {
        JsonNode d = overview();
        assertThat(d.path("uid").asString()).isEqualTo("antlab-overview");
        assertThat(sourceProblems(d, true)).isEmpty();
        assertThat(overviewPrometheusProblems(d, GrafanaDashboards.allowlist().keySet())).isEmpty();
        assertThat(panels(d)).hasSize(8);
        assertThat(expressions(d)).hasSize(4);
        assertThat(sqlStatements(d)).hasSize(4);
    }

    @Test
    void 개요의_up_선택자는_prometheus_yml의_API_잡_이름과_같고_항상_값을_내는_꼼수가_없다() throws IOException {
        String up = panel(overview(), 1).path("targets").get(0).path("expr").asString();
        Matcher job = Pattern.compile("job_name:\\s*(\\S+)").matcher(Files.readString(Path.of("monitoring/prometheus/prometheus.yml")));
        assertThat(job.find()).isTrue();
        assertThat(up).isEqualTo("max(up{job=\"" + job.group(1) + "\"})").doesNotContain("vector(");
    }

    @Test
    void 개요의_오늘_패널은_시간_선택과_상관없이_오늘로_고정된다() throws IOException {
        for (int id : List.of(2, 3)) {
            JsonNode p = panel(overview(), id);
            assertThat(p.path("timeFrom").asString()).as("panel " + id).isEqualTo("now/d");
            assertThat(p.path("targets").get(0).path("expr").asString()).contains("[$__range]").doesNotContain("$__rate_interval");
        }
        assertThat(panel(overview(), 3).path("targets").get(0).path("expr").asString()).contains("status=~\"5..\"");
        assertThat(panel(overview(), 3).path("title").asString()).isEqualTo("오늘 5xx 비율");
    }

    @Test
    void 개요는_API_대시보드와_같은_가동_시간_쿼리를_쓴다() throws IOException {
        assertThat(expressions(GrafanaDashboards.load().get("jvm-db.json")))
                .contains(panel(overview(), 4).path("targets").get(0).path("expr").asString());
    }

    @Test
    void 두_대시보드는_서울_시간대를_명시하고_일별_막대는_서울_날짜로_묶는다() throws IOException {
        assertThat(collectors().path("timezone").asString()).isEqualTo("Asia/Seoul");
        assertThat(overview().path("timezone").asString()).isEqualTo("Asia/Seoul");
        assertThat(sqlStatements(collectors()).get(1)).contains("date_trunc('day', started_at AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'")
                .doesNotContain("$__timeGroupAlias");
    }

    @Test
    void 표_패널은_빈_칸에_noValue_글을_채우지_않는다() throws IOException {
        for (JsonNode d : List.of(collectors(), overview())) {
            for (JsonNode p : panels(d)) {
                if ("table".equals(p.path("type").asString())) {
                    assertThat(p.path("fieldConfig").path("defaults").has("noValue")).as(p.path("title").asString()).isFalse();
                }
            }
        }
    }

    @Test
    void 개요는_application_변수와_다른_antlab_대시보드_링크를_가진다() throws IOException {
        JsonNode d = overview();
        assertThat(variableQuery(d.path("templating").path("list").get(0))).isEqualTo("label_values(jvm_info, application)");
        JsonNode link = d.path("links").get(0);
        assertThat(link.path("type").asString()).isEqualTo("dashboards");
        assertThat(link.path("tags").get(0).asString()).isEqualTo("antlab");
        assertThat(d.path("time").path("from").asString()).isEqualTo("now/d");
    }

    @Test
    void 패널_SQL은_obs_뷰와_두_매크로_표만_읽고_돈이나_실수_형으로_바꾸지_않는다() throws IOException {
        Set<String> views = obsViews();
        assertThat(views).contains("job_run", "series_freshness", "price_freshness");
        for (JsonNode d : List.of(collectors(), overview())) {
            assertThat(sqlProblems(d, views)).isEmpty();
        }
        String all = String.join("\n", sqlStatements(collectors())) + String.join("\n", sqlStatements(overview()));
        assertThat(all).doesNotContain("public.").doesNotContain("error_detail").doesNotContain("request_text");
        assertThat(all).contains("obs.job_run", "obs.series_freshness", "obs.price_freshness");
    }

    @Test
    void 모든_SQL_패널은_antlab_postgres를_쓴다() throws IOException {
        for (JsonNode d : List.of(collectors(), overview())) {
            for (JsonNode p : panels(d)) {
                for (JsonNode t : p.path("targets")) {
                    if (t.has("rawSql")) {
                        assertThat(t.path("datasource").path("uid").asString()).isEqualTo("antlab-postgres");
                        assertThat(t.path("datasource").path("type").asString()).isEqualTo(PG_TYPE);
                        assertThat(p.path("datasource").path("uid").asString()).isEqualTo("antlab-postgres");
                    }
                }
            }
        }
    }

    @Test
    void 잡_이름과_요청_상한은_코드와_설정과_같다() throws IOException {
        Map<String, Integer> caps = configuredCaps();
        assertThat(caps).containsOnlyKeys("KIS_DAILY_PRICE", "FRED_SERIES", "ECOS_SERIES");
        assertThat(jobProblems(collectors(), caps)).isEmpty();
        assertThat(jobProblems(overview(), caps)).isEmpty();
        String capsSql = sqlStatements(collectors()).stream().filter(s -> s.contains("c(job_name, cap)")).findFirst().orElseThrow();
        assertThat(capsSql).contains("('KIS_DAILY_PRICE', 60)", "('FRED_SERIES', 30)", "('ECOS_SERIES', 20)");
    }

    @Test
    void 한_번도_돌지_않은_잡은_no_run으로_보인다() throws IOException {
        for (String sql : List.of(sqlStatements(collectors()).get(0), sqlStatements(overview()).stream()
                .filter(s -> s.contains("WITH jobs")).findFirst().orElseThrow())) {
            assertThat(sql).contains("WITH jobs(job_name) AS (VALUES").contains("LEFT JOIN last").contains("COALESCE(l.status, 'no run')");
        }
    }

    @Test
    void 요청_수_패널은_상한_선을_점선으로_그린다() throws IOException {
        JsonNode p = panel(collectors(), 3);
        assertThat(p.path("targets")).hasSize(2);
        assertThat(p.path("fieldConfig").path("overrides").toString()).contains(".* cap$").contains("dash");
    }

    // ---- negative cases: the checks must reject broken input ------------------------------------------------------

    @Test
    void public_표와_알_수_없는_뷰는_거부한다() throws IOException {
        Set<String> views = obsViews();
        assertThat(relationProblems("SELECT 1 FROM obs.job_run", views)).isEmpty();
        assertThat(relationProblems("SELECT 1 FROM public.series s JOIN public.series_observation o ON true", views)).isEmpty();
        assertThat(relationProblems("SELECT 1 FROM public.job_run", views)).isNotEmpty();
        assertThat(relationProblems("SELECT 1 FROM job_run", views)).isNotEmpty();
        assertThat(relationProblems("SELECT 1 FROM public.member", views)).isNotEmpty();
        assertThat(relationProblems("SELECT 1 FROM obs.nope", views)).isNotEmpty();
        assertThat(relationProblems("SELECT 1 FROM obs.job_run r, public.orders o", views)).isNotEmpty();
        assertThat(relationProblems("SELECT a, b FROM obs.job_run r", views)).isEmpty();
    }

    @Test
    void CTE_이름만_허용하고_다른_이름은_거부한다() {
        Set<String> views = Set.of("job_run");
        assertThat(relationProblems("WITH last AS (SELECT 1 FROM obs.job_run) SELECT 1 FROM last", views)).isEmpty();
        assertThat(relationProblems("WITH jobs(job_name) AS (VALUES ('A')), last AS (SELECT 1 FROM obs.job_run) SELECT 1 FROM jobs j JOIN last l ON true", views)).isEmpty();
        assertThat(relationProblems("WITH last AS (SELECT 1 FROM obs.job_run) SELECT 1 FROM member", views)).isNotEmpty();
    }

    @Test
    void 실수_형과_캐스트는_거부한다() {
        assertThat(typeProblems("SELECT count(*) FROM obs.job_run")).isEmpty();
        assertThat(typeProblems("SELECT cost_usd::float8 FROM obs.agent_call")).isNotEmpty();
        assertThat(typeProblems("SELECT CAST(cost_usd AS double precision) FROM obs.agent_call")).isNotEmpty();
        assertThat(typeProblems("SELECT cost_usd::numeric FROM obs.agent_call")).isNotEmpty();
        assertThat(typeProblems("SELECT cost_usd::real FROM obs.agent_call")).isNotEmpty();
    }

    @Test
    void 알_수_없는_매크로와_시간_조건_없는_시계열은_거부한다() {
        ObjectNode ok = (ObjectNode) parse("{\"format\":\"time_series\",\"rawSql\":\"SELECT $__timeGroupAlias(a, '1d'), 1 FROM obs.job_run WHERE $__timeFilter(a)\"}");
        assertThat(macroProblems(ok)).isEmpty();
        ObjectNode noFilter = (ObjectNode) parse("{\"format\":\"time_series\",\"rawSql\":\"SELECT a AS time, 1 FROM obs.job_run\"}");
        assertThat(macroProblems(noFilter)).isNotEmpty();
        ObjectNode unknown = (ObjectNode) parse("{\"format\":\"table\",\"rawSql\":\"SELECT $__unixEpochFilter(a) FROM obs.job_run\"}");
        assertThat(macroProblems(unknown)).isNotEmpty();
        ObjectNode badFormat = (ObjectNode) parse("{\"format\":\"logs\",\"rawSql\":\"SELECT 1 FROM obs.job_run\"}");
        assertThat(macroProblems(badFormat)).isNotEmpty();
    }

    @Test
    void 다른_데이터_원천과_섞인_표적은_거부한다() throws IOException {
        String text = collectors().toString();
        assertThat(sourceProblems(parse(text.replaceFirst("\"uid\":\"antlab-postgres\"", "\"uid\":\"other\"")), false)).isNotEmpty();
        assertThat(sourceProblems(parse(text.replace("\"uid\":\"antlab-postgres\"", "\"uid\":\"antlab-prometheus\"")
                .replace("grafana-postgresql-datasource", "prometheus")), false)).isNotEmpty();
        assertThat(sourceProblems(parse(text.replace("\"rawSql\"", "\"expr\"")), false)).isNotEmpty();
        // Prometheus in the collectors dashboard is refused, in the overview it is allowed.
        assertThat(sourceProblems(overview(), false)).isNotEmpty();
    }

    @Test
    void 잡_이름과_상한이_어긋나면_거부한다() throws IOException {
        Map<String, Integer> caps = configuredCaps();
        String text = collectors().toString();
        assertThat(jobProblems(parse(text.replace("('FRED_SERIES', 30)", "('FRED_SERIES', 31)")), caps)).isNotEmpty();
        assertThat(jobProblems(parse(text.replace("('ECOS_SERIES', 20)", "('ECOS_SERIES', 25)")), caps)).isNotEmpty();
        assertThat(jobProblems(parse(text.replace("('KIS_DAILY_PRICE', 60), ", "")), caps)).isNotEmpty();
    }

    @Test
    void 허용되지_않은_Prometheus_이름과_application_필터_빠짐은_거부한다() throws IOException {
        Set<String> allowed = GrafanaDashboards.allowlist().keySet();
        String text = overview().toString();
        assertThat(overviewPrometheusProblems(parse(text.replace("process_uptime_seconds", "process_uptime_second")), allowed)).isNotEmpty();
        assertThat(overviewPrometheusProblems(parse(text.replaceFirst("application=~\\\\\"\\$application\\\\\"", "")), allowed)).isNotEmpty();
        assertThat(overviewPrometheusProblems(parse(text.replace("job=\\\"antlab-api\\\"", "job=\\\"other\\\"")), allowed)).isNotEmpty();
    }
}
