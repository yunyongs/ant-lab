package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** {@code GET …/turns/{n}} returns the feed of turn n only, through the assembler the advance uses. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnViewFeedTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private long playedSeason(JsonNode[] advanced) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int turn = 0; turn < advanced.length; turn++) {
            MockHttpServletResponse r = advance(mvc, season, turn);
            assertThat(r.getStatus()).isEqualTo(201);
            advanced[turn] = json(r);
        }
        return season;
    }

    @Test
    void each_turn_shows_its_own_feed_and_equals_what_the_advance_answered() throws Exception {
        JsonNode[] advanced = new JsonNode[3];
        long season = playedSeason(advanced);
        int shown = 0;
        for (int turn = 1; turn <= 3; turn++) {
            MockHttpServletResponse r = getTurn(mvc, season, turn);
            assertThat(r.getStatus()).isEqualTo(200);
            JsonNode feed = json(r).get("feed");
            assertThat(feed).as("turn " + turn).isEqualTo(advanced[turn - 1].get("feed"));
            for (JsonNode item : feed) {
                assertThat(item.get("turnNo").asInt()).as("not the feed of another turn").isEqualTo(turn);
            }
            assertThat(feed.size()).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM turn_feed WHERE season_id = ? AND turn_no = ?",
                    Integer.class, season, turn));
            shown += feed.size();
        }
        assertThat(shown).isPositive();
    }

    @Test
    void a_turn_with_no_stored_feed_answers_an_empty_list_and_a_later_turn_is_not_reached() throws Exception {
        long season = playedSeason(new JsonNode[2]);
        jdbc.update("DELETE FROM turn_feed WHERE season_id = ? AND turn_no = 2", season);

        MockHttpServletResponse empty = getTurn(mvc, season, 2);
        assertThat(empty.getStatus()).isEqualTo(200);
        JsonNode feed = json(empty).get("feed");
        assertThat(feed.isArray()).isTrue();
        assertThat(feed).isEmpty();
        assertThat(json(getTurn(mvc, season, 1)).get("feed")).as("the other turn keeps its rows").isNotEmpty();

        MockHttpServletResponse later = getTurn(mvc, season, 3);
        assertThat(later.getStatus()).isEqualTo(409);
        assertThat(code(later)).isEqualTo("TURN_NOT_REACHED");
    }

    @Test
    void two_seasons_never_see_each_others_feed() throws Exception {
        JsonNode[] firstAdvanced = new JsonNode[2];
        long first = playedSeason(firstAdvanced);
        JsonNode[] secondAdvanced = new JsonNode[2];
        long second = playedSeason(secondAdvanced);
        for (int turn = 1; turn <= 2; turn++) {
            for (long season : new long[] {first, second}) {
                JsonNode feed = json(getTurn(mvc, season, turn)).get("feed");
                assertThat(feed.size()).as("season " + season + " turn " + turn).isEqualTo(jdbc.queryForObject(
                        "SELECT count(*) FROM turn_feed WHERE season_id = ? AND turn_no = ?", Integer.class, season, turn));
                assertThat(feed).isNotEmpty();
            }
            assertThat(json(getTurn(mvc, first, turn)).get("feed")).isEqualTo(firstAdvanced[turn - 1].get("feed"));
            assertThat(json(getTurn(mvc, second, turn)).get("feed")).isEqualTo(secondAdvanced[turn - 1].get("feed"));
        }
        // Both seasons have rows of the same turn numbers, so a missing season condition would double the lists.
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT season_id) FROM turn_feed WHERE season_id IN (?, ?)", Integer.class,
                first, second)).isEqualTo(2);
    }
}
