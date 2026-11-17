package antlab.api.order;

import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.code;
import static antlab.api.order.OrderApiSupport.json;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.state;
import static antlab.api.turn.RankingSupport.getRanking;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.RankingVisibility;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The RANKING origin of an order (MVP-13 PR 3): accepted only when the order's asset is on the ranking board of the current turn,
 * computed from the same rows as the board endpoint and from nothing later. The fixture season holds the four synthetic pool assets
 * plus one flat asset that moves only at turn 2, so it is off the board at turns 1 and 3 and on it at turn 2. Synthetic data only.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class RankingOriginApiTest {

    private static final String RANKING = "{\"kind\":\"RANKING\"}";
    private static final List<String> ASSET_LISTS = List.of("gainers", "losers", "attention", "risingStreaks", "fallingStreaks");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry registry;
    @Autowired
    RankingVisibility visibility;
    @Autowired
    SeasonRepository seasons;

    /** A played season (current turn = {@code advances}) and the id of its flat asset. */
    private record Played(long season, long flat) {
    }

    /**
     * Starts a season, adds an asset that does not move except at turn 2 (it has no attention row), buys the team at turn 0 and plays
     * {@code advances} turns.
     */
    private Played played(int advances) throws Exception {
        long season = startSeason(mvc);
        long flat = OrderApiSupport.addAsset(jdbc, season, "KR", "FLAT01", "100000");
        jdbc.update("""
                INSERT INTO asset_position (asset_id, base_date, base_price, base_fx_rate, window_start, window_end, months_used,
                                            up_participation, down_participation, volatility, max_drawdown, system_position)
                VALUES (?, DATE '2026-01-02', 100000.0000, 1.0000, DATE '2020-01-01', DATE '2025-12-01', 72,
                        1.00000000, 1.00000000, 0.05000000, -0.20000000, 'MIDFIELDER')""", flat);
        jdbc.update("""
                INSERT INTO asset_month_return (asset_id, month_block_id, local_return, return_kind)
                SELECT ?, mb.id, CASE WHEN mb.id = (SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = 2)
                                      THEN 0.05000000 ELSE 0 END, 'ACTUAL'
                FROM month_block mb
                WHERE mb.month_start BETWEEN DATE '2006-12-01' AND DATE '2026-01-01'
                  AND mb.rate_dir IS NULL AND mb.prob_p1 IS NULL AND mb.event_summary IS NULL AND mb.events_reviewed = TRUE""",
                flat, season);
        jdbc.update("""
                INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak, attention_index)
                SELECT a.id, sp.month_block_id, 6, 1.00000000, 2, round(((a.id * 7 + sp.turn_no * 3) % 4 + 1) / 10.0, 8)
                FROM season_path sp CROSS JOIN asset a
                WHERE sp.season_id = ? AND a.name LIKE '가짜%' AND a.id <> ?
                ON CONFLICT (asset_id, month_block_id) DO NOTHING""", season, flat);
        buyTeam(mvc, jdbc, season);
        for (int turn = 0; turn < advances; turn++) {
            assertThat(advance(mvc, season, turn).getStatus()).as("advance " + turn).isEqualTo(201);
        }
        return new Played(season, flat);
    }

    private MockHttpServletResponse order(long season, int turn, String side, long asset, int qty, String position, String source) {
        String base = body(turn, side, asset, qty, position);
        return place(mvc, season, source == null ? base : base.substring(0, base.length() - 1) + ", \"source\": " + source + "}");
    }

    private Map<String, Object> lastOrder(long season) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE season_id = ? ORDER BY id DESC LIMIT 1", season);
    }

    private Set<Long> boardAssets(long season, int turn) throws Exception {
        JsonNode board = json(getRanking(mvc, season, turn));
        Set<Long> ids = new HashSet<>();
        for (String list : ASSET_LISTS) {
            board.get(list).forEach(row -> ids.add(row.get("assetId").asLong()));
        }
        return ids;
    }

    private double rejectedCount() {
        return registry.find("antlab.order.rejected").tags("code", "SOURCE_NOT_VISIBLE").counter().count();
    }

    /** The order is refused with 422 SOURCE_NOT_VISIBLE and nothing is written, not even an identity value. */
    private MockHttpServletResponse refused(long season, int turn, String side, long asset, int qty, String position) throws Exception {
        Map<String, Object> before = state(jdbc, season);
        double counted = rejectedCount();
        MockHttpServletResponse r = order(season, turn, side, asset, qty, position, RANKING);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(422);
        assertThat(code(r)).isEqualTo("SOURCE_NOT_VISIBLE");
        assertThat(state(jdbc, season)).as("a refused origin writes nothing").isEqualTo(before);
        assertThat(rejectedCount()).isEqualTo(counted + 1);
        return r;
    }

    // ---- R1: on the current board ----

    @Test
    void R1_an_asset_on_the_current_board_is_accepted_and_stored_as_RANKING() throws Exception {
        Played p = played(1);
        Set<Long> board = boardAssets(p.season(), 1);
        assertThat(board).as("the fixture board is not empty").isNotEmpty().doesNotContain(p.flat());
        long onBoard = board.iterator().next();
        // a held asset: a second buy needs no declared position
        MockHttpServletResponse r = order(p.season(), 1, "BUY", onBoard, 1, null, RANKING);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        assertThat(lastOrder(p.season())).containsEntry("source", "RANKING").containsEntry("turn_no", 1)
                .containsEntry("side", "BUY").containsEntry("turn_feed_id", null);
        // the response does not say where the order started
        assertThat(json(r).has("source")).isFalse();
    }

    @Test
    void R1_sells_start_from_the_board_too() throws Exception {
        Played p = played(1);
        long held = boardAssets(p.season(), 1).iterator().next();
        MockHttpServletResponse r = order(p.season(), 1, "SELL", held, 1, null, RANKING);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        assertThat(lastOrder(p.season())).containsEntry("source", "RANKING").containsEntry("side", "SELL");
    }

    // ---- R2 to R4: not on the board ----

    @Test
    void R2_a_pool_asset_that_is_not_on_the_board_is_refused_and_nothing_is_written() throws Exception {
        Played p = played(1);
        assertThat(boardAssets(p.season(), 1)).doesNotContain(p.flat());
        refused(p.season(), 1, "BUY", p.flat(), 1, "MIDFIELDER");
        // the same asset is fine as a direct order, so the refusal is about the origin only
        assertThat(order(p.season(), 1, "BUY", p.flat(), 1, "MIDFIELDER", null).getStatus()).isEqualTo(201);
        assertThat(lastOrder(p.season())).containsEntry("source", "DIRECT");
    }

    @Test
    void R2_the_refusal_is_the_same_for_a_board_miss_and_an_unknown_reference_kind() throws Exception {
        Played p = played(1);
        MockHttpServletResponse miss = refused(p.season(), 1, "BUY", p.flat(), 1, "MIDFIELDER");
        // a feed reference that does not exist answers with the very same body, so the route does not tell the two apart
        MockHttpServletResponse ghost = order(p.season(), 1, "BUY", p.flat(), 1, "MIDFIELDER",
                "{\"kind\":\"FEED\",\"feedTurnNo\":1,\"feedSeq\":9999}");
        assertThat(ghost.getStatus()).isEqualTo(miss.getStatus());
        assertThat(json(ghost).get("code")).isEqualTo(json(miss).get("code"));
        assertThat(json(ghost).get("detail")).isEqualTo(json(miss).get("detail"));
        assertThat(json(ghost).get("title")).isEqualTo(json(miss).get("title"));
    }

    @Test
    void R3_an_asset_that_enters_the_board_only_at_the_next_turn_is_refused_now_and_accepted_then() throws Exception {
        Played p = played(1);
        // at turn 1 the flat asset has not moved; at turn 2 it is the top gainer
        refused(p.season(), 1, "BUY", p.flat(), 1, "MIDFIELDER");
        assertThat(advance(mvc, p.season(), 1).getStatus()).isEqualTo(201);
        assertThat(boardAssets(p.season(), 2)).contains(p.flat());
        MockHttpServletResponse r = order(p.season(), 2, "BUY", p.flat(), 1, "MIDFIELDER", RANKING);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        assertThat(lastOrder(p.season())).containsEntry("source", "RANKING").containsEntry("turn_no", 2);
    }

    @Test
    void R4_the_board_of_an_earlier_turn_does_not_count() throws Exception {
        Played p = played(3);
        assertThat(boardAssets(p.season(), 2)).as("on the board at turn 2").contains(p.flat());
        assertThat(boardAssets(p.season(), 3)).as("off the board at turn 3").doesNotContain(p.flat());
        refused(p.season(), 3, "BUY", p.flat(), 1, "MIDFIELDER");
    }

    // ---- R5: turn 0 ----

    @Test
    void R5_turn_0_has_no_board_so_RANKING_is_refused() throws Exception {
        long season = startSeason(mvc);
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = order(season, 0, "BUY", assetId(jdbc, "000660"), 1, "FORWARD_BREAKOUT", RANKING);
        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(code(r)).isEqualTo("SOURCE_NOT_VISIBLE");
        assertThat(state(jdbc, season)).isEqualTo(before);
    }

    // ---- the port answers what the endpoint shows ----

    @Test
    void the_port_matches_the_endpoint_for_the_current_turn_and_refuses_every_other() throws Exception {
        Played p = played(3);
        Season season = seasons.findById(p.season()).orElseThrow();
        List<Long> pool = jdbc.queryForList("SELECT DISTINCT asset_id FROM turn_price WHERE season_id = ? ORDER BY asset_id",
                Long.class, p.season());
        assertThat(pool).hasSizeGreaterThanOrEqualTo(5);
        List<String> mismatches = new ArrayList<>();
        int onBoardCount = 0;
        for (int turn = 1; turn <= 3; turn++) {
            Set<Long> shown = boardAssets(p.season(), turn);
            for (long asset : pool) {
                boolean answer = visibility.onBoard(season, turn, asset);
                onBoardCount += answer ? 1 : 0;
                // the port answers for the current turn (3) only; earlier boards are still shown by the endpoint
                if (answer != (turn == 3 && shown.contains(asset))) {
                    mismatches.add("turn " + turn + " asset " + asset);
                }
            }
        }
        assertThat(mismatches).isEmpty();
        assertThat(onBoardCount).as("the check is not vacuous").isPositive();
        assertThat(visibility.onBoard(season, 0, pool.get(0))).as("no board at turn 0").isFalse();
        // only the current turn counts, whichever turn the caller passes
        for (long asset : pool) {
            assertThat(visibility.onBoard(season, 2, asset)).as("a past turn, asset " + asset).isFalse();
            assertThat(visibility.onBoard(season, 4, asset)).as("a future turn, asset " + asset).isFalse();
        }
        assertThat(visibility.onBoard(season, 1, Long.MAX_VALUE)).as("an unknown asset is on no board").isFalse();
    }

    @Test
    void an_asset_that_is_on_the_attention_list_only_is_on_the_board() throws Exception {
        Played p = played(3);
        // turn 3 left the flat asset off every list; give it the top attention of that turn's block
        jdbc.update("""
                INSERT INTO asset_month_attention (asset_id, month_block_id, window_months, attention_z, streak, attention_index)
                SELECT ?, sp.month_block_id, 6, 1.00000000, 2, 0.99000000
                FROM season_path sp WHERE sp.season_id = ? AND sp.turn_no = 3""", p.flat(), p.season());
        JsonNode board = json(getRanking(mvc, p.season(), 3));
        for (String list : List.of("gainers", "losers", "risingStreaks", "fallingStreaks")) {
            board.get(list).forEach(row -> assertThat(row.get("assetId").asLong()).as(list).isNotEqualTo(p.flat()));
        }
        assertThat(board.get("attention")).anyMatch(row -> row.get("assetId").asLong() == p.flat());
        MockHttpServletResponse r = order(p.season(), 3, "BUY", p.flat(), 1, "MIDFIELDER", RANKING);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        assertThat(lastOrder(p.season())).containsEntry("source", "RANKING");
    }

    // ---- cost ----

    @Test
    void a_DIRECT_order_costs_its_known_statements_and_a_RANKING_order_adds_the_four_board_statements() throws Exception {
        Played p = played(1);
        long onBoard = boardAssets(p.season(), 1).iterator().next();
        SqlRecorder.start();
        assertThat(order(p.season(), 1, "BUY", onBoard, 1, null, null).getStatus()).isEqualTo(201);
        List<String> direct = SqlRecorder.stopAll().all();
        SqlRecorder.start();
        assertThat(order(p.season(), 1, "BUY", onBoard, 1, null, RANKING).getStatus()).isEqualTo(201);
        List<String> ranking = SqlRecorder.stopAll().all();
        assertThat(direct).as("statements of one DIRECT order").hasSize(17);
        // turn prices of the turn, earlier prices, pool, attention (RankingInputReader)
        assertThat(ranking.size() - direct.size()).isEqualTo(4);
    }
}
