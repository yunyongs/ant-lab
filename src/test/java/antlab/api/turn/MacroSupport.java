package antlab.api.turn;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared tools of the macro tests: the expected {@code macro} subtree is read by SQL, not by the code under test. */
public final class MacroSupport {

    public static final String[] AXES = {"rate", "growth", "fx", "sentiment"};

    private MacroSupport() {
    }

    /** The scripts the macro tests run first: the usual fixtures, then distinct regime values on every fake month. */
    static final String[] SCRIPTS = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
            "/fixtures/replay_months.sql", "/fixtures/turn_returns.sql", "/fixtures/replay_macro.sql"};

    static final String[] CLEANUP = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"};

    /** The stored regime values of the month that {@code season_path} gives for (season, turn), as the response must show them. */
    public static ObjectNode expected(JdbcTemplate jdbc, long season, int turn) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT mb.rate_dir, mb.rate_strength::text AS rate_strength, mb.growth_dir, mb.growth_strength::text AS growth_strength,
                       mb.fx_dir, mb.fx_strength::text AS fx_strength, mb.sentiment_dir, mb.sentiment_strength::text AS sentiment_strength,
                       mb.prob_p1::text AS p1, mb.prob_p2::text AS p2, mb.prob_p3::text AS p3, mb.prob_p4::text AS p4
                FROM season_path sp JOIN month_block mb ON mb.id = sp.month_block_id
                WHERE sp.season_id = ? AND sp.turn_no = ?""", season, turn);
        ObjectNode macro = JsonMapper.shared().createObjectNode();
        ObjectNode axes = macro.putObject("axes");
        for (String axis : AXES) {
            Object direction = row.get(axis + "_dir");
            if (direction == null) {
                axes.putNull(axis);
            } else {
                axes.putObject(axis).put("direction", direction.toString()).put("strength", row.get(axis + "_strength").toString());
            }
        }
        if (row.get("p1") == null) {
            macro.putNull("probabilities");
        } else {
            macro.putObject("probabilities").put("p1", row.get("p1").toString()).put("p2", row.get("p2").toString())
                    .put("p3", row.get("p3").toString()).put("p4", row.get("p4").toString());
        }
        return macro;
    }

    /** Month ids of the path of a season, in turn order. */
    public static List<Long> pathBlocks(JdbcTemplate jdbc, long season) {
        return jdbc.queryForList("SELECT month_block_id FROM season_path WHERE season_id = ? ORDER BY turn_no", Long.class, season);
    }
}
