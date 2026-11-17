package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/** Static and dry-run checks of the runtime smoke (M1-03): usage, guards, cleanup and the shutdown time relation. No Docker, no Spring context. */
class RuntimeSmokeScriptTest {

    private static final Path SCRIPT = Path.of("scripts/runtime_smoke.sh");
    private static final Path WORKFLOW = Path.of(".github/workflows/api.yml");

    private record Run(int exit, String output) {}

    private static List<String> scriptLines() throws IOException {
        return Files.readAllLines(SCRIPT);
    }

    private static List<String> codeLines() throws IOException {
        return scriptLines().stream().filter(l -> !l.strip().startsWith("#")).toList();
    }

    private static Run run(Map<String, String> env, String pathPrefix, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().remove("ANTLAB_RUNTIME_PROJECT");
        pb.environment().remove("ANTLAB_RUNTIME_DB_PORT");
        pb.environment().remove("ANTLAB_RUNTIME_API_PORT");
        pb.environment().putAll(env);
        if (pathPrefix != null) {
            pb.environment().put("PATH", pathPrefix + ":" + System.getenv("PATH"));
        }
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }

    private static Run script(Map<String, String> env, String... args) throws Exception {
        String[] command = new String[args.length + 2];
        command[0] = "bash";
        command[1] = SCRIPT.toString();
        System.arraycopy(args, 0, command, 2, args.length);
        return run(env, null, command);
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
    void bash_문법() throws Exception {
        assertThat(run(Map.of(), null, "bash", "-n", SCRIPT.toString()).exit()).isZero();
    }

    @Test
    void 시험_실행은_docker와_psql을_부르지_않는다(@TempDir Path fake) throws Exception {
        Path marker = fake.resolve("called");
        for (String tool : List.of("docker", "psql", "curl")) {
            Path file = fake.resolve(tool);
            Files.writeString(file, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Run result = run(Map.of(), fake.toString(), "bash", SCRIPT.toString(), "graceful", "--dry-run");
        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(result.output()).contains("mode=graceful", "project=antlab-runtime", "db_port=55480", "api_port=18098",
                "image=build", "files=compose.yaml");
        Run withImage = run(Map.of(), fake.toString(), "bash", SCRIPT.toString(), "startup-fail", "--image", "antlab-api:ci", "--dry-run");
        assertThat(withImage.exit()).isZero();
        assertThat(withImage.output()).contains("mode=startup-fail", "image=antlab-api:ci");
        assertThat(marker).doesNotExist();
    }

    @Test
    void 사용법_가드는_2로_끝난다() throws Exception {
        List<List<String>> bad = List.of(
                List.of(),
                List.of("--dry-run"),
                List.of("unknown", "--dry-run"),
                List.of("graceful", "startup-fail", "--dry-run"),
                List.of("graceful", "--bogus"),
                List.of("startup-fail", "--negative", "--dry-run"),
                List.of("graceful", "--image"),
                List.of("graceful", "--image", "bad ref;rm", "--dry-run"));
        for (List<String> args : bad) {
            Run result = script(Map.of(), args.toArray(String[]::new));
            assertThat(result.exit()).as(String.join(" ", args)).isEqualTo(2);
        }
        Run help = script(Map.of(), "--help");
        assertThat(help.exit()).isZero();
        assertThat(help.output()).contains("Usage:", "startup-fail", "graceful");
        assertThat(script(Map.of(), "graceful", "--negative", "--dry-run").exit()).isZero();
    }

    @Test
    void 프로젝트_이름_가드() throws Exception {
        for (String bad : List.of("antlab", "antlab-qa", "antlab-smoke", "Antlab-runtime", "antlab-runtime-", "antlab-runtime-a_b")) {
            Run result = script(Map.of("ANTLAB_RUNTIME_PROJECT", bad), "graceful", "--dry-run");
            assertThat(result.exit()).as(bad).isEqualTo(2);
            assertThat(result.output()).contains("ANTLAB_RUNTIME_PROJECT");
        }
        assertThat(script(Map.of("ANTLAB_RUNTIME_PROJECT", "antlab-runtime-ci2"), "graceful", "--dry-run").exit()).isZero();
        Matcher dflt = Pattern.compile("project=\"\\$\\{ANTLAB_RUNTIME_PROJECT:-([^}]*)\\}\"").matcher(String.join("\n", scriptLines()));
        assertThat(dflt.find()).isTrue();
        assertThat(dflt.group(1)).matches("^antlab-runtime(-[a-z0-9]+)?$");
    }

    @Test
    void 포트_가드() throws Exception {
        for (Map.Entry<String, String> owner : Map.of("ANTLAB_RUNTIME_DB_PORT", "5432", "ANTLAB_RUNTIME_API_PORT", "8080").entrySet()) {
            Run result = script(Map.of(owner.getKey(), owner.getValue()), "graceful", "--dry-run");
            assertThat(result.exit()).as(owner.getKey()).isEqualTo(2);
            assertThat(result.output()).contains(owner.getKey());
        }
        for (String zero : List.of("05432", "005432", "08080", "0")) {
            assertThat(script(Map.of("ANTLAB_RUNTIME_DB_PORT", zero), "graceful", "--dry-run").exit()).as(zero).isEqualTo(2);
            assertThat(script(Map.of("ANTLAB_RUNTIME_API_PORT", zero), "graceful", "--dry-run").exit()).as(zero).isEqualTo(2);
        }
        assertThat(script(Map.of("ANTLAB_RUNTIME_API_PORT", "not-a-port"), "graceful", "--dry-run").exit()).isEqualTo(2);
        assertThat(script(Map.of("ANTLAB_RUNTIME_DB_PORT", "80"), "graceful", "--dry-run").exit()).isEqualTo(2);
        assertThat(script(Map.of("ANTLAB_RUNTIME_DB_PORT", "20000", "ANTLAB_RUNTIME_API_PORT", "20000"), "graceful", "--dry-run").exit())
                .isEqualTo(2);
        Matcher db = Pattern.compile("db_port=\"\\$\\{ANTLAB_RUNTIME_DB_PORT:-(\\d+)\\}\"").matcher(String.join("\n", scriptLines()));
        Matcher api = Pattern.compile("api_port=\"\\$\\{ANTLAB_RUNTIME_API_PORT:-(\\d+)\\}\"").matcher(String.join("\n", scriptLines()));
        assertThat(db.find() && api.find()).isTrue();
        assertThat(db.group(1)).isNotEqualTo("5432");
        assertThat(api.group(1)).isNotEqualTo("8080");
    }

    @Test
    void compose_진입점은_하나다() throws IOException {
        List<String> entries = codeLines().stream().filter(l -> l.contains("docker compose")).toList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).contains("-p \"$project\"", "--env-file", "-f compose.yaml")
                .doesNotContain("-f compose.yaml -f");
        assertThat(codeLines()).filteredOn(l -> l.contains("docker-compose")).isEmpty();
    }

    @Test
    void 정리는_항상_이_프로젝트만_지운다() throws IOException {
        List<String> code = codeLines();
        assertThat(code).anyMatch(l -> l.strip().equals("trap cleanup EXIT"));
        assertThat(code).anyMatch(l -> l.strip().equals("trap 'exit 130' INT TERM"));
        assertThat(code).anyMatch(l -> l.strip().startsWith("dc down -v --remove-orphans --rmi local"));
        for (String line : code) {
            if (line.contains("down -v")) {
                assertThat(line.strip()).as(line).matches("^(dc down -v .*)$");
            }
        }
        // The project-name guard runs before the first docker call, so dc (and its -p) can only ever see a guarded name.
        int guard = indexOf(code, l -> l.contains("[[ \"$project\" =~ ^antlab-runtime"));
        int firstDocker = indexOf(code, l -> l.matches("^\\s*(docker|dc)\\s.*"));
        assertThat(guard).isLessThan(firstDocker);
        assertThat(String.join("\n", code)).doesNotContain("docker volume rm").doesNotContain("system prune")
                .doesNotContain("docker network rm").doesNotContain("--all");
        assertThat(code).filteredOn(l -> l.contains("rm -rf")).allMatch(l -> l.contains("rm -rf \"$work\""));
        assertThat(code).filteredOn(l -> l.contains("docker rm")).allMatch(l -> l.contains("docker rm -f \"$project-startfail\""));
        assertThat(code).filteredOn(l -> l.contains("docker run")).hasSize(1)
                .allMatch(l -> l.contains("--name \"$project-startfail\""));
        assertThat(code).filteredOn(l -> l.contains("docker stop --timeout")).hasSize(1)
                .allMatch(l -> l.contains("\"$project-api-1\""));
        assertThat(code).filteredOn(l -> l.contains("docker tag")).hasSize(1).allMatch(l -> l.contains("\"$project-api\""));
    }

    @Test
    void 서명_키는_임의로_만들어_임시_env_파일에만_쓴다() throws IOException {
        List<String> code = codeLines();
        // The api container (prod guard on, SEC-03) refuses to start without a Base64 key of at least 32 bytes.
        assertThat(code).anyMatch(l -> l.contains("jwt_key=") && l.contains("secrets.token_bytes(32)") && l.contains("b64encode"));
        assertThat(code).filteredOn(l -> l.contains("ANTLAB_JWT_SECRET") && l.contains("printf"))
                .hasSize(1).allMatch(l -> l.contains("$jwt_key") && l.contains(">> \"$env_file\""));
        // The key reaches the throwaway container for the failing start and is checked against its log; it is never printed.
        assertThat(code).filteredOn(l -> l.contains("$jwt_key") && !l.contains("ANTLAB_JWT_SECRET=%s"))
                .allMatch(l -> l.contains("-e ANTLAB_JWT_SECRET=\"$jwt_key\"") || l.contains("*\"$jwt_key\"*") || l.startsWith("jwt_key="));
        assertThat(code).noneMatch(l -> l.matches(".*\\b(echo|printf)\\b.*\\$\\{?jwt_key\\b.*") && !l.contains(">> \"$env_file\"")
                && !l.contains("> \"$env_file\""));
        assertThat(code).anyMatch(l -> l.strip().equals("umask 077"));
        assertThat(code).noneMatch(l -> l.matches(".*(JWT_SECRET|jwt_key)=['\"]?[A-Za-z0-9+/]{16,}.*"));
        assertThat(Files.readString(Path.of("compose.yaml"))).contains("ANTLAB_JWT_SECRET: ${ANTLAB_JWT_SECRET:-}");
    }

    @Test
    void 기동_실패_시험은_db_주소_없이_prod로_뜬다() throws IOException {
        String dockerRun = codeLines().stream().filter(l -> l.contains("docker run")).findFirst().orElseThrow();
        assertThat(dockerRun).contains("-e SPRING_PROFILES_ACTIVE=prod").doesNotContain("ANTLAB_DB_URL");
        String text = String.join("\n", codeLines());
        assertThat(text).contains("-lt 60").contains("[ \"$rc\" = 1 ]").contains("Application run failed")
                .contains("'url' must start with \\\"jdbc\\\"");
    }

    @Test
    void api_log_texts_are_read_from_json_fields_and_never_grepped_raw() throws IOException {
        List<String> code = codeLines();
        // Inside a JSON line the quotes of the Hikari message are escaped, so a raw grep for them cannot match.
        assertThat(code).noneMatch(l -> l.contains("grep") && l.contains("must start with"));
        assertThat(code).noneMatch(l -> l.contains("grep") && l.contains("Application run failed"));
        assertThat(code).filteredOn(l -> l.contains("json_lines_only \"$work/")).hasSize(2);
        assertThat(code).anyMatch(l -> l.contains("json_lines_only \"$work/startfail.log\""));
        assertThat(code).anyMatch(l -> l.contains("json_lines_only \"$work/api.log\""));
        assertThat(code).anyMatch(l -> l.contains("dc logs --no-log-prefix api"));
        // Raw text checks that stay valid on JSON: no fallback to localhost, no signing key.
        assertThat(String.join("\n", code)).contains("localhost:5432|Connection to .* refused", "*\"$jwt_key\"*");
    }

    /** The two inline python helpers are run against crafted logs: JSON escaping, plain lines, flat and nested fields. */
    @Test
    void the_json_log_helpers_accept_json_and_reject_plain_lines(@TempDir Path dir) throws Exception {
        String source = String.join("\n", scriptLines());
        String onlyPy = helperSource(source, "json_lines_only");
        String textPy = helperSource(source, "json_log_text");

        Path good = dir.resolve("good.log");
        Files.writeString(good, "{\"message\":\"a \\\"quoted\\\" word\",\"log\":{\"level\":\"INFO\"}}\n\n"
                + "{\"message\":\"boom\",\"error.message\":\"flat \\\"x\\\"\",\"error\":{\"stack_trace\":\"trace line\"}}\n");
        assertThat(python(onlyPy, good).exit()).as("every line is a JSON object").isZero();
        Run text = python(textPy, good);
        assertThat(text.exit()).isZero();
        assertThat(text.output()).contains("a \"quoted\" word", "flat \"x\"", "trace line", "boom");

        Path plain = dir.resolve("plain.log");
        Files.writeString(plain, "{\"message\":\"ok\"}\n2026-11-17 12:00:00 INFO plain text line\n");
        Run rejected = python(onlyPy, plain);
        assertThat(rejected.exit()).isEqualTo(1);
        assertThat(rejected.output()).contains("line 2 is not a JSON object");
        Path array = dir.resolve("array.log");
        Files.writeString(array, "[1, 2]\n");
        assertThat(python(onlyPy, array).exit()).as("a JSON array is not a log event").isEqualTo(1);
        // The text helper skips plain lines instead of failing; the strict check is json_lines_only.
        assertThat(python(textPy, plain).output()).contains("ok").doesNotContain("plain text line");
    }

    private static String helperSource(String script, String function) {
        Matcher m = Pattern.compile(function + "\\(\\) \\{\\s*python3 -c '(.*?)' \"\\$1\"", Pattern.DOTALL).matcher(script);
        assertThat(m.find()).as("function " + function).isTrue();
        return m.group(1);
    }

    private static Run python(String code, Path file) throws Exception {
        Process process = new ProcessBuilder("python3", "-c", code, file.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }

    @Test
    void 정상_종료_시험은_진행_중_요청을_기다리고_단언을_모두_가진다() throws IOException {
        List<String> code = codeLines();
        int lock = indexOf(code, l -> l.contains("LOCK TABLE member IN EXCLUSIVE MODE"));
        int waiting = indexOf(code, l -> l.contains("wait_event_type = 'Lock'"));
        int stop = indexOf(code, l -> l.contains("docker stop --timeout"));
        assertThat(lock).isLessThan(waiting);
        assertThat(waiting).as("the stop comes only after a backend waits on the lock").isLessThan(stop);
        String text = String.join("\n", code);
        assertThat(text).contains("\"$status\" = 201", "\"$exit_code\" = 143", "\"$exit_code\" != 137", "-lt \"$grace\"",
                "Graceful shutdown complete", "Graceful shutdown aborted", "\"$refused\" = 1");
        // stop_grace_period is read from compose.yaml, not copied into the script.
        assertThat(text).contains("stop_grace_period");
        // The positive run must also fail on the abort line, and the failing start must not fall back to a local database.
        assertThat(text).contains("fail \"the graceful phase was aborted with requests still active\"")
                .contains("fail \"the app fell back to a local database\"");
        assertThat(code).noneMatch(l -> l.matches(".*grace=\\d+.*"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 컨테이너_종료_대기는_스프링_단계_시간보다_길다() throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> compose = yaml.load(Files.readString(Path.of("compose.yaml")));
        Map<String, Object> api = ((Map<String, Map<String, Object>>) compose.get("services")).get("api");
        assertThat(api.get("stop_grace_period")).isEqualTo("30s");
        assertThat(((Map<String, Object>) api.get("environment")).get("SPRING_PROFILES_ACTIVE")).isEqualTo("prod");
        Map<String, Object> app = null;
        for (Object doc : yaml.loadAll(Files.readString(Path.of("src/main/resources/application.yml")))) {
            Map<String, Object> candidate = (Map<String, Object>) doc;
            if (candidate.containsKey("spring") && ((Map<String, Object>) candidate.get("spring")).containsKey("lifecycle")) {
                app = candidate;
            }
        }
        assertThat(app).isNotNull();
        String phase = (String) ((Map<String, Object>) ((Map<String, Object>) app.get("spring")).get("lifecycle")).get("timeout-per-shutdown-phase");
        assertThat(phase).isEqualTo("20s");
        assertThat(seconds(phase)).isLessThan(seconds((String) api.get("stop_grace_period")));
        assertThat(((Map<String, Object>) app.get("server")).get("shutdown")).isEqualTo("graceful");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 워크플로_smoke_잡은_image_뒤에_같은_명령을_돌린다() throws IOException {
        Map<String, Object> workflow = new Yaml().load(Files.readString(WORKFLOW));
        Map<String, Map<String, Object>> jobs = (Map<String, Map<String, Object>>) workflow.get("jobs");
        Map<String, Object> smoke = jobs.get("smoke");
        assertThat(smoke).isNotNull();
        assertThat(smoke.get("needs")).isEqualTo("image");
        String text = Files.readString(WORKFLOW);
        assertThat(text).contains("scripts/runtime_smoke.sh startup-fail --image antlab-api:ci",
                "scripts/runtime_smoke.sh graceful --image antlab-api:ci");
        // Every action is pinned to a commit SHA; the image travels as a one-day artifact; no secret is referenced.
        assertThat(text).doesNotContain("${{ secrets").doesNotContain("pull_request_target");
        for (String line : text.lines().filter(l -> l.strip().startsWith("- uses:") || l.strip().startsWith("uses:")).toList()) {
            assertThat(line).as(line).containsPattern("@[0-9a-f]{40}\\b");
        }
        assertThat(text).contains("name: api-image").contains("retention-days: 1");
    }

    private static int seconds(String value) {
        assertThat(value).matches("\\d+s");
        return Integer.parseInt(value.substring(0, value.length() - 1));
    }

    private static int indexOf(List<String> lines, java.util.function.Predicate<String> match) {
        for (int i = 0; i < lines.size(); i++) {
            if (match.test(lines.get(i))) {
                return i;
            }
        }
        throw new AssertionError("no matching line in the runtime smoke script");
    }
}
