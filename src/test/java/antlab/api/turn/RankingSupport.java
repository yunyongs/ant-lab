package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Shared tools of the ranking tests: the request, and a season whose path blocks carry synthetic attention rows. */
public final class RankingSupport {

    private RankingSupport() {
    }

    public static MockHttpServletResponse getRanking(MockMvcTester mvc, long seasonId, String turnNo) {
        return mvc.get().uri("/api/v1/seasons/" + seasonId + "/turns/" + turnNo + "/ranking").exchange().getResponse();
    }

    public static MockHttpServletResponse getRanking(MockMvcTester mvc, long seasonId, int turnNo) {
        return getRanking(mvc, seasonId, Integer.toString(turnNo));
    }

    /**
     * One attention row per synthetic pool asset and path block; the index differs by asset and turn so the list has a clear order and
     * a tie. The fixture pool is synthetic, so the rows are removed with it by the cleanup script.
     */
    public static void seedAttention(JdbcTemplate jdbc, long seasonId) {
        jdbc.update("""
                INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak, attention_index)
                SELECT a.id, sp.month_block_id, 6, 1.00000000, 2,
                       round(((a.id * 7 + sp.turn_no * 3) % 4 + 1) / 10.0, 8)
                FROM season_path sp
                CROSS JOIN asset a
                WHERE sp.season_id = ? AND a.name LIKE '가짜%'
                ON CONFLICT (asset_id, month_block_id) DO NOTHING""", seasonId);
    }

    /** A 12-turn season with the team bought and {@code advances} turns played, so the current turn is {@code advances}. */
    public static long playedSeason(MockMvcTester mvc, JdbcTemplate jdbc, int advances) throws Exception {
        long season = startSeason(mvc);
        seedAttention(jdbc, season);
        buyTeam(mvc, jdbc, season);
        for (int turn = 0; turn < advances; turn++) {
            MockHttpServletResponse r = advance(mvc, season, turn);
            if (r.getStatus() != 201) {
                throw new IllegalStateException("advance " + turn + " failed: " + r.getStatus());
            }
        }
        return season;
    }
}
