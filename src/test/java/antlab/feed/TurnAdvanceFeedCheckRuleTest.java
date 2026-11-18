package antlab.feed;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Pins the feed row-count rule of {@code db/23_turn_advance_check.sql} without the golden sample: the 15-row cap counts feed rows only,
 * nudge alerts come after them with a continuous {@code seq}. The statement is cut out of the script and run on temporary tables, so
 * the script text is what is tested.
 */
@TestPropertySource(properties = "antlab.test.context=empty-schema")
@IntegrationTest
class TurnAdvanceFeedCheckRuleTest {

    private static final Pattern FEED_ROWS = Pattern.compile(
            "피드 ① [^']*', '0',\\s*\\(SELECT count\\(\\*\\) FROM \\((SELECT f\\.turn_no FROM turn_feed f.*?)\\) x\\)::text\\);", Pattern.DOTALL);

    @Autowired
    JdbcTemplate jdbc;

    private long violatingTurns(String... rows) {
        String query = feedRowsQuery();
        return jdbc.execute((java.sql.Connection c) -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TEMP TABLE zz_cur (id BIGINT)");
                st.execute("CREATE TEMP TABLE turn_feed (season_id BIGINT, turn_no INT, seq INT, kind VARCHAR(6))");
                st.execute("INSERT INTO zz_cur VALUES (1)");
                for (String row : rows) {
                    st.execute("INSERT INTO turn_feed " + row);
                }
                try (var rs = st.executeQuery("SELECT count(*) FROM (" + query + ") x")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                try (Statement st = c.createStatement()) {
                    st.execute("DROP TABLE IF EXISTS pg_temp.turn_feed");
                    st.execute("DROP TABLE IF EXISTS pg_temp.zz_cur");
                }
            }
        });
    }

    private static String feedRowsQuery() {
        try {
            String script = Files.readString(Path.of("db/23_turn_advance_check.sql"));
            Matcher m = FEED_ROWS.matcher(script);
            assertThat(m.find()).as("feed row-count check not found in db/23").isTrue();
            return m.group(1);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String turn(int turn, int feed, int alerts) {
        StringBuilder sql = new StringBuilder("VALUES ");
        for (int seq = 1; seq <= feed + alerts; seq++) {
            sql.append(seq > 1 ? "," : "").append("(1,").append(turn).append(',').append(seq).append(",'")
                    .append(seq <= feed ? "RISE" : "NUDGE").append("')");
        }
        return sql.toString();
    }

    @Test
    void 피드_15행에_알림이_붙어_합계가_15를_넘어도_통과한다() {
        assertThat(violatingTurns(turn(1, 15, 4), turn(2, 5, 0), turn(3, 12, 3))).isZero();
    }

    @Test
    void 피드가_15행을_넘으면_알림이_없어도_걸린다() {
        assertThat(violatingTurns(turn(1, 16, 0))).isEqualTo(1);
    }

    @Test
    void 알림이_섞여도_seq가_끊기면_걸린다() {
        assertThat(violatingTurns(turn(1, 10, 2) + ", (1,1,20,'NUDGE')")).isEqualTo(1);
    }

    @Test
    void 알림_seq가_1부터_다시_시작하면_걸린다() {
        assertThat(violatingTurns("VALUES (1,1,1,'RISE'),(1,1,2,'RISE'),(1,1,1,'NUDGE')")).isEqualTo(1);
    }

    @Test
    void 알림이_피드_행_수와_상한에_들어가는_상수는_일치한다() {
        assertThat(FeedRules.MAX_ROWS).isEqualTo(15);
        assertThat(FeedRules.MAX_ROWS + AlertRules.MAX_ALERTS).isGreaterThan(15);
    }
}
