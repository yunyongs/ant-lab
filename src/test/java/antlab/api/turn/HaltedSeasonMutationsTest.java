package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * A halted season keeps refusing writes with the existing SEASON_NOT_ACTIVE (orders, formation changes) and still counts
 * toward the three-season limit. The order and formation paths are deliberately not edited by MVP-15.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class HaltedSeasonMutationsTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private MockHttpServletResponse post(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
    }

    @Test
    void buySellAndFormationChangeOnAHaltedSeasonAre409SeasonNotActiveAndWriteNothing() throws Exception {
        long season = startSeason(mvc);
        TurnApiSupport.buy(mvc, jdbc, season, 0, "000660", 10, "FORWARD_BREAKOUT");
        jdbc.update("UPDATE season SET status = 'HALTED' WHERE id = ?", season);
        Map<String, Object> before = TurnApiSupport.state(jdbc, season);

        String buy = "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, "069500")
                + ", \"quantity\": 1, \"declaredPosition\": \"MIDFIELDER\"}";
        String sell = "{\"expectedTurnNo\": 0, \"side\": \"SELL\", \"assetId\": " + assetId(jdbc, "000660") + ", \"quantity\": 1}";
        String formation = "{\"expectedTurnNo\": 0, \"formation\": {\"goalkeeper\": 20, \"defender\": 30,"
                + " \"midfielder\": 30, \"forward\": 20}}";
        for (MockHttpServletResponse r : new MockHttpServletResponse[] {
                post("/api/v1/seasons/" + season + "/orders", buy), post("/api/v1/seasons/" + season + "/orders", sell),
                post("/api/v1/seasons/" + season + "/formation-changes", formation)}) {
            assertThat(r.getStatus()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("SEASON_NOT_ACTIVE");
        }
        assertThat(TurnApiSupport.state(jdbc, season)).isEqualTo(before);
    }

    @Test
    void haltedSeasonsCountTowardTheThreeSeasonLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            long id = startSeason(mvc);
            jdbc.update("UPDATE season SET status = 'HALTED' WHERE id = ?", id);
        }
        MockHttpServletResponse fourth = post("/api/v1/seasons", "{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10,"
                + " \"defender\": 30, \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}");
        assertThat(fourth.getStatus()).isEqualTo(409);
        assertThat(code(fourth)).isEqualTo("SEASON_LIMIT");
    }
}
