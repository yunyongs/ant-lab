package antlab.api.order;

import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.code;
import static antlab.api.order.OrderApiSupport.fixTurn;
import static antlab.api.order.OrderApiSupport.json;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static antlab.api.order.OrderApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.turn.TurnViewSupport;
import antlab.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The origin of an order (MVP-13 PR 1): no source means DIRECT; FEED and ALERT name a stored feed item by (turn, display order)
 * and are accepted only when that item was shown to this season at or before the current turn; every "not shown" reason answers
 * with the same 422 and writes nothing. Feed rows are planted through the owner connection (the generator emits no NUDGE), and
 * the season is moved to a turn directly, so each case controls exactly what was "shown". Synthetic fixtures only.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class OrderSourceApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry registry;

    // ---- fixtures ----

    /** A season at {@code turn} holding 000660 (10) and 069500 (100), bought at turn 0; prices are the turn-0 prices. */
    private long seasonAt(int turn) throws Exception {
        long season = startSeason(mvc);
        assertThat(place(mvc, season, body(0, "BUY", assetId(jdbc, "000660"), 10, "FORWARD_BREAKOUT")).getStatus()).isEqualTo(201);
        assertThat(place(mvc, season, body(0, "BUY", assetId(jdbc, "069500"), 100, "MIDFIELDER")).getStatus()).isEqualTo(201);
        if (turn > 0) {
            fixTurn(jdbc, season, turn, Map.of());
        }
        return season;
    }

    private long plant(long season, int turn, int seq, String kind, Long asset, Long group, Long event) {
        return jdbc.queryForObject("INSERT INTO turn_feed (season_id, turn_no, seq, kind, asset_id, asset_group_id, month_event_id,"
                + " template_id, body) VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST_ROW', 'test row') RETURNING id", Long.class,
                season, turn, seq, kind, asset, group, event);
    }

    /** A rise sentence about one asset. */
    private long chipItem(long season, int turn, int seq, String ticker) {
        return plant(season, turn, seq, "RISE", assetId(jdbc, ticker), null, null);
    }

    /** Group noise: names a group but no asset. */
    private long groupItem(long season, int turn, int seq) {
        Long group = jdbc.queryForObject("SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'", Long.class);
        return plant(season, turn, seq, "SPREAD", null, group, null);
    }

    /** An event card: names neither an asset nor a group. */
    private long eventItem(long season, int turn, int seq) {
        Long event = jdbc.queryForObject("SELECT id FROM month_event WHERE event_key = 'FAKE_EVENT_01'", Long.class);
        return plant(season, turn, seq, "EVENT", null, null, event);
    }

    /** An alert with no target. */
    private long nudgeItem(long season, int turn, int seq) {
        return plant(season, turn, seq, "NUDGE", null, null, null);
    }

    private static String src(String kind, int feedTurnNo, int feedSeq) {
        return "{\"kind\":\"" + kind + "\",\"feedTurnNo\":" + feedTurnNo + ",\"feedSeq\":" + feedSeq + "}";
    }

    private MockHttpServletResponse order(long season, int turn, String side, String ticker, int qty, String source) {
        return place(mvc, season, withSource(body(turn, side, assetId(jdbc, ticker), qty, null), source));
    }

    private static String withSource(String requestBody, String source) {
        return source == null ? requestBody : requestBody.substring(0, requestBody.length() - 1) + ", \"source\": " + source + "}";
    }

    private Map<String, Object> lastOrder(long season) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE season_id = ? ORDER BY id DESC LIMIT 1", season);
    }

    private double rejectedCount(String code) {
        return registry.find("antlab.order.rejected").tags("code", code).counter().count();
    }

    /** Sends the request, checks status and code, and checks nothing was written (rows, balance, identity values). */
    private JsonNode refused(long season, String requestBody, int status, String code) throws Exception {
        Map<String, Object> before = state(jdbc, season);
        // the metric counts domain rejections (the controller), not bean-validation failures
        double counted = status == 422 ? rejectedCount(code) : 0;
        MockHttpServletResponse r = place(mvc, season, requestBody);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(code(r)).isEqualTo(code);
        assertThat(state(jdbc, season)).as("a refused order changes nothing and uses no ticket").isEqualTo(before);
        if (status == 422) {
            assertThat(rejectedCount(code)).as("metric for " + code).isEqualTo(counted + 1);
        }
        return json(r);
    }

    private JsonNode refusedSource(long season, int turn, String side, String ticker, int qty, String source) throws Exception {
        return refused(season, withSource(body(turn, side, assetId(jdbc, ticker), qty, null), source), 422, "SOURCE_NOT_VISIBLE");
    }

    // ---- accepted ----

    @Test
    void T1_without_source_the_order_is_DIRECT_with_no_feed_reference() throws Exception {
        long season = seasonAt(0);
        assertThat(lastOrder(season)).containsEntry("source", "DIRECT").containsEntry("turn_feed_id", null);
        MockHttpServletResponse r = order(season, 0, "BUY", "000660", 1, null);
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("source", "DIRECT").containsEntry("turn_feed_id", null);
    }

    @Test
    void T2_DIRECT_and_null_source_are_the_same_as_no_source() throws Exception {
        long season = seasonAt(0);
        assertThat(order(season, 0, "BUY", "000660", 1, "{\"kind\":\"DIRECT\"}").getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("source", "DIRECT").containsEntry("turn_feed_id", null);
        assertThat(order(season, 0, "BUY", "000660", 1, "null").getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("source", "DIRECT").containsEntry("turn_feed_id", null);
    }

    @Test
    void T3_a_feed_item_of_the_current_turn_with_the_same_chip_starts_a_FEED_order() throws Exception {
        long season = seasonAt(1);
        long item = chipItem(season, 1, 1, "000660");
        MockHttpServletResponse r = order(season, 1, "BUY", "000660", 2, src("FEED", 1, 1));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        Map<String, Object> row = lastOrder(season);
        assertThat(row).containsEntry("source", "FEED").containsEntry("turn_feed_id", item).containsEntry("turn_no", 1);
        // T9: neither the origin nor the feed reference is echoed
        Set<String> keys = new HashSet<>();
        json(r).propertyNames().forEach(keys::add);
        assertThat(keys).doesNotContainAnyElementsOf(BuyOrderExposureTest.FORBIDDEN_KEYS)
                .doesNotContain("feedTurnNo", "feedSeq", "turnFeedId", "source");
        assertThat(r.getContentAsString()).doesNotContain("FEED");
    }

    @Test
    void T4_an_item_shown_at_an_earlier_turn_can_still_start_an_order_now() throws Exception {
        long season = seasonAt(3);
        long item = chipItem(season, 1, 1, "000660");
        MockHttpServletResponse r = order(season, 3, "BUY", "000660", 1, src("FEED", 1, 1));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        Map<String, Object> row = lastOrder(season);
        assertThat(row).containsEntry("source", "FEED").containsEntry("turn_feed_id", item).containsEntry("turn_no", 3);
        assertThat(jdbc.queryForObject("SELECT turn_no FROM turn_feed WHERE id = ?", Integer.class, item))
                .as("the item keeps its own turn").isEqualTo(1);
    }

    @Test
    void T5_an_item_with_no_chip_starts_an_order_for_any_asset_on_both_sides() throws Exception {
        long season = seasonAt(2);
        eventItem(season, 2, 1);
        groupItem(season, 2, 2);
        assertThat(order(season, 2, "BUY", "069500", 1, src("FEED", 2, 1)).getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("source", "FEED");
        assertThat(order(season, 2, "SELL", "000660", 1, src("FEED", 2, 2)).getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("source", "FEED").containsEntry("side", "SELL");
    }

    @Test
    void T6_an_alert_item_starts_an_ALERT_order() throws Exception {
        long season = seasonAt(1);
        long alert = nudgeItem(season, 1, 1);
        assertThat(order(season, 1, "BUY", "069500", 1, src("ALERT", 1, 1)).getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("source", "ALERT").containsEntry("turn_feed_id", alert);
        long chipAlert = plant(season, 1, 2, "NUDGE", assetId(jdbc, "000660"), null, null);
        assertThat(order(season, 1, "BUY", "000660", 1, src("ALERT", 1, 2)).getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("turn_feed_id", chipAlert);
    }

    @Test
    void T6b_a_sell_stores_the_origin_and_the_feed_row_it_points_at() throws Exception {
        long season = seasonAt(1);
        long chip = chipItem(season, 1, 1, "000660");
        long alert = plant(season, 1, 2, "NUDGE", assetId(jdbc, "069500"), null, null);
        assertThat(order(season, 1, "SELL", "000660", 1, src("FEED", 1, 1)).getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("side", "SELL").containsEntry("source", "FEED").containsEntry("turn_feed_id", chip);
        assertThat(order(season, 1, "SELL", "069500", 1, src("ALERT", 1, 2)).getStatus()).isEqualTo(201);
        assertThat(lastOrder(season)).containsEntry("side", "SELL").containsEntry("source", "ALERT").containsEntry("turn_feed_id", alert);
        // a sell of another asset than the chip's is refused like a buy
        refusedSource(season, 1, "SELL", "069500", 1, src("FEED", 1, 1));
    }

    @Test
    void T7_a_formation_change_earlier_in_the_turn_and_a_feed_origin_bind_the_same_order() throws Exception {
        long season = seasonAt(1);
        long item = eventItem(season, 1, 1);
        TurnViewSupport.changeFormation(jdbc, season, 1, 20, 20, 30, 30);
        MockHttpServletResponse r = order(season, 1, "BUY", "069500", 1, src("FEED", 1, 1));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        Map<String, Object> row = lastOrder(season);
        assertThat(row.get("formation_change_id")).isNotNull();
        assertThat(row).containsEntry("turn_feed_id", item).containsEntry("source", "FEED");
    }

    // ---- refused: not shown ----

    @Test
    void N1_a_turn_after_the_current_one_is_refused_even_though_the_row_exists() throws Exception {
        long season = seasonAt(1);
        chipItem(season, 2, 1, "000660");
        refusedSource(season, 1, "BUY", "000660", 1, src("FEED", 2, 1));
    }

    @Test
    void N2_an_unknown_turn_and_order_pair_is_refused() throws Exception {
        long season = seasonAt(2);
        chipItem(season, 1, 1, "000660");
        refusedSource(season, 2, "BUY", "000660", 1, src("FEED", 1, 9));
        refusedSource(season, 2, "BUY", "000660", 1, src("FEED", 2, 1));
        refusedSource(season, 2, "BUY", "000660", 1, src("FEED", 1, 32768));
    }

    @Test
    void N3_an_item_of_another_season_of_the_same_member_is_refused_not_a_server_error() throws Exception {
        long mine = seasonAt(1);
        long other = seasonAt(1);
        eventItem(other, 1, 1);
        refusedSource(mine, 1, "BUY", "069500", 1, src("FEED", 1, 1));
        // the other season's own item still works there
        assertThat(order(other, 1, "BUY", "069500", 1, src("FEED", 1, 1)).getStatus()).isEqualTo(201);
    }

    @Test
    void N4_N5_the_kind_must_match_the_item() throws Exception {
        long season = seasonAt(1);
        eventItem(season, 1, 1);
        nudgeItem(season, 1, 2);
        refusedSource(season, 1, "BUY", "069500", 1, src("FEED", 1, 2));
        refusedSource(season, 1, "BUY", "069500", 1, src("ALERT", 1, 1));
    }

    @Test
    void N6_an_item_with_a_chip_starts_orders_for_that_asset_only() throws Exception {
        long season = seasonAt(1);
        chipItem(season, 1, 1, "000660");
        plant(season, 1, 2, "NUDGE", assetId(jdbc, "000660"), null, null);
        refusedSource(season, 1, "BUY", "069500", 1, src("FEED", 1, 1));
        refusedSource(season, 1, "BUY", "069500", 1, src("ALERT", 1, 2));
        refusedSource(season, 1, "SELL", "069500", 1, src("FEED", 1, 1));
    }

    @Test
    void N7_a_ranking_origin_is_refused_when_the_asset_is_not_on_the_board() throws Exception {
        long season = seasonAt(1);
        refusedSource(season, 1, "BUY", "000660", 1, "{\"kind\":\"RANKING\"}");
    }

    @Test
    void N8_BENCH_is_reserved_and_answers_400() throws Exception {
        long season = seasonAt(1);
        refused(season, withSource(body(1, "BUY", assetId(jdbc, "000660"), 1, null), "{\"kind\":\"BENCH\"}"), 400, "INVALID_ARGUMENT");
    }

    @Test
    void N12_turn_0_has_no_feed_so_FEED_and_ALERT_are_refused() throws Exception {
        long season = seasonAt(0);
        chipItem(season, 1, 1, "000660");
        nudgeItem(season, 1, 2);
        refusedSource(season, 0, "BUY", "000660", 1, src("FEED", 1, 1));
        refusedSource(season, 0, "BUY", "000660", 1, src("ALERT", 1, 2));
    }

    // ---- refused: shape (400, before the lock) ----

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"kind\":\"FEED\",\"feedTurnNo\":1}",
            "{\"kind\":\"FEED\",\"feedSeq\":1}",
            "{\"kind\":\"ALERT\"}",
            "{\"kind\":\"DIRECT\",\"feedSeq\":1}",
            "{\"kind\":\"RANKING\",\"feedTurnNo\":1}",
            "{\"kind\":\"BENCH\",\"feedSeq\":1}",
            "{\"kind\":\"FEED\",\"feedTurnNo\":1,\"feedSeq\":0}",
            "{\"kind\":\"FEED\",\"feedTurnNo\":0,\"feedSeq\":1}",
            "{\"feedTurnNo\":1,\"feedSeq\":1}",
            "{}",
            "{\"kind\":\"MAGIC\"}",
            "{\"kind\":\"FEED\",\"feedTurnNo\":1,\"feedSeq\":\"3\"}",
            "{\"kind\":\"FEED\",\"feedTurnNo\":1,\"feedSeq\":3.0}",
            "\"FEED\""})
    void N9_a_malformed_source_is_400_and_never_reaches_the_lock(String source) throws Exception {
        long season = seasonAt(1);
        chipItem(season, 1, 1, "000660");
        refused(season, withSource(body(1, "BUY", assetId(jdbc, "000660"), 1, null), source), 400, "INVALID_ARGUMENT");
        // "before the lock": a season that does not exist answers the shape error, not 404
        MockHttpServletResponse r = place(mvc, 999_999_999L, withSource(body(1, "BUY", assetId(jdbc, "000660"), 1, null), source));
        assertThat(r.getStatus()).as(source).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void N9b_an_unknown_key_inside_source_follows_the_api_wide_rule() throws Exception {
        long season = seasonAt(1);
        MockHttpServletResponse r = order(season, 1, "BUY", "000660", 1, "{\"kind\":\"DIRECT\",\"extra\":1}");
        MockHttpServletResponse top = place(mvc, season,
                body(1, "BUY", assetId(jdbc, "000660"), 1, null).replace("{", "{\"extra\": 1, "));
        assertThat(top.getStatus()).as("unknown keys are ignored at the top level").isEqualTo(201);
        assertThat(r.getStatus()).as("and inside source").isEqualTo(201);
    }

    // ---- priority and sameness ----

    @Test
    void N11_the_origin_is_checked_first_and_the_turn_lock_still_comes_before_it() throws Exception {
        long season = seasonAt(1);
        eventItem(season, 1, 1);
        // bad origin + unknown asset -> the origin answers; the same unknown asset with DIRECT -> ASSET_NOT_IN_POOL
        refused(season, withSource(body(1, "BUY", 999_999_999L, 1, null), src("FEED", 1, 9)), 422, "SOURCE_NOT_VISIBLE");
        refused(season, withSource(body(1, "BUY", 999_999_999L, 1, null), "{\"kind\":\"DIRECT\"}"), 422, "ASSET_NOT_IN_POOL");
        // a good origin does not hide the cash check
        refused(season, withSource(body(1, "BUY", assetId(jdbc, "069500"), 100_000, null), src("FEED", 1, 1)), 422, "INSUFFICIENT_CASH");
        // a stale turn wins over a bad origin
        refused(season, withSource(body(0, "BUY", assetId(jdbc, "069500"), 1, null), src("FEED", 1, 9)), 409, "TURN_MISMATCH");
    }

    @Test
    void T8_an_event_card_and_group_noise_are_treated_alike_by_the_order_route() throws Exception {
        long season = seasonAt(1);
        eventItem(season, 1, 1);
        groupItem(season, 1, 2);
        MockHttpServletResponse viaEvent = order(season, 1, "BUY", "069500", 1, src("FEED", 1, 1));
        MockHttpServletResponse viaNoise = order(season, 1, "BUY", "069500", 1, src("FEED", 1, 2));
        assertThat(viaEvent.getStatus()).isEqualTo(201);
        assertThat(viaNoise.getStatus()).isEqualTo(viaEvent.getStatus());
        assertThat(keys(json(viaNoise))).isEqualTo(keys(json(viaEvent)));
        // and any asset is fine with either
        assertThat(order(season, 1, "BUY", "000660", 1, src("FEED", 1, 1)).getStatus()).isEqualTo(201);
        assertThat(order(season, 1, "BUY", "000660", 1, src("FEED", 1, 2)).getStatus()).isEqualTo(201);
    }

    @Test
    void T8b_every_not_shown_reason_gives_the_same_answer() throws Exception {
        long season = seasonAt(1);
        long other = seasonAt(1);
        chipItem(season, 1, 1, "000660");
        eventItem(season, 1, 3);
        nudgeItem(season, 1, 2);
        eventItem(season, 2, 1);
        groupItem(other, 1, 5);
        List<JsonNode> answers = new ArrayList<>();
        answers.add(refusedSource(season, 1, "BUY", "000660", 1, src("FEED", 2, 1)));
        answers.add(refusedSource(season, 1, "BUY", "000660", 1, src("FEED", 1, 77)));
        answers.add(refusedSource(season, 1, "BUY", "000660", 1, src("FEED", 1, 5)));
        answers.add(refusedSource(season, 1, "BUY", "000660", 1, src("FEED", 1, 2)));
        answers.add(refusedSource(season, 1, "BUY", "000660", 1, src("ALERT", 1, 3)));
        answers.add(refusedSource(season, 1, "BUY", "069500", 1, src("FEED", 1, 1)));
        answers.add(refusedSource(season, 1, "BUY", "000660", 1, "{\"kind\":\"RANKING\"}"));
        Set<String> distinct = new HashSet<>();
        for (JsonNode answer : answers) {
            ObjectNode copy = (ObjectNode) answer.deepCopy();
            copy.remove("instance");
            distinct.add(copy.toString());
        }
        assertThat(distinct).as("one code, one message, no sub-reason").hasSize(1);
    }

    private static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }
}
