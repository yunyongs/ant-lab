package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Static guard of .github/workflows/api.yml and its skip-count script. No Docker, no Spring context (web CI has the same kind of test). */
class ApiWorkflowTest {

    private static final Path WORKFLOW = Path.of(".github/workflows/api.yml");
    private static final Path SUMMARY_SCRIPT = Path.of("scripts/ci_test_summary.py");
    private static final Path DB_README = Path.of("db/README.md");
    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Pattern PINNED_USES =
            Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*[\\w.-]+/[\\w./-]+@[0-9a-f]{40}\\s+#\\s*v\\d+\\.\\d+\\.\\d+$");
    private static final Path EXPECTED_SKIPS = Path.of("scripts/ci_expected_skips.txt");
    private static final String GUARD_COMMAND = "python3 -I scripts/ci_test_summary.py build/test-results/test "
            + "--expected-skips scripts/ci_expected_skips.txt --summary-file \"$GITHUB_STEP_SUMMARY\"";
    private static final Pattern SQL_FILE = Pattern.compile("db/0[123]_\\w+\\.sql");
    private static final Pattern README_PSQL_LINE =
            Pattern.compile("^psql -X (?:-q )?-v ON_ERROR_STOP=1 -d antlab_test -f (db/\\S+\\.sql)$");

    private static String text() throws IOException {
        return Files.readString(WORKFLOW);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workflow() throws IOException {
        return (Map<String, Object>) new Yaml().load(text());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(String job) throws IOException {
        return (List<Map<String, Object>>) map(map(workflow().get("jobs")).get(job)).get("steps");
    }

    /** YAML 1.1 reads the key {@code on} as boolean true. */
    private static Map<String, Object> triggers() throws IOException {
        Map<String, Object> workflow = workflow();
        Object on = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
        return map(on);
    }

    private static List<String> usesLines(String text) {
        return text.lines().filter(l -> l.stripLeading().matches("^(-\\s*)?uses:.*")).toList();
    }

    private static List<String> runCommands(String job) throws IOException {
        List<String> commands = new ArrayList<>();
        for (Map<String, Object> step : steps(job)) {
            if (step.get("run") instanceof String run) {
                commands.add(run.strip());
            }
        }
        return commands;
    }

    @Test
    void runsOnPullRequestAndMainForEverythingExceptWeb() throws IOException {
        Map<String, Object> on = triggers();
        assertThat(on).containsKeys("pull_request", "push", "workflow_dispatch");
        Map<String, Object> pr = map(on.get("pull_request"));
        assertThat(pr.get("types")).isEqualTo(List.of("opened", "synchronize", "reopened"));
        assertThat(pr.get("paths-ignore")).isEqualTo(List.of("web/**"));
        assertThat(pr).doesNotContainKey("paths");
        Map<String, Object> push = map(on.get("push"));
        assertThat(push.get("branches")).isEqualTo(List.of("main"));
        assertThat(push.get("paths-ignore")).isEqualTo(List.of("web/**"));
        assertThat(push).doesNotContainKey("paths");
    }

    @Test
    void neverUsesPullRequestTargetOrWorkflowRun() throws IOException {
        assertThat(text()).doesNotContain("pull_request_target").doesNotContain("workflow_run");
    }

    @Test
    void grantsContentsReadAndNothingElse() throws IOException {
        Map<String, Object> workflow = workflow();
        assertThat(workflow.get("permissions")).isEqualTo(Map.of("contents", "read"));
        for (Object job : map(workflow.get("jobs")).values()) {
            assertThat(map(job)).doesNotContainKey("permissions");
        }
        assertThat(text()).doesNotContainPattern("(?m)^\\s*[\\w-]+:\\s*write(-all)?\\s*$");
    }

    @Test
    void usesNoSecretsAndNoUntrustedEventText() throws IOException {
        String text = text();
        assertThat(text).doesNotContain("secrets.").doesNotContain("github.event").doesNotContain("head_ref");
        assertThat(text).doesNotContain("ANTLAB_GOLDEN_SAMPLE_DIR");
    }

    @Test
    void pinsEveryActionToACommitShaWithAVersionComment() throws IOException {
        List<String> lines = usesLines(text());
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            assertThat(PINNED_USES.matcher(line).matches()).as(line).isTrue();
        }
        Set<String> names = new TreeSet<>();
        for (String line : lines) {
            Matcher m = Pattern.compile("uses:\\s*(\\S+?)@").matcher(line);
            assertThat(m.find()).isTrue();
            names.add(m.group(1));
        }
        assertThat(names).containsExactly(
                "actions/checkout", "actions/download-artifact", "actions/setup-java", "actions/upload-artifact",
                "docker/build-push-action", "docker/setup-buildx-action", "gradle/actions/setup-gradle");
    }

    @Test
    void checkoutsDoNotPersistCredentials() throws IOException {
        for (String job : List.of("test", "sql-schema", "image", "smoke")) {
            for (Map<String, Object> step : steps(job)) {
                if (String.valueOf(step.get("uses")).startsWith("actions/checkout@")) {
                    assertThat(map(step.get("with")).get("persist-credentials")).isEqualTo(false);
                }
            }
        }
    }

    @Test
    void cancelsSupersededRunsOfTheSameRef() throws IOException {
        Map<String, Object> concurrency = map(workflow().get("concurrency"));
        assertThat(concurrency.get("group")).isEqualTo("api-${{ github.ref }}");
        assertThat(concurrency.get("cancel-in-progress")).isEqualTo(true);
    }

    private static Map<String, Object> step(String job, String name) throws IOException {
        return steps(job).stream().filter(s -> name.equals(s.get("name"))).findFirst().orElseThrow();
    }

    /** The executable lines of a run block: comments and blank lines are dropped, so a commented-out call cannot count. */
    private static List<String> codeLines(String run) {
        return run.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    private static List<String> sqlFilesInOrder(List<String> lines) {
        List<String> files = new ArrayList<>();
        for (String line : lines) {
            Matcher m = SQL_FILE.matcher(line);
            while (m.find()) {
                files.add(m.group());
            }
        }
        return files;
    }

    @Test
    void testJobRunsThePlainLocalCommandThenTheSkipSetGuard() throws IOException {
        Map<String, Object> job = map(map(workflow().get("jobs")).get("test"));
        assertThat(job.get("runs-on")).isEqualTo("ubuntu-24.04-arm");
        assertThat((Integer) job.get("timeout-minutes")).isBetween(1, 30);
        assertThat(text()).doesNotContain("continue-on-error").doesNotContain("--expect-skipped");

        List<String> commands = runCommands("test");
        assertThat(commands).containsOnlyOnce("./gradlew test");
        assertThat(commands).noneMatch(c -> c.contains("gradlew") && !c.equals("./gradlew test"));
        List<String> guards = commands.stream().filter(c -> c.contains("ci_test_summary")).toList();
        assertThat(guards).hasSize(1);
        assertThat(commands.indexOf(guards.get(0))).isGreaterThan(commands.indexOf("./gradlew test"));
        assertThat(guards.get(0)).isEqualTo(GUARD_COMMAND);

        Map<String, Object> guardStep = step("test", "Skip-set guard and summary");
        assertThat(guardStep.get("if")).isEqualTo("${{ !cancelled() }}");
        assertThat(guardStep).doesNotContainKey("continue-on-error");
        assertThat(step("test", "Tests")).doesNotContainKey("continue-on-error").doesNotContainKey("env");
    }

    @Test
    void expectedSkipsFileIsWellFormedAndNonEmpty() throws IOException {
        Set<String> seen = new TreeSet<>();
        for (String line : Files.readAllLines(EXPECTED_SKIPS, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            assertThat(line.split("\t", -1)).as(line).hasSize(2).allMatch(part -> !part.isBlank());
            assertThat(seen.add(line)).as("duplicate " + line).isTrue();
        }
        assertThat(seen).isNotEmpty();
    }

    @Test
    void gradleCacheIsReadOnlyExceptOnMain() throws IOException {
        Map<String, Object> setup = steps("test").stream()
                .filter(s -> String.valueOf(s.get("uses")).startsWith("gradle/actions/setup-gradle@"))
                .findFirst().orElseThrow();
        assertThat(map(setup.get("with")).get("cache-read-only")).isEqualTo("${{ github.ref != 'refs/heads/main' }}");
    }

    @Test
    void uploadsOnlyTheReportAndXmlFilesAndOnlyOnFailure() throws IOException {
        List<Map<String, Object>> uploads = steps("test").stream()
                .filter(s -> String.valueOf(s.get("uses")).startsWith("actions/upload-artifact@")).toList();
        assertThat(uploads).hasSize(1);
        Map<String, Object> upload = uploads.get(0);
        assertThat(upload.get("if")).isEqualTo("${{ failure() }}");
        Map<String, Object> with = map(upload.get("with"));
        assertThat(with.get("retention-days")).isEqualTo(7);
        assertThat(String.valueOf(with.get("path")).lines().map(String::strip).toList())
                .containsExactly("build/reports/tests/test", "build/test-results/test/*.xml");
    }

    @Test
    void sqlSchemaJobRunsTheSameFilesAsTheDbReadme() throws IOException {
        Map<String, Object> job = map(map(workflow().get("jobs")).get("sql-schema"));
        assertThat(job.get("container")).isEqualTo("postgres:17");
        Map<String, Object> db = map(map(job.get("services")).get("db"));
        assertThat(db.get("image")).isEqualTo("postgres:17");
        assertThat(map(db.get("env")).get("POSTGRES_DB")).isEqualTo("antlab_test");
        assertThat(map(job.get("env")).get("PGHOST")).isEqualTo("db");

        Set<String> readmeFiles = new TreeSet<>();
        for (String line : Files.readAllLines(DB_README, StandardCharsets.UTF_8)) {
            Matcher m = README_PSQL_LINE.matcher(line);
            if (m.matches() && SQL_FILE.matcher(m.group(1)).matches()) {
                readmeFiles.add(m.group(1));
            }
        }
        List<String> workflowFiles = new ArrayList<>();
        workflowFiles.addAll(sqlFilesInOrder(codeLines(runCommands("sql-schema").get(0))));
        workflowFiles.addAll(sqlFilesInOrder(codeLines(runCommands("sql-schema").get(1))));
        assertThat(readmeFiles).hasSize(6);
        assertThat(workflowFiles).hasSize(6).containsExactlyInAnyOrderElementsOf(readmeFiles);
        assertThat(sqlFilesInOrder(codeLines(text()))).as("no SQL call outside the two steps").isEqualTo(workflowFiles);
    }

    @Test
    void sqlSchemaStepsCallEachFileOnceUncommentedAndReadTheVerdictFromTheLastLine() throws IOException {
        List<String> schema = codeLines(runCommands("sql-schema").get(0));
        assertThat(schema.get(0)).startsWith("psql -X -d postgres -c \"ALTER DATABASE antlab_test SET timezone TO 'Asia/Seoul'\"");
        assertThat(schema.subList(1, schema.size())).containsExactly(
                "psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/01_schema_history.sql",
                "psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/02_schema_season.sql",
                "psql -X -v ON_ERROR_STOP=1 -d antlab_test -f db/03_schema_account.sql");

        List<String> checks = codeLines(runCommands("sql-schema").get(1));
        assertThat(checks).anyMatch(l -> l.contains("-q -A -P footer=off -v ON_ERROR_STOP=1"));
        assertThat(checks).anyMatch(l -> l.contains("= PASS ]") && l.contains("exit 1"));
        assertThat(checks.subList(checks.size() - 3, checks.size())).containsExactly(
                "check db/01_schema_history_check.sql",
                "check db/02_schema_season_check.sql",
                "check db/03_schema_account_check.sql");
        assertThat(checks).noneMatch(l -> l.contains("|| true") || l.contains("set +e"));
    }

    @Test
    void imageJobBuildsTheArmImageAndNeverPushesIt() throws IOException {
        Map<String, Object> job = map(map(workflow().get("jobs")).get("image"));
        assertThat(job.get("runs-on")).isEqualTo("ubuntu-24.04-arm");
        assertThat((Integer) job.get("timeout-minutes")).isBetween(1, 30);
        assertThat(job).doesNotContainKeys("needs", "env", "services", "container", "permissions");

        Map<String, Object> build = step("image", "Build the api image");
        assertThat(String.valueOf(build.get("uses"))).startsWith("docker/build-push-action@");
        Map<String, Object> with = map(build.get("with"));
        assertThat(with.keySet()).containsExactlyInAnyOrder("context", "push", "load", "tags", "cache-from", "cache-to");
        assertThat(with.get("context")).isEqualTo(".");
        assertThat(with.get("push")).isEqualTo(false);
        assertThat(with.get("load")).isEqualTo(true);
        assertThat(with.get("cache-from")).isEqualTo("type=gha,scope=api");
        assertThat(with.get("cache-to")).isEqualTo("type=gha,scope=api,mode=max");
        assertThat(build).doesNotContainKeys("env", "continue-on-error");

        List<String> uses = steps("image").stream().map(s -> String.valueOf(s.get("uses"))).toList();
        assertThat(uses).anyMatch(u -> u.startsWith("docker/setup-buildx-action@"));
        assertThat(uses).noneMatch(u -> u.startsWith("docker/login-action@"));
        assertThat(String.join("\n", runCommands("image"))).doesNotContain("docker push").doesNotContain("docker login");
        assertThat(text()).doesNotContain("ghcr.io").doesNotContain("packages:");
    }

    @Test
    void imageJobChecksArchitectureAndNonRootUserOfTheLoadedImage() throws IOException {
        List<String> checks = codeLines(runCommands("image").get(0));
        assertThat(checks).containsExactly(
                "[ \"$(docker image inspect --format '{{.Architecture}}' antlab-api:ci)\" = arm64 ]",
                "[ \"$(docker image inspect --format '{{.Config.User}}' antlab-api:ci)\" = 10001:10001 ]");
        assertThat(map(map(step("image", "Build the api image").get("with"))).get("tags")).isEqualTo("antlab-api:ci");
    }

    @Test
    void dockerfileResolvesDependenciesBeforeTheSourceAndBuildsOffline() throws IOException {
        List<String> lines = codeLines(Files.readString(DOCKERFILE));
        int resolve = lines.indexOf("RUN ./gradlew --no-daemon resolveDependencies");
        int sources = lines.indexOf("COPY src/main src/main");
        int bootJar = lines.indexOf("RUN ./gradlew --no-daemon --offline bootJar");
        assertThat(resolve).isPositive();
        assertThat(sources).isGreaterThan(resolve);
        assertThat(bootJar).isGreaterThan(sources);
        assertThat(lines.stream().filter(l -> l.contains("gradlew") && l.contains("bootJar"))).hasSize(1);
        // The dependency layer's inputs: only the Gradle wrapper and build files are copied before it.
        assertThat(lines.subList(0, resolve).stream().filter(l -> l.startsWith("COPY ")).toList())
                .containsExactly("COPY gradlew settings.gradle build.gradle ./", "COPY gradle gradle");
        assertThat(String.join("\n", lines)).doesNotContain("--refresh-dependencies");

        String build = Files.readString(BUILD_GRADLE);
        assertThat(build).contains("tasks.register('resolveDependencies')");
        assertThat(build).contains(
                "files(configurations.compileClasspath, configurations.runtimeClasspath, configurations.annotationProcessor)");
    }

    @Test
    void matchersRejectATagAShortShaAndAMissingVersionComment() {
        String sha = "3d3c42e5aac5ba805825da76410c181273ba90b1";
        assertThat(PINNED_USES.matcher("      - uses: actions/checkout@" + sha + " # v7.0.1").matches()).isTrue();
        assertThat(PINNED_USES.matcher("      - uses: gradle/actions/setup-gradle@" + sha + " # v6.4.1").matches()).isTrue();
        assertThat(PINNED_USES.matcher("      - uses: actions/checkout@v5").matches()).isFalse();
        assertThat(PINNED_USES.matcher("      - uses: actions/checkout@" + sha.substring(1) + " # v7.0.1").matches()).isFalse();
        assertThat(PINNED_USES.matcher("      - uses: actions/checkout@" + sha).matches()).isFalse();
    }

    private record Run(int exit, String output) {}

    private static Run summary(Path resultsDir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("python3", "-I", SUMMARY_SCRIPT.toString(), resultsDir.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }

    /** Writes a suite XML with t0..t(tests-1); the names in {@code skipped} get a skipped child. */
    private static void suite(Path dir, String name, int tests, Set<String> skipped, int failures) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(name).append("\" tests=\"").append(tests).append("\" skipped=\"").append(skipped.size())
                .append("\" failures=\"").append(failures).append("\" errors=\"0\" timestamp=\"2026-11-16T10:00:00\" time=\"1.5\">\n");
        for (int i = 0; i < tests; i++) {
            xml.append("<testcase name=\"t").append(i).append("\" classname=\"").append(name).append("\" time=\"0.1\">");
            if (skipped.contains("t" + i)) {
                xml.append("<skipped/>");
            }
            xml.append("</testcase>\n");
        }
        xml.append("</testsuite>\n");
        Files.writeString(dir.resolve("TEST-" + name + ".xml"), xml.toString());
    }

    private static Path expected(Path dir, String... entries) throws IOException {
        Path file = dir.resolve("expected-" + entries.length + "-" + Math.abs(String.join("|", entries).hashCode()) + ".txt");
        Files.writeString(file, "# comment\n\n" + String.join("\n", entries) + "\n");
        return file;
    }

    @Test
    void skipGuardPassesWhenTheSkippedSetEqualsTheList(@TempDir Path dir) throws Exception {
        suite(dir, "a.GoldenTest", 4, Set.of("t0", "t1", "t2"), 0);
        suite(dir, "b.PlainTest", 5, Set.of(), 0);
        Path list = expected(dir, "a.GoldenTest\tt0", "a.GoldenTest\tt1", "a.GoldenTest\tt2");

        Run run = summary(dir, "--expected-skips", list.toString());
        assertThat(run.exit()).isZero();
        assertThat(run.output()).contains("skipped: 3 (expected list: 3)").contains("`a.GoldenTest`: 3");
    }

    @Test
    void skipGuardFailsWhenTheTotalIsEqualButTheSetDiffers(@TempDir Path dir) throws Exception {
        // t0 of the golden class was deleted from the list's point of view and a plain test was disabled instead.
        suite(dir, "a.GoldenTest", 4, Set.of("t1", "t2"), 0);
        suite(dir, "b.PlainTest", 5, Set.of("t3"), 0);
        Path list = expected(dir, "a.GoldenTest\tt0", "a.GoldenTest\tt1", "a.GoldenTest\tt2");

        Run run = summary(dir, "--expected-skips", list.toString());
        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.output()).contains("Guard violated").contains("1 added, 1 removed")
                .contains("`b.PlainTest` / `t3`").contains("`a.GoldenTest` / `t0`");
    }

    @Test
    void skipGuardFailsOnAnAddedOrAMissingSkip(@TempDir Path dir) throws Exception {
        suite(dir, "a.GoldenTest", 3, Set.of("t0", "t1"), 0);
        assertThat(summary(dir, "--expected-skips", expected(dir, "a.GoldenTest\tt0").toString()).exit()).isEqualTo(1);
        assertThat(summary(dir, "--expected-skips", expected(dir, "a.GoldenTest\tt0", "a.GoldenTest\tt1", "a.GoldenTest\tt2").toString()).exit())
                .isEqualTo(1);
    }

    @Test
    void skipGuardFailsOnAFailedTestcase(@TempDir Path dir) throws Exception {
        suite(dir, "a.FailingTest", 2, Set.of(), 1);
        assertThat(summary(dir, "--expected-skips", expected(dir).toString()).exit()).isEqualTo(1);
    }

    @Test
    void inputErrorsExitTwoAndLeaveTheCauseInTheSummary(@TempDir Path dir) throws Exception {
        Path list = expected(dir);
        Path summaryFile = dir.resolve("summary.md");

        Path empty = Files.createDirectory(dir.resolve("empty"));
        assertThat(summary(empty, "--expected-skips", list.toString(), "--summary-file", summaryFile.toString()).exit()).isEqualTo(2);
        assertThat(Files.readString(summaryFile)).contains("Input error").contains("no JUnit XML found");

        Path truncated = Files.createDirectory(dir.resolve("truncated"));
        Files.writeString(truncated.resolve("TEST-x.xml"), "<?xml version=\"1.0\"?>\n<testsuite name=\"x\" tests=\"2\"><testcase name=\"t0\"");
        Run broken = summary(truncated, "--expected-skips", list.toString(), "--summary-file", summaryFile.toString());
        assertThat(broken.exit()).isEqualTo(2);
        assertThat(broken.output()).doesNotContain("Traceback");
        assertThat(Files.readString(summaryFile)).contains("cannot parse TEST-x.xml");

        Path badNumber = Files.createDirectory(dir.resolve("badnumber"));
        Files.writeString(badNumber.resolve("TEST-y.xml"), "<testsuite name=\"y\" tests=\"many\"></testsuite>");
        assertThat(summary(badNumber, "--expected-skips", list.toString()).exit()).isEqualTo(2);

        assertThat(summary(dir.resolve("absent"), "--expected-skips", list.toString()).exit()).isEqualTo(2);
    }

    @Test
    void badOrMissingExpectedListExitsTwo(@TempDir Path dir) throws Exception {
        suite(dir, "a.PlainTest", 1, Set.of(), 0);
        assertThat(summary(dir).exit()).as("no list and no --print-skips").isEqualTo(2);
        assertThat(summary(dir, "--expected-skips", dir.resolve("absent.txt").toString()).exit()).isEqualTo(2);

        Path noTab = dir.resolve("notab.txt");
        Files.writeString(noTab, "a.PlainTest t0\n");
        assertThat(summary(dir, "--expected-skips", noTab.toString()).exit()).isEqualTo(2);

        Path duplicate = dir.resolve("dup.txt");
        Files.writeString(duplicate, "a.PlainTest\tt0\na.PlainTest\tt0\n");
        assertThat(summary(dir, "--expected-skips", duplicate.toString()).exit()).isEqualTo(2);
    }

    @Test
    void printSkipsListsTheCurrentSetInTheFileFormat(@TempDir Path dir) throws Exception {
        suite(dir, "a.GoldenTest", 3, Set.of("t2", "t0"), 0);
        Run run = summary(dir, "--print-skips");
        assertThat(run.exit()).isZero();
        assertThat(run.output()).isEqualTo("a.GoldenTest\tt0\na.GoldenTest\tt2\n");
    }

    @Test
    void skipGuardAppendsTheReportToTheSummaryFile(@TempDir Path dir) throws Exception {
        Path results = Files.createDirectory(dir.resolve("results"));
        suite(results, "a.PlainTest", 3, Set.of(), 0);
        Path summaryFile = dir.resolve("summary.md");
        Files.writeString(summaryFile, "earlier\n");

        assertThat(summary(results, "--expected-skips", expected(dir).toString(), "--summary-file", summaryFile.toString()).exit()).isZero();

        assertThat(Files.readString(summaryFile)).startsWith("earlier\n").contains("### Gradle tests").contains("`a.PlainTest`");
    }
}
