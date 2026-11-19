package antlab;

import static antlab.support.GrafanaDashboards.DB_ALLOWLIST;
import static antlab.support.GrafanaDashboards.expressions;
import static antlab.support.GrafanaDashboards.metricNames;
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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * Static checks of the database dashboard (OBS-06): data source, the job selector on every query, metric names against the
 * allowlist taken from a real postgres_exporter scrape, and the exporter's own query file. No Spring context, no Docker.
 * Each check returns its problems so the negative cases can feed it broken input.
 */
class GrafanaDbDashboardTest {

    private static final String JOB_MATCHER = "job=\"antlab-db\"";
    private static final Path QUERIES = Path.of("monitoring/postgres-exporter/queries.yaml");
    private static final Pattern ALLOWLIST_NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");

    private static JsonNode db() throws IOException {
        return GrafanaDashboards.load().get("db.json");
    }

    private static Set<String> allowed() throws IOException {
        return GrafanaDashboards.allowlist(DB_ALLOWLIST).keySet();
    }

    // ---- checks (return problems) ---------------------------------------------------------------------------------

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
                    problems.add("no job=\"antlab-db\" filter: " + s);
                }
            }
        }
        return problems;
    }

    /** What the exporter's query file may read: statistics views of pg_catalog, never a user relation and never statement text. */
    static List<String> exporterQueryProblems(Map<String, Map<String, Object>> queries) {
        List<String> problems = new ArrayList<>();
        for (var entry : queries.entrySet()) {
            String sql = String.valueOf(entry.getValue().get("query"));
            String lower = sql.toLowerCase();
            if (!lower.startsWith("select ")) {
                problems.add(entry.getKey() + ": not a SELECT");
            }
            if (sql.contains(";")) {
                problems.add(entry.getKey() + ": more than one statement");
            }
            if (Pattern.compile("\\bquery\\b").matcher(lower).find()) {
                problems.add(entry.getKey() + ": reads statement text");
            }
            var relation = Pattern.compile("\\b(?:from|join)\\s+([a-z_][a-z0-9_.]*)(\\s*\\()?").matcher(lower);
            while (relation.find()) {
                if (relation.group(2) != null) {
                    continue; // a function call such as extract(epoch FROM now() - x)
                }
                if (!relation.group(1).startsWith("pg_catalog.pg_stat_")) {
                    problems.add(entry.getKey() + ": relation not allowed: " + relation.group(1));
                }
            }
            if (Pattern.compile("\\b(public|obs)\\.").matcher(lower).find()) {
                problems.add(entry.getKey() + ": names a public or obs relation");
            }
            if (!entry.getValue().containsKey("metrics")) {
                problems.add(entry.getKey() + ": no metrics");
            }
        }
        return problems;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> exporterQueries() throws IOException {
        return (Map<String, Map<String, Object>>) new Yaml().load(Files.readString(QUERIES));
    }

    /** The metric names the exporter derives from a query: pg_<query>_<column> for every non-label column. */
    @SuppressWarnings("unchecked")
    private static Set<String> derivedNames(Map<String, Map<String, Object>> queries) {
        Set<String> names = new java.util.TreeSet<>();
        for (var entry : queries.entrySet()) {
            for (Map<String, Map<String, Object>> column : (List<Map<String, Map<String, Object>>>) (List<?>) entry.getValue().get("metrics")) {
                column.forEach((col, spec) -> {
                    if (!"LABEL".equals(spec.get("usage"))) {
                        names.add(entry.getKey() + "_" + col);
                    }
                });
            }
        }
        return names;
    }

    // ---- positive tests -------------------------------------------------------------------------------------------

    @Test
    void 데이터베이스_대시보드는_정해진_모양이다() throws IOException {
        JsonNode d = db();
        assertThat(d.path("uid").asString()).isEqualTo("antlab-db");
        assertThat(d.path("title").asString()).isEqualTo("ant-lab · Database");
        assertThat(d.path("tags")).hasSize(1);
        assertThat(d.path("tags").get(0).asString()).isEqualTo("antlab");
        assertThat(d.path("timezone").asString()).isEqualTo("Asia/Seoul");
        assertThat(d.path("templating").path("list")).isEmpty();
        assertThat(panels(d)).hasSize(11);
        assertThat(panels(d).stream().map(p -> p.path("title").asString()).toList()).containsExactly(
                "Database reachable", "Database size", "Client connections", "Deadlocks in range", "Longest open transaction",
                "Connections by role and state", "Database size over time", "Transactions per second", "Deadlocks",
                "Longest open transaction over time", "Largest tables");
        assertThat(GrafanaDashboardsSettingsTest.exportLeftovers(d)).isEmpty();
        assertThat(GrafanaDashboardsSettingsTest.panelProblems(d)).isEmpty();
        assertThat(GrafanaDashboardsSettingsTest.uidProblems(List.copyOf(GrafanaDashboards.load().values()))).isEmpty();
    }

    @Test
    void 모든_쿼리는_Prometheus_표적이고_허용된_이름과_job_선택자만_쓴다() throws IOException {
        JsonNode d = db();
        assertThat(sourceProblems(d)).isEmpty();
        assertThat(queryProblems(d, allowed())).isEmpty();
        assertThat(GrafanaDashboards.sqlStatements(d)).isEmpty();
        assertThat(expressions(d)).hasSize(12).allSatisfy(e -> assertThat(e).contains(JOB_MATCHER));
        assertThat(metricNames(d)).isSubsetOf(allowed());
    }

    @Test
    void 대시보드가_약속한_지표를_모두_보여_준다() throws IOException {
        Set<String> names = metricNames(db());
        assertThat(names).contains("pg_up", "pg_database_size_bytes", "pg_connections_count", "pg_stat_database_xact_commit",
                "pg_stat_database_xact_rollback", "pg_stat_database_deadlocks", "pg_longest_transaction_seconds", "pg_table_size_bytes");
        String all = String.join("\n", expressions(db()));
        assertThat(all).contains("sum by (role, state) (pg_connections_count", "rate(pg_stat_database_xact_commit",
                "increase(pg_stat_database_deadlocks");
    }

    @Test
    void 개요_대시보드는_데이터베이스_대시보드로_가는_링크를_가진다() throws IOException {
        JsonNode links = GrafanaDashboards.load().get("overview.json").path("links");
        boolean linked = false;
        for (JsonNode link : links) {
            linked |= "link".equals(link.path("type").asString()) && "/d/antlab-db".equals(link.path("url").asString());
        }
        assertThat(linked).isTrue();
    }

    @Test
    void 허용_목록은_정렬되어_있고_이름_형식이며_프로메테우스가_만드는_이름을_뺀다() throws IOException {
        Map<String, Boolean> names = GrafanaDashboards.allowlist(DB_ALLOWLIST);
        assertThat(names.keySet()).isNotEmpty().allSatisfy(n -> assertThat(n).matches(ALLOWLIST_NAME).startsWith("pg_"));
        List<String> inFile = Files.readAllLines(DB_ALLOWLIST).stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).map(l -> l.split("\\s", 2)[0]).toList();
        assertThat(inFile).isSorted().doesNotHaveDuplicates();
        assertThat(names.values()).doesNotContain(true);
    }

    @Test
    void 내보내기의_자체_쿼리는_통계_뷰만_읽고_구문_원문을_고르지_않는다() throws IOException {
        var queries = exporterQueries();
        assertThat(queries).containsOnlyKeys("pg_connections", "pg_longest_transaction", "pg_table_size");
        assertThat(exporterQueryProblems(queries)).isEmpty();
        assertThat(Files.readString(QUERIES)).doesNotContain("password").doesNotContain("query_text");
    }

    @Test
    void 자체_쿼리가_만드는_지표_이름은_허용_목록에_있다() throws IOException {
        assertThat(derivedNames(exporterQueries())).containsExactlyInAnyOrder("pg_connections_count", "pg_longest_transaction_seconds",
                "pg_table_size_bytes").isSubsetOf(allowed());
    }

    // ---- negative cases: the checks must reject broken input ------------------------------------------------------

    @Test
    void 다른_job과_job_없는_선택자는_거부한다() throws IOException {
        String text = db().toString();
        assertThat(queryProblems(parse(text.replaceFirst("job=\\\\\"antlab-db\\\\\"", "job=\\\\\"antlab-api\\\\\"")), allowed())).isNotEmpty();
        assertThat(queryProblems(parse(text.replaceFirst("\\{job=\\\\\"antlab-db\\\\\",datname=\\\\\"antlab_api\\\\\"\\}", "{datname=\\\\\"antlab_api\\\\\"}")),
                allowed())).isNotEmpty();
        assertThat(queryProblems(parse("{\"panels\":[{\"targets\":[{\"expr\":\"pg_up\"}]}]}"), allowed())).isNotEmpty();
    }

    @Test
    void 허용_목록에_없는_이름은_거부한다() throws IOException {
        String text = db().toString().replace("pg_stat_database_deadlocks", "pg_stat_database_deadlock");
        assertThat(queryProblems(parse(text), allowed())).anyMatch(p -> p.startsWith("unknown metric pg_stat_database_deadlock "));
        assertThat(allowed()).doesNotContain("pg_stat_database_deadlock");
    }

    @Test
    void SQL_표적과_다른_데이터_원천은_거부한다() throws IOException {
        String text = db().toString();
        assertThat(sourceProblems(parse(text.replace("\"expr\"", "\"rawSql\"")))).isNotEmpty();
        assertThat(sourceProblems(parse(text.replaceFirst("\"uid\":\"antlab-prometheus\"", "\"uid\":\"antlab-postgres\"")))).isNotEmpty();
    }

    @Test
    void 내보내기_쿼리가_사용자_테이블이나_구문_원문을_읽으면_거부한다() throws IOException {
        var good = exporterQueries();
        assertThat(exporterQueryProblems(good)).isEmpty();
        var text = Files.readString(QUERIES);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> userTable = (Map<String, Map<String, Object>>) new Yaml()
                .load(text.replace("pg_catalog.pg_stat_user_tables", "public.member"));
        assertThat(exporterQueryProblems(userTable)).anyMatch(p -> p.contains("relation not allowed"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> statementText = (Map<String, Map<String, Object>>) new Yaml()
                .load(text.replace("SELECT usename AS role", "SELECT query, usename AS role"));
        assertThat(exporterQueryProblems(statementText)).anyMatch(p -> p.contains("reads statement text"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> twoStatements = (Map<String, Map<String, Object>>) new Yaml()
                .load(text.replace("GROUP BY 1, 2\"", "GROUP BY 1, 2; DELETE FROM x\""));
        assertThat(exporterQueryProblems(twoStatements)).anyMatch(p -> p.contains("more than one statement"));
    }
}
