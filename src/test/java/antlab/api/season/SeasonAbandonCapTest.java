package antlab.api.season;

import static antlab.api.season.AbandonSupport.abandon;
import static antlab.api.season.AbandonSupport.count;
import static antlab.api.season.AbandonSupport.currentTurn;
import static antlab.api.season.AbandonSupport.json;
import static antlab.api.season.AbandonSupport.noWriteCounts;
import static antlab.api.season.AbandonSupport.position;
import static antlab.api.season.AbandonSupport.status;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.asset.Position;
import antlab.common.error.DomainException;
import antlab.season.LineupChangeService;
import antlab.support.IntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * MVP-14 per-request cap: with two auto turns per request an abandon of a 12-turn season takes several requests, and
 * between them the season really is ABANDONING. That state refuses every other write with SEASON_NOT_ACTIVE.
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.abandon.max-turns-per-request=2")
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAbandonCapTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LineupChangeService lineupChanges;

    private MockHttpServletResponse post(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
    }

    private Map<String, Object> state(long season) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String table : new String[] {"turn", "turn_price", "turn_feed", "reconciliation_log", "holding", "cash_ledger",
                "lineup", "orders", "formation_change"}) {
            out.put(table, count(jdbc, table, season));
        }
        out.put("season", jdbc.queryForObject("SELECT status || ':' || current_turn_no || ':' || version FROM season"
                + " WHERE id = ?", String.class, season));
        return out;
    }

    @Test
    void theCapStopsTheRequestAndRepeatsFinishTheSeason() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);

        JsonNode first = json(abandon(mvc, season, 1));
        assertThat(first.get("status").asString()).isEqualTo("ABANDONING");
        assertThat(first.get("advancedTurns").asInt()).isEqualTo(2);
        assertThat(first.get("currentTurnNo").asInt()).isEqualTo(3);
        assertThat(first.get("remainingTurns").asInt()).isEqualTo(9);
        assertThat(first.get("abandonedTurnNo").asInt()).isEqualTo(1);

        int rounds = 0;
        JsonNode last = first;
        while (!last.get("status").asString().equals("FINISHED")) {
            last = json(abandon(mvc, season, 0));
            assertThat(last.get("advancedTurns").asInt()).isLessThanOrEqualTo(2);
            assertThat(++rounds).isLessThan(10);
        }
        assertThat(rounds).isEqualTo(5);
        assertThat(count(jdbc, "turn", season)).isEqualTo(12L);
        assertThat(jdbc.queryForObject("SELECT abandoned_turn_no FROM season WHERE id = ?", Integer.class, season)).isEqualTo(1);
    }

    @Test
    void anAbandoningSeasonRefusesOrdersFormationLineupAndItsOwnTurnRequest() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        assertThat(abandon(mvc, season, 2).getStatus()).isEqualTo(200);
        assertThat(status(jdbc, season)).isEqualTo("ABANDONING");
        int turn = currentTurn(jdbc, season);
        Map<String, Object> before = state(season);
        String positionBefore = position(jdbc, season);
        Map<String, Long> writesBefore = noWriteCounts(jdbc, season);

        String buy = "{\"expectedTurnNo\": " + turn + ", \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, "069500")
                + ", \"quantity\": 1, \"declaredPosition\": \"MIDFIELDER\"}";
        String sell = "{\"expectedTurnNo\": " + turn + ", \"side\": \"SELL\", \"assetId\": " + assetId(jdbc, "000660")
                + ", \"quantity\": 1}";
        String formation = "{\"expectedTurnNo\": " + turn + ", \"formation\": {\"goalkeeper\": 20, \"defender\": 30,"
                + " \"midfielder\": 30, \"forward\": 20}}";
        for (MockHttpServletResponse r : new MockHttpServletResponse[] {
                post("/api/v1/seasons/" + season + "/orders", buy), post("/api/v1/seasons/" + season + "/orders", sell),
                post("/api/v1/seasons/" + season + "/formation-changes", formation), advance(mvc, season, turn)}) {
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("SEASON_NOT_ACTIVE");
        }
        assertThatThrownBy(() -> lineupChanges.change(season, turn, assetId(jdbc, "000660"), Position.MIDFIELDER))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode().name()).isEqualTo("SEASON_NOT_ACTIVE"));

        assertThat(state(season)).as("every refused write leaves the season as it was").isEqualTo(before);
        assertThat(position(jdbc, season)).isEqualTo(positionBefore);
        assertThat(noWriteCounts(jdbc, season)).isEqualTo(writesBefore);
    }
}
