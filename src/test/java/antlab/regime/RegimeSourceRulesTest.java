package antlab.regime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Static guards for the regime job: where the label column may be named, what it may write and log, and that the column lists agree. */
class RegimeSourceRulesTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path DIR = MAIN.resolve("antlab/regime");
    private static final Pattern LOG_CALL = Pattern.compile("(?m)^\\s*LOG\\.\\w+\\((.*)$");
    private static final Pattern SENSITIVE = Pattern.compile("\\b(month|months|value|values|answer|stored|request|body|token|indicators?|probabilities)\\b",
            Pattern.CASE_INSENSITIVE);

    private static List<Path> javaIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    static boolean logsSensitive(String source) {
        Matcher matcher = LOG_CALL.matcher(source);
        while (matcher.find()) {
            // the quoted format text may name fields (rows=, reason=); only the argument expressions are checked
            if (SENSITIVE.matcher(matcher.group(1).replaceAll("\"[^\"]*\"", "\"\"")).find()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theCheckerCatchesWhatItIsFor() {
        assertThat(logsSensitive("LOG.info(\"x {}\", month);")).isTrue();
        assertThat(logsSensitive("LOG.error(\"x {}\", e.getMessage(), stored.values());")).isTrue();
        assertThat(logsSensitive("LOG.info(\"regime.labelled rows={} filled={}\", outcome.rows(), outcome.filled());")).isFalse();
        assertThat(logsSensitive("LOG.error(\"regime.failed reason={}\", describe(e));")).isFalse();
    }

    @Test
    void noLogCallOfTheRegimePackageTakesAMonthAValueOrAnAnswer() throws IOException {
        List<Path> files = javaIn(DIR);
        assertThat(files).hasSizeGreaterThanOrEqualTo(5);
        for (Path file : files) {
            assertThat(logsSensitive(Files.readString(file))).as(file.toString()).isFalse();
        }
    }

    @Test
    void theRegimeLabelColumnIsNamedOnlyByTheEntityTheWriterPackageAndTheDigestPath() throws IOException {
        Set<String> allowed = Set.of("antlab/history/MonthBlock.java", "antlab/history/RegimePool.java", "antlab/regime/MonthRegimeWriter.java",
                "antlab/regime/MonthRegime.java", "antlab/regime/RegimeAnswerCheck.java",
                // the engine path source reads the label only to hash it into the history digest (never to answer)
                "antlab/history/ReplayMonth.java", "antlab/history/MonthBlockRepository.java", "antlab/engine/HistoryDigest.java");
        Set<String> found = new TreeSet<>();
        for (Path file : javaIn(MAIN)) {
            String source = Files.readString(file);
            if (source.contains("regime_label") || source.contains("regimeLabel") || source.contains("indicator_z") || source.contains("indicatorZ")) {
                found.add(MAIN.relativize(file).toString());
            }
        }
        assertThat(allowed).containsAll(found);
        assertThat(found).contains("antlab/history/MonthBlock.java", "antlab/regime/MonthRegimeWriter.java");
    }

    @Test
    void noApiResponseTypeReachesTheRegimeLabelOrTheRegimePackage() throws IOException {
        for (Path file : javaIn(MAIN.resolve("antlab/api"))) {
            String source = Files.readString(file);
            assertThat(source).as(file.toString()).doesNotContain("RegimePool").doesNotContain("antlab.regime").doesNotContain("regimeLabel");
        }
    }

    @Test
    void theWriterOnlyUpdatesTheFourteenColumnsAndNothingElseIsWritten() throws IOException {
        String fill = MonthRegimeWriter.FILL;
        Matcher set = Pattern.compile("(?s)SET(.*)WHERE").matcher(fill);
        assertThat(set.find()).isTrue();
        Set<String> assigned = Pattern.compile("(?m)^\\s*(\\w+) = COALESCE\\(\\1,").matcher(set.group(1)).results()
                .map(m -> m.group(1)).collect(Collectors.toCollection(TreeSet::new));
        assertThat(assigned).containsExactlyInAnyOrderElementsOf(MonthRegime.COLUMNS);
        assertThat(fill).startsWith("UPDATE month_block SET").endsWith("WHERE id = :id");
        // every other statement of the package only reads
        for (Path file : javaIn(DIR)) {
            String source = Files.readString(file).replaceAll("(?s)static final String FILL = \"\"\".*?\"\"\";", "");
            assertThat(source).as(file.toString()).doesNotContainPattern("(?i)\\b(INSERT\\s+INTO|DELETE\\s+FROM|TRUNCATE|DROP\\s+|ALTER\\s+TABLE|UPDATE\\s+month_block)");
        }
    }

    @Test
    void noDoubleOrFloatIsUsedInTheRegimePackage() throws IOException {
        for (Path file : javaIn(DIR)) {
            assertThat(Files.readString(file)).as(file.toString()).doesNotContainPattern("\\b(double|float|Double|Float)\\b");
        }
    }

    private static Set<String> listAfter(String file, String marker) throws IOException {
        String source = Files.readString(Path.of(file));
        int at = source.indexOf(marker);
        assertThat(at).as(marker + " in " + file).isNotNegative();
        int open = source.indexOf("ARRAY[", at);
        String list = source.substring(open + "ARRAY[".length(), source.indexOf(']', open));
        return Pattern.compile("'(\\w+)'").matcher(list).results().map(m -> m.group(1)).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void theThreeColumnListsAreTheSame() throws IOException {
        Set<String> columns = new TreeSet<>(MonthRegime.COLUMNS);

        assertThat(listAfter("src/main/resources/db/callback/afterMigrate.sql", "regime_columns constant text[]")).isEqualTo(columns);
        assertThat(listAfter("db/roles/antlab_owner_app.sql", "a.attname::text = ANY (ARRAY[")).isEqualTo(columns);
    }
}
