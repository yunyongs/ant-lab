package antlab.api.review;

import static antlab.api.turn.TurnApiSupport.startSeason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Review API test helpers. A season's stored path is replaced by SQL so that turn 1 is the fixture month with the fixture
 * event and turn 2 the fixture month without one; the other turns cycle over the synthetic replay months.
 */
final class ReviewApiSupport {

    static final String[] SCRIPTS = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
            "/fixtures/replay_months.sql"};

    /** Every text the fixture stores in a reveal-only column. None may appear in a rejected response. */
    static final List<String> FIXTURE_TEXTS = List.of("가짜 사건 제목", "가짜 출처", "가짜 사후 설명", "가짜 사건 요약", "TARIFF");

    /** The oracle: path joined to month and events straight from the tables, event_key order. */
    private static final String ORACLE = """
            SELECT sp.turn_no, to_char(mb.month_start, 'YYYY-MM') AS year_month, mb.event_summary,
                   me.category, me.event_type, me.phase, me.title, me.hindsight, me.source
            FROM season_path sp
            JOIN month_block mb ON mb.id = sp.month_block_id
            LEFT JOIN month_event me ON me.month_block_id = mb.id
            WHERE sp.season_id = ?
            ORDER BY sp.turn_no, me.event_key
            """;

    private ReviewApiSupport() {
    }

    static MockHttpServletResponse getReview(MockMvcTester mvc, String seasonId) {
        return mvc.get().uri("/api/v1/seasons/" + seasonId + "/review").exchange().getResponse();
    }

    static MockHttpServletResponse getReview(MockMvcTester mvc, long seasonId) {
        return getReview(mvc, Long.toString(seasonId));
    }

    /** An IN_PROGRESS season of {@code turns} turns whose stored path is the one described in the class comment. */
    static long seasonWithFixturePath(MockMvcTester mvc, JdbcTemplate jdbc, int turns) throws Exception {
        long season = startSeason(mvc);
        List<Long> blocks = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM month_block WHERE month_start IN (DATE '2001-02-01', DATE '2001-03-01') ORDER BY month_start",
                Long.class));
        blocks.addAll(jdbc.queryForList("SELECT id FROM month_block WHERE month_start >= DATE '2007-01-01' "
                + "AND rate_dir IS NULL AND events_reviewed = FALSE ORDER BY month_start", Long.class));
        jdbc.update("DELETE FROM season_path WHERE season_id = ?", season);
        for (int t = 1; t <= turns; t++) {
            jdbc.update("INSERT INTO season_path (season_id, turn_no, month_block_id) VALUES (?, ?, ?)", season, t,
                    blocks.get((t - 1) % blocks.size()));
        }
        jdbc.update("UPDATE season SET path_length = ? WHERE id = ?", turns, season);
        return season;
    }

    static long finishedSeason(MockMvcTester mvc, JdbcTemplate jdbc, int turns) throws Exception {
        long season = seasonWithFixturePath(mvc, jdbc, turns);
        finish(jdbc, season);
        return season;
    }

    static void finish(JdbcTemplate jdbc, long season) {
        jdbc.update("UPDATE season SET current_turn_no = path_length, status = 'FINISHED' WHERE id = ?", season);
    }

    /** Oracle rows grouped by turn: {@code turn_no -> [event row or all-null row]}, in turn order. */
    static Map<Integer, List<Map<String, Object>>> oracle(JdbcTemplate jdbc, long season) {
        Map<Integer, List<Map<String, Object>>> byTurn = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(ORACLE, season)) {
            byTurn.computeIfAbsent(((Number) row.get("turn_no")).intValue(), t -> new ArrayList<>()).add(row);
        }
        return byTurn;
    }
}
