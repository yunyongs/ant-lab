package antlab.api.lineup;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.ProblemBodyAssert;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The position-only change answer carries exactly the contract keys and no internal number, date or path. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class LineupChangeExposureTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private MockHttpServletResponse change(long season, String body) {
        return mvc.post().uri("/api/v1/seasons/" + season + "/lineup-changes").contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange().getResponse();
    }

    @Test
    void responseKeysEqualTheContractAndHideInternals() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        long asset = assetId(jdbc, "069500");

        for (String position : new String[] {"DEFENDER", "DEFENDER"}) {
            MockHttpServletResponse r = change(season, "{\"expectedTurnNo\": 1, \"assetId\": " + asset
                    + ", \"declaredPosition\": \"" + position + "\"}");
            assertThat(r.getStatus()).isEqualTo(200);
            assertThat(new HashSet<>(json(r).propertyNames())).containsExactlyInAnyOrder("assetId", "declaredPosition",
                    "startTurn", "changed");
            assertThat(r.getContentAsString()).doesNotContain("createdAt", "created_at", "seasonId", "endTurn", "id\":",
                    "seed", "path");
            assertThat(r.getContentAsString()).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
        }
    }

    @Test
    void aRejectedInputIsNotEchoedBack() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);

        MockHttpServletResponse r = change(season, "{\"expectedTurnNo\": 1, \"assetId\": " + assetId(jdbc, "069500")
                + ", \"declaredPosition\": \"SECRETPOSITIONWORD\"}");

        assertThat(r.getStatus()).isEqualTo(400);
        ProblemBodyAssert.assertAbsent(r.getContentAsString(), "SECRETPOSITIONWORD");
        ProblemBodyAssert.assertInstanceShape(r.getContentAsString(), "lineup-changes");
    }
}
