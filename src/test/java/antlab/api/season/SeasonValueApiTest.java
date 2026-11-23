package antlab.api.season;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * WEB-16: the season list and detail carry the current turn's stored total and the total return to it. Null at turn 0;
 * from turn 1 the value equals the turn row and the turn lookup, and the return is computed once from it (checked
 * against PostgreSQL's own rounding).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonValueApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private JsonNode get(String uri) throws Exception {
        MockHttpServletResponse r = mvc.get().uri(uri).exchange().getResponse();
        assertThat(r.getStatus()).as(uri).isEqualTo(200);
        return json(r);
    }

    private JsonNode listed(long seasonId) throws Exception {
        for (JsonNode item : get("/api/v1/seasons").get("items")) {
            if (item.get("id").asLong() == seasonId) {
                return item;
            }
        }
        throw new AssertionError("season not listed: " + seasonId);
    }

    @Test
    void atTurnZeroValueAndReturnAreNullInTheListAndTheDetail() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);

        for (JsonNode dto : new JsonNode[] {listed(season), get("/api/v1/seasons/" + season)}) {
            assertThat(dto.has("latestTotalValue")).isTrue();
            assertThat(dto.get("latestTotalValue").isNull()).isTrue();
            assertThat(dto.has("totalReturn")).isTrue();
            assertThat(dto.get("totalReturn").isNull()).isTrue();
        }
    }

    @Test
    void fromTurnOneTheValueIsTheCurrentTurnRowAndTheReturnIsRoundedOnce() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);

        Map<String, Object> expected = jdbc.queryForMap("""
                SELECT t.total_value, round((t.total_value - s.initial_cash) / s.initial_cash, 8) AS total_return
                FROM turn t JOIN season s ON s.id = t.season_id WHERE t.season_id = ? AND t.turn_no = 2
                """, season);
        String value = ((BigDecimal) expected.get("total_value")).setScale(0).toPlainString();
        String totalReturn = ((BigDecimal) expected.get("total_return")).toPlainString();

        JsonNode turn = get("/api/v1/seasons/" + season + "/turns/2");
        assertThat(turn.get("totalValue").asString()).isEqualTo(value);
        for (JsonNode dto : new JsonNode[] {listed(season), get("/api/v1/seasons/" + season)}) {
            assertThat(dto.get("currentTurnNo").asInt()).isEqualTo(2);
            assertThat(dto.get("latestTotalValue").asString()).isEqualTo(value);
            assertThat(dto.get("totalReturn").asString()).isEqualTo(totalReturn).matches("-?\\d+\\.\\d{8}");
        }
    }

    @Test
    void eachListedSeasonCarriesItsOwnCurrentTurn() throws Exception {
        long played = startSeason(mvc);
        buyTeam(mvc, jdbc, played);
        assertThat(advance(mvc, played, 0).getStatus()).isEqualTo(201);
        long fresh = startSeason(mvc);

        String turnOne = jdbc.queryForObject("SELECT total_value FROM turn WHERE season_id = ? AND turn_no = 1",
                BigDecimal.class, played).setScale(0).toPlainString();
        assertThat(listed(played).get("latestTotalValue").asString()).isEqualTo(turnOne);
        assertThat(listed(fresh).get("latestTotalValue").isNull()).isTrue();
        assertThat(listed(fresh).get("totalReturn").isNull()).isTrue();
    }
}
