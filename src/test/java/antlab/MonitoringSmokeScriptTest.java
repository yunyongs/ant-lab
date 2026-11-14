package antlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import antlab.support.GrafanaDashboards;
import antlab.support.PromQlNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/** Static and dry-run checks of the monitoring smoke (MON-05): script, helper and compose override. No Docker, no Spring context. */
class MonitoringSmokeScriptTest {

    private static final Path SCRIPT = Path.of("scripts/monitoring_smoke.sh");
    private static final Path HELPER = Path.of("scripts/monitoring_smoke_checks.py");
    private static final Path OVERRIDE = Path.of("compose.play.yaml");
    private static final Pattern SECRET_WORDS = Pattern.compile(
            "GF_SECURITY_ADMIN_PASSWORD|ANTLAB_JWT_SECRET|\\$\\{?gf_password|\\$\\{?jwt_key|SMOKE_GF_PASSWORD");

    private record Run(int exit, String output) {}

    private static List<String> scriptLines() throws IOException {
        return Files.readAllLines(SCRIPT);
    }

    private static List<String> codeLines() throws IOException {
        return scriptLines().stream().filter(l -> !l.strip().startsWith("#")).toList();
    }

    private static Run run(Map<String, String> env, String pathPrefix, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().remove("ANTLAB_SMOKE_PROJECT");
        pb.environment().remove("GF_SECURITY_ADMIN_PASSWORD");
        pb.environment().putAll(env);
        if (pathPrefix != null) {
            pb.environment().put("PATH", pathPrefix + ":" + System.getenv("PATH"));
        }
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }

    private static Run dryRun(Map<String, String> env) throws Exception {
        return run(env, null, "bash", SCRIPT.toString(), "--dry-run");
    }

    @Test
    void 스크립트_모양() throws IOException {
        assertThat(Files.isExecutable(SCRIPT)).isTrue();
        List<String> lines = scriptLines();
        assertThat(lines.get(0)).isEqualTo("#!/usr/bin/env bash");
        assertThat(lines).contains("set -euo pipefail");
        assertThat(lines).noneMatch(l -> l.strip().matches("set\\s+-\\w*x\\w*.*"));
    }

    @Test
    void 거절_주문_탐침은_토큰을_붙이고_토큰을_출력하지_않는다() throws IOException {
        List<String> lines = codeLines();
        int probe = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("/api/v1/seasons/999999999/orders")) {
                probe = i;
            }
        }
        assertThat(probe).as("reject probe present").isPositive();
        // The curl command may continue over several lines; look at the continuation chain ending at the probe URL.
        int start = probe;
        while (start > 0 && lines.get(start - 1).stripTrailing().endsWith("\\")) {
            start--;
        }
        String command = String.join("\n", lines.subList(start, probe + 1));
        assertThat(command).contains("Authorization: Bearer $token");
        assertThat(lines).noneMatch(l -> l.matches(".*\\b(echo|printf)\\b.*\\$\\{?token\\b.*"));
    }

    @Test
    void bash_문법() throws Exception {
        assertThat(run(Map.of(), null, "bash", "-n", SCRIPT.toString()).exit()).isZero();
    }

    @Test
    void 시험_실행은_docker와_psql을_부르지_않는다(@TempDir Path fake) throws Exception {
        Path marker = fake.resolve("called");
        for (String tool : List.of("docker", "psql")) {
            Path file = fake.resolve(tool);
            Files.writeString(file, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Run result = run(Map.of(), fake.toString(), "bash", SCRIPT.toString(), "--dry-run");
        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("project=antlab-smoke", "db_port=55472", "api_port=18094",
                "prometheus_port=19104", "grafana_port=13014", "files=compose.yaml,compose.play.yaml");
        assertThat(marker).doesNotExist();
    }

    @Test
    void 프로젝트_이름_가드() throws Exception {
        for (String bad : List.of("antlab", "antlab-qa", "Antlab-smoke", "antlab-smoke-")) {
            Run result = dryRun(Map.of("ANTLAB_SMOKE_PROJECT", bad));
            assertThat(result.exit()).as(bad).isEqualTo(2);
            assertThat(result.output()).contains("ANTLAB_SMOKE_PROJECT");
        }
        assertThat(dryRun(Map.of("ANTLAB_SMOKE_PROJECT", "antlab-smoke-2")).exit()).isZero();
        Matcher dflt = Pattern.compile("project=\"\\$\\{ANTLAB_SMOKE_PROJECT:-([^}]*)\\}\"").matcher(String.join("\n", scriptLines()));
        assertThat(dflt.find()).isTrue();
        assertThat(dflt.group(1)).matches("^antlab-smoke(-[a-z0-9]+)?$");
    }

    @Test
    void 포트_가드() throws Exception {
        Map<String, String> owner = Map.of("ANTLAB_SMOKE_DB_PORT", "5432", "ANTLAB_SMOKE_API_PORT", "8080",
                "ANTLAB_SMOKE_PROMETHEUS_PORT", "9090", "ANTLAB_SMOKE_GRAFANA_PORT", "3000");
        owner.forEach((name, port) -> {
            try {
                Run result = dryRun(Map.of(name, port));
                assertThat(result.exit()).as(name).isEqualTo(2);
                assertThat(result.output()).contains(name);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(dryRun(Map.of("ANTLAB_SMOKE_API_PORT", "not-a-port")).exit()).isEqualTo(2);
    }

    @Test
    void compose_진입점은_하나다() throws IOException {
        List<String> entries = codeLines().stream().filter(l -> l.contains("docker compose")).toList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).contains("-p \"$project\"", "--env-file", "-f compose.yaml -f compose.play.yaml");
    }

    @Test
    void 정리는_항상_이_프로젝트만_지운다() throws IOException {
        List<String> code = codeLines();
        assertThat(code).anyMatch(l -> l.strip().equals("trap cleanup EXIT"));
        assertThat(code).anyMatch(l -> l.strip().startsWith("dc down -v --remove-orphans --rmi local"));
        for (String line : code) {
            if (line.contains("down -v")) {
                assertThat(line.strip()).as(line).matches("^(dc down -v .*|printf .*)$");
            }
        }
        assertThat(String.join("\n", code)).doesNotContain("docker volume rm").doesNotContain("system prune");
        assertThat(code).filteredOn(l -> l.contains("rm -rf")).allMatch(l -> l.contains("rm -rf \"$work\""));
    }

    @Test
    void 비밀값은_출력하거나_하드코딩하지_않는다() throws IOException {
        for (String line : codeLines()) {
            if (SECRET_WORDS.matcher(line).find() && (line.contains("echo") || line.contains("printf"))) {
                assertThat(line).as(line).containsPattern(">>? \"\\$env_file\"");
            }
            assertThat(line).as(line).doesNotContainPattern("(gf_password|jwt_key|GF_SECURITY_ADMIN_PASSWORD|ANTLAB_JWT_SECRET)=['\"]?[A-Za-z0-9+/]{8,}");
        }
        for (String line : Files.readAllLines(HELPER)) {
            if (line.toLowerCase().contains("password") || line.contains("SMOKE_GF")) {
                assertThat(line).as(line).doesNotContain("print(").doesNotContain("report(");
            }
        }
    }

    @Test
    void 서명_키는_임의로_만들어_임시_env_파일에만_쓴다() throws IOException {
        List<String> code = codeLines();
        // The api container (prod guard on, SEC-03) refuses to start without a Base64 key of at least 32 bytes.
        assertThat(code).anyMatch(l -> l.contains("jwt_key=") && l.contains("secrets.token_bytes(32)") && l.contains("b64encode"));
        assertThat(code).filteredOn(l -> l.contains("ANTLAB_JWT_SECRET")).hasSize(1)
                .allMatch(l -> l.contains("$jwt_key") && l.contains(">> \"$env_file\""));
        assertThat(code).filteredOn(l -> l.contains("$jwt_key")).hasSize(1);
        assertThat(code).anyMatch(l -> l.strip().equals("umask 077"));
        assertThat(code).anyMatch(l -> l.contains("rm -rf \"$work\""));
        assertThat(Files.readString(Path.of("compose.yaml"))).contains("ANTLAB_JWT_SECRET: ${ANTLAB_JWT_SECRET:-}");
        assertThat(Files.readString(OVERRIDE)).doesNotContain("JWT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 덮어쓰기_파일은_프로필만_바꾼다() throws IOException {
        Yaml yaml = new Yaml();
        Object override = yaml.load(Files.readString(OVERRIDE));
        assertThat(override).isEqualTo(Map.of("services", Map.of("api", Map.of("environment", Map.of("SPRING_PROFILES_ACTIVE", "default")))));
        Map<String, Object> base = yaml.load(Files.readString(Path.of("compose.yaml")));
        Map<String, Object> api = ((Map<String, Map<String, Object>>) base.get("services")).get("api");
        assertThat(((Map<String, Object>) api.get("environment")).get("SPRING_PROFILES_ACTIVE")).isEqualTo("prod");
    }

    private static String helperSource() throws IOException {
        return Files.readString(HELPER);
    }

    @Test
    void 대시보드_uid가_도우미와_같다() throws IOException {
        Matcher m = Pattern.compile("UIDS = \\(([^)]*)\\)").matcher(helperSource());
        assertThat(m.find()).isTrue();
        Set<String> helperUids = new TreeSet<>();
        Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (q.find()) {
            helperUids.add(q.group(1));
        }
        Set<String> repoUids = new TreeSet<>();
        for (JsonNode dashboard : GrafanaDashboards.load().values()) {
            repoUids.add(dashboard.path("uid").asString());
        }
        assertThat(helperUids).isEqualTo(repoUids);
    }

    @Test
    void 도우미_쿼리의_지표_이름은_허용_목록에_있다() throws IOException {
        String source = helperSource();
        String block = source.substring(source.indexOf("# BEGIN SMOKE QUERIES"), source.indexOf("# END SMOKE QUERIES"));
        List<String> queries = new ArrayList<>();
        Matcher m = Pattern.compile("^\\w+ = '(.*)'$", Pattern.MULTILINE).matcher(block);
        while (m.find()) {
            queries.add(m.group(1));
        }
        assertThat(queries).hasSizeGreaterThanOrEqualTo(4);
        Set<String> allowed = new TreeSet<>(GrafanaDashboards.allowlist().keySet());
        allowed.add("up");
        for (String query : queries) {
            assertThat(allowed).as(query).containsAll(PromQlNames.names(query));
            assertThat(PromQlNames.bareIdentifiers(query)).as(query).isEmpty();
        }
    }

    @Test
    void 도우미_자체_시험은_통과한다() throws Exception {
        assumeTrue(run(Map.of(), null, "python3", "--version").exit() == 0);
        Run result = run(Map.of(), null, "python3", HELPER.toString(), "self-test", GrafanaDashboards.DASHBOARD_DIR.toString());
        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).doesNotContain("FAIL");
    }
}
