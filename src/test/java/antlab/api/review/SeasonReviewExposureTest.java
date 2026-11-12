package antlab.api.review;

import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** The review body has exactly the contract keys: no ids, no event key, no block number, no base date. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReviewExposureTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    @Test
    void 응답_키는_계약과_정확히_같다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        jdbc.update("INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, "
                + "before_midfielder, before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward, "
                + "reason) VALUES (?, 3, 10, 30, 30, 30, 10, 20, 40, 30, NULL)", season);
        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(keys(body)).containsExactly("seasonStatus", "pathLength", "abandonedTurnNo", "seed", "engineVersion",
                "dataSnapshotDate", "turns", "formationChanges");
        for (JsonNode turn : body.get("turns")) {
            assertThat(keys(turn)).containsExactly("turnNo", "yearMonth", "eventSummary", "events");
            for (JsonNode event : turn.get("events")) {
                assertThat(keys(event)).containsExactly("category", "eventType", "phase", "title", "hindsight", "source");
            }
        }
        assertThat(keys(body.get("formationChanges").get(0))).containsExactly("turnNo", "before", "after", "reason");
        assertThat(r.getContentAsString()).doesNotContain("monthBlockId", "eventKey", "baseDate", "memberId", "createdAt",
                "\"id\"", "version");
    }
}
