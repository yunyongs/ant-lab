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
    private static final Pattern PINNED_USES =
            Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*[\\w.-]+/[\\w./-]+@[0-9a-f]{40}\\s+#\\s*v\\d+\\.\\d+\\.\\d+$");
    private static final Pattern GUARD_COMMAND = Pattern.compile(
            "^python3 -I scripts/ci_test_summary\\.py build/test-results/test --expect-skipped (\\d+) --summary-file \"\\$GITHUB_STEP_SUMMARY\"$");
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
                "actions/checkout", "actions/setup-java", "actions/upload-artifact", "gradle/actions/setup-gradle");
    }

    @Test
    void checkoutsDoNotPersistCredentials() throws IOException {
        for (String job : List.of("test", "sql-schema")) {
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

    @Test
    void testJobRunsThePlainLocalCommandThenTheSkipGuard() throws IOException {
        Map<String, Object> job = map(map(workflow().get("jobs")).get("test"));
        assertThat(job.get("runs-on")).isEqualTo("ubuntu-24.04-arm");
        assertThat((Integer) job.get("timeout-minutes")).isBetween(1, 30);

        List<String> commands = runCommands("test");
        assertThat(commands).contains("./gradlew test");
        assertThat(commands).noneMatch(c -> c.contains("gradlew") && c.contains(" -P"));
        int gradle = commands.indexOf("./gradlew test");
        int guard = -1;
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).startsWith("python3 -I scripts/ci_test_summary.py")) {
                guard = i;
            }
        }
        assertThat(guard).as("guard step present").isGreaterThan(gradle);
        Matcher pin = GUARD_COMMAND.matcher(commands.get(guard));
        assertThat(pin.matches()).as(commands.get(guard)).isTrue();
        assertThat(Integer.parseInt(pin.group(1))).isPositive();

        Map<String, Object> guardStep = steps("test").stream()
                .filter(s -> String.valueOf(s.get("run")).startsWith("python3 -I"))
                .findFirst().orElseThrow();
        assertThat(guardStep.get("if")).isEqualTo("${{ !cancelled() }}");
    }

    @Test
    void uploadsReportsOnlyOnFailure() throws IOException {
        Map<String, Object> upload = steps("test").stream()
                .filter(s -> String.valueOf(s.get("uses")).startsWith("actions/upload-artifact@"))
                .findFirst().orElseThrow();
        assertThat(upload.get("if")).isEqualTo("${{ failure() }}");
        assertThat(map(upload.get("with")).get("retention-days")).isEqualTo(7);
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
        Set<String> workflowFiles = new TreeSet<>();
        Matcher m = SQL_FILE.matcher(text());
        while (m.find()) {
            workflowFiles.add(m.group());
        }
        assertThat(readmeFiles).hasSize(6);
        assertThat(workflowFiles).isEqualTo(readmeFiles);
    }

    @Test
    void sqlSchemaVerdictIsReadFromTheLastOutputLine() throws IOException {
        String checks = runCommands("sql-schema").get(1);
        assertThat(checks).contains("-q -A -P footer=off -v ON_ERROR_STOP=1");
        assertThat(checks).contains("= PASS ]");
        assertThat(runCommands("sql-schema").get(0)).doesNotContain("_check.sql");
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

    private static void suite(Path dir, String name, int tests, int skipped, int failures) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(name).append("\" tests=\"").append(tests).append("\" skipped=\"").append(skipped)
                .append("\" failures=\"").append(failures).append("\" errors=\"0\" timestamp=\"2026-11-16T10:00:00\" time=\"1.5\">\n");
        for (int i = 0; i < tests; i++) {
            xml.append("<testcase name=\"t").append(i).append("\" classname=\"").append(name).append("\" time=\"0.1\">");
            if (i < skipped) {
                xml.append("<skipped/>");
            }
            xml.append("</testcase>\n");
        }
        xml.append("</testsuite>\n");
        Files.writeString(dir.resolve("TEST-" + name + ".xml"), xml.toString());
    }

    @Test
    void skipGuardPassesOnlyWhenTheSkippedCountEqualsThePin(@TempDir Path dir) throws Exception {
        suite(dir, "a.GoldenTest", 4, 3, 0);
        suite(dir, "b.PlainTest", 5, 0, 0);

        Run exact = summary(dir, "--expect-skipped", "3");
        assertThat(exact.exit()).isZero();
        assertThat(exact.output()).contains("skipped: 3 (pinned: 3)").contains("`a.GoldenTest`: 3");

        assertThat(summary(dir, "--expect-skipped", "2").exit()).isEqualTo(1);
        assertThat(summary(dir, "--expect-skipped", "4").exit()).isEqualTo(1);
        assertThat(summary(dir, "--expect-skipped", "0").output()).contains("Guard violated");
    }

    @Test
    void skipGuardFailsOnAFailedTestcaseAndOnMissingResults(@TempDir Path dir) throws Exception {
        suite(dir, "a.FailingTest", 2, 0, 1);
        assertThat(summary(dir, "--expect-skipped", "0").exit()).isEqualTo(1);

        Path empty = Files.createDirectory(dir.resolve("empty"));
        assertThat(summary(empty, "--expect-skipped", "0").exit()).isEqualTo(2);
        assertThat(summary(dir.resolve("absent"), "--expect-skipped", "0").exit()).isEqualTo(2);
    }

    @Test
    void skipGuardAppendsTheReportToTheSummaryFile(@TempDir Path dir) throws Exception {
        Path results = Files.createDirectory(dir.resolve("results"));
        suite(results, "a.PlainTest", 3, 0, 0);
        Path summaryFile = dir.resolve("summary.md");
        Files.writeString(summaryFile, "earlier\n");

        assertThat(summary(results, "--expect-skipped", "0", "--summary-file", summaryFile.toString()).exit()).isZero();

        assertThat(Files.readString(summaryFile)).startsWith("earlier\n").contains("### Gradle tests").contains("`a.PlainTest`");
    }
}
