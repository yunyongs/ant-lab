package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.HEADER;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.TOKEN;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.HoldingsDto;
import antlab.account.HoldingsQueryService;
import antlab.account.OrderService;
import antlab.account.OrderSide;
import antlab.account.PlaceOrderRequest;
import antlab.agent.AgentCall;
import antlab.agent.AgentCallKind;
import antlab.agent.AgentCallRepository;
import antlab.agent.AgentCallStatus;
import antlab.agent.AgentPartDto;
import antlab.agent.AgentTurnViewDto;
import antlab.agent.AgentTurnViewService;
import antlab.agent.DecisionOutcome;
import antlab.agent.RecentTradeDto;
import antlab.agent.RejectDto;
import antlab.asset.PoolService;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.asset.Position;
import antlab.auth.AgentActingAuthentication;
import antlab.history.ReturnKind;
import antlab.season.SeasonQueryService;
import antlab.season.TurnFacade;
import antlab.season.TurnQueryService;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /internal/agent-runner/v1/agents/{agentId}/seasons/{seasonId}/turn-view. Committing mode: orders and turn
 * advances run through the human services under the acting agent, as the later decision endpoint will. The embedded
 * parts equal what the human services answer, the agent part holds only past turns, and nothing of the history leaks.
 */
@ExtendWith(OutputCaptureExtension.class)
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentTurnViewTest {

    private static final Set<String> FORBIDDEN_KEYS = Set.of("seed", "engineVersion", "dataSnapshotDate", "baseDate",
            "monthBlockId", "monthBlock", "monthStart", "memberId", "version", "createdAt", "updatedAt", "attemptNo",
            "attempt", "checkCode", "mismatchCount", "reconciliation", "results", "path", "returnKind", "localReturn",
            "fxChange", "month", "year", "date", "config", "personaSha256", "modelRequested", "requestText",
            "responseText", "ledgerCallId", "costUsd");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper mapper;
    @Autowired
    OrderService orders;
    @Autowired
    TurnFacade turns;
    @Autowired
    SeasonQueryService seasonQueries;
    @Autowired
    TurnQueryService turnQueries;
    @Autowired
    HoldingsQueryService holdingsQueries;
    @Autowired
    PoolService pools;
    @Autowired
    AgentCallRepository calls;
    @Autowired
    AgentTurnViewService turnViews;
    @Autowired
    antlab.member.CurrentMember currentMember;
    @Autowired
    antlab.account.OrderRepository orderRepository;
    @Autowired
    TestTokens tokens;
    @Autowired
    TestMembers members;

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

    private record Agent(long id, long season) {
    }

    private Agent startAgent(String persona, String model, String name) throws Exception {
        long agent = AgentRunnerSupport.register(mvc, registerBody(persona, "OPENROUTER", model, name));
        MockHttpServletResponse started = post(mvc, AGENTS + "/" + agent + "/seasons",
                START_BODY.replace("fake/model-1", model));
        assertThat(started.getStatus()).isEqualTo(201);
        return new Agent(agent, json(started).get("id").asLong());
    }

    private Agent startAgent() throws Exception {
        return startAgent("value-investor", "fake/model-1", "가치 투자자");
    }

    /** Runs a service call as the acting agent, the way the runner filter would set the context for a request. */
    private <T> T acting(long agent, Supplier<T> call) {
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long assetId(String ticker) {
        return jdbc.queryForObject("SELECT id FROM asset WHERE ticker = ?", Long.class, ticker);
    }

    private void order(Agent a, int turn, OrderSide side, String ticker, int quantity, Position position) {
        acting(a.id(), () -> orders.place(a.season(),
                new PlaceOrderRequest(turn, side, assetId(ticker), quantity, position, null)));
    }

    private void advance(Agent a, int fromTurn) {
        acting(a.id(), () -> turns.advance(a.season(), fromTurn));
    }

    private MockHttpServletResponse view(long agent, long season, String token) {
        var request = mvc.get().uri(AGENTS + "/" + agent + "/seasons/" + season + "/turn-view")
                .requestAttr(ANONYMOUS, true);
        if (token != null) {
            request = request.header(HEADER, token);
        }
        return request.exchange().getResponse();
    }

    private MockHttpServletResponse view(Agent a) {
        return view(a.id(), a.season(), TOKEN);
    }

    private JsonNode viewJson(Agent a) throws Exception {
        MockHttpServletResponse r = view(a);
        assertThat(r.getStatus()).isEqualTo(200);
        return json(r);
    }

    /** Buys of turn 0, a sell in turn 1, a buy in turn 2, a sell in turn 3; the season is then at turn 4. */
    private void playToTurnFour(Agent a) {
        order(a, 0, OrderSide.BUY, "000660", 10, Position.FORWARD_BREAKOUT);
        order(a, 0, OrderSide.BUY, "069500", 100, Position.MIDFIELDER);
        order(a, 0, OrderSide.BUY, "SPY", 5, Position.DEFENDER);
        order(a, 0, OrderSide.BUY, "BRK.B", 5, Position.GOALKEEPER);
        advance(a, 0);
        order(a, 1, OrderSide.SELL, "000660", 2, null);
        advance(a, 1);
        order(a, 2, OrderSide.BUY, "SPY", 1, null);
        advance(a, 2);
        order(a, 3, OrderSide.SELL, "069500", 10, null);
        advance(a, 3);
    }

    /** The JSON the application would write for a value (money as plain strings), read back as a tree. */
    private JsonNode written(Object value) {
        return mapper.readTree(mapper.writeValueAsString(value));
    }

    private static Set<String> components(Class<?> record) {
        Set<String> out = new HashSet<>();
        Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).forEach(out::add);
        return out;
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new HashSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static void allKeys(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                out.add(e.getKey());
                allKeys(e.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(n -> allKeys(n, out));
        }
    }

    private static JsonNode without(JsonNode node, String... names) {
        JsonNode copy = node.deepCopy();
        if (copy instanceof tools.jackson.databind.node.ObjectNode object) {
            object.remove(Arrays.asList(names));
        }
        return copy;
    }

    private static String won(Object dbValue) {
        return ((BigDecimal) dbValue).setScale(0, RoundingMode.UNNECESSARY).toPlainString();
    }

    private void saveFinal(Agent a, int turn, int attempt, DecisionOutcome outcome, String rejects) {
        calls.save(new AgentCall(a.season(), turn, attempt, AgentCallKind.TURN_DECISION, UUID.randomUUID(),
                AgentCallStatus.OK, 200, "fake/model-1", "fake/model-1", "fake", "request", "reply", null, 10, 5,
                new BigDecimal("0.00010000"), 100, outcome, rejects, "b".repeat(64)));
    }

    private void saveAttempt(Agent a, int turn, int attempt) {
        calls.save(new AgentCall(a.season(), turn, attempt, AgentCallKind.TURN_DECISION, UUID.randomUUID(),
                AgentCallStatus.TIMEOUT, null, "fake/model-1", null, null, "request", null, null, null, null, null, 100,
                null, null, null));
    }

    // ---- turn 0 and equality with the human endpoints ------------------------------------------------------------

    @Test
    void at_turn_zero_the_last_turn_and_the_agent_history_are_empty_and_the_rest_is_what_a_human_sees() throws Exception {
        Agent a = startAgent();
        JsonNode body = viewJson(a);

        assertThat(keys(body)).isEqualTo(components(AgentTurnViewDto.class));
        assertThat(body.get("lastTurn").isNull()).isTrue();
        JsonNode agent = body.get("agent");
        assertThat(keys(agent)).isEqualTo(components(AgentPartDto.class));
        assertThat(agent.get("schema").asString()).isEqualTo("turn-decision/1");
        assertThat(agent.get("recentTrades").isArray()).isTrue();
        assertThat(agent.get("recentTrades")).isEmpty();
        assertThat(agent.get("lastRejects")).isEmpty();
        assertThat(agent.get("memory").isNull()).isTrue();

        // the same season opened by a human gives the same values; only the season number differs
        long humanSeason = json(mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse())
                .get("id").asLong();
        JsonNode humanSeasonBody = json(mvc.get().uri("/api/v1/seasons/" + humanSeason).exchange().getResponse());
        JsonNode humanHoldings = json(mvc.get().uri("/api/v1/seasons/" + humanSeason + "/holdings").exchange()
                .getResponse());
        JsonNode humanPrices = json(mvc.get().uri("/api/v1/seasons/" + humanSeason + "/prices").exchange()
                .getResponse());
        JsonNode humanPool = json(mvc.get().uri("/api/v1/pool").exchange().getResponse());

        assertThat(without(body.get("season"), "id")).isEqualTo(without(humanSeasonBody, "id"));
        assertThat(without(body.get("holdings"), "seasonId")).isEqualTo(without(humanHoldings, "seasonId"));
        assertThat(body.get("prices")).isEqualTo(humanPrices);
        assertThat(body.get("pool")).isEqualTo(humanPool.get("assets"));
    }

    @Test
    void every_embedded_part_equals_the_answer_of_the_human_service_for_the_same_season() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        JsonNode body = viewJson(a);

        Object[] human = acting(a.id(), () -> new Object[] {seasonQueries.get(a.season()),
                turnQueries.get(a.season(), 4), holdingsQueries.get(a.season()), seasonQueries.prices(a.season(), null),
                pools.pool().assets()});
        assertThat(body.get("season")).isEqualTo(written(human[0]));
        assertThat(body.get("lastTurn")).isEqualTo(written(human[1]));
        assertThat(body.get("holdings")).isEqualTo(written(human[2]));
        assertThat(body.get("prices")).isEqualTo(written(human[3]));
        assertThat(body.get("pool")).isEqualTo(written(human[4]));

        assertThat(body.get("season").get("currentTurnNo").asInt()).isEqualTo(4);
        assertThat(body.get("lastTurn").get("turnNo").asInt()).isEqualTo(4);
        assertThat(body.get("lastTurn").get("feed")).as("the feed of turn 4 is embedded, as the human turn answers it").isNotEmpty();
        assertThat(body.get("prices").get("turnNo").asInt()).isEqualTo(4);
        assertThat(((HoldingsDto) human[2]).turnNo()).isEqualTo(4);
        assertThat(body.get("pool")).isNotEmpty();
    }

    // ---- the agent part: only past turns ---------------------------------------------------------------------------

    @Test
    void recent_trades_are_the_own_fills_of_the_three_turns_before_the_current_one_and_nothing_else() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        // a fill of the current turn must not show up either
        order(a, 4, OrderSide.BUY, "BRK.B", 1, null);

        JsonNode trades = viewJson(a).get("agent").get("recentTrades");
        List<Map<String, Object>> expected = jdbc.queryForList("""
                SELECT o.turn_no, o.side, a.market || ':' || a.ticker AS asset, e.quantity, e.fill_price, e.fee, o.source
                FROM orders o JOIN execution e ON e.order_id = o.id JOIN asset a ON a.id = o.asset_id
                WHERE o.season_id = ? AND o.turn_no BETWEEN 1 AND 3 ORDER BY o.turn_no, o.id""", a.season());
        assertThat(expected).hasSize(3);
        assertThat(trades).hasSize(3);
        for (int i = 0; i < 3; i++) {
            JsonNode t = trades.get(i);
            Map<String, Object> row = expected.get(i);
            assertThat(keys(t)).isEqualTo(components(RecentTradeDto.class));
            assertThat(t.get("turnNo").asInt()).isEqualTo(((Number) row.get("turn_no")).intValue());
            assertThat(t.get("side").asString()).isEqualTo(row.get("side"));
            assertThat(t.get("asset").asString()).isEqualTo(row.get("asset"));
            assertThat(t.get("quantity").asInt()).isEqualTo(((Number) row.get("quantity")).intValue());
            assertThat(t.get("price").asString()).isEqualTo(won(row.get("fill_price")));
            assertThat(t.get("fee").asString()).isEqualTo(won(row.get("fee")));
            assertThat(t.get("source").asString()).isEqualTo(row.get("source"));
        }
        assertThat(trades.get(0).get("turnNo").asInt()).isEqualTo(1);
        assertThat(trades.get(0).get("side").asString()).isEqualTo("SELL");
        assertThat(trades.get(0).get("asset").asString()).isEqualTo("KR:000660");
        assertThat(trades.get(2).get("turnNo").asInt()).isEqualTo(3);
        // the fills of turn 0 and of the current turn 4 exist but are outside the window
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ? AND turn_no IN (0, 4)",
                Long.class, a.season())).isEqualTo(5L);
    }

    @Test
    void a_window_that_reaches_before_turn_zero_is_cut_at_turn_zero() throws Exception {
        Agent a = startAgent();
        order(a, 0, OrderSide.BUY, "000660", 10, Position.FORWARD_BREAKOUT);
        advance(a, 0);
        JsonNode trades = viewJson(a).get("agent").get("recentTrades");
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).get("turnNo").asInt()).isZero();
        assertThat(trades.get(0).get("side").asString()).isEqualTo("BUY");
    }

    @Test
    void another_agents_fills_are_not_shown() throws Exception {
        Agent a = startAgent();
        Agent b = startAgent("momentum", "fake/model-2", "모멘텀");
        order(b, 0, OrderSide.BUY, "SPY", 5, Position.DEFENDER);
        advance(b, 0);
        order(a, 0, OrderSide.BUY, "000660", 10, Position.FORWARD_BREAKOUT);
        advance(a, 0);
        JsonNode trades = viewJson(a).get("agent").get("recentTrades");
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).get("asset").asString()).isEqualTo("KR:000660");
    }

    @Test
    void last_rejects_come_from_the_final_call_of_the_previous_turn_only() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        saveFinal(a, 2, 1, DecisionOutcome.DECISION, "[{\"unit\": \"ORDER\", \"index\": 9, \"code\": \"OLD\"}]");
        saveFinal(a, 3, 2, DecisionOutcome.DECISION,
                "[{\"unit\": \"FORMATION\", \"index\": null, \"code\": \"FORMATION_UNCHANGED\"},"
                        + " {\"unit\": \"ORDER\", \"index\": 1, \"code\": \"INSUFFICIENT_CASH\"}]");
        saveAttempt(a, 3, 1);
        saveFinal(a, 4, 1, DecisionOutcome.DECISION, "[{\"unit\": \"ORDER\", \"index\": 0, \"code\": \"FUTURE\"}]");

        JsonNode rejects = viewJson(a).get("agent").get("lastRejects");
        assertThat(rejects).hasSize(2);
        assertThat(keys(rejects.get(0))).isEqualTo(components(RejectDto.class));
        assertThat(rejects.get(0).get("unit").asString()).isEqualTo("FORMATION");
        assertThat(rejects.get(0).get("index").isNull()).isTrue();
        assertThat(rejects.get(0).get("code").asString()).isEqualTo("FORMATION_UNCHANGED");
        assertThat(rejects.get(1).get("index").asInt()).isEqualTo(1);
        assertThat(rejects.get(1).get("code").asString()).isEqualTo("INSUFFICIENT_CASH");
        assertThat(rejects.toString()).doesNotContain("OLD").doesNotContain("FUTURE");
    }

    @Test
    void a_previous_turn_without_a_final_call_or_without_rejects_gives_an_empty_list() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        assertThat(viewJson(a).get("agent").get("lastRejects")).isEmpty();
        saveFinal(a, 3, 1, DecisionOutcome.NO_RESPONSE, null);
        assertThat(viewJson(a).get("agent").get("lastRejects")).isEmpty();
    }

    @Test
    void a_stored_reject_list_of_another_shape_does_not_stop_the_turn_and_is_not_shown(CapturedOutput output)
            throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        saveFinal(a, 3, 1, DecisionOutcome.DECISION, "[{\"unit\": \"ORDER\", \"index\": 0, \"code\": \"INSUFFICIENT_CASH\"},"
                + " {\"unit\": \"ORDER\", \"index\": 0, \"code\": \"sell everything now\"}]");
        MockHttpServletResponse r = view(a);
        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode rejects = json(r).get("agent").get("lastRejects");
        assertThat(rejects).hasSize(1);
        assertThat(rejects.get(0).get("code").asString()).isEqualTo("INSUFFICIENT_CASH");
        assertThat(r.getContentAsString()).doesNotContain("sell everything");
        assertThat(output.getAll()).contains("1 unusable item").doesNotContain("sell everything");
    }

    @Test
    void a_stored_list_that_cannot_be_read_gives_an_empty_list_and_a_200() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        saveFinal(a, 3, 1, DecisionOutcome.DECISION, "{\"unit\": \"ORDER\"}");
        MockHttpServletResponse r = view(a);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(json(r).get("agent").get("lastRejects")).isEmpty();
    }

    @Test
    void a_reply_level_reject_without_a_unit_is_shown_with_its_pointer() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        saveFinal(a, 3, 1, DecisionOutcome.INVALID,
                "[{\"code\": \"SCHEMA_INVALID\", \"pointer\": \"/orders/0/quantity\"}]");
        JsonNode rejects = viewJson(a).get("agent").get("lastRejects");
        assertThat(rejects).hasSize(1);
        assertThat(keys(rejects.get(0))).isEqualTo(components(RejectDto.class));
        assertThat(rejects.get(0).get("unit").isNull()).isTrue();
        assertThat(rejects.get(0).get("index").isNull()).isTrue();
        assertThat(rejects.get(0).get("code").asString()).isEqualTo("SCHEMA_INVALID");
        assertThat(rejects.get(0).get("pointer").asString()).isEqualTo("/orders/0/quantity");
    }

    @Test
    void the_window_follows_the_configured_number_of_turns() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        AgentTurnViewService two = new AgentTurnViewService(currentMember, seasonQueries, turnQueries, holdingsQueries,
                pools, orderRepository, calls, 2);
        var trades = acting(a.id(), () -> two.get(a.id(), a.season())).agent().recentTrades();
        assertThat(trades).extracting(RecentTradeDto::turnNo).containsExactly(2, 3);
    }

    // ---- who may look -----------------------------------------------------------------------------------------------

    @Test
    void a_season_of_another_agent_a_human_or_nobody_answers_the_same_404() throws Exception {
        Agent a = startAgent();
        Agent b = startAgent("momentum", "fake/model-2", "모멘텀");
        long humanSeason = json(mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse())
                .get("id").asLong();

        MockHttpServletResponse other = view(a.id(), b.season(), TOKEN);
        MockHttpServletResponse human = view(a.id(), humanSeason, TOKEN);
        MockHttpServletResponse missing = view(a.id(), 987_654_321L, TOKEN);
        MockHttpServletResponse otherAgent = view(b.id(), a.season(), TOKEN);
        for (MockHttpServletResponse r : List.of(other, human, missing, otherAgent)) {
            assertThat(r.getStatus()).isEqualTo(404);
            assertThat(json(r).get("code").asString()).isEqualTo("NOT_FOUND");
        }
        assertThat(without(json(other), "instance")).isEqualTo(without(json(human), "instance"))
                .isEqualTo(without(json(missing), "instance")).isEqualTo(without(json(otherAgent), "instance"));
    }

    @Test
    void the_last_turn_is_the_current_turn_at_every_turn_and_in_a_finished_season() throws Exception {
        Agent a = startAgent();
        order(a, 0, OrderSide.BUY, "000660", 10, Position.FORWARD_BREAKOUT);
        for (int turn = 0; turn < 12; turn++) {
            advance(a, turn);
            int current = turn + 1;
            JsonNode body = viewJson(a);
            assertThat(body.get("season").get("currentTurnNo").asInt()).isEqualTo(current);
            assertThat(body.get("lastTurn").get("turnNo").asInt()).as("turn " + current).isEqualTo(current);
            assertThat(body.get("holdings").get("turnNo").asInt()).isEqualTo(current);
            assertThat(body.get("prices").get("turnNo").asInt()).isEqualTo(current);
            if (current == 12) {
                assertThat(body.get("season").get("status").asString()).isEqualTo("FINISHED");
                assertThat(body.get("lastTurn").get("status").asString()).isEqualTo("FINISHED");
            }
        }
    }

    @Test
    void an_empty_pool_answers_409_pool_not_loaded() throws Exception {
        Agent a = startAgent();
        jdbc.update("DELETE FROM asset_position WHERE base_date = DATE '2026-01-02'");
        MockHttpServletResponse r = view(a);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("POOL_NOT_LOADED");
    }

    @Test
    void an_unjudged_asset_answers_409_pool_not_judged() throws Exception {
        Agent a = startAgent();
        jdbc.update("""
                UPDATE asset_position SET window_start = NULL, window_end = NULL, months_used = NULL,
                    up_participation = NULL, down_participation = NULL, volatility = NULL, max_drawdown = NULL,
                    system_position = NULL
                WHERE base_date = DATE '2026-01-02' AND asset_id = (SELECT id FROM asset WHERE ticker = 'SPY')""");
        MockHttpServletResponse r = view(a);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("POOL_NOT_JUDGED");
    }

    @Test
    void the_service_method_is_read_only_with_repeatable_read() throws Exception {
        var tx = AgentTurnViewService.class.getMethod("get", long.class, long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
    }

    @Test
    void the_service_itself_refuses_a_path_agent_other_than_the_acting_one() throws Exception {
        Agent a = startAgent();
        Agent b = startAgent("momentum", "fake/model-2", "모멘텀");
        assertThat(acting(a.id(), () -> turnViews.get(a.id(), a.season())).season().id()).isEqualTo(a.season());
        // even for the acting agent's own season, a path that names another agent is not found
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> acting(a.id(), () -> turnViews.get(b.id(), a.season())))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void an_agent_id_that_is_a_human_or_unknown_or_not_a_number_is_not_found() throws Exception {
        Agent a = startAgent();
        long human = members.defaultId();
        for (String agentId : new String[] {Long.toString(human), "987654321", "abc", "0", "-1"}) {
            MockHttpServletResponse r = mvc.get().uri(AGENTS + "/" + agentId + "/seasons/" + a.season() + "/turn-view")
                    .header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
            assertThat(r.getStatus()).as(agentId).isEqualTo(404);
        }
    }

    @Test
    void a_season_number_that_is_not_a_number_is_400() throws Exception {
        Agent a = startAgent();
        MockHttpServletResponse r = mvc.get().uri(AGENTS + "/" + a.id() + "/seasons/abc/turn-view")
                .header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void a_missing_or_wrong_token_or_a_human_token_is_401_and_reads_nothing() throws Exception {
        Agent a = startAgent();
        for (String token : new String[] {null, "", "wrong", TOKEN + "x", TOKEN.substring(1)}) {
            MockHttpServletResponse r = view(a.id(), a.season(), token);
            assertThat(r.getStatus()).as("token " + token).isEqualTo(401);
            assertThat(json(r).get("code").asString()).isEqualTo("UNAUTHENTICATED");
            assertThat(r.getContentAsString()).doesNotContain(TOKEN).doesNotContain("holdings");
        }
        MockHttpServletResponse human = mvc.get().uri(AGENTS + "/" + a.id() + "/seasons/" + a.season() + "/turn-view")
                .header("Authorization", "Bearer " + tokens.user(members.defaultId())).requestAttr(ANONYMOUS, true)
                .exchange().getResponse();
        assertThat(human.getStatus()).isEqualTo(401);
    }

    @Test
    void the_view_is_read_only_and_a_retired_agent_can_still_look() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        String before = tableCounts(a.season());
        assertThat(view(a).getStatus()).isEqualTo(200);
        assertThat(view(a).getStatus()).isEqualTo(200);
        assertThat(tableCounts(a.season())).isEqualTo(before);
        jdbc.update("UPDATE agent_profile SET status = 'RETIRED' WHERE member_id = ?", a.id());
        assertThat(view(a).getStatus()).isEqualTo(200);
    }

    private String tableCounts(long season) {
        StringBuilder out = new StringBuilder();
        for (String table : new String[] {"orders", "cash_ledger", "turn", "turn_price", "holding", "agent_call",
                "reconciliation_log"}) {
            out.append(table).append('=').append(
                    jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id = ?", Long.class, season))
                    .append(' ');
        }
        out.append(jdbc.queryForObject("SELECT status || current_turn_no || version FROM season WHERE id = ?",
                String.class, season));
        return out.toString();
    }

    // ---- exposure --------------------------------------------------------------------------------------------------

    @Test
    void the_keys_are_the_record_components_and_nothing_of_the_history_is_in_the_body_or_the_log(
            CapturedOutput output) throws Exception {
        Agent a = startAgent();
        String seed = jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, a.season());
        String engine = jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, a.season());
        playToTurnFour(a);
        saveFinal(a, 3, 1, DecisionOutcome.DECISION, "[{\"unit\": \"ORDER\", \"index\": 0, \"code\": \"INSUFFICIENT_CASH\"}]");

        MockHttpServletResponse response = view(a);
        String text = response.getContentAsString();
        JsonNode body = json(response);
        System.out.println("agent turn view at turn 4, fixture pool of 4 assets: " + text.getBytes().length + " bytes");

        assertThat(keys(body)).isEqualTo(components(AgentTurnViewDto.class));
        assertThat(keys(body.get("lastTurn"))).isEqualTo(components(antlab.season.TurnResultDto.class));
        assertThat(keys(body.get("holdings"))).isEqualTo(components(HoldingsDto.class));
        assertThat(keys(body.get("prices"))).isEqualTo(components(antlab.season.TurnPricesDto.class));
        assertThat(keys(body.get("pool").get(0))).isEqualTo(components(antlab.asset.PoolAssetDto.class));
        assertThat(keys(body.get("agent"))).isEqualTo(components(AgentPartDto.class));
        body.get("agent").get("recentTrades").forEach(t -> assertThat(keys(t)).isEqualTo(components(RecentTradeDto.class)));
        body.get("agent").get("lastRejects").forEach(t -> assertThat(keys(t)).isEqualTo(components(RejectDto.class)));

        Set<String> all = new HashSet<>();
        allKeys(body, all);
        assertThat(all).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);

        assertThat(text).doesNotContain(seed).doesNotContain(engine).doesNotContainPattern("\\d{4}-\\d{2}")
                .doesNotContain("request").doesNotContain("fake/model");
        for (ReturnKind kind : ReturnKind.values()) {
            assertThat(text).as("return kind").doesNotContain(kind.name());
        }
        List<String> months = jdbc.queryForList("SELECT mb.month_start::text FROM season_path sp"
                + " JOIN month_block mb ON mb.id = sp.month_block_id WHERE sp.season_id = ? ORDER BY sp.turn_no",
                String.class, a.season());
        assertThat(months).isNotEmpty();
        months.forEach(m -> assertThat(output.getAll()).as("month in the log").doesNotContain(m));
        for (ReturnKind kind : ReturnKind.values()) {
            assertThat(output.getAll()).as("return kind in the log").doesNotContain(kind.name());
        }
        assertThat(output.getAll()).doesNotContain(engine).doesNotContain(TOKEN).doesNotContain(seed);
    }

    @Test
    void nothing_beyond_the_current_turn_is_read() throws Exception {
        Agent a = startAgent();
        playToTurnFour(a);
        String before = viewJson(a).toString();
        // a violation injected on purpose: a price row of the next turn at twice the price
        int injected = jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price)"
                + " SELECT season_id, turn_no + 1, asset_id, price * 2 FROM turn_price WHERE season_id = ? AND turn_no = 4",
                a.season());
        assertThat(injected).isPositive();
        assertThat(viewJson(a).toString()).isEqualTo(before);
    }
}
