package antlab.api.turn;

import static antlab.api.turn.RankingSupport.getRanking;
import static antlab.api.turn.RankingSupport.playedSeason;
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

/** Fixed statement count, no writes, and no previous-feed statement on the ranking path. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class RankingQueryCountTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private List<String> measure(long season, int turn) {
        SqlRecorder.start();
        MockHttpServletResponse r = getRanking(mvc, season, turn);
        List<String> sqls = SqlRecorder.stopAll().all();
        assertThat(r.getStatus()).isEqualTo(200);
        return sqls;
    }

    @Test
    void six_statements_whatever_the_turn_and_no_write_and_no_feed_read() throws Exception {
        long season = playedSeason(mvc, jdbc, 5);
        for (int turn = 1; turn <= 5; turn++) {
            List<String> sqls = measure(season, turn);
            System.out.println("[MVP-12] ranking turns/" + turn + " statements = " + sqls.size());
            // current member 1 + season 1 + turn prices 1 + earlier prices 1 + pool 1 + attention 1
            assertThat(sqls).as("turn " + turn).hasSize(6);
            sqls.forEach(q -> {
                String lower = q.toLowerCase(Locale.ROOT);
                assertThat(lower).doesNotStartWith("insert").doesNotStartWith("update").doesNotStartWith("delete");
                assertThat(lower).doesNotContain("turn_feed").doesNotContain("month_event").doesNotContain("orders");
            });
        }
    }

    @Test
    void two_seasons_with_different_holdings_cost_the_same() throws Exception {
        long first = playedSeason(mvc, jdbc, 2);
        long second = TurnApiSupport.startSeason(mvc);
        RankingSupport.seedAttention(jdbc, second);
        assertThat(TurnApiSupport.advance(mvc, second, 0).getStatus()).isEqualTo(201);
        assertThat(measure(second, 1)).hasSameSizeAs(measure(first, 2));
    }
}
