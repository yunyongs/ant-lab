package antlab;

import static antlab.support.GrafanaDashboards.PROVISIONING;
import static antlab.support.GrafanaDashboards.allowlist;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Static checks of the provisioned alert rules (OBS-10): provisioned from file only, nothing that can send, data sources and
 * metric names that exist, no query on a {@code public} table, and the fields every rule needs. No Spring context, no Docker.
 * Each check returns its problems so the negative cases can feed it broken input.
 */
class GrafanaAlertRulesSettingsTest {

    private static final Path ALERTING = Path.of(PROVISIONING, "alerting");
    private static final Path RULES = ALERTING.resolve("antlab-rules.yaml");
    private static final Set<String> EXPECTED_UIDS = Set.of(
            "antlab-alert-api-down", "antlab-alert-api-5xx", "antlab-alert-reconciliation-mismatch", "antlab-alert-engine-down",
            "antlab-alert-collector-failed", "antlab-alert-collector-stale", "antlab-alert-openrouter-cap", "antlab-alert-ollama-stop",
            "antlab-alert-ledger-unreadable");
    private static final Pattern UID_FORMAT = Pattern.compile("^antlab-alert-[a-z0-9-]{1,30}$");
    private static final Set<String> SEVERITIES = Set.of("critical", "warning", "info");
    private static final Pattern RELATION = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_][a-z0-9_.]*)");
    private static final Pattern SELECTOR_NAME = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*\\{");
    private static final Pattern JOB_LABEL = Pattern.compile("job\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern SENDERS = Pattern.compile("(?i)webhook|slack|email|smtp|telegram|discord|pagerduty|opsgenie|teams");
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3]");
    private static final List<String> FORBIDDEN_KEYS =
            List.of("contactPoints", "receivers", "policies", "muteTimes", "templates", "deleteContactPoints", "deletePolicies");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String text) {
        return (Map<String, Object>) new Yaml().load(text);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rules(Map<String, Object> file) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) file.get("groups")) {
            all.addAll((List<Map<String, Object>>) group.get("rules"));
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> queries(Map<String, Object> rule) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> step : (List<Map<String, Object>>) rule.get("data")) {
            if (!"__expr__".equals(step.get("datasourceUid"))) {
                out.add(step);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String text(Map<String, Object> step) {
        Map<String, Object> model = (Map<String, Object>) step.get("model");
        return String.valueOf(model.containsKey("rawSql") ? model.get("rawSql") : model.get("expr"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> dataSourceUids() throws IOException {
        Set<String> uids = new HashSet<>();
        for (String file : List.of("datasources/prometheus.yaml", "datasources/postgres.yaml")) {
            Map<String, Object> ds = load(Files.readString(Path.of(PROVISIONING, file)));
            for (Map<String, Object> d : (List<Map<String, Object>>) ds.get("datasources")) {
                uids.add((String) d.get("uid"));
            }
        }
        return uids;
    }

    private static Set<String> allowedMetrics() throws IOException {
        Set<String> names = new HashSet<>(Set.of("up"));
        for (String file : List.of("metric-names.txt", "metric-names-ledger.txt", "metric-names-engine.txt")) {
            names.addAll(allowlist(Path.of("src/test/resources/monitoring", file)).keySet());
        }
        return names;
    }

    private static Set<String> scrapeJobs() throws IOException {
        Set<String> jobs = new HashSet<>();
        Matcher m = Pattern.compile("job_name:\\s*(\\S+)").matcher(Files.readString(Path.of("monitoring/prometheus/prometheus.yml")));
        while (m.find()) {
            jobs.add(m.group(1));
        }
        return jobs;
    }

    static List<String> senderProblems(Map<String, Object> file, String raw) {
        List<String> problems = new ArrayList<>();
        for (String key : FORBIDDEN_KEYS) {
            if (file.containsKey(key)) {
                problems.add("provisions " + key);
            }
        }
        // the rule text may say "email" nowhere: a receiver word in any value is a sign of a channel
        Matcher m = SENDERS.matcher(raw.replaceAll("(?m)^\\s*#.*$", ""));
        if (m.find()) {
            problems.add("mentions a sender: " + m.group());
        }
        for (Map<String, Object> rule : rules(file)) {
            if (rule.containsKey("notification_settings")) {
                problems.add(rule.get("uid") + " routes to a receiver");
            }
        }
        return problems;
    }

    static List<String> queryProblems(Map<String, Object> file, Set<String> dsUids, Set<String> metrics, Set<String> jobs) {
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> rule : rules(file)) {
            String uid = String.valueOf(rule.get("uid"));
            for (Map<String, Object> step : queries(rule)) {
                String ds = String.valueOf(step.get("datasourceUid"));
                String body = text(step);
                if (!dsUids.contains(ds)) {
                    problems.add(uid + " reads unknown data source " + ds);
                }
                if ("antlab-postgres".equals(ds)) {
                    Matcher rel = RELATION.matcher(body);
                    boolean any = false;
                    while (rel.find()) {
                        any = true;
                        String name = rel.group(1);
                        if (!name.startsWith("obs.")) {
                            problems.add(uid + " reads " + name + " (only obs. views)");
                        }
                    }
                    if (!any) {
                        problems.add(uid + " has a SQL query without a relation");
                    }
                    if (Pattern.compile("(?i)\\bpublic\\b").matcher(body).find()) {
                        problems.add(uid + " names the public schema");
                    }
                } else {
                    Matcher sel = SELECTOR_NAME.matcher(body);
                    while (sel.find()) {
                        if (!metrics.contains(sel.group(1))) {
                            problems.add(uid + " uses metric " + sel.group(1) + " that no allowlist has");
                        }
                    }
                    Matcher job = JOB_LABEL.matcher(body);
                    while (job.find()) {
                        if (!jobs.contains(job.group(1))) {
                            problems.add(uid + " uses job " + job.group(1) + " that Prometheus does not scrape");
                        }
                    }
                }
            }
        }
        return problems;
    }

    @SuppressWarnings("unchecked")
    static List<String> shapeProblems(Map<String, Object> file) {
        List<String> problems = new ArrayList<>();
        if (!Integer.valueOf(1).equals(file.get("apiVersion"))) {
            problems.add("apiVersion is not 1");
        }
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) file.get("groups")) {
            if (group.get("folder") == null || String.valueOf(group.get("folder")).equals("ant-lab")) {
                problems.add("group " + group.get("name") + " folder must be set and differ from the dashboard folder title");
            }
            if (group.get("interval") == null) {
                problems.add("group " + group.get("name") + " has no interval");
            }
        }
        for (Map<String, Object> rule : rules(file)) {
            String uid = String.valueOf(rule.get("uid"));
            if (!UID_FORMAT.matcher(uid).matches() || !seen.add(uid)) {
                problems.add(uid + " uid is malformed or repeated");
            }
            if (rule.get("title") == null || rule.get("condition") == null) {
                problems.add(uid + " has no title or condition");
            } else if (!HANGUL.matcher(String.valueOf(rule.get("title"))).find()) {
                problems.add(uid + " title must be Korean");
            }
            if (rule.get("for") == null) {
                problems.add(uid + " has no for");
            }
            Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
            if (labels == null || labels.get("severity") == null || !SEVERITIES.contains(labels.get("severity")) || labels.get("component") == null) {
                problems.add(uid + " needs labels severity (critical|warning|info) and component");
            }
            Map<String, Object> notes = (Map<String, Object>) rule.get("annotations");
            if (notes == null || notes.get("summary") == null || notes.get("description") == null) {
                problems.add(uid + " needs annotations summary and description");
            } else if (!HANGUL.matcher(notes.get("summary") + " " + notes.get("description")).find()) {
                problems.add(uid + " annotations must be Korean");
            }
            if (rule.get("noDataState") == null || rule.get("execErrState") == null) {
                problems.add(uid + " needs noDataState and execErrState");
            }
            if (Boolean.TRUE.equals(rule.get("isPaused"))) {
                problems.add(uid + " is paused");
            }
        }
        return problems;
    }

    /** A ledger that cannot be read must not look like "0 percent used": the budget rules stay silent and a separate rule says why. */
    static List<String> ledgerProblems(Map<String, Object> file) {
        List<String> problems = new ArrayList<>();
        Map<String, Map<String, Object>> byUid = new java.util.HashMap<>();
        rules(file).forEach(r -> byUid.put(String.valueOf(r.get("uid")), r));
        for (String uid : List.of("antlab-alert-openrouter-cap", "antlab-alert-ollama-stop")) {
            Map<String, Object> rule = byUid.get(uid);
            if (rule == null) {
                problems.add(uid + " is missing");
                continue;
            }
            String expr = text(queries(rule).get(0));
            if (expr.contains("vector(0)") || expr.contains("absent") || expr.contains("or on()")) {
                problems.add(uid + " fills a missing ledger with a value");
            }
            if (!"OK".equals(rule.get("noDataState"))) {
                problems.add(uid + " must treat no data as OK");
            }
        }
        Map<String, Object> unreadable = byUid.get("antlab-alert-ledger-unreadable");
        if (unreadable == null || !text(queries(unreadable).get(0)).contains("antlab_ledger_readable")) {
            problems.add("no rule on antlab_ledger_readable");
        } else if (!text(queries(unreadable).get(0)).contains("max_over_time(antlab_ledger_readable")) {
            problems.add("ledger-unreadable fires for a ledger that was never readable (an unset path)");
        }
        return problems;
    }

    /** The collector schedule does not exist before M1-10: manual one-shot runs must not make the stale rule fire. */
    static List<String> staleProblems(Map<String, Object> file) {
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> rule : rules(file)) {
            if ("antlab-alert-collector-stale".equals(rule.get("uid"))
                    && !text(queries(rule).get(0)).contains("HAVING bool_or(run_trigger = 'SCHEDULED')")) {
                problems.add("collector-stale counts jobs that never ran on a schedule");
            }
        }
        return problems;
    }

    private static Map<String, Object> provisioned() throws IOException {
        return load(Files.readString(RULES));
    }

    private static String mutate(String from, String to) throws IOException {
        String raw = Files.readString(RULES);
        assertThat(raw).contains(from);
        return raw.replace(from, to);
    }

    @Test
    void theNineRulesAreProvisionedFromOneFile() throws IOException {
        Set<String> uids = new HashSet<>();
        rules(provisioned()).forEach(r -> uids.add((String) r.get("uid")));
        assertThat(uids).isEqualTo(EXPECTED_UIDS);
        try (Stream<Path> files = Files.list(ALERTING)) {
            assertThat(files.map(f -> f.getFileName().toString()).toList()).containsExactly("antlab-rules.yaml");
        }
    }

    @Test
    void everyRuleHasForLabelsAnnotationsAndStates() throws IOException {
        assertThat(shapeProblems(provisioned())).isEmpty();
    }

    @Test
    void nothingThatCanSendIsProvisioned() throws IOException {
        assertThat(senderProblems(provisioned(), Files.readString(RULES))).isEmpty();
    }

    @Test
    void queriesUseExistingDataSourcesMetricsJobsAndOnlyObsViews() throws IOException {
        assertThat(queryProblems(provisioned(), dataSourceUids(), allowedMetrics(), scrapeJobs())).isEmpty();
    }

    @Test
    void anUnreadableLedgerIsNotZeroPercentUsed() throws IOException {
        assertThat(ledgerProblems(provisioned())).isEmpty();
    }

    @Test
    void theStaleRuleIsQuietWhileCollectorsOnlyRunByHand() throws IOException {
        assertThat(staleProblems(provisioned())).isEmpty();
    }

    @Test
    void theReconciliationWindowIsADay() throws IOException {
        assertThat(Files.readString(RULES)).contains("antlab_reconciliation_mismatch_rules_total{job=\\\"antlab-api\\\"}[24h]");
    }

    @Test
    void anUnsetLedgerPathStaysQuiet() throws IOException {
        assertThat(Files.readString(RULES)).contains("max_over_time(antlab_ledger_readable").contains("[7d]) == 1");
    }

    @Test
    void theEngineRuleFiresWithinTheSmokeWindow() throws IOException {
        Map<String, Object> engine = rules(provisioned()).stream()
                .filter(r -> "antlab-alert-engine-down".equals(r.get("uid"))).findFirst().orElseThrow();
        assertThat(engine.get("for")).isEqualTo("1m");
        assertThat(text(queries(engine).get(0))).contains("antlab-engine").contains("up{");
        assertThat(engine.get("noDataState")).isEqualTo("Alerting");
    }

    @Test
    void deployDocumentsEveryRuleInBothLanguages() throws IOException {
        String deploy = Files.readString(Path.of("docs/DEPLOY.md"));
        String entry = deploy.substring(deploy.indexOf("OBS-10 (2026"), deploy.indexOf("OBS-10 (2026") + 6000);
        for (String uid : EXPECTED_UIDS) {
            assertThat(entry).as(uid).contains("`" + uid + "`");
            // the rule's text runs to the next rule; its Korean half comes after " / "
            int from = entry.indexOf("`" + uid + "`");
            int next = entry.indexOf("`antlab-alert-", from + 1);
            String line = entry.substring(from, next < 0 ? entry.length() : next);
            assertThat(line.contains(" / ") && HANGUL.matcher(line.substring(line.indexOf(" / "))).find()).as(uid + " has a Korean line").isTrue();
        }
    }

    @Test
    void aSenderIsCaught() throws IOException {
        String raw = mutate("groups:", "contactPoints:\n  - name: ops\ngroups:");
        assertThat(senderProblems(load(raw), raw)).anyMatch(p -> p.contains("contactPoints"));
        String viaSetting = mutate("        isPaused: false\n        labels:\n          severity: critical\n          component: api",
                "        isPaused: false\n        notification_settings:\n          receiver: ops\n        labels:\n          severity: critical\n          component: api");
        assertThat(senderProblems(load(viaSetting), viaSetting)).anyMatch(p -> p.contains("routes to a receiver"));
        String word = mutate("Prometheus가 api:8081을 2분 넘게 읽지 못할 때 울리고", "Sends a Slack message when api:8081 is down.");
        assertThat(senderProblems(load(word), word)).anyMatch(p -> p.contains("sender"));
    }

    @Test
    void aPublicTableIsCaught() throws IOException {
        String raw = mutate("FROM obs.job_run WHERE status <>", "FROM public.job_run WHERE status <>");
        assertThat(queryProblems(load(raw), dataSourceUids(), allowedMetrics(), scrapeJobs())).anyMatch(p -> p.contains("public.job_run"));
        String joined = mutate("FROM obs.job_run WHERE job_name IN", "FROM obs.job_run JOIN member m ON true WHERE job_name IN");
        assertThat(queryProblems(load(joined), dataSourceUids(), allowedMetrics(), scrapeJobs())).anyMatch(p -> p.contains("reads member"));
    }

    @Test
    void anUnknownDataSourceMetricOrJobIsCaught() throws IOException {
        String ds = mutate("datasourceUid: antlab-postgres", "datasourceUid: other-postgres");
        assertThat(queryProblems(load(ds), dataSourceUids(), allowedMetrics(), scrapeJobs())).anyMatch(p -> p.contains("other-postgres"));
        String metric = mutate("antlab_ollama_stop_usd{", "antlab_ollama_cap_usd{");
        assertThat(queryProblems(load(metric), dataSourceUids(), allowedMetrics(), scrapeJobs())).anyMatch(p -> p.contains("antlab_ollama_cap_usd"));
        String job = mutate("up{job=\\\"antlab-engine\\\"}", "up{job=\\\"antlab-engin\\\"}");
        assertThat(queryProblems(load(job), dataSourceUids(), allowedMetrics(), scrapeJobs())).anyMatch(p -> p.contains("antlab-engin"));
    }

    @Test
    void aRuleWithoutForLabelsOrAnnotationsIsCaught() throws IOException {
        String noFor = mutate("        for: 1m\n", "");
        assertThat(shapeProblems(load(noFor))).anyMatch(p -> p.contains("has no for"));
        String noSeverity = mutate("          severity: info\n", "");
        assertThat(shapeProblems(load(noSeverity))).anyMatch(p -> p.contains("labels severity"));
        String noDescription = mutate("          description: \"왜: 엔진이 꺼지면", "          other: \"왜: 엔진이 꺼지면");
        assertThat(shapeProblems(load(noDescription))).anyMatch(p -> p.contains("annotations"));
        String sameFolder = mutate("folder: ant-lab alerts", "folder: ant-lab");
        assertThat(shapeProblems(load(sameFolder))).anyMatch(p -> p.contains("folder"));
    }

    @Test
    void anUnsetPathGuardAndTheScheduleFilterAreEnforced() throws IOException {
        String noGuard = mutate(" and on(ledger) max_over_time(antlab_ledger_readable{job=\\\"antlab-ledgers\\\",ledger=~\\\"openrouter|ollama\\\"}[7d]) == 1", "");
        assertThat(ledgerProblems(load(noGuard))).anyMatch(p -> p.contains("never readable"));
        String noSchedule = mutate("HAVING bool_or(run_trigger = 'SCHEDULED')", "HAVING count(*) > 0");
        assertThat(staleProblems(load(noSchedule))).anyMatch(p -> p.contains("never ran on a schedule"));
    }

    @Test
    void aLedgerGapFilledWithZeroIsCaught() throws IOException {
        String raw = mutate("max(antlab_ollama_spend_usd{job=\\\"antlab-ledgers\\\",window=\\\"total\\\"})",
                "(max(antlab_ollama_spend_usd{job=\\\"antlab-ledgers\\\",window=\\\"total\\\"}) or vector(0))");
        assertThat(ledgerProblems(load(raw))).anyMatch(p -> p.contains("fills a missing ledger"));
        String noData = mutate("        noDataState: OK\n        execErrState: Error\n        for: 2m\n        isPaused: false\n        labels:\n          severity: warning\n          component: ledgers\n        annotations:\n          summary: \"오늘 OpenRouter",
                "        noDataState: Alerting\n        execErrState: Error\n        for: 2m\n        isPaused: false\n        labels:\n          severity: warning\n          component: ledgers\n        annotations:\n          summary: \"오늘 OpenRouter");
        assertThat(ledgerProblems(load(noData))).anyMatch(p -> p.contains("no data as OK"));
    }
}
