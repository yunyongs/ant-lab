package antlab.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** psql does not expand :name, :'name' or :"name" inside a dollar-quoted body, so the script fails only when it runs. */
class PsqlVariableInDollarQuoteTest {

    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    static List<String> findHits(String sql) {
        List<String> hits = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            int skipped = skipNonCode(sql, i);
            if (skipped != i) {
                i = skipped;
                continue;
            }
            char c = sql.charAt(i);
            if (c == '$' && (i == 0 || !isWord(sql.charAt(i - 1)))) {
                Matcher m = DOLLAR_TAG.matcher(sql);
                m.region(i, n);
                if (m.lookingAt()) {
                    String tag = m.group();
                    int end = sql.indexOf(tag, m.end());
                    if (end < 0) {
                        end = n;
                    }
                    scanBody(sql, m.end(), end, hits);
                    i = Math.min(n, end + tag.length());
                    continue;
                }
            }
            i++;
        }
        return hits;
    }

    private static void scanBody(String sql, int from, int to, List<String> hits) {
        int i = from;
        while (i < to) {
            int skipped = skipNonCode(sql, i);
            if (skipped != i) {
                i = skipped;
                continue;
            }
            char c = sql.charAt(i);
            if (c == ':' && i + 1 < to && (sql.charAt(i - 1) != ':' || i == from)) {
                char next = sql.charAt(i + 1);
                boolean start = Character.isLetter(next) || next == '_' || next == '\'' || next == '"';
                char prev = i > from ? sql.charAt(i - 1) : ' ';
                boolean afterOperand = isWord(prev) || prev == ']' || prev == ')';
                if (start && !afterOperand) {
                    int line = 1;
                    for (int k = 0; k < i; k++) {
                        if (sql.charAt(k) == '\n') {
                            line++;
                        }
                    }
                    int e = Math.min(to, i + 30);
                    hits.add("line " + line + ": " + sql.substring(i, e).split("\n")[0]);
                }
            }
            i++;
        }
    }

    /** Returns the index after a comment, string literal or quoted identifier starting at i, or i when none starts there. */
    private static int skipNonCode(String sql, int i) {
        int n = sql.length();
        char c = sql.charAt(i);
        if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
            int nl = sql.indexOf('\n', i);
            return nl < 0 ? n : nl;
        }
        if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
            int end = sql.indexOf("*/", i + 2);
            return end < 0 ? n : end + 2;
        }
        if (c == '\'' || c == '"') {
            int j = i + 1;
            while (j < n) {
                if (sql.charAt(j) == c) {
                    if (j + 1 < n && sql.charAt(j + 1) == c) {
                        j += 2;
                        continue;
                    }
                    return j + 1;
                }
                j++;
            }
            return n;
        }
        return i;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    @Test
    void tracked_db_scripts_have_no_psql_variable_inside_dollar_quotes() throws IOException {
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("db"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                for (String hit : findHits(Files.readString(file))) {
                    failures.add(file + " " + hit);
                }
            }
        }
        assertThat(failures).as("psql variables inside dollar-quoted bodies are not expanded").isEmpty();
    }

    @Test
    void the_scanner_flags_the_db_20_bug() {
        String bug = "DO $$ BEGIN\n IF date_trunc('month', :'replay_start'::date)::date <> :'replay_start'::date THEN\n END IF;\nEND $$;";
        assertThat(findHits(bug)).hasSize(2);
        assertThat(findHits("DO $tag$ BEGIN PERFORM :x; END $tag$;")).hasSize(1);
        assertThat(findHits("DO $$ BEGIN PERFORM :\"q\"; END $$;")).hasSize(1);
    }

    @Test
    void the_scanner_ignores_legitimate_colons() {
        String ok = "SELECT :'v'::date;\nDO $$ DECLARE a int[]; BEGIN x := 1; y := a[1:2]; z := b[lo:hi]; PERFORM 1::int;\n"
                + "-- :'in_comment'\n/* :x */ PERFORM ':in_string'; PERFORM 'it''s :x'; END $$;";
        assertThat(findHits(ok)).isEmpty();
    }
}
