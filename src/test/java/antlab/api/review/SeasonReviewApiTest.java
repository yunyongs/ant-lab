package antlab.api.review;

import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** The review of a FINISHED season equals an independent SQL oracle, field by field. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReviewApiTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String text(JsonNode node) {
        return node.isNull() ? null : node.asString();
    }

    @Test
    void 끝난_시즌은_턴마다_실제_연월과_사건을_준다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        jdbc.update("INSERT INTO formation_change (season_id, turn_no, before_goalkeeper, before_defender, "
                + "before_midfielder, before_forward, after_goalkeeper, after_defender, after_midfielder, after_forward, "
                + "reason) VALUES (?, 3, 10, 30, 30, 30, 10, 20, 40, 30, '금리 상승')", season);
        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getContentType()).startsWith("application/json");
        JsonNode body = json(r);

        Map<String, Object> s = jdbc.queryForMap("SELECT status, path_length, abandoned_turn_no, seed::text AS seed, "
                + "engine_version, data_snapshot_date::text AS snapshot FROM season WHERE id = ?", season);
        assertThat(body.get("seasonStatus").asString()).isEqualTo(s.get("status"));
        assertThat(body.get("pathLength").asInt()).isEqualTo(((Number) s.get("path_length")).intValue());
        assertThat(body.get("abandonedTurnNo").isNull()).isTrue();
        assertThat(body.get("seed").isString()).as("seed is a string").isTrue();
        assertThat(body.get("seed").asString()).isEqualTo(s.get("seed")).matches("^-?[0-9]{1,19}$");
        assertThat(body.get("engineVersion").asString()).isEqualTo(s.get("engine_version"));
        assertThat(body.get("dataSnapshotDate").asString()).isEqualTo(s.get("snapshot"));

        Map<Integer, List<Map<String, Object>>> oracle = ReviewApiSupport.oracle(jdbc, season);
        JsonNode turns = body.get("turns");
        assertThat(turns.size()).isEqualTo(12).isEqualTo(oracle.size());
        int expectedTurn = 1;
        int eventTurns = 0;
        for (JsonNode turn : turns) {
            assertThat(turn.get("turnNo").asInt()).as("no gap").isEqualTo(expectedTurn);
            List<Map<String, Object>> rows = oracle.get(expectedTurn);
            Map<String, Object> first = rows.get(0);
            assertThat(turn.get("yearMonth").asString()).isEqualTo(first.get("year_month"));
            assertThat(text(turn.get("eventSummary"))).isEqualTo(text(first.get("event_summary")));
            List<Map<String, Object>> eventRows = rows.stream().filter(row -> row.get("title") != null).toList();
            JsonNode events = turn.get("events");
            assertThat(events.size()).isEqualTo(eventRows.size());
            for (int i = 0; i < eventRows.size(); i++) {
                eventTurns++;
                Map<String, Object> row = eventRows.get(i);
                JsonNode e = events.get(i);
                assertThat(e.get("category").asString()).isEqualTo(row.get("category"));
                assertThat(e.get("eventType").asString()).isEqualTo(row.get("event_type"));
                assertThat(e.get("phase").asString()).isEqualTo(row.get("phase"));
                assertThat(e.get("title").asString()).isEqualTo(row.get("title"));
                assertThat(text(e.get("hindsight"))).isEqualTo(text(row.get("hindsight")));
                assertThat(e.get("source").asString()).isEqualTo(row.get("source"));
            }
            expectedTurn++;
        }
        assertThat(eventTurns).as("the event path is exercised").isPositive();
        assertThat(turns.get(0).get("yearMonth").asString()).isEqualTo("2001-02");
        assertThat(turns.get(0).get("eventSummary").asString()).isEqualTo("가짜 사건 요약");
        assertThat(turns.get(0).get("events").get(0).get("hindsight").asString()).isEqualTo("가짜 사후 설명");

        // Q1: formation changes ride along in the existing shape
        JsonNode changes = body.get("formationChanges");
        assertThat(changes.size()).isEqualTo(1);
        assertThat(changes.get(0).get("turnNo").asInt()).isEqualTo(3);
        assertThat(changes.get(0).get("before").get("defender").asInt()).isEqualTo(30);
        assertThat(changes.get(0).get("after").get("defender").asInt()).isEqualTo(20);
        assertThat(changes.get(0).get("reason").asString()).isEqualTo("금리 상승");
    }

    @Test
    void 사건_없는_달은_빈_목록이고_요약_없음은_null이며_키는_빠지지_않는다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        jdbc.update("UPDATE month_event SET hindsight = NULL WHERE event_key = 'FAKE_EVENT_01'");
        JsonNode body = json(ReviewApiSupport.getReview(mvc, season));

        JsonNode withoutEvent = body.get("turns").get(1);
        assertThat(withoutEvent.get("yearMonth").asString()).isEqualTo("2001-03");
        assertThat(withoutEvent.has("eventSummary")).isTrue();
        assertThat(withoutEvent.get("eventSummary").isNull()).isTrue();
        assertThat(withoutEvent.get("events").isArray()).isTrue();
        assertThat(withoutEvent.get("events").size()).isZero();

        JsonNode event = body.get("turns").get(0).get("events").get(0);
        assertThat(event.has("hindsight")).isTrue();
        assertThat(event.get("hindsight").isNull()).isTrue();
        assertThat(body.get("formationChanges").isArray()).isTrue();
        assertThat(body.get("formationChanges").size()).isZero();
    }

    @Test
    void 포기_표시가_있는_끝난_시즌도_200이고_포기_턴을_준다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        jdbc.update("UPDATE season SET abandoned_turn_no = 5 WHERE id = ?", season);
        MockHttpServletResponse r = ReviewApiSupport.getReview(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(json(r).get("abandonedTurnNo").asInt()).isEqualTo(5);
    }
}
