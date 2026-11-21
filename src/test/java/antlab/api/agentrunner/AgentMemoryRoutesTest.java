package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.BASE;
import static antlab.api.agentrunner.AgentRunnerSupport.HEADER;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.TOKEN;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static antlab.api.agentrunner.AgentRunnerSupport.startBody;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderService;
import antlab.account.OrderSide;
import antlab.account.PlaceOrderRequest;
import antlab.agent.AgentCall;
import antlab.agent.AgentCallKind;
import antlab.agent.AgentCallRepository;
import antlab.agent.AgentCallStatus;
import antlab.agent.DecisionOutcome;
import antlab.asset.Position;
import antlab.auth.AgentActingAuthentication;
import antlab.season.SeasonAbandonService;
import antlab.season.TurnFacade;
import antlab.support.EnginePercentileAnswers;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The memory reads and the reflection view of the runner (AG-05 C1), committing mode: seasons are played to the end through
 * the turn facade as the acting agent, the score comes from a fake engine, and the stored memory rows are written by SQL
 * because the write path is C2. Every gate of the view is checked in its fixed order, and an agent only ever reads its own
 * memory.
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.agent-runner.max-seasons-per-week=40")
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql", "/fixtures/replay_macro.sql"})
@Sql(scripts = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentMemoryRoutesTest {

    private static final String FILTER = "mf1-0123456789ab";
    private static final int TURNS = 12;
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();
    private static final String PERCENTILES = "/v1/percentiles";

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ENGINE::close));
    }

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.url", ENGINE::url);
        registry.add("antlab.engine.token", () -> "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop");
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderService orders;
    @Autowired
    TurnFacade turns;
    @Autowired
    SeasonAbandonService abandons;
    @Autowired
    AgentCallRepository calls;

    @BeforeEach
    void setUp() {
        ensureOwner(jdbc);
        ENGINE.reset();
        ENGINE.respond(PERCENTILES, 200, "application/json", EnginePercentileAnswers.middle(2000, TURNS));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        List<Long> seasonIds = jdbc.queryForList("SELECT s.id FROM season s JOIN agent_profile p ON p.member_id = s.member_id "
                + "WHERE p.owner_member_id = ?", Long.class, OWNER_ID);
        List<Long> memberIds = new ArrayList<>(jdbc.queryForList(
                "SELECT member_id FROM agent_profile WHERE owner_member_id = ?", Long.class, OWNER_ID));
        memberIds.add(OWNER_ID);
        // scores and rating rows are made on demand by the reflection view
        for (Long member : memberIds) {
            jdbc.update("DELETE FROM rating_history WHERE member_id = ?", member);
            jdbc.update("DELETE FROM season_score WHERE member_id = ?", member);
        }
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private long agent(String model) throws Exception {
        return AgentRunnerSupport.register(mvc, registerBody("value-investor", "OPENROUTER", model, "에이전트 " + model));
    }

    private long start(long agent, String model, Long league) throws Exception {
        MockHttpServletResponse r = post(mvc, AGENTS + "/" + agent + "/seasons", startBody(model, league, null));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

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

    private void buy(long agent, long season, String ticker, int quantity, Position position) {
        acting(agent, () -> orders.place(season,
                new PlaceOrderRequest(0, OrderSide.BUY, assetId(ticker), quantity, position, null)));
    }

    /** Plays the season to its last turn with no orders. */
    private void play(long agent, long season) {
        play(agent, season, 0);
    }

    private void play(long agent, long season, int fromTurn) {
        for (int t = fromTurn; t < TURNS; t++) {
            int turn = t;
            acting(agent, () -> turns.advance(season, turn));
        }
    }

    private long league() throws Exception {
        MockHttpServletResponse r = post(mvc, BASE + "/leagues", "{\"name\": \"Memory league\"}");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private void closeLeague(long league) throws Exception {
        MockHttpServletResponse r = post(mvc, BASE + "/leagues/" + league + "/close", "");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    /** A sibling league of the group root: the same seed, pointing at the root (the create route comes with PR D). */
    private long sibling(long root) throws Exception {
        long sibling = league();
        jdbc.update("UPDATE league SET seed = (SELECT seed FROM league WHERE id = ?), seed_league_id = ? WHERE id = ?", root,
                root, sibling);
        return sibling;
    }

    /** A season that exists only as the source of a memory version: started, then set to FINISHED by SQL. */
    private long source(long agent, String model) throws Exception {
        long season = start(agent, model, null);
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", season);
        return season;
    }

    private static String reflectionBody(String mark) {
        return "{\"schema\":\"season-reflection/1\",\"good\":[\"" + mark + " a\",\"" + mark + " b\",\"" + mark
                + " c\"],\"mistakes\":[],\"ruleCandidates\":[\"" + mark + " rule\"]}";
    }

    private void outcome(long agent, long season, int version, String mark, boolean carried) {
        jdbc.update("INSERT INTO agent_reflection (season_id, member_id, outcome, body, filter_version) "
                + "VALUES (?, ?, 'ACCEPTED', ?::jsonb, ?)", season, agent, reflectionBody(mark), FILTER);
        jdbc.update("INSERT INTO agent_memory (member_id, version, source_season_id, playbook, playbook_carried, "
                + "filter_version) VALUES (?, ?, ?, ?::jsonb, ?, ?)", agent, version, season,
                "{\"schema\":\"operating-note/1\",\"rules\":[\"" + mark + " playbook\"]}", carried, FILTER);
    }

    private MockHttpServletResponse get(String uri) {
        return mvc.get().uri(uri).header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
    }

    private JsonNode ok(String uri) throws Exception {
        MockHttpServletResponse r = get(uri);
        assertThat(r.getStatus()).as(uri + " " + r.getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private String refused(String uri, int status) throws Exception {
        MockHttpServletResponse r = get(uri);
        assertThat(r.getStatus()).as(uri + " " + r.getContentAsString()).isEqualTo(status);
        return json(r).get("code").asString();
    }

    private static String view(long agent, long season) {
        return AGENTS + "/" + agent + "/seasons/" + season + "/reflection-view";
    }

    private static String memory(long agent) {
        return AGENTS + "/" + agent + "/memory";
    }

    private static List<Long> ids(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asLong()));
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    // ---- state and gates --------------------------------------------------------------------------------------

    @Test
    void a_season_is_held_by_its_league_and_its_group_and_waits_for_the_earlier_ones() throws Exception {
        long x = agent("fake/model-x");
        long y = agent("fake/model-y");
        long root = league();
        long sibling = sibling(root);
        long s1 = start(x, "fake/model-x", null);
        play(x, s1);
        long s2 = start(x, "fake/model-x", root);
        play(x, s2);
        closeLeague(root);
        long s3 = start(y, "fake/model-y", sibling);

        JsonNode held = ok(memory(x));
        assertThat(held.propertyNames()).containsExactly("latestVersion", "pendingSeasonIds", "heldSeasonCount");
        assertThat(held.get("latestVersion").isNull()).isTrue();
        assertThat(ids(held.get("pendingSeasonIds"))).as("s2's own league is closed but its group is not").containsExactly(s1);
        assertThat(held.get("heldSeasonCount").asInt()).isOne();
        assertThat(refused(view(x, s2), 409)).as("the group is open").isEqualTo("LEAGUE_OPEN");
        assertThat(refused(view(y, s3), 409)).as("still playing").isEqualTo("SEASON_NOT_FINISHED");

        play(y, s3);
        closeLeague(sibling);

        JsonNode released = ok(memory(x));
        assertThat(ids(released.get("pendingSeasonIds"))).as("finish order").containsExactly(s1, s2);
        assertThat(released.get("heldSeasonCount").asInt()).isZero();
        assertThat(refused(view(x, s2), 409)).as("s1 finished first").isEqualTo("MEMORY_ORDER");

        long base = source(x, "fake/model-x");
        outcome(x, s1, 1, "first", false);
        assertThat(refused(view(x, s1), 409)).isEqualTo("MEMORY_DONE");
        JsonNode next = ok(memory(x));
        assertThat(next.get("latestVersion").asInt()).isOne();
        assertThat(ids(next.get("pendingSeasonIds"))).containsExactly(s2);
        assertThat(base).isPositive();
    }

    @Test
    void a_void_an_abandoned_and_an_unfinished_season_get_no_reflection_and_no_season_of_another_agent_is_seen()
            throws Exception {
        long x = agent("fake/model-x");
        long y = agent("fake/model-y");
        long inProgress = start(x, "fake/model-x", null);
        long abandoned = start(x, "fake/model-x", null);
        acting(x, () -> abandons.abandon(abandoned, 0));
        long voided = start(x, "fake/model-x", null);
        MockHttpServletResponse v = post(mvc, AGENTS + "/" + x + "/seasons/" + voided + "/void",
                "{\"reason\": \"MODEL_GONE\", \"note\": \"gone\"}");
        assertThat(v.getStatus()).isEqualTo(200);
        long others = start(y, "fake/model-y", null);

        assertThat(refused(view(x, inProgress), 409)).isEqualTo("SEASON_NOT_FINISHED");
        assertThat(refused(view(x, abandoned), 409)).isEqualTo("MEMORY_NOT_ELIGIBLE");
        assertThat(refused(view(x, voided), 409)).isEqualTo("MEMORY_NOT_ELIGIBLE");
        assertThat(refused(view(x, others), 404)).as("a season of another agent").isEqualTo("NOT_FOUND");
        assertThat(refused(view(x, 987654321L), 404)).isEqualTo("NOT_FOUND");
        JsonNode state = ok(memory(x));
        assertThat(ids(state.get("pendingSeasonIds"))).as("the abandoned season is finished but never pending").isEmpty();
        assertThat(state.get("heldSeasonCount").asInt()).isZero();
    }

    // ---- memory reads -----------------------------------------------------------------------------------------

    @Test
    void a_version_holds_its_note_and_the_reflections_of_the_last_three_versions_and_only_the_agents_own() throws Exception {
        long x = agent("fake/model-x");
        long y = agent("fake/model-y");
        for (int v = 1; v <= 4; v++) {
            outcome(x, source(x, "fake/model-x"), v, "x" + v, v == 3);
        }
        outcome(y, source(y, "fake/model-y"), 1, "y1", false);

        JsonNode four = ok(memory(x) + "/4");
        assertThat(four.propertyNames()).containsExactly("memory", "playbookCarried", "createdAt");
        assertThat(four.get("memory").propertyNames()).containsExactly("version", "playbook", "reflections");
        assertThat(four.get("memory").get("version").asInt()).isEqualTo(4);
        assertThat(texts(four.get("memory").get("playbook"))).containsExactly("x4 playbook");
        assertThat(texts(reflectionMarks(four.get("memory")))).as("versions 2, 3, 4, oldest first")
                .containsExactly("x2 a", "x3 a", "x4 a");
        assertThat(four.get("playbookCarried").asBoolean()).isFalse();
        assertThat(four.get("createdAt").asString()).isNotBlank();
        assertThat(ok(memory(x) + "/3").get("playbookCarried").asBoolean()).isTrue();
        assertThat(texts(reflectionMarks(ok(memory(x) + "/2").get("memory")))).containsExactly("x1 a", "x2 a");
        assertThat(texts(reflectionMarks(ok(memory(x) + "/1").get("memory")))).containsExactly("x1 a");

        assertThat(ok(memory(x)).get("latestVersion").asInt()).isEqualTo(4);
        assertThat(ok(memory(y)).get("latestVersion").asInt()).isOne();
        assertThat(texts(ok(memory(y) + "/1").get("memory").get("playbook"))).containsExactly("y1 playbook");
        assertThat(refused(memory(y) + "/2", 404)).as("x has a version 2, y does not").isEqualTo("NOT_FOUND");
        assertThat(refused(memory(x) + "/5", 404)).isEqualTo("NOT_FOUND");
        assertThat(refused(memory(x) + "/0", 400)).isEqualTo("INVALID_ARGUMENT");
        assertThat(refused(memory(OWNER_ID), 404)).as("a member that is not an agent").isEqualTo("NOT_FOUND");
        assertThat(refused(memory(987654321L), 404)).isEqualTo("NOT_FOUND");
        MockHttpServletResponse noToken = mvc.get().uri(memory(x)).requestAttr(ANONYMOUS, true).exchange().getResponse();
        assertThat(noToken.getStatus()).isEqualTo(401);
    }

    private static JsonNode reflectionMarks(JsonNode memory) {
        var out = tools.jackson.databind.json.JsonMapper.shared().createArrayNode();
        memory.get("reflections").forEach(r -> out.add(r.get("good").get(0).asString()));
        return out;
    }

    // ---- the reflection view ----------------------------------------------------------------------------------

    @Test
    void the_view_shows_the_agents_own_season_and_nothing_that_dates_it() throws Exception {
        long x = agent("fake/model-x");
        outcome(x, source(x, "fake/model-x"), 1, "base", false);
        long season = start(x, "fake/model-x", null);
        buy(x, season, "000660", 10, Position.FORWARD_BREAKOUT);
        buy(x, season, "069500", 100, Position.MIDFIELDER);
        acting(x, () -> turns.advance(season, 0));
        // a sell at turn 1 that cites the first feed item the turn advance stored for turn 1
        acting(x, () -> orders.place(season, new PlaceOrderRequest(1, OrderSide.SELL, assetId("000660"), 2, null, null)));
        var feed = jdbc.queryForMap("SELECT id, seq, kind FROM turn_feed WHERE season_id = ? AND turn_no = 1 "
                + "ORDER BY seq LIMIT 1", season);
        jdbc.update("UPDATE orders SET source = 'FEED', turn_feed_id = ? WHERE season_id = ? AND turn_no = 1",
                feed.get("id"), season);
        acting(x, () -> turns.advance(season, 1));
        // a sell at turn 2 that cites the turn-1 item (an earlier turn's item is allowed): the view must say which turn
        acting(x, () -> orders.place(season, new PlaceOrderRequest(2, OrderSide.SELL, assetId("000660"), 1, null, null)));
        jdbc.update("UPDATE orders SET source = 'FEED', turn_feed_id = ? WHERE season_id = ? AND turn_no = 2",
                feed.get("id"), season);
        play(x, season, 2);
        String reasonText = "둔화 신호가 보여 비중을 줄인다";
        calls.save(new AgentCall(season, 0, 1, AgentCallKind.TURN_DECISION, UUID.randomUUID(), AgentCallStatus.OK, 200,
                "fake/model-x", "fake/model-x", "fake", "request", "reply",
                "{\"schema\":\"turn-decision/1\",\"formation_change\":null,\"orders\":[],\"theses\":[],\"reason\":\""
                        + reasonText + "\"}", 10, 5, new BigDecimal("0.00010000"), 100, DecisionOutcome.DECISION, "[]",
                "b".repeat(64)));
        calls.save(new AgentCall(season, 1, 1, AgentCallKind.TURN_DECISION, UUID.randomUUID(), AgentCallStatus.OK, 200,
                "fake/model-x", "fake/model-x", "fake", "request", "reply", null, 10, 5, new BigDecimal("0.00010000"), 100,
                DecisionOutcome.INVALID,
                "[{\"unit\":null,\"index\":null,\"code\":\"SCHEMA_INVALID\",\"pointer\":\"/reason\"}]", "c".repeat(64)));
        // a memory call recorded with a final-looking outcome must not be read as the turn's decision
        calls.save(new AgentCall(season, 2, 1, AgentCallKind.REFLECTION, UUID.randomUUID(), AgentCallStatus.OK, 200,
                "fake/model-x", "fake/model-x", "fake", "memory request", "memory reply",
                "{\"schema\":\"turn-decision/1\",\"reason\":\"memory reason\"}", 10, 5, new BigDecimal("0.00010000"),
                100, DecisionOutcome.DECISION, "[]", "d".repeat(64)));
        ENGINE.respond(PERCENTILES, 503, "application/problem+json", FakeEngineServer.fixture("problem-engine-busy.json"));

        assertThat(refused(view(x, season), 503)).as("the score is not stored yet").isEqualTo("SCORE_PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_score WHERE season_id = ?", Long.class, season)).isZero();

        ENGINE.respond(PERCENTILES, 200, "application/json", EnginePercentileAnswers.middle(2000, TURNS));
        JsonNode body = ok(view(x, season));

        assertThat(body.propertyNames()).containsExactly("baseVersion", "basePlaybook", "pathLength", "report", "score",
                "turns");
        assertThat(body.get("baseVersion").asInt()).isOne();
        assertThat(texts(body.get("basePlaybook"))).containsExactly("base playbook");
        assertThat(body.get("pathLength").asInt()).isEqualTo(TURNS);
        assertThat(body.get("report").get("pathLength").asInt()).isEqualTo(TURNS);
        assertThat(body.get("score").get("scoreVersion").asString()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT process_score FROM season_score WHERE season_id = ?", BigDecimal.class,
                season)).isEqualByComparingTo(new BigDecimal(body.get("score").get("processScore").asString()));

        JsonNode turns = body.get("turns");
        assertThat(turns).hasSize(TURNS);
        assertThat(turns.get(0).propertyNames()).containsExactly("turnNo", "label", "reason", "fills", "rejects", "cited");
        assertThat(turns.get(0).get("label").asString()).isEqualTo("1년차 1월");
        assertThat(turns.get(11).get("label").asString()).isEqualTo("1년차 12월");
        assertThat(turns.get(0).get("reason").asString()).isEqualTo(reasonText);
        assertThat(turns.get(1).get("reason").isNull()).as("an INVALID reply has no usable reason").isTrue();
        assertThat(turns.get(1).get("rejects").get(0).get("code").asString()).isEqualTo("SCHEMA_INVALID");
        assertThat(turns.get(2).get("reason").isNull()).as("a memory call is not a decision").isTrue();
        JsonNode fills = turns.get(0).get("fills");
        assertThat(fills).hasSize(2);
        assertThat(fills.get(0).propertyNames()).containsExactly("asset", "side", "quantity");
        Set<String> assets = new HashSet<>(texts(jsonArray(fills, "asset")));
        assertThat(assets).containsExactlyInAnyOrder("KR:000660", "KR:069500");
        assertThat(turns.get(0).get("cited")).isEmpty();
        JsonNode cited = turns.get(1).get("cited");
        assertThat(cited).hasSize(1);
        assertThat(cited.get(0).propertyNames()).containsExactly("feedTurnNo", "seq", "kind");
        assertThat(cited.get(0).get("feedTurnNo").asInt()).isOne();
        JsonNode past = turns.get(2).get("cited");
        assertThat(past).as("turn 2 cites the item of turn 1").hasSize(1);
        assertThat(past.get(0).get("feedTurnNo").asInt()).isOne();
        assertThat(past.get(0).get("seq").asInt()).isEqualTo(((Number) feed.get("seq")).intValue());
        assertThat(cited.get(0).get("seq").asInt()).isEqualTo(((Number) feed.get("seq")).intValue());
        assertThat(cited.get(0).get("kind").asString()).as("the kind only, never the text").isEqualTo(feed.get("kind"));
        assertThat(turns.get(1).get("fills")).hasSize(1);

        Set<String> keys = new HashSet<>();
        allKeys(body, keys);
        assertThat(keys).doesNotContain("seed", "engineVersion", "dataSnapshotDate", "baseDate", "monthBlockId",
                "monthStart", "month", "year", "date", "title", "hindsight", "price", "fee");
    }

    private static JsonNode jsonArray(JsonNode array, String field) {
        var out = tools.jackson.databind.json.JsonMapper.shared().createArrayNode();
        array.forEach(n -> out.add(n.get(field).asString()));
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
}
