package antlab.api.review;

import static antlab.api.turn.TurnApiSupport.assetId;
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
        long asset = assetId(jdbc, "000660");
        Long eventId = jdbc.queryForObject("SELECT id FROM month_event WHERE event_key = 'FAKE_EVENT_01'", Long.class);
        jdbc.update("INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, template_id, template_vars, direction, strength, "
                + "repeat_count, body) VALUES (?, 1, 1, 'RISE', ?, 'SECRET_TEMPLATE', '{\"secretVar\": 1}', 'UP', 2, 1, '상승 문장')",
                season, asset);
        jdbc.update("INSERT INTO turn_feed (season_id, turn_no, seq, kind, month_event_id, category_hidden, template_id, body) "
                + "VALUES (?, 1, 2, 'EVENT', ?, TRUE, 'SECRET_TEMPLATE', '사건 문장')", season, eventId);
        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(keys(body)).containsExactly("seasonStatus", "pathLength", "abandonedTurnNo", "seed", "engineVersion",
                "dataSnapshotDate", "turns", "formationChanges");
        for (JsonNode turn : body.get("turns")) {
            assertThat(keys(turn)).containsExactly("turnNo", "yearMonth", "eventSummary", "events", "feed");
            for (JsonNode event : turn.get("events")) {
                assertThat(keys(event)).containsExactly("category", "eventType", "phase", "title", "hindsight", "source");
            }
        }
        JsonNode feed = body.get("turns").get(0).get("feed");
        assertThat(feed.size()).isEqualTo(2);
        for (JsonNode item : feed) {
            assertThat(keys(item)).containsExactly("seq", "kind", "signal", "assetId", "groupId", "body", "event", "ordersFromItem");
        }
        assertThat(keys(feed.get(1).get("event"))).containsExactly("category", "eventType", "phase", "title", "hindsight", "source");
        assertThat(r.getContentAsString()).doesNotContain("templateId", "templateVars", "SECRET_TEMPLATE", "secretVar", "direction",
                "strength", "repeatCount", "monthEventId", "categoryHidden", "eventKey");
        assertThat(keys(body.get("formationChanges").get(0))).containsExactly("turnNo", "before", "after", "reason");
        assertThat(r.getContentAsString()).doesNotContain("monthBlockId", "eventKey", "baseDate", "memberId", "createdAt",
                "\"id\"", "version");
    }
}
