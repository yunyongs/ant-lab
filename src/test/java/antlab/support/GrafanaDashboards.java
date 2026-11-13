package antlab.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the provisioned Grafana dashboards and the metric-name allowlist (MON-04); shared by the static and the live test. */
public final class GrafanaDashboards {

    public static final String PROVISIONING = "monitoring/grafana/provisioning";
    public static final Path DASHBOARD_DIR = Path.of(PROVISIONING, "dashboards", "antlab");
    public static final Path ALLOWLIST = Path.of("src/test/resources/monitoring/metric-names.txt");

    private GrafanaDashboards() {}

    public static JsonNode parse(String json) {
        return JsonMapper.shared().readTree(json);
    }

    /** Dashboards by file name. */
    public static Map<String, JsonNode> load() throws IOException {
        return load(DASHBOARD_DIR);
    }

    /** Only {@code *.json} files are read, as Grafana does; other files (for example {@code .DS_Store}) are ignored. */
    public static Map<String, JsonNode> load(Path dir) throws IOException {
        Map<String, JsonNode> byFile = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                byFile.put(file.getFileName().toString(), parse(Files.readString(file)));
            }
        }
        return byFile;
    }

    /** Allowlist: metric name to "lazy" flag (names that a fresh JVM exports only after an event). */
    public static Map<String, Boolean> allowlist() throws IOException {
        Map<String, Boolean> names = new TreeMap<>();
        for (String raw : Files.readAllLines(ALLOWLIST)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int hash = line.indexOf('#');
            String name = (hash < 0 ? line : line.substring(0, hash)).strip();
            boolean lazy = hash >= 0 && line.substring(hash).startsWith("# lazy:");
            names.put(name, lazy);
        }
        return names;
    }

    public static List<JsonNode> panels(JsonNode dashboard) {
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            all.add(panel);
            for (JsonNode child : panel.path("panels")) {
                all.add(child);
            }
        }
        return all;
    }

    public static List<String> expressions(JsonNode dashboard) {
        List<String> exprs = new ArrayList<>();
        for (JsonNode panel : panels(dashboard)) {
            for (JsonNode target : panel.path("targets")) {
                exprs.add(target.path("expr").asString(""));
            }
        }
        return exprs;
    }

    public static String variableQuery(JsonNode variable) {
        JsonNode query = variable.path("query");
        return query.isString() ? query.asString() : query.path("query").asString("");
    }

    /** Every metric name a dashboard uses: selectors in panel queries plus the metric of query variables. */
    public static Set<String> metricNames(JsonNode dashboard) {
        Set<String> names = new LinkedHashSet<>();
        expressions(dashboard).forEach(e -> names.addAll(PromQlNames.names(e)));
        for (JsonNode variable : dashboard.path("templating").path("list")) {
            String metric = PromQlNames.variableMetric(variableQuery(variable));
            if (!metric.isEmpty()) {
                names.add(metric);
            }
        }
        return names;
    }
}
