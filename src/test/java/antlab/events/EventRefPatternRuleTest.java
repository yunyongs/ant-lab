package antlab.events;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Pins the reaction reference regexes of {@code db/11_load_events.sql} without the golden sample: the literals are cut out of the
 * script and run on PostgreSQL, and the single-reference form must equal the Python {@code REF} of the events file test.
 */
@TestPropertySource(properties = "antlab.test.context=empty-schema")
@IntegrationTest
class EventRefPatternRuleTest {

    private static final Pattern SINGLE = Pattern.compile("t\\.reaction_ref !~ '([^']+)'");
    private static final Pattern LIST = Pattern.compile("t\\.reaction_refs !~ '([^']+)'");
    private static final Pattern PY_REF = Pattern.compile("(?m)^REF = re\\.compile\\(r\"([^\"]+)\"\\)");

    @Autowired
    JdbcTemplate jdbc;

    private static String script() {
        try {
            return Files.readString(Path.of("db/11_load_events.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String literal(Pattern p) {
        Matcher m = p.matcher(script());
        assertThat(m.find()).as("literal " + p + " not found").isTrue();
        String value = m.group(1);
        assertThat(m.find()).as("literal " + p + " found more than once").isFalse();
        return value;
    }

    private boolean matches(String value, String pattern) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ?::text ~ ?::text", Boolean.class, value, pattern));
    }

    @Test
    void 단일_참조는_풀_티커를_받고_잘못된_형식을_거부한다() {
        String pattern = literal(SINGLE);
        for (String ok : List.of("KR:0019K0", "KR:069500", "US:BRK.B", "US:SPY", "FX:USD")) {
            assertThat(matches(ok, pattern)).as(ok).isTrue();
        }
        for (String bad : List.of("KR:12345", "KR:0019k0", "US:brk.b", "US:BRK.BB", "US:BRK.", "FX:EUR")) {
            assertThat(matches(bad, pattern)).as(bad).isFalse();
        }
    }

    @Test
    void 목록_참조도_같은_규칙이다() {
        String pattern = literal(LIST);
        assertThat(matches("KR:0019K0;US:BRK.B;FX:USD", pattern)).isTrue();
        assertThat(matches("US:SPY;KR:12345", pattern)).isFalse();
        assertThat(matches("US:BRK.B;", pattern)).isFalse();
    }

    @Test
    void SQL_단일_참조_정규식은_파이썬_REF와_같다() throws IOException {
        String sql = literal(SINGLE);
        assertThat(sql).startsWith("^(").endsWith(")$");
        String body = sql.substring(2, sql.length() - 2);
        Matcher m = PY_REF.matcher(Files.readString(Path.of("tools/fetch/tests/test_events_files.py")));
        assertThat(m.find()).as("REF not found in test_events_files.py").isTrue();
        assertThat(body).isEqualTo(m.group(1));
    }
}
