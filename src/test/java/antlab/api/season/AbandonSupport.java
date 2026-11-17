package antlab.api.season;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers of the abandon tests (MVP-14). */
final class AbandonSupport {

    static final String[] SCRIPTS = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
            "/fixtures/replay_months.sql", "/fixtures/turn_returns.sql"};

    /** Tables an abandon must never write to after the mark (auto-hold: no trades, no formation or lineup change). */
    static final List<String> NO_WRITE_TABLES = List.of("orders", "execution", "cash_ledger", "formation_change",
            "lineup");

    private AbandonSupport() {
    }

    static MockHttpServletResponse abandonRaw(MockMvcTester mvc, long seasonId, String body) {
        return mvc.post().uri("/api/v1/seasons/" + seasonId + "/abandon").contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange().getResponse();
    }

    static MockHttpServletResponse abandon(MockMvcTester mvc, long seasonId, int expectedTurnNo) {
        return abandonRaw(mvc, seasonId, "{\"expectedTurnNo\": " + expectedTurnNo + "}");
    }

    static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    static long count(JdbcTemplate jdbc, String table, long season) {
        if (table.equals("execution")) {
            return jdbc.queryForObject("SELECT count(*) FROM execution WHERE order_id IN (SELECT id FROM orders"
                    + " WHERE season_id = ?)", Long.class, season);
        }
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id = ?", Long.class, season);
    }

    static String status(JdbcTemplate jdbc, long season) {
        return jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season);
    }

    static int currentTurn(JdbcTemplate jdbc, long season) {
        return jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season);
    }

    static Integer abandonedTurn(JdbcTemplate jdbc, long season) {
        return jdbc.queryForObject("SELECT abandoned_turn_no FROM season WHERE id = ?", Integer.class, season);
    }

    /** Row counts of the tables an abandon must not write to. */
    static Map<String, Long> noWriteCounts(JdbcTemplate jdbc, long season) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : NO_WRITE_TABLES) {
            out.put(table, count(jdbc, table, season));
        }
        return out;
    }

    /** Holdings, cash and open lineup intervals, as one comparable text. */
    static String position(JdbcTemplate jdbc, long season) {
        String holdings = String.join(",", jdbc.queryForList(
                "SELECT asset_id || ':' || quantity FROM holding WHERE season_id = ? ORDER BY asset_id", String.class, season));
        String lineup = String.join(",", jdbc.queryForList(
                "SELECT asset_id || ':' || declared_position || ':' || start_turn FROM lineup WHERE season_id = ?"
                        + " AND end_turn IS NULL ORDER BY asset_id", String.class, season));
        String cash = jdbc.queryForObject("SELECT balance::text FROM cash_account WHERE season_id = ?", String.class, season);
        return holdings + "|" + cash + "|" + lineup;
    }
}
