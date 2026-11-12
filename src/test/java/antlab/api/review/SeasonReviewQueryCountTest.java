package antlab.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The statement count of a review does not depend on the number of turns, and nothing writes or locks. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReviewQueryCountTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private List<String> measure(long season) {
        SqlRecorder.start();
        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, season);
        List<String> sqls = SqlRecorder.stop();
        assertThat(r.getStatus()).isEqualTo(200);
        return sqls;
    }

    private void addChanges(long season, int... turns) {
        for (int turn : turns) {
            jdbc.update("INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, "
                    + "before_midfielder, before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward, "
                    + "reason) VALUES (?, ?, 10, 30, 30, 30, 10, 20, 40, 30, '금리 상승')", season, turn);
        }
    }

    @Test
    void 문장_수가_턴_수와_무관하고_쓰기와_잠금이_없다() throws Exception {
        long s12 = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        long s24 = ReviewApiSupport.finishedSeason(mvc, jdbc, 24);
        addChanges(s12, 3);
        addChanges(s24, 3, 5, 7);
        List<String> twelve = measure(s12);
        List<String> twentyFour = measure(s24);
        System.out.println("[M] review Hibernate statements: 12 turns = " + twelve.size() + ", 24 turns = " + twentyFour.size());
        assertThat(twelve).hasSameSizeAs(twentyFour);
        // member, season, months, events, formation changes
        assertThat(twelve).hasSize(5);
        for (String sql : twelve) {
            String s = sql.toLowerCase(Locale.ROOT);
            assertThat(s).doesNotStartWith("insert").doesNotStartWith("update").doesNotStartWith("delete");
            assertThat(s).doesNotContain("for update").doesNotContain("for share").doesNotContain("for no key update");
        }
    }
}
