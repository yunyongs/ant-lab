package antlab.api.turn;

import static antlab.api.turn.RankingSupport.getRanking;
import static antlab.api.turn.RankingSupport.playedSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The board of turn n reads nothing of a later turn. The data mutation changes every later price and attention row; the
 * statement check reads the SQL text of every statement that touches those tables.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class RankingLookAheadTest {

    /** A comparison that lets rows above the asked turn through. */
    private static final Pattern OPEN_ENDED = Pattern.compile("turn_no\\s*(<=|>=|>)");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void changing_every_later_price_and_attention_row_leaves_the_earlier_board_unchanged() throws Exception {
        long season = playedSeason(mvc, jdbc, 5);
        String two = getRanking(mvc, season, 2).getContentAsString();
        String three = getRanking(mvc, season, 3).getContentAsString();
        String five = getRanking(mvc, season, 5).getContentAsString();

        jdbc.update("UPDATE turn_price SET price = price * 3 + turn_no WHERE season_id = ? AND turn_no >= 3", season);
        jdbc.update("""
                UPDATE asset_month_attention SET attention_index = round(0.95 - attention_index, 8)
                WHERE month_block_id IN (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no >= 3)""", season);

        assertThat(getRanking(mvc, season, 2).getContentAsString()).as("turn 2 ignores turns 3 and later").isEqualTo(two);
        assertThat(getRanking(mvc, season, 3).getContentAsString()).as("the mutation does change turn 3").isNotEqualTo(three);
        assertThat(getRanking(mvc, season, 5).getContentAsString()).as("and turn 5").isNotEqualTo(five);
        assertThat(getRanking(mvc, season, 1).getStatus()).isEqualTo(200);
    }

    @Test
    void an_extra_row_of_a_turn_above_the_current_one_changes_nothing() throws Exception {
        long season = playedSeason(mvc, jdbc, 3);
        String three = getRanking(mvc, season, 3).getContentAsString();
        jdbc.update("""
                INSERT INTO turn_price (season_id, turn_no, asset_id, price)
                SELECT season_id, 4, asset_id, price * 2 FROM turn_price WHERE season_id = ? AND turn_no = 3""", season);
        assertThat(getRanking(mvc, season, 3).getContentAsString()).isEqualTo(three);
        assertThat(getRanking(mvc, season, 4).getStatus()).as("the season has not played turn 4").isEqualTo(409);
    }

    @Test
    void no_statement_of_the_read_asks_for_rows_above_the_turn() throws Exception {
        long season = playedSeason(mvc, jdbc, 4);
        for (int turn = 1; turn <= 4; turn++) {
            SqlRecorder.start();
            MockHttpServletResponse r = getRanking(mvc, season, turn);
            SqlRecorder.Recorded recorded = SqlRecorder.stopAll();
            assertThat(r.getStatus()).isEqualTo(200);
            List<String> touching = recorded.all().stream().filter(s -> {
                String sql = s.toLowerCase(Locale.ROOT);
                return sql.contains("turn_price") || sql.contains("season_path");
            }).toList();
            assertThat(touching).as("turn " + turn).isNotEmpty();
            for (String sql : touching) {
                String lower = sql.toLowerCase(Locale.ROOT);
                assertThat(OPEN_ENDED.matcher(lower).find()).as(sql).isFalse();
                assertThat(lower).as(sql).containsAnyOf("turn_no = :turnno", "turn_no < :turnno", "turn_no = 0",
                        "turn_no between :previousturn and :turnno");
            }
        }
    }
}
