package antlab.api.turn;

import static antlab.api.turn.RankingSupport.getRanking;
import static antlab.api.turn.RankingSupport.playedSeason;
import static antlab.api.turn.TurnApiSupport.advance;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The board of a turn is the same text whenever it is asked: later in the season and after the season ended. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class RankingStabilityTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void the_board_of_turn_two_is_byte_equal_at_current_two_five_and_after_the_end() throws Exception {
        long season = playedSeason(mvc, jdbc, 2);
        String atTwo = getRanking(mvc, season, 2).getContentAsString();
        assertThat(atTwo).isNotEmpty();
        List<String> boards = new ArrayList<>();
        for (int turn = 2; turn < 12; turn++) {
            assertThat(advance(mvc, season, turn).getStatus()).isEqualTo(201);
            if (turn == 4) {
                boards.add(getRanking(mvc, season, 2).getContentAsString());
            }
        }
        boards.add(getRanking(mvc, season, 2).getContentAsString());
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season)).isEqualTo("FINISHED");
        assertThat(boards).hasSize(2).allSatisfy(b -> assertThat(b).isEqualTo(atTwo));
        assertThat(getRanking(mvc, season, 2).getContentAsString()).isEqualTo(atTwo);
    }
}
