package antlab.support;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Three-way comparison of a pure PostgreSQL function (the repository's SQL original, installed as a session-local
 * {@code pg_temp} function) against its Java counterpart and a hand-computed expected value.
 *
 * <p>Every case has an ID and one shared input: the same literals feed the SQL expression and the Java call, and the
 * expected value is written down independently of both. All cases of one run are evaluated in a single psql session
 * through {@link PsqlScriptRunner#runDefinitionQuery}, so the temporary functions are visible to the query. The report
 * records the server version and SHA-256 checksums of the SQL that was installed. A failure lists the case IDs with all
 * three values and never prints raw psql output.
 */
public final class SqlFunctionParity {

    /** Container-relative location of the generated install script. */
    static final String SCRIPT_PATH = "parity/functions.sql";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,60}");
    private static final Pattern SAFE_FUNCTION = Pattern.compile("[a-z][a-z0-9_]{0,40}");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * One comparison. {@code sqlExpression} must yield a value castable to text (numeric); {@code java} and
     * {@code expected} are the other two sides. With {@code exactScale} the digits after the point must match too.
     */
    public record Case(String id, String sqlExpression, BigDecimal java, BigDecimal expected, boolean exactScale) {
        public Case {
            if (!SAFE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("unsafe case id: " + id);
            }
            if (sqlExpression.contains(";") || sqlExpression.contains("\\")) {
                throw new IllegalArgumentException("unsafe SQL expression in case " + id);
            }
        }
    }

    /** Run summary: server version, source file checksums, installed script checksum, case count. */
    public record Report(String serverVersion, Map<String, String> sourceSha256, String scriptSha256, int cases) {
        @Override
        public String toString() {
            return "Report[" + serverVersion + ", cases=" + cases + ", script=" + scriptSha256 + ", sources="
                    + sourceSha256 + "]";
        }
    }

    /** Value comparison (scale ignored). */
    public static Case valueCase(String id, String sqlExpression, BigDecimal java, String expected) {
        return new Case(id, sqlExpression, java, new BigDecimal(expected), false);
    }

    /** Value and scale comparison, for results stored with a fixed number of decimals. */
    public static Case exactCase(String id, String sqlExpression, BigDecimal java, String expected) {
        return new Case(id, sqlExpression, java, new BigDecimal(expected), true);
    }

    /** SQL numeric literal from a decimal; the only way test inputs reach the SQL text. */
    public static String lit(BigDecimal value) {
        return "CAST('" + value.toPlainString() + "' AS numeric)";
    }

    public static String lit(int value) {
        return "CAST(" + value + " AS integer)";
    }

    public static String lit(String text) {
        if (!Pattern.compile("[A-Za-z0-9_]{0,20}").matcher(text).matches()) {
            throw new IllegalArgumentException("unsafe text literal");
        }
        return "'" + text + "'";
    }

    private final PostgreSQLContainer container;
    private final PsqlScriptRunner runner;
    private final Map<String, String> sourceSha256 = new LinkedHashMap<>();
    private final List<String> definitions = new ArrayList<>();

    public SqlFunctionParity(PostgreSQLContainer container) {
        this.container = container;
        this.runner = new PsqlScriptRunner(container);
    }

    /** Installs the named {@code pg_temp} functions, copied verbatim from the repository's SQL file. */
    public SqlFunctionParity install(Path sqlFile, String... functionNames) throws IOException {
        String source = Files.readString(sqlFile);
        sourceSha256.put(sqlFile.toString(), GoldenFixtureSource.sha256(source.getBytes(StandardCharsets.UTF_8)));
        for (String name : functionNames) {
            definitions.add(extract(source, name));
        }
        return this;
    }

    /** Text of one {@code CREATE OR REPLACE FUNCTION pg_temp.<name>(…) … $$;} statement. */
    static String extract(String source, String name) {
        if (!SAFE_FUNCTION.matcher(name).matches()) {
            throw new IllegalArgumentException("unsafe function name: " + name);
        }
        String marker = "CREATE OR REPLACE FUNCTION pg_temp." + name + "(";
        int start = source.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("SQL function definition not found: " + name);
        }
        int end = source.indexOf("$$;", start);
        if (end < 0) {
            throw new IllegalStateException("SQL function end marker not found: " + name);
        }
        return source.substring(start, end + 3);
    }

    /** Evaluates every case in one psql session and compares Java, SQL and expected. Throws on any difference. */
    public Report verify(List<Case> cases) throws Exception {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("no cases");
        }
        Set<String> ids = new HashSet<>();
        for (Case c : cases) {
            if (!ids.add(c.id())) {
                throw new IllegalArgumentException("duplicate case id: " + c.id());
            }
        }
        String script = String.join("\n\n", definitions) + "\n";
        runner.putFile(SCRIPT_PATH, script.getBytes(StandardCharsets.UTF_8));
        String values = String.join(", ", cases.stream()
                .map(c -> "('" + c.id() + "', CAST(" + c.sqlExpression() + " AS text))").toList());
        String query = "SELECT c.id, c.val FROM (VALUES " + values + ") AS c(id, val)";
        PsqlScriptRunner.Result result = runner.runDefinitionQuery(container.getDatabaseName(), SCRIPT_PATH, query, TIMEOUT);
        if (result.exitCode() != 0) {
            throw new AssertionError("SQL parity query failed: exit " + result.exitCode() + ", timedOut=" + result.timedOut());
        }
        Map<String, String> sqlValues = parse(result.stdout());
        List<String> mismatches = new ArrayList<>();
        for (Case c : cases) {
            String sqlText = sqlValues.get(c.id());
            if (sqlText == null) {
                mismatches.add(c.id() + ": SQL returned no value");
                continue;
            }
            BigDecimal sql = new BigDecimal(sqlText);
            boolean same = sql.compareTo(c.java()) == 0 && sql.compareTo(c.expected()) == 0;
            if (c.exactScale()) {
                same = same && sql.scale() == c.java().scale() && sql.scale() == c.expected().scale();
            }
            if (!same) {
                mismatches.add(c.id() + ": java=" + c.java().toPlainString() + " sql=" + sql.toPlainString()
                        + " expected=" + c.expected().toPlainString());
            }
        }
        Report report = new Report(serverVersion(), Map.copyOf(sourceSha256),
                GoldenFixtureSource.sha256(script.getBytes(StandardCharsets.UTF_8)), cases.size());
        System.out.println("[sql-parity] " + report);
        if (!mismatches.isEmpty()) {
            throw new AssertionError("Java / SQL / expected differ in " + mismatches.size() + " of " + cases.size()
                    + " cases " + report + "\n" + String.join("\n", mismatches));
        }
        return report;
    }

    private String serverVersion() throws Exception {
        PsqlScriptRunner.Result result = runner.runSql(container.getDatabaseName(),
                "COPY (SELECT version()) TO STDOUT", TIMEOUT);
        if (result.exitCode() != 0) {
            throw new AssertionError("SELECT version() failed: exit " + result.exitCode());
        }
        return result.stdout().strip();
    }

    /** CSV with header {@code id,val}; the ids and numeric texts contain no commas or quotes. */
    private static Map<String, String> parse(String stdout) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = stdout.strip().split("\n");
        for (int i = 1; i < lines.length; i++) {
            int comma = lines[i].indexOf(',');
            if (comma < 0) {
                throw new AssertionError("unexpected psql output shape");
            }
            String value = lines[i].substring(comma + 1).strip();
            if (!value.equals("\\N")) {
                values.put(lines[i].substring(0, comma), value);
            }
        }
        return values;
    }
}
