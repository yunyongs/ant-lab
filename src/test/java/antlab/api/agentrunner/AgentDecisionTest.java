package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.HEADER;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.TOKEN;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderService;
import antlab.account.OrderSide;
import antlab.account.PlaceOrderRequest;
import antlab.agent.decision.AgentDecisionService;
import antlab.agent.decision.DecisionRequest;
import antlab.asset.Position;
import antlab.auth.AgentActingAuthentication;
import antlab.season.ChangeFormationRequest;
import antlab.season.FormationChangeService;
import antlab.season.FormationDto;
import antlab.season.SeedGenerator;
import antlab.season.TurnFacade;
import antlab.season.TurnQueryService;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * POST /internal/agent-runner/v1/agents/{agentId}/seasons/{seasonId}/turns/{turnNo}/decision and its neighbours (calls,
 * read-back, advance guard). Committing mode, real PostgreSQL: units run through the same check and apply code as the human
 * endpoints, rejects are results, a broken state rolls the whole decision back, and the same decision is applied once.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentDecisionTest {

    /** Both seasons of the parity test must walk the same path, so the seed is fixed for this class. */
    @TestConfiguration
    static class FixedSeed {
        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> 20_261_117L;
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper mapper;
    @Autowired
    OrderService orders;
    @Autowired
    FormationChangeService formations;
    @Autowired
    TurnFacade turns;
    @Autowired
    TurnQueryService turnQueries;
    @Autowired
    AgentDecisionService decisions;

    private int personaCounter;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        List<Long> seasonIds = jdbc.queryForList("SELECT s.id FROM season s JOIN agent_profile p ON p.member_id = s.member_id "
                + "WHERE p.owner_member_id = ?", Long.class, OWNER_ID);
        List<Long> memberIds = new ArrayList<>(jdbc.queryForList(
                "SELECT member_id FROM agent_profile WHERE owner_member_id = ?", Long.class, OWNER_ID));
        memberIds.add(OWNER_ID);
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    record Agent(long id, long season) {
        String turns() {
            return AGENTS + "/" + id + "/seasons/" + season + "/turns";
        }
    }

    private Agent startAgent() throws Exception {
        long agent = AgentRunnerSupport.register(mvc,
                registerBody("persona-" + (++personaCounter), "OPENROUTER", "fake/model-1", "에이전트 " + personaCounter));
        MockHttpServletResponse started = post(mvc, AGENTS + "/" + agent + "/seasons", START_BODY);
        assertThat(started.getStatus()).isEqualTo(201);
        return new Agent(agent, json(started).get("id").asLong());
    }

    private ObjectNode call(int attemptNo, String status) {
        ObjectNode c = mapper.createObjectNode();
        c.put("attemptNo", attemptNo);
        c.put("kind", "TURN_DECISION");
        c.put("ledgerCallId", UUID.randomUUID().toString());
        c.put("status", status);
        c.put("modelRequested", "fake/model-1");
        c.put("requestText", "request " + attemptNo);
        c.put("responseText", "reply " + attemptNo);
        c.put("latencyMs", 12);
        return c;
    }

    private ObjectNode decisionBody(JsonNode decision, ObjectNode... calls) {
        ObjectNode body = mapper.createObjectNode();
        body.put("outcome", "DECISION");
        body.set("decision", decision);
        ArrayNode array = body.putArray("calls");
        if (calls.length == 0) {
            array.add(call(1, "OK"));
        }
        for (ObjectNode c : calls) {
            array.add(c);
        }
        return body;
    }

    private ObjectNode noResponseBody() {
        ObjectNode body = mapper.createObjectNode();
        body.put("outcome", "NO_RESPONSE");
        body.putNull("decision");
        body.putArray("calls").add(call(1, "TIMEOUT"));
        return body;
    }

    private ObjectNode dec(JsonNode formation, JsonNode... orderNodes) {
        ObjectNode d = mapper.createObjectNode();
        d.put("schema", "turn-decision/1");
        if (formation == null) {
            d.putNull("formation_change");
        } else {
            d.set("formation_change", formation);
        }
        ArrayNode array = d.putArray("orders");
        for (JsonNode o : orderNodes) {
            array.add(o);
        }
        d.putArray("theses");
        d.put("reason", "test");
        return d;
    }

    private ObjectNode formation(int g, int d, int m, int f) {
        ObjectNode n = mapper.createObjectNode();
        n.put("goalkeeper", g);
        n.put("defender", d);
        n.put("midfielder", m);
        n.put("forward", f);
        n.put("reason", "shift");
        return n;
    }

    private ObjectNode order(String asset, String side, int quantity, String position) {
        return order(asset, side, quantity, position, "DIRECT");
    }

    private ObjectNode order(String asset, String side, int quantity, String position, String source) {
        ObjectNode o = mapper.createObjectNode();
        o.put("asset", asset);
        o.put("side", side);
        o.put("quantity", quantity);
        if (position != null) {
            o.put("position", position);
        }
        o.putObject("source").put("kind", source);
        return o;
    }

    private MockHttpServletResponse decide(Agent a, int turn, JsonNode body) {
        return post(mvc, a.turns() + "/" + turn + "/decision", TOKEN, body.toString(), true);
    }

    private JsonNode decideOk(Agent a, int turn, JsonNode body) throws Exception {
        MockHttpServletResponse r = decide(a, turn, body);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private MockHttpServletResponse get(String uri) {
        return mvc.get().uri(uri).header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
    }

    private void advanceApi(Agent a, int fromTurn) throws Exception {
        MockHttpServletResponse r = post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": " + fromTurn + "}", true);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    }

    private <T> T acting(long agent, Supplier<T> call) {
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long count(String table, long season) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id = ?", Long.class, season);
    }

    private long assetId(String ticker) {
        return jdbc.queryForObject("SELECT id FROM asset WHERE ticker = ?", Long.class, ticker);
    }

    private static List<String> codes(JsonNode result) {
        List<String> out = new ArrayList<>();
        out.add("F:" + result.get("formationChange").get("status").asString() + ":"
                + result.get("formationChange").get("code").asString("-"));
        result.get("orders").forEach(o -> out.add(o.get("index").asInt() + ":" + o.get("status").asString() + ":"
                + o.get("code").asString("-")));
        out.add("T:" + result.get("theses").get("status").asString());
        return out;
    }

    /** Plays turn 0 with a no-response decision and advances, so the season stands at turn 1. */
    private void toTurnOne(Agent a) throws Exception {
        decideOk(a, 0, noResponseBody());
        advanceApi(a, 0);
    }

    private List<Map<String, Object>> trades(long season) {
        return jdbc.queryForList("""
                SELECT o.turn_no, o.side, a.ticker, o.quantity, o.source, o.formation_change_id IS NOT NULL AS linked,
                       e.fill_price, e.fee, e.realized_pnl
                FROM orders o JOIN asset a ON a.id = o.asset_id JOIN execution e ON e.order_id = o.id
                WHERE o.season_id = ? ORDER BY o.id""", season);
    }

    private List<Map<String, Object>> ledger(long season) {
        return jdbc.queryForList("SELECT type, amount, balance_after FROM cash_ledger WHERE season_id = ? ORDER BY id",
                season);
    }

    private List<Map<String, Object>> holdingRows(long season) {
        return jdbc.queryForList("""
                SELECT a.ticker, h.quantity, h.avg_price FROM holding h JOIN asset a ON a.id = h.asset_id
                WHERE h.season_id = ? ORDER BY a.ticker""", season);
    }

    // ---- golden: order of application and parity with the human path ---------------------------------------------

    @Test
    void a_formation_two_sells_and_two_buys_apply_in_that_order_and_equal_the_human_replay() throws Exception {
        Agent a = startAgent();
        JsonNode first = decideOk(a, 0, decisionBody(dec(formation(10, 20, 30, 40),
                order("KR:000660", "BUY", 10, "FORWARD_BREAKOUT"), order("KR:069500", "BUY", 100, "MIDFIELDER"),
                order("US:SPY", "BUY", 5, "DEFENDER"))));
        assertThat(codes(first)).containsExactly("F:REJECTED:TURN_ZERO_FORMATION", "0:APPLIED:-", "1:APPLIED:-",
                "2:APPLIED:-", "T:NONE");
        advanceApi(a, 0);

        ObjectNode turnOne = dec(formation(10, 20, 30, 40), order("US:BRK.B", "BUY", 2, "GOALKEEPER"),
                order("KR:000660", "SELL", 2, null), order("KR:069500", "BUY", 5, null), order("US:SPY", "SELL", 1, null));
        JsonNode result = decideOk(a, 1, decisionBody(turnOne));
        assertThat(codes(result)).containsExactly("F:APPLIED:-", "0:APPLIED:-", "1:APPLIED:-", "2:APPLIED:-",
                "3:APPLIED:-", "T:NONE");
        assertThat(result.get("replayed").asBoolean()).isFalse();

        List<Map<String, Object>> turnOneTrades = trades(a.season()).stream().filter(t -> (int) t.get("turn_no") == 1).toList();
        assertThat(turnOneTrades.stream().map(t -> t.get("side") + " " + t.get("ticker")).toList())
                .containsExactly("SELL 000660", "SELL SPY", "BUY BRK.B", "BUY 069500");
        assertThat(turnOneTrades).allMatch(t -> (boolean) t.get("linked"));
        List<Long> ids = jdbc.queryForList("SELECT id FROM orders WHERE season_id = ? AND turn_no = 1 ORDER BY id",
                Long.class, a.season());
        assertThat(List.of(result.get("orders").get(1).get("orderId").asLong(), result.get("orders").get(3).get("orderId").asLong(),
                result.get("orders").get(0).get("orderId").asLong(), result.get("orders").get(2).get("orderId").asLong()))
                .isEqualTo(ids);
        assertThat(result.get("formationChange").get("formationChangeId").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM formation_change WHERE season_id = ?", Long.class, a.season()));
        JsonNode readBack = json(get(a.turns() + "/1/decision"));
        assertThat(readBack.get("orders")).isEqualTo(result.get("orders"));
        assertThat(readBack.get("formationChange")).isEqualTo(result.get("formationChange"));
        assertThat(turnOneTrades).allMatch(t -> "DIRECT".equals(t.get("source")));
        advanceApi(a, 1);

        // The same accepted units through the human services, in the same order, for another agent's season.
        Agent h = startAgent();
        Supplier<Object> replay = () -> {
            orders.place(h.season(), new PlaceOrderRequest(0, OrderSide.BUY, assetId("000660"), 10, Position.FORWARD_BREAKOUT, null));
            orders.place(h.season(), new PlaceOrderRequest(0, OrderSide.BUY, assetId("069500"), 100, Position.MIDFIELDER, null));
            orders.place(h.season(), new PlaceOrderRequest(0, OrderSide.BUY, assetId("SPY"), 5, Position.DEFENDER, null));
            turns.advance(h.season(), 0);
            formations.change(h.season(), new ChangeFormationRequest(1, new FormationDto(10, 20, 30, 40), "shift"));
            orders.place(h.season(), new PlaceOrderRequest(1, OrderSide.SELL, assetId("000660"), 2, null, null));
            orders.place(h.season(), new PlaceOrderRequest(1, OrderSide.SELL, assetId("SPY"), 1, null, null));
            orders.place(h.season(), new PlaceOrderRequest(1, OrderSide.BUY, assetId("BRK.B"), 2, Position.GOALKEEPER, null));
            orders.place(h.season(), new PlaceOrderRequest(1, OrderSide.BUY, assetId("069500"), 5, null, null));
            turns.advance(h.season(), 1);
            return null;
        };
        acting(h.id(), replay);

        assertThat(ledger(a.season())).isEqualTo(ledger(h.season()));
        assertThat(holdingRows(a.season())).isEqualTo(holdingRows(h.season()));
        List<Map<String, Object>> agentTrades = trades(a.season());
        assertThat(agentTrades.stream().filter(t -> (int) t.get("turn_no") == 1).toList())
                .isEqualTo(trades(h.season()).stream().filter(t -> (int) t.get("turn_no") == 1).toList());
        JsonNode agentTurn = acting(a.id(), () -> mapper.readTree(mapper.writeValueAsString(turnQueries.get(a.season(), 2))));
        JsonNode humanTurn = acting(h.id(), () -> mapper.readTree(mapper.writeValueAsString(turnQueries.get(h.season(), 2))));
        ((ObjectNode) agentTurn).remove("seasonId");
        ((ObjectNode) humanTurn).remove("seasonId");
        assertThat(agentTurn).isEqualTo(humanTurn);
    }

    @Test
    void sells_run_before_buys_so_the_cash_they_free_pays_for_a_buy_listed_earlier() throws Exception {
        Agent a = startAgent();
        java.math.BigDecimal price0 = jdbc.queryForObject("SELECT price FROM turn_price tp JOIN asset s ON s.id = tp.asset_id WHERE tp.season_id = ? "
                + "AND tp.turn_no = 0 AND s.ticker = '069500'", java.math.BigDecimal.class, a.season());
        long balance = jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", Long.class, a.season());
        int all = (int) (balance / (price0.longValue() * 1.001));
        decideOk(a, 0, decisionBody(dec(null, order("KR:069500", "BUY", all, "MIDFIELDER"))));
        advanceApi(a, 0);
        java.math.BigDecimal leftover = jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?",
                java.math.BigDecimal.class, a.season());
        java.math.BigDecimal bigPrice = jdbc.queryForObject("SELECT price FROM turn_price tp JOIN asset s ON s.id = tp.asset_id "
                + "WHERE tp.season_id = ? AND tp.turn_no = 1 AND s.ticker = '000660'", java.math.BigDecimal.class, a.season());
        assertThat(leftover).as("the cash left cannot pay one share").isLessThan(bigPrice);

        JsonNode result = decideOk(a, 1, decisionBody(dec(null, order("KR:000660", "BUY", 1, "FORWARD_BREAKOUT"),
                order("KR:069500", "SELL", all, null))));

        assertThat(codes(result)).containsExactly("F:NONE:-", "0:APPLIED:-", "1:APPLIED:-", "T:NONE");
        assertThat(trades(a.season()).stream().filter(t -> (int) t.get("turn_no") == 1).map(t -> t.get("side") + " " + t.get("ticker")))
                .containsExactly("SELL 069500", "BUY 000660");
    }

    @Test
    void the_second_of_two_buys_that_exceeds_the_cash_left_is_rejected_alone() throws Exception {
        Agent a = startAgent();
        java.math.BigDecimal price = jdbc.queryForObject("SELECT price FROM turn_price tp JOIN asset s ON s.id = tp.asset_id "
                + "WHERE tp.season_id = ? AND tp.turn_no = 0 AND s.ticker = '069500'", java.math.BigDecimal.class, a.season());
        long balance = jdbc.queryForObject("SELECT balance FROM cash_account WHERE season_id = ?", Long.class, a.season());
        int sixty = (int) (balance * 0.6 / price.longValue());

        JsonNode result = decideOk(a, 0, decisionBody(dec(null, order("KR:069500", "BUY", sixty, "MIDFIELDER"),
                order("KR:069500", "BUY", sixty, "MIDFIELDER"))));

        assertThat(codes(result)).containsExactly("F:NONE:-", "0:APPLIED:-", "1:REJECTED:INSUFFICIENT_CASH", "T:NONE");
        assertThat(count("orders", a.season())).isEqualTo(1);
    }

    // ---- per-unit rejects ----------------------------------------------------------------------------------------

    /** Each case: a problem unit plus a good buy listed after it; the good buy still fills. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "KR:999999|BUY|1|MIDFIELDER|DIRECT|ASSET_NOT_IN_POOL",
            "KR:069500|BUY|0|MIDFIELDER|DIRECT|QUANTITY_INVALID",
            "KR:069500|BUY|-4|MIDFIELDER|DIRECT|QUANTITY_INVALID",
            "KR:069500|SELL|1|-|DIRECT|INSUFFICIENT_HOLDING",
            "KR:069500|SELL|1|DEFENDER|DIRECT|POSITION_ON_SELL",
            "KR:069500|BUY|1|-|DIRECT|POSITION_REQUIRED",
            "KR:069500|BUY|20000000|MIDFIELDER|DIRECT|INSUFFICIENT_CASH",
            "KR:069500|BUY|1|MIDFIELDER|BENCH|SOURCE_NOT_ALLOWED",
            "KR:069500|BUY|1|MIDFIELDER|RANKING|SOURCE_NOT_VISIBLE",
            "KR:069500|BUY|1|MIDFIELDER|FEED|SOURCE_NOT_VISIBLE",
            "KR:069500|BUY|1|MIDFIELDER|ALERT|SOURCE_NOT_VISIBLE"})
    void a_unit_that_fails_its_check_is_rejected_with_its_code_and_the_good_unit_fills(String asset, String side, int quantity,
            String position, String source, String code) throws Exception {
        Agent a = startAgent();
        JsonNode result = decideOk(a, 0, decisionBody(dec(null,
                order(asset, side, quantity, "-".equals(position) ? null : position, source),
                order("US:SPY", "BUY", 1, "DEFENDER"))));

        assertThat(codes(result)).containsExactly("F:NONE:-", "0:REJECTED:" + code, "1:APPLIED:-", "T:NONE");
        assertThat(result.get("orders").get(0).get("orderId").isNull()).isTrue();
        assertThat(trades(a.season())).singleElement().satisfies(t -> assertThat(t.get("ticker")).isEqualTo("SPY"));
        assertThat(jdbc.queryForObject("SELECT rejects::text FROM agent_call WHERE season_id = ? AND is_final", String.class,
                a.season())).contains(code).contains("\"unit\": \"ORDER\"").contains("\"index\": 0");
    }

    @Test
    void a_second_buy_of_an_asset_with_another_declared_position_conflicts_with_the_first() throws Exception {
        Agent a = startAgent();
        JsonNode result = decideOk(a, 0, decisionBody(dec(null, order("KR:000660", "BUY", 1, "FORWARD_BREAKOUT"),
                order("KR:000660", "BUY", 1, "DEFENDER"), order("KR:000660", "BUY", 1, null))));

        assertThat(codes(result)).containsExactly("F:NONE:-", "0:APPLIED:-", "1:REJECTED:POSITION_CONFLICT", "2:APPLIED:-",
                "T:NONE");
    }

    @Test
    void the_first_failing_check_names_the_code() throws Exception {
        Agent a = startAgent();
        JsonNode result = decideOk(a, 0, decisionBody(dec(null,
                order("KR:999999", "BUY", 0, "MIDFIELDER", "BENCH"),
                order("KR:999999", "BUY", 0, "MIDFIELDER", "RANKING"),
                order("KR:999999", "BUY", 0, "MIDFIELDER"),
                order("KR:069500", "SELL", 0, "DEFENDER"),
                order("KR:069500", "BUY", 20000000, null))));

        // sells run first (index 3), then the buys in the reply's order
        assertThat(codes(result)).containsExactly("F:NONE:-", "0:REJECTED:SOURCE_NOT_ALLOWED",
                "1:REJECTED:SOURCE_NOT_VISIBLE", "2:REJECTED:ASSET_NOT_IN_POOL", "3:REJECTED:QUANTITY_INVALID",
                "4:REJECTED:INSUFFICIENT_CASH", "T:NONE");
    }

    @Test
    void an_asset_without_a_price_this_turn_is_not_in_the_pool_even_with_a_bad_quantity() throws Exception {
        Agent a = startAgent();
        jdbc.update("DELETE FROM turn_price WHERE season_id = ? AND turn_no = 0 AND asset_id = ?", a.season(), assetId("SPY"));

        JsonNode result = decideOk(a, 0, decisionBody(dec(null, order("US:SPY", "BUY", 0, "DEFENDER"),
                order("US:SPY", "SELL", 0, "DEFENDER"))));

        assertThat(codes(result)).containsExactly("F:NONE:-", "0:REJECTED:ASSET_NOT_IN_POOL",
                "1:REJECTED:ASSET_NOT_IN_POOL", "T:NONE");
    }

    @Test
    void theses_are_rejected_as_a_unit_and_do_not_stop_the_other_units() throws Exception {
        Agent a = startAgent();
        ObjectNode d = dec(null, order("US:SPY", "BUY", 1, "DEFENDER"));
        ((ArrayNode) d.get("theses")).addObject().put("asset", "US:SPY");
        JsonNode result = decideOk(a, 0, decisionBody(d));

        assertThat(codes(result)).containsExactly("F:NONE:-", "0:APPLIED:-", "T:REJECTED");
        assertThat(result.get("theses").get("code").asString()).isEqualTo("THESES_NOT_SUPPORTED");
    }

    @Test
    void a_rejected_formation_change_keeps_the_old_formation_and_leaves_the_orders_unlinked() throws Exception {
        for (int[] shares : new int[][] {{10, 30, 30, 30}, {50, 50, 10, 0}, {0, 40, 30, 30}}) {
            // each attempt needs its own turn: the decision of turn 1 is final after the first
            Agent other = startAgent();
            toTurnOne(other);
            JsonNode result = decideOk(other, 1, decisionBody(dec(formation(shares[0], shares[1], shares[2], shares[3]),
                    order("US:SPY", "BUY", 1, "DEFENDER"))));
            String expected = shares[0] == 10 ? "FORMATION_UNCHANGED" : "FORMATION_INVALID";
            assertThat(codes(result)).containsExactly("F:REJECTED:" + expected, "0:APPLIED:-", "T:NONE");
            assertThat(count("formation_change", other.season())).isZero();
            assertThat(jdbc.queryForObject("SELECT current_goalkeeper FROM season WHERE id = ?", Integer.class, other.season()))
                    .isEqualTo(10);
            assertThat(trades(other.season())).singleElement().satisfies(t -> assertThat(t.get("linked")).isEqualTo(false));
        }
    }

    // ---- shape check -------------------------------------------------------------------------------------------

    @Test
    void a_reply_that_breaks_the_shape_is_recorded_invalid_and_applies_nothing() throws Exception {
        Agent a = startAgent();
        ObjectNode bad = dec(null, order("US:SPY", "BUY", 1, "DEFENDER"));
        bad.put("price", 100);
        bad.set("orders", mapper.createArrayNode().add(order("US:SPY", "BUY", 1, "DEFENDER").put("fee", 0)));

        JsonNode result = decideOk(a, 0, decisionBody(bad));

        assertThat(result.get("outcome").asString()).isEqualTo("INVALID");
        assertThat(result.get("schemaViolations").toString()).contains("additionalProperties");
        assertThat(result.get("orders")).isEmpty();
        assertThat(count("orders", a.season())).isZero();
        assertThat(count("cash_ledger", a.season())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM agent_call WHERE season_id = ? AND is_final", String.class,
                a.season())).isEqualTo("INVALID");
        assertThat(count("agent_call", a.season())).isEqualTo(1);
        // Each violation is recorded as a reply-level reject: no unit, no index, a pointer of plain names.
        assertThat(jdbc.queryForObject("SELECT rejects::text FROM agent_call WHERE season_id = ? AND is_final", String.class,
                a.season())).contains("\"unit\": null").contains("SCHEMA_INVALID").contains("\"pointer\": \"/orders/0\"")
                .contains("\"pointer\": \"/reply\"");
        advanceApi(a, 0);
        JsonNode view = json(get(AGENTS + "/" + a.id() + "/seasons/" + a.season() + "/turn-view"));
        assertThat(view.get("agent").get("lastRejects")).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("unit").isNull()).isTrue();
            assertThat(r.get("code").asString()).isEqualTo("SCHEMA_INVALID");
        });
        // The stored result reads back with the same violations.
        JsonNode read = json(get(a.turns() + "/0/decision"));
        assertThat(read.get("schemaViolations")).isEqualTo(result.get("schemaViolations"));
    }

    @Test
    void a_reply_the_runner_found_invalid_records_invalid_without_a_decision() throws Exception {
        Agent a = startAgent();
        ObjectNode body = mapper.createObjectNode();
        body.put("outcome", "INVALID");
        body.putNull("decision");
        body.putArray("calls").add(call(1, "INVALID"));

        JsonNode result = decideOk(a, 0, body);

        assertThat(result.get("outcome").asString()).isEqualTo("INVALID");
        assertThat(result.get("schemaViolations")).isEmpty();
        advanceApi(a, 0);
        JsonNode view = json(get(AGENTS + "/" + a.id() + "/seasons/" + a.season() + "/turn-view"));
        assertThat(view.get("agent").get("lastRejects").toString()).contains("SCHEMA_INVALID").contains("/reply");
    }

    @Test
    void every_fixture_of_the_corpus_gets_its_verdict_and_a_bad_one_changes_nothing() throws Exception {
        Agent a = startAgent();
        JsonNode manifest = mapper.readTree(getClass().getResourceAsStream("/agent/turn-decision/manifest.json"));
        for (JsonNode entry : manifest.get("files")) {
            JsonNode decision = mapper.readTree(getClass().getResourceAsStream("/agent/turn-decision/" + entry.get("file").asString()));
            long ordersBefore = count("orders", a.season());
            ObjectNode body = decisionBody(decision);
            if (!decision.isObject()) {
                body = mapper.createObjectNode();
                body.put("outcome", "INVALID");
                body.putNull("decision");
                body.putArray("calls").add(call(1, "INVALID"));
            }
            if (entry.has("apiStatus")) {
                MockHttpServletResponse refused = decide(a, 0, body);
                assertThat(refused.getStatus()).as(entry.get("file").asString()).isEqualTo(entry.get("apiStatus").asInt());
                assertThat(count("agent_call", a.season())).isZero();
                continue;
            }
            JsonNode result = decideOk(a, 0, body);
            if (entry.get("valid").asBoolean()) {
                assertThat(result.get("outcome").asString()).as(entry.get("file").asString()).isEqualTo("DECISION");
            } else {
                assertThat(result.get("outcome").asString()).as(entry.get("file").asString()).isEqualTo("INVALID");
                assertThat(count("orders", a.season())).as(entry.get("file").asString()).isEqualTo(ordersBefore);
                if (decision.isObject()) {
                    assertThat(result.get("schemaViolations")).as(entry.get("file").asString()).isNotEmpty();
                }
            }
            // Free the turn for the next fixture: the record is append-only through the application, not in the test.
            jdbc.update("DELETE FROM agent_call WHERE season_id = ?", a.season());
        }
    }

    // ---- idempotency ---------------------------------------------------------------------------------------------

    @Test
    void the_same_decision_again_answers_the_stored_result_and_writes_nothing() throws Exception {
        Agent a = startAgent();
        ObjectNode body = decisionBody(dec(null, order("KR:000660", "BUY", 1, "FORWARD_BREAKOUT"),
                order("KR:999999", "BUY", 1, "MIDFIELDER")));
        JsonNode first = decideOk(a, 0, body);
        long calls = count("agent_call", a.season());
        long trades = count("orders", a.season());
        long ledger = count("cash_ledger", a.season());

        // same decision, keys written in another order, other call texts
        ObjectNode again = mapper.createObjectNode();
        ObjectNode reordered = mapper.createObjectNode();
        List<String> names = new ArrayList<>(body.get("decision").propertyNames());
        java.util.Collections.reverse(names);
        names.forEach(n -> reordered.set(n, body.get("decision").get(n)));
        again.set("calls", mapper.createArrayNode().add(call(1, "OK").put("requestText", "another text")));
        again.set("decision", reordered);
        again.put("outcome", "DECISION");
        JsonNode second = decideOk(a, 0, again);

        assertThat(second.get("replayed").asBoolean()).isTrue();
        assertThat(codes(second)).isEqualTo(codes(first));
        assertThat(second.get("orders")).isEqualTo(first.get("orders"));
        assertThat(count("agent_call", a.season())).isEqualTo(calls);
        assertThat(count("orders", a.season())).isEqualTo(trades);
        assertThat(count("cash_ledger", a.season())).isEqualTo(ledger);
        assertThat(json(get(a.turns() + "/0/decision")).get("replayed").asBoolean()).isTrue();
    }

    @Test
    void a_different_decision_for_a_decided_turn_is_409_and_changes_nothing() throws Exception {
        Agent a = startAgent();
        decideOk(a, 0, decisionBody(dec(null, order("KR:000660", "BUY", 1, "FORWARD_BREAKOUT"))));
        long calls = count("agent_call", a.season());

        MockHttpServletResponse r = decide(a, 0, decisionBody(dec(null, order("KR:000660", "BUY", 2, "FORWARD_BREAKOUT"))));
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("DECISION_CONFLICT");
        MockHttpServletResponse other = decide(a, 0, noResponseBody());
        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(count("agent_call", a.season())).isEqualTo(calls);
        assertThat(count("orders", a.season())).isEqualTo(1);
    }

    @Test
    void two_identical_submits_at_once_apply_the_decision_exactly_once() throws Exception {
        Agent a = startAgent();
        DecisionRequest request = mapper.readValue(decisionBody(dec(null, order("KR:000660", "BUY", 3, "FORWARD_BREAKOUT"),
                order("US:SPY", "BUY", 2, "DEFENDER"))).toString(), DecisionRequest.class);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> task = () -> {
                start.await();
                return acting(a.id(), () -> decisions.submit(a.id(), a.season(), 0, request)).replayed();
            };
            Future<Boolean> one = pool.submit(task);
            Future<Boolean> two = pool.submit(task);
            start.countDown();
            List<Boolean> replayed = List.of(one.get(), two.get());
            assertThat(replayed).containsExactlyInAnyOrder(false, true);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("orders", a.season())).isEqualTo(2);
        assertThat(count("agent_call", a.season())).isEqualTo(1);
        assertThat(count("cash_ledger", a.season())).isEqualTo(1 + 2 + 2);
    }

    // ---- attempts ------------------------------------------------------------------------------------------------

    @Test
    void flushed_attempts_are_not_stored_twice_and_a_changed_one_is_409() throws Exception {
        Agent a = startAgent();
        String uri = a.turns() + "/0/calls";
        ObjectNode first = call(1, "TIMEOUT");
        ObjectNode flush = mapper.createObjectNode();
        flush.putArray("calls").add(first);
        assertThat(post(mvc, uri, TOKEN, flush.toString(), true).getStatus()).isEqualTo(204);
        assertThat(post(mvc, uri, TOKEN, flush.toString(), true).getStatus()).isEqualTo(204);
        assertThat(count("agent_call", a.season())).isEqualTo(1);

        ObjectNode changed = first.deepCopy();
        changed.put("requestText", "something else");
        ObjectNode flushChanged = mapper.createObjectNode();
        flushChanged.putArray("calls").add(changed);
        MockHttpServletResponse r = post(mvc, uri, TOKEN, flushChanged.toString(), true);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("DECISION_CONFLICT");

        JsonNode result = decideOk(a, 0, decisionBody(dec(null, order("US:SPY", "BUY", 1, "DEFENDER")), first, call(2, "OK")));
        assertThat(result.get("outcome").asString()).isEqualTo("DECISION");
        assertThat(jdbc.queryForList("SELECT attempt_no, is_final FROM agent_call WHERE season_id = ? ORDER BY attempt_no",
                a.season())).extracting(row -> row.get("attempt_no") + ":" + row.get("is_final"))
                .containsExactly("1:false", "2:true");

        // a decided turn takes no new attempt, but a stored one may be sent again
        assertThat(post(mvc, uri, TOKEN, flush.toString(), true).getStatus()).isEqualTo(204);
        ObjectNode late = mapper.createObjectNode();
        late.putArray("calls").add(call(3, "OK"));
        assertThat(json(post(mvc, uri, TOKEN, late.toString(), true)).get("code").asString()).isEqualTo("DECISION_CONFLICT");
    }

    @Test
    void a_decision_whose_attempts_start_after_the_next_free_number_is_400() throws Exception {
        Agent a = startAgent();
        MockHttpServletResponse r = decide(a, 0, decisionBody(dec(null), call(3, "OK")));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(count("agent_call", a.season())).isZero();
    }

    // ---- envelope ------------------------------------------------------------------------------------------------

    @Test
    void a_wrong_envelope_is_400_and_nothing_is_recorded_nor_echoed() throws Exception {
        Agent a = startAgent();
        String secretLike = "sk-or-" + "v1-" + "x".repeat(24);
        List<ObjectNode> bodies = new ArrayList<>();
        ObjectNode emptyCalls = decisionBody(dec(null));
        emptyCalls.putArray("calls");
        bodies.add(emptyCalls);
        ObjectNode gap = decisionBody(dec(null), call(1, "OK"), call(3, "OK"));
        bodies.add(gap);
        bodies.add(decisionBody(dec(null), call(1, "TIMEOUT")));
        ObjectNode noDecision = decisionBody(dec(null));
        noDecision.putNull("decision");
        bodies.add(noDecision);
        ObjectNode noResponseWithDecision = noResponseBody();
        noResponseWithDecision.set("decision", dec(null));
        bodies.add(noResponseWithDecision);
        ObjectNode noResponseWithOk = noResponseBody();
        ((ObjectNode) noResponseWithOk.get("calls").get(0)).put("status", "OK");
        bodies.add(noResponseWithOk);
        bodies.add(decisionBody(dec(null), call(1, "OK").put("requestText", "x".repeat(262_145))));
        bodies.add(decisionBody(dec(null), call(1, "OK").put("responseText", "a\u0000b")));
        bodies.add(decisionBody(dec(null), call(1, "OK").put("requestText", "key " + secretLike)));
        bodies.add(decisionBody(dec(null), call(1, "OK").put("requestText", "Authorization: Bearer abcdefghij123456")));
        bodies.add(decisionBody(dec(null), call(1, "OK").put("costUsd", "-0.5")));
        bodies.add(decisionBody(dec(null), call(1, "OK").put("tokensIn", -1)));
        bodies.add(decisionBody(dec(null), call(1, "OK").put("latencyMs", -5)));
        bodies.add(decisionBody(dec(null), call(1, "OK").put("modelRequested", "other/model")));
        ObjectNode noLedger = decisionBody(dec(null));
        ((ObjectNode) noLedger.get("calls").get(0)).remove("ledgerCallId");
        bodies.add(noLedger);
        ObjectNode withSecretInDecision = decisionBody(dec(null));
        ((ObjectNode) withSecretInDecision.get("decision")).put("reason", secretLike);
        bodies.add(withSecretInDecision);

        for (ObjectNode body : bodies) {
            MockHttpServletResponse r = decide(a, 0, body);
            assertThat(r.getStatus()).as(body.toString().substring(0, Math.min(120, body.toString().length()))).isEqualTo(400);
            assertThat(r.getContentAsString()).doesNotContain(secretLike).doesNotContain("abcdefghij123456");
            assertThat(count("agent_call", a.season())).isZero();
            assertThat(count("orders", a.season())).isZero();
        }
    }

    // ---- broken state --------------------------------------------------------------------------------------------

    @Test
    void a_broken_ledger_chain_answers_409_and_records_nothing() throws Exception {
        Agent a = startAgent();
        jdbc.update("INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (?, 'ADJUSTMENT', -1, 29999999)",
                a.season());

        MockHttpServletResponse r = decide(a, 0, decisionBody(dec(null, order("US:SPY", "BUY", 1, "DEFENDER"))));

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("LEDGER_MISMATCH");
        assertThat(count("agent_call", a.season())).isZero();
        assertThat(count("orders", a.season())).isZero();
    }

    @Test
    void a_broken_state_found_after_a_unit_was_applied_rolls_back_the_whole_decision() throws Exception {
        Agent a = startAgent();
        decideOk(a, 0, decisionBody(dec(null, order("KR:000660", "BUY", 10, "FORWARD_BREAKOUT"))));
        advanceApi(a, 0);
        jdbc.update("UPDATE lineup SET end_turn = 0 WHERE season_id = ?", a.season());
        long callsBefore = count("agent_call", a.season());
        long tradesBefore = count("orders", a.season());

        // the formation change applies first; the sell then finds the broken lineup
        MockHttpServletResponse r = decide(a, 1, decisionBody(dec(formation(10, 20, 30, 40),
                order("KR:000660", "SELL", 1, null))));

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("LINEUP_MISMATCH");
        assertThat(count("formation_change", a.season())).isZero();
        assertThat(jdbc.queryForObject("SELECT current_defender FROM season WHERE id = ?", Integer.class, a.season())).isEqualTo(30);
        assertThat(count("agent_call", a.season())).isEqualTo(callsBefore);
        assertThat(count("orders", a.season())).isEqualTo(tradesBefore);
    }

    // ---- turn, season and agent guards ---------------------------------------------------------------------------

    @Test
    void a_stale_turn_a_missing_season_and_another_agents_season_are_refused() throws Exception {
        Agent a = startAgent();
        Agent b = startAgent();
        assertThat(json(decide(a, 3, decisionBody(dec(null)))).get("code").asString()).isEqualTo("TURN_MISMATCH");
        assertThat(post(mvc, AGENTS + "/" + a.id() + "/seasons/987654321/turns/0/decision", TOKEN,
                decisionBody(dec(null)).toString(), true).getStatus()).isEqualTo(404);
        MockHttpServletResponse crossed = post(mvc, AGENTS + "/" + a.id() + "/seasons/" + b.season() + "/turns/0/decision", TOKEN,
                decisionBody(dec(null)).toString(), true);
        assertThat(crossed.getStatus()).isEqualTo(404);
        assertThat(count("agent_call", b.season())).isZero();
        assertThat(decide(a, -1, decisionBody(dec(null))).getStatus()).isEqualTo(400);
    }

    @Test
    void the_turn_does_not_advance_without_a_final_decision_and_advances_after_a_no_response() throws Exception {
        Agent a = startAgent();
        MockHttpServletResponse missing = post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": 0}", true);
        assertThat(missing.getStatus()).isEqualTo(409);
        assertThat(json(missing).get("code").asString()).isEqualTo("DECISION_MISSING");
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, a.season())).isZero();
        assertThat(count("turn_price", a.season())).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 0", Long.class, a.season()));

        // a flushed attempt is not a decision
        ObjectNode flush = mapper.createObjectNode();
        ObjectNode flushed = call(1, "TIMEOUT");
        flush.putArray("calls").add(flushed);
        assertThat(post(mvc, a.turns() + "/0/calls", TOKEN, flush.toString(), true).getStatus()).isEqualTo(204);
        assertThat(json(post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": 0}", true)).get("code").asString())
                .isEqualTo("DECISION_MISSING");

        ObjectNode last = noResponseBody();
        ((ArrayNode) last.get("calls")).insert(0, flushed);
        ((ObjectNode) last.get("calls").get(1)).put("attemptNo", 2);
        JsonNode result = decideOk(a, 0, last);
        assertThat(result.get("outcome").asString()).isEqualTo("NO_RESPONSE");
        assertThat(codes(result)).containsExactly("F:NONE:-", "T:NONE");
        advanceApi(a, 0);
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, a.season())).isEqualTo(1);
        assertThat(count("orders", a.season())).isZero();
        assertThat(json(post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": 7}", true)).get("code").asString())
                .isEqualTo("TURN_MISMATCH");
        assertThat(post(mvc, AGENTS + "/" + a.id() + "/seasons/987654321/turns", TOKEN, "{\"expectedTurnNo\": 0}", true)
                .getStatus()).isEqualTo(404);
    }

    @Test
    void the_stored_result_reads_back_and_the_next_view_shows_its_rejects() throws Exception {
        Agent a = startAgent();
        JsonNode result = decideOk(a, 0, decisionBody(dec(formation(10, 20, 30, 40),
                order("KR:999999", "BUY", 1, "MIDFIELDER"), order("US:SPY", "BUY", 1, "DEFENDER"))));
        MockHttpServletResponse read = get(a.turns() + "/0/decision");
        assertThat(read.getStatus()).isEqualTo(200);
        JsonNode stored = json(read);
        assertThat(stored.get("replayed").asBoolean()).isTrue();
        assertThat(stored.get("orders")).isEqualTo(result.get("orders"));
        assertThat(stored.get("formationChange")).isEqualTo(result.get("formationChange"));

        assertThat(get(a.turns() + "/1/decision").getStatus()).isEqualTo(409);
        assertThat(json(get(a.turns() + "/1/decision")).get("code").asString()).isEqualTo("TURN_NOT_REACHED");
        assertThat(get(a.turns() + "/-1/decision").getStatus()).isEqualTo(400);
        Agent fresh = startAgent();
        assertThat(get(fresh.turns() + "/0/decision").getStatus()).isEqualTo(404);

        advanceApi(a, 0);
        JsonNode view = json(get(AGENTS + "/" + a.id() + "/seasons/" + a.season() + "/turn-view"));
        assertThat(view.get("agent").get("lastRejects").toString()).contains("TURN_ZERO_FORMATION").contains("ASSET_NOT_IN_POOL");
        assertThat(view.get("agent").get("recentTrades")).hasSize(1);
    }

    @Test
    void the_result_has_exactly_the_documented_keys_and_no_model_text() throws Exception {
        Agent a = startAgent();
        JsonNode result = decideOk(a, 0, decisionBody(dec(null, order("US:SPY", "BUY", 1, "DEFENDER"))));
        assertThat(keys(result)).containsExactlyInAnyOrder("seasonId", "turnNo", "outcome", "replayed", "formationChange",
                "orders", "theses", "schemaViolations");
        assertThat(keys(result.get("formationChange"))).containsExactlyInAnyOrder("status", "code", "formationChangeId");
        assertThat(keys(result.get("orders").get(0))).containsExactlyInAnyOrder("index", "status", "code", "orderId");
        assertThat(result.toString()).doesNotContain("request 1").doesNotContain("reply 1").doesNotContain("persona");
    }

    @Test
    void a_nul_character_anywhere_in_the_reply_is_400_not_500() throws Exception {
        Agent a = startAgent();
        ObjectNode inReason = dec(null);
        inReason.put("reason", "a\u0000b");
        ObjectNode inFormation = dec(formation(10, 30, 30, 30));
        ((ObjectNode) inFormation.get("formation_change")).put("reason", "a\u0000b");
        ObjectNode inKey = dec(null);
        inKey.put("k\u0000", 1);
        ObjectNode inThesis = dec(null);
        ((ArrayNode) inThesis.get("theses")).addObject().put("note", "x\u0000");
        for (ObjectNode bad : List.of(inReason, inFormation, inKey, inThesis)) {
            MockHttpServletResponse r = decide(a, 0, decisionBody(bad));
            assertThat(r.getStatus()).isEqualTo(400);
            assertThat(count("agent_call", a.season())).isZero();
        }
    }

    @Test
    void an_invalid_verdict_and_a_no_response_for_one_turn_are_different_decisions() throws Exception {
        Agent a = startAgent();
        ObjectNode invalid = mapper.createObjectNode();
        invalid.put("outcome", "INVALID");
        invalid.putNull("decision");
        invalid.putArray("calls").add(call(1, "INVALID"));
        decideOk(a, 0, invalid);

        MockHttpServletResponse r = decide(a, 0, noResponseBody());

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("DECISION_CONFLICT");
    }

    @Test
    void an_attempt_number_one_past_the_next_free_one_is_400() throws Exception {
        Agent a = startAgent();
        ObjectNode flush = mapper.createObjectNode();
        flush.putArray("calls").add(call(1, "TIMEOUT"));
        assertThat(post(mvc, a.turns() + "/0/calls", TOKEN, flush.toString(), true).getStatus()).isEqualTo(204);

        assertThat(decide(a, 0, decisionBody(dec(null), call(3, "OK"))).getStatus()).isEqualTo(400);
        assertThat(decide(a, 0, decisionBody(dec(null), call(2, "OK"))).getStatus()).isEqualTo(200);
    }

    @Test
    void invalid_shares_at_turn_zero_are_answered_by_the_turn_zero_rule() throws Exception {
        Agent a = startAgent();
        JsonNode result = decideOk(a, 0, decisionBody(dec(formation(50, 50, 10, 0))));
        assertThat(codes(result)).containsExactly("F:REJECTED:TURN_ZERO_FORMATION", "T:NONE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"risk-on-risk-off-rotation-regime", "Bearer securities are rare", "task-management-dashboard-v2",
            "disk-space-utilization-alert", "bearer bonds-and-certificates-of-deposit", "risk-adjusted-return-profile", "task-managementdashboardoverview", "risk-assessmentframeworkreview"})
    void ordinary_words_that_look_like_key_prefixes_are_not_secrets(String text) throws Exception {
        Agent a = startAgent();
        ObjectNode body = decisionBody(dec(null), call(1, "OK").put("responseText", text).put("requestText", text));
        ((ObjectNode) body.get("decision")).put("reason", text);
        assertThat(decide(a, 0, body).getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUVWXYZ", "sk-proj-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", "see sk-or-v1-0123456789abcdef0123456789abcdef", "Bearer abcdefghij123456"})
    void key_shaped_tokens_are_refused(String text) throws Exception {
        Agent a = startAgent();
        ObjectNode body = decisionBody(dec(null), call(1, "OK").put("responseText", text));
        MockHttpServletResponse r = decide(a, 0, body);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(r.getContentAsString()).doesNotContain(text);
    }

    @Test
    void a_turn_number_ahead_of_the_current_one_never_passes_the_guard() throws Exception {
        Agent a = startAgent();
        decideOk(a, 0, noResponseBody());
        // turn 0 has a decision; turn 1 has none and must not be reachable by asking for it early
        MockHttpServletResponse ahead = post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": 1}", true);
        assertThat(ahead.getStatus()).isEqualTo(409);
        assertThat(json(ahead).get("code").asString()).isEqualTo("TURN_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, a.season())).isZero();
        advanceApi(a, 0);
        assertThat(json(post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": 2}", true)).get("code").asString())
                .isEqualTo("TURN_MISMATCH");
        assertThat(json(post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": 1}", true)).get("code").asString())
                .isEqualTo("DECISION_MISSING");
    }

    @Test
    void a_ledger_call_id_used_twice_is_400_not_500() throws Exception {
        Agent a = startAgent();
        ObjectNode one = call(1, "TIMEOUT");
        ObjectNode two = call(2, "OK");
        two.put("ledgerCallId", one.get("ledgerCallId").asString());
        assertThat(decide(a, 0, decisionBody(dec(null), one, two)).getStatus()).isEqualTo(400);

        decideOk(a, 0, decisionBody(dec(null), call(1, "OK").put("ledgerCallId", one.get("ledgerCallId").asString())));
        Agent b = startAgent();
        MockHttpServletResponse reused = decide(b, 0, decisionBody(dec(null),
                call(1, "OK").put("ledgerCallId", one.get("ledgerCallId").asString())));
        assertThat(reused.getStatus()).isEqualTo(400);
        assertThat(count("agent_call", b.season())).isZero();
        ObjectNode flush = mapper.createObjectNode();
        flush.putArray("calls").add(call(1, "TIMEOUT").put("ledgerCallId", one.get("ledgerCallId").asString()));
        assertThat(post(mvc, b.turns() + "/0/calls", TOKEN, flush.toString(), true).getStatus()).isEqualTo(400);
    }

    @Test
    void a_decision_or_a_body_over_the_size_bounds_is_400() throws Exception {
        Agent a = startAgent();
        ObjectNode big = dec(null);
        ((ArrayNode) big.get("theses")).addObject().put("note", "x".repeat(262_200));
        assertThat(decide(a, 0, decisionBody(big)).getStatus()).isEqualTo(400);
        assertThat(count("agent_call", a.season())).isZero();

        String huge = "{\"outcome\": \"NO_RESPONSE\", \"decision\": null, \"calls\": [], \"pad\": \""
                + "x".repeat(13 * 1024 * 1024) + "\"}";
        MockHttpServletResponse r = post(mvc, a.turns() + "/0/decision", TOKEN, huge, true);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void a_sell_with_a_declared_position_and_no_holding_names_the_position_first() throws Exception {
        Agent a = startAgent();
        JsonNode result = decideOk(a, 0, decisionBody(dec(null, order("KR:000660", "SELL", 1, "DEFENDER"))));
        assertThat(codes(result)).containsExactly("F:NONE:-", "0:REJECTED:POSITION_ON_SELL", "T:NONE");
    }

    private static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    @Test
    void every_answer_seen_on_the_new_operations_is_listed_in_the_contract() throws Exception {
        JsonNode doc = antlab.support.OpenApiNormalizer.fromYaml(java.nio.file.Files.readString(java.nio.file.Path.of("docs/api/agent-runner.yaml")));
        Agent a = startAgent();
        String decision = "POST " + AGENTS + "/{agentId}/seasons/{seasonId}/turns/{turnNo}/decision";
        String calls = "POST " + AGENTS + "/{agentId}/seasons/{seasonId}/turns/{turnNo}/calls";
        String read = "GET " + AGENTS + "/{agentId}/seasons/{seasonId}/turns/{turnNo}/decision";
        String advance = "POST " + AGENTS + "/{agentId}/seasons/{seasonId}/turns";
        ObjectNode emptyCalls = decisionBody(dec(null));
        emptyCalls.putArray("calls");
        assertListed(doc, decision, decide(a, 0, emptyCalls));
        assertListed(doc, decision, decide(a, 4, decisionBody(dec(null))));
        assertListed(doc, decision, post(mvc, AGENTS + "/" + a.id() + "/seasons/987654321/turns/0/decision", TOKEN,
                decisionBody(dec(null)).toString(), true));
        ObjectNode flush = mapper.createObjectNode();
        flush.putArray("calls");
        assertListed(doc, calls, post(mvc, a.turns() + "/0/calls", TOKEN, flush.toString(), true));
        assertListed(doc, calls, post(mvc, a.turns() + "/5/calls", TOKEN,
                mapper.createObjectNode().set("calls", mapper.createArrayNode().add(call(1, "OK"))).toString(), true));
        assertListed(doc, read, get(a.turns() + "/0/decision"));
        assertListed(doc, read, get(a.turns() + "/3/decision"));
        assertListed(doc, read, get(a.turns() + "/-2/decision"));
        assertListed(doc, advance, post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": 0}", true));
        assertListed(doc, advance, post(mvc, a.turns(), TOKEN, "{\"expectedTurnNo\": 9}", true));
        assertListed(doc, advance, post(mvc, a.turns(), TOKEN, "{}", true));
        assertListed(doc, advance, post(mvc, AGENTS + "/" + a.id() + "/seasons/987654321/turns", TOKEN,
                "{\"expectedTurnNo\": 0}", true));
        decideOk(a, 0, decisionBody(dec(null)));
        assertListed(doc, decision, decide(a, 0, decisionBody(dec(null, order("US:SPY", "BUY", 1, "DEFENDER")))));
        advanceApi(a, 0);
        jdbc.update("INSERT INTO cash_ledger (season_id, type, amount, balance_after) VALUES (?, 'ADJUSTMENT', -1, 1)", a.season());
        assertListed(doc, decision, decide(a, 1, decisionBody(dec(null))));
    }

    private static void assertListed(JsonNode doc, String operation, MockHttpServletResponse response) throws Exception {
        String code = json(response).get("code").asString();
        assertThat(antlab.support.ContractErrorCodes.isListed(doc, operation, response.getStatus(), code))
                .as(operation + " " + response.getStatus() + " " + code).isTrue();
    }
}
