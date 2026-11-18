package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The macro environment of the turn result equals the stored values of the month that the season path gives for exactly that
 * turn, in the advance response and in the lookup alike, for every season and in every season state. The expected values are
 * read by SQL in the test. Null parts stay null: no axis becomes NEUTRAL or zero.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql", "/fixtures/replay_macro.sql"})
@Sql(scripts = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnMacroApiTest {

    private static final Pattern EIGHT = Pattern.compile("\"strength\":\"\\d+\\.\\d{8}\"");
    private static final Pattern FOUR = Pattern.compile("\"p[1-4]\":\"\\d\\.\\d{4}\"");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private long advancedSeason(int turns) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < turns; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        return season;
    }

    /** The advance responses of turns 1..n, kept to compare with the lookup afterwards. */
    private List<JsonNode> advanceAll(long season, int turns) throws Exception {
        List<JsonNode> posts = new ArrayList<>();
        for (int t = 0; t < turns; t++) {
            MockHttpServletResponse r = advance(mvc, season, t);
            assertThat(r.getStatus()).isEqualTo(201);
            posts.add(json(r));
        }
        return posts;
    }

    @Test
    void every_turn_shows_the_values_of_its_own_month_and_the_advance_answers_the_same() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        List<JsonNode> posts = advanceAll(season, 3);
        Set<String> seen = new HashSet<>();
        for (int turn = 1; turn <= 3; turn++) {
            JsonNode get = json(getTurn(mvc, season, turn));
            assertThat(get.get("macro")).as("lookup turn " + turn).isEqualTo(MacroSupport.expected(jdbc, season, turn));
            assertThat(posts.get(turn - 1).get("macro")).as("advance turn " + turn).isEqualTo(get.get("macro"));
            seen.add(get.get("macro").toString());
        }
        assertThat(seen).as("the fixture gives each month different values").hasSize(3);
    }

    @Test
    void two_seasons_each_show_the_values_of_their_own_path() throws Exception {
        long first = advancedSeason(3);
        long second = advancedSeason(3);
        for (long season : new long[] {first, second}) {
            for (int turn = 1; turn <= 3; turn++) {
                assertThat(json(getTurn(mvc, season, turn)).get("macro")).as("season " + season + " turn " + turn)
                        .isEqualTo(MacroSupport.expected(jdbc, season, turn));
            }
        }
    }

    @Test
    void the_values_are_json_strings_with_eight_and_four_decimal_places_and_the_same_for_a_finished_and_an_abandoned_season()
            throws Exception {
        long finished = advancedSeason(12);
        long abandoned = advancedSeason(2);
        assertThat(mvc.post().uri("/api/v1/seasons/" + abandoned + "/abandon").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTurnNo\": 2}").exchange().getResponse().getStatus()).isEqualTo(200);
        for (long season : new long[] {finished, abandoned}) {
            int last = season == finished ? 12 : 2;
            MockHttpServletResponse response = getTurn(mvc, season, last);
            assertThat(response.getStatus()).isEqualTo(200);
            String body = response.getContentAsString();
            assertThat(json(response).get("macro")).isEqualTo(MacroSupport.expected(jdbc, season, last));
            assertThat(EIGHT.matcher(body).results().count()).as("four axes, each with a string strength").isEqualTo(4);
            assertThat(FOUR.matcher(body).results().count()).as("four probabilities as strings").isEqualTo(4);
            JsonNode macro = json(response).get("macro");
            for (String axis : MacroSupport.AXES) {
                assertThat(macro.get("axes").get(axis).get("direction").asString()).isIn("UP", "DOWN", "NEUTRAL");
            }
        }
    }

    @Test
    void a_month_without_values_gives_an_all_null_macro_and_the_turn_still_advances() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        jdbc.update("""
                UPDATE month_block SET rate_dir = NULL, growth_dir = NULL, fx_dir = NULL, sentiment_dir = NULL,
                    rate_strength = NULL, growth_strength = NULL, fx_strength = NULL, sentiment_strength = NULL,
                    prob_p1 = NULL, prob_p2 = NULL, prob_p3 = NULL, prob_p4 = NULL, regime_label = NULL, indicator_z = NULL
                WHERE id = (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 1)""", season);
        MockHttpServletResponse first = advance(mvc, season, 0);
        assertThat(first.getStatus()).isEqualTo(201);
        String expected = "{\"axes\":{\"rate\":null,\"growth\":null,\"fx\":null,\"sentiment\":null},\"probabilities\":null}";
        assertThat(json(first).get("macro")).isEqualTo(tree(expected));
        assertThat(json(getTurn(mvc, season, 1)).get("macro")).isEqualTo(tree(expected));
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
    }

    @Test
    void a_missing_axis_is_null_and_never_neutral_or_zero_while_the_other_parts_stay() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        jdbc.update("""
                UPDATE month_block SET fx_dir = NULL, fx_strength = NULL
                WHERE id = (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 1)""", season);
        MockHttpServletResponse response = advance(mvc, season, 0);
        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode macro = json(response).get("macro");
        assertThat(macro.get("axes").get("fx").isNull()).isTrue();
        for (String axis : new String[] {"rate", "growth", "sentiment"}) {
            assertThat(macro.get("axes").get(axis).get("direction").asString()).isNotBlank();
        }
        assertThat(macro.get("probabilities").get("p1").asString()).matches("\\d\\.\\d{4}");
        assertThat(macro.toString()).doesNotContain("\"fx\":{");
        assertThat(macro).isEqualTo(MacroSupport.expected(jdbc, season, 1));
    }

    @Test
    void probabilities_may_be_missing_while_the_axes_are_present() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        jdbc.update("""
                UPDATE month_block SET prob_p1 = NULL, prob_p2 = NULL, prob_p3 = NULL, prob_p4 = NULL, regime_label = NULL
                WHERE id = (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 1)""", season);
        JsonNode macro = json(advance(mvc, season, 0)).get("macro");
        assertThat(macro.get("probabilities").isNull()).isTrue();
        assertThat(macro.get("axes").get("rate").get("strength").asString()).matches("\\d+\\.\\d{8}");
    }

    @Test
    void a_played_turn_whose_path_row_is_missing_is_a_server_error_not_no_data() throws Exception {
        long season = advancedSeason(2);
        assertThat(jdbc.update("DELETE FROM season_path WHERE season_id = ? AND turn_no = 2", season)).isEqualTo(1);
        MockHttpServletResponse response = getTurn(mvc, season, 2);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).doesNotContain("macro");
        assertThat(getTurn(mvc, season, 1).getStatus()).isEqualTo(200);
    }

    private static JsonNode tree(String text) {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(text);
    }
}
