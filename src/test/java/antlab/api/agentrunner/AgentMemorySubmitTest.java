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

import antlab.agent.AgentCall;
import antlab.agent.AgentCallKind;
import antlab.agent.AgentCallRepository;
import antlab.agent.AgentCallStatus;
import antlab.agent.DecisionOutcome;
import antlab.auth.AgentActingAuthentication;
import antlab.season.SeasonAbandonService;
import antlab.season.TurnFacade;
import antlab.support.ContractErrorCodes;
import antlab.support.EnginePercentileAnswers;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import antlab.support.OpenApiNormalizer;
import antlab.support.SeasonAccountCleaner;
import antlab.support.SqlRecorder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Recording the memory step of a finished season (AG-05 C2), committing mode with real threads: seasons are played to the
 * end through the turn facade as the acting agent. Every refusal is checked to leave no row behind, the base-version
 * compare-and-set and the row lock are shown with a mutation each in mind, and the shape check must win over the leak
 * filter.
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.agent-runner.max-seasons-per-week=60")
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql", "/fixtures/replay_macro.sql"})
@Sql(scripts = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentMemorySubmitTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final int TURNS = 12;
    private static final String MODEL_X = "fake/model-x";
    private static final String MODEL_Y = "fake/model-y";
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();
    private static final String PERCENTILES = "/v1/percentiles";
    private static final JsonNode CONTRACT = contract();

    private static JsonNode contract() {
        try {
            return OpenApiNormalizer.fromYaml(java.nio.file.Files.readString(java.nio.file.Path.of("docs/api/agent-runner.yaml")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

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

    private void play(long agent, long season) {
        for (int t = 0; t < TURNS; t++) {
            int turn = t;
            acting(agent, () -> turns.advance(season, turn));
        }
    }

    /** A season played to its last turn: finished, with its final turn row, so it is pending for a reflection. */
    private long finished(long agent, String model) throws Exception {
        long season = start(agent, model, null);
        play(agent, season);
        return season;
    }

    private long league() throws Exception {
        MockHttpServletResponse r = post(mvc, BASE + "/leagues", "{\"name\": \"Memory league\"}");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private void closeLeague(long league) throws Exception {
        assertThat(post(mvc, BASE + "/leagues/" + league + "/close", "").getStatus()).isEqualTo(200);
    }

    private static String uri(long agent, long season) {
        return AGENTS + "/" + agent + "/seasons/" + season + "/memory";
    }

    private MockHttpServletResponse get(String uri) {
        return mvc.get().uri(uri).header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
    }

    private static Map<String, Object> reflection(String a, String b, String c, String rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "season-reflection/1");
        m.put("good", List.of(a, b, c));
        m.put("mistakes", List.of(Map.of("type", "PANIC_SELL", "text", "sold into the dip")));
        m.put("ruleCandidates", List.of(rule));
        return m;
    }

    private static Map<String, Object> reflection(String mark) {
        return reflection(mark + " a", mark + " b", mark + " c", mark + " rule");
    }

    private static Map<String, Object> note(String... rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "operating-note/1");
        m.put("rules", List.of(rules));
        return m;
    }

    private static Map<String, Object> call(String model, AgentCallKind kind, int attempt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attemptNo", attempt);
        m.put("kind", kind.name());
        m.put("ledgerCallId", UUID.randomUUID().toString());
        m.put("status", "OK");
        m.put("httpStatus", 200);
        m.put("modelRequested", model);
        m.put("modelActual", model);
        m.put("provider", "fake");
        m.put("requestText", "request " + kind + " " + attempt);
        m.put("responseText", "reply " + kind + " " + attempt);
        m.put("tokensIn", 10);
        m.put("tokensOut", 5);
        m.put("costUsd", "0.00010000");
        m.put("latencyMs", 100);
        return m;
    }

    private static List<Map<String, Object>> memoryCalls(String model) {
        return List.of(call(model, AgentCallKind.REFLECTION, 1), call(model, AgentCallKind.REFLECTION, 2),
                call(model, AgentCallKind.PLAYBOOK, 1));
    }

    private static Map<String, Object> accepted(String model, Integer base, Map<String, Object> reflection,
            Map<String, Object> playbook, boolean carried) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseVersion", base);
        m.put("outcome", "ACCEPTED");
        m.put("reflection", reflection);
        m.put("playbook", playbook);
        m.put("playbookCarried", carried);
        m.put("calls", memoryCalls(model));
        return m;
    }

    private static Map<String, Object> accepted(String model, Integer base, String mark) {
        return accepted(model, base, reflection(mark), note(mark + " rule one", mark + " rule two"), false);
    }

    private static Map<String, Object> discarded(String model, List<String> codes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseVersion", null);
        m.put("outcome", "DISCARDED");
        m.put("discardCodes", codes);
        m.put("calls", List.of(call(model, AgentCallKind.REFLECTION, 1), call(model, AgentCallKind.REFLECTION, 2)));
        return m;
    }

    private MockHttpServletResponse submit(long agent, long season, Map<String, Object> body) throws Exception {
        return post(mvc, uri(agent, season), MAPPER.writeValueAsString(body));
    }

    private MockHttpServletResponse submitRaw(long agent, long season, String body) {
        return post(mvc, uri(agent, season), body);
    }

    private JsonNode created(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r);
    }

    /** The code of a refusal; the contract must list that code under that status of the submit operation. */
    private String refused(MockHttpServletResponse r, int status) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        String code = json(r).get("code").asString();
        assertThat(ContractErrorCodes.isListed(CONTRACT, "POST " + AGENTS + "/{agentId}/seasons/{seasonId}/memory", status, code))
                .as(status + " " + code).isTrue();
        return code;
    }

    /** Rows of the agent that the memory step writes. */
    private record Rows(long reflections, long memories, long memoryCalls) {
    }

    private Rows rows(long agent) {
        return new Rows(
                jdbc.queryForObject("SELECT count(*) FROM agent_reflection WHERE member_id = ?", Long.class, agent),
                jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE member_id = ?", Long.class, agent),
                jdbc.queryForObject("SELECT count(*) FROM agent_call c JOIN season s ON s.id = c.season_id "
                        + "WHERE s.member_id = ? AND c.call_kind <> 'TURN_DECISION'", Long.class, agent));
    }

    private static final Rows NONE = new Rows(0, 0, 0);

    // ---- the accepted step ------------------------------------------------------------------------------------

    @Test
    void an_accepted_step_stores_the_calls_the_nfc_text_and_the_next_version_and_only_for_its_own_agent() throws Exception {
        long x = agent(MODEL_X);
        long y = agent(MODEL_Y);
        long s1 = finished(x, MODEL_X);
        long s2 = finished(x, MODEL_X);
        // a jamo pair is stored as the one syllable it composes to
        Map<String, Object> first = accepted(MODEL_X, null, reflection("\u1100\u1161", "b", "c", "rule"), note("keep cash"),
                false);

        JsonNode made = created(submit(x, s1, first));

        assertThat(made.propertyNames()).containsExactly("outcome", "version");
        assertThat(made.get("outcome").asString()).isEqualTo("ACCEPTED");
        assertThat(made.get("version").asInt()).isOne();
        assertThat(rows(x)).isEqualTo(new Rows(1, 1, 3));
        assertThat(jdbc.queryForObject("SELECT body ->> 'schema' FROM agent_reflection WHERE season_id = ?", String.class, s1))
                .isEqualTo("season-reflection/1");
        assertThat(jdbc.queryForObject("SELECT body -> 'good' ->> 0 FROM agent_reflection WHERE season_id = ?", String.class,
                s1)).isEqualTo("가");
        assertThat(jdbc.queryForObject("SELECT outcome FROM agent_reflection WHERE season_id = ?", String.class, s1))
                .isEqualTo("ACCEPTED");
        var memory = jdbc.queryForMap("SELECT source_season_id, playbook_carried, filter_version, playbook -> 'rules' ->> 0 AS rule "
                + "FROM agent_memory WHERE member_id = ? AND version = 1", x);
        assertThat(memory.get("source_season_id")).isEqualTo(s1);
        assertThat(memory.get("playbook_carried")).isEqualTo(false);
        assertThat((String) memory.get("filter_version")).startsWith("mf1-");
        assertThat(memory.get("rule")).isEqualTo("keep cash");
        var stored = jdbc.queryForList("SELECT call_kind, attempt_no, turn_no, is_final, outcome, request_sha256, parsed "
                + "FROM agent_call WHERE season_id = ? AND call_kind <> 'TURN_DECISION' ORDER BY call_kind, attempt_no", s1);
        assertThat(stored).extracting(r -> r.get("call_kind") + "#" + r.get("attempt_no"))
                .containsExactly("PLAYBOOK#1", "REFLECTION#1", "REFLECTION#2");
        assertThat(stored).allSatisfy(r -> {
            assertThat(((Number) r.get("turn_no")).intValue()).as("the path length").isEqualTo(TURNS);
            assertThat(r.get("is_final")).isEqualTo(false);
            assertThat(r.get("outcome")).isNull();
            assertThat(r.get("request_sha256")).isNull();
            assertThat(r.get("parsed")).isNull();
        });

        // the second step builds on the first: the old base is refused, the new one is taken
        assertThat(refused(submit(x, s2, accepted(MODEL_X, null, "two")), 409)).isEqualTo("MEMORY_BASE_STALE");
        assertThat(rows(x)).isEqualTo(new Rows(1, 1, 3));
        assertThat(refused(submit(x, s2, accepted(MODEL_X, 2, "two")), 409)).as("ahead of the latest").isEqualTo("MEMORY_BASE_STALE");
        assertThat(created(submit(x, s2, accepted(MODEL_X, 1, "two"))).get("version").asInt()).isEqualTo(2);
        assertThat(rows(x)).isEqualTo(new Rows(2, 2, 6));

        JsonNode read = json(get(AGENTS + "/" + x + "/memory/2"));
        assertThat(read.get("memory").get("version").asInt()).isEqualTo(2);
        assertThat(read.get("memory").get("playbook")).hasSize(2);
        assertThat(read.get("memory").get("reflections")).hasSize(2);
        assertThat(read.get("playbookCarried").asBoolean()).isFalse();

        // the step is done once; sending it again is not a second write
        assertThat(refused(submit(x, s1, accepted(MODEL_X, 2, "again")), 409)).isEqualTo("MEMORY_DONE");
        assertThat(rows(x)).isEqualTo(new Rows(2, 2, 6));

        // another agent has no memory, and cannot write into this agent's season
        assertThat(json(get(AGENTS + "/" + y + "/memory")).get("latestVersion").isNull()).isTrue();
        assertThat(refused(submit(y, s2, accepted(MODEL_Y, null, "stolen")), 404)).isEqualTo("NOT_FOUND");
        assertThat(rows(y)).isEqualTo(NONE);
        assertThat(refused(submit(x, 987654321L, accepted(MODEL_X, 2, "none")), 404)).isEqualTo("NOT_FOUND");
    }

    @Test
    void the_memory_calls_do_not_touch_the_turn_decisions_of_the_season() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);
        calls.save(new AgentCall(season, TURNS - 1, 1, AgentCallKind.TURN_DECISION, UUID.randomUUID(), AgentCallStatus.OK, 200,
                MODEL_X, MODEL_X, "fake", "request", "reply", "{\"reason\":\"last turn\"}", 10, 5,
                new BigDecimal("0.00010000"), 100, DecisionOutcome.DECISION, "[]", "e".repeat(64)));

        created(submit(x, season, accepted(MODEL_X, null, "mark")));

        assertThat(calls.findFinalDecisions(season)).hasSize(1);
        assertThat(calls.findFinalBySeasonIdAndTurnNo(season, TURNS - 1, AgentCallKind.TURN_DECISION)).isPresent();
        assertThat(calls.findAllBySeasonIdAndTurnNoAndCallKind(season, TURNS, AgentCallKind.TURN_DECISION)).isEmpty();
        assertThat(calls.findAllBySeasonIdAndTurnNoAndCallKind(season, TURNS, AgentCallKind.REFLECTION)).hasSize(2);
        assertThat(calls.findFinalBySeasonIdAndTurnNo(season, TURNS, AgentCallKind.REFLECTION)).isEmpty();
    }

    // ---- gates -----------------------------------------------------------------------------------------------

    @Test
    void every_gate_of_the_view_holds_on_submit_and_a_refused_step_writes_nothing() throws Exception {
        long x = agent(MODEL_X);
        long inProgress = start(x, MODEL_X, null);
        long abandoned = start(x, MODEL_X, null);
        acting(x, () -> abandons.abandon(abandoned, 0));
        long voided = start(x, MODEL_X, null);
        assertThat(post(mvc, AGENTS + "/" + x + "/seasons/" + voided + "/void",
                "{\"reason\": \"MODEL_GONE\", \"note\": \"gone\"}").getStatus()).isEqualTo(200);
        long first = finished(x, MODEL_X);
        long second = finished(x, MODEL_X);

        assertThat(refused(submit(x, inProgress, accepted(MODEL_X, null, "a")), 409)).isEqualTo("SEASON_NOT_FINISHED");
        assertThat(refused(submit(x, abandoned, accepted(MODEL_X, null, "a")), 409)).isEqualTo("MEMORY_NOT_ELIGIBLE");
        assertThat(refused(submit(x, voided, accepted(MODEL_X, null, "a")), 409)).isEqualTo("MEMORY_NOT_ELIGIBLE");
        assertThat(refused(submit(x, second, accepted(MODEL_X, null, "a")), 409)).as("the earlier season goes first")
                .isEqualTo("MEMORY_ORDER");
        assertThat(rows(x)).isEqualTo(NONE);

        assertThat(created(submit(x, first, accepted(MODEL_X, null, "a"))).get("version").asInt()).isOne();
    }

    @Test
    void a_season_held_by_its_league_or_its_group_is_refused_until_every_league_is_closed() throws Exception {
        long x = agent(MODEL_X);
        long y = agent(MODEL_Y);
        long root = league();
        long sibling = league();
        jdbc.update("UPDATE league SET seed = (SELECT seed FROM league WHERE id = ?), seed_league_id = ? WHERE id = ?", root,
                root, sibling);
        long held = start(x, MODEL_X, root);
        play(x, held);
        closeLeague(root);
        long other = start(y, MODEL_Y, sibling);

        assertThat(refused(submit(x, held, accepted(MODEL_X, null, "a")), 409)).as("its own league is closed, its group is not")
                .isEqualTo("LEAGUE_OPEN");
        assertThat(rows(x)).isEqualTo(NONE);

        play(y, other);
        closeLeague(sibling);
        assertThat(created(submit(x, held, accepted(MODEL_X, null, "a"))).get("version").asInt()).isOne();
    }

    // ---- shape (422) and filter (422) --------------------------------------------------------------------------

    @Test
    void a_shape_the_filter_would_skip_is_refused_by_the_schema_first_and_nothing_is_written() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);
        Map<String, Object> slipping = reflection("mark");
        slipping.put("good", List.of(Map.of("x", "2008년"), "b", "c"));

        MockHttpServletResponse r = submit(x, season, accepted(MODEL_X, null, slipping, note("keep cash"), false));

        assertThat(refused(r, 422)).isEqualTo("MEMORY_SCHEMA_INVALID");
        JsonNode violations = json(r).get("violations");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).propertyNames()).containsExactly("pointer", "keyword");
        assertThat(violations.get(0).get("pointer").asString()).isEqualTo("/reflection/good/0");
        assertThat(violations.get(0).get("keyword").asString()).isEqualTo("type");
        assertThat(r.getContentAsString()).doesNotContain("2008");
        assertThat(rows(x)).isEqualTo(NONE);
    }

    @Test
    void a_document_that_breaks_both_the_shape_and_the_filter_is_a_shape_error() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);
        Map<String, Object> both = reflection("mark");
        both.put("good", List.of("2008년 리먼 사태", "b"));

        MockHttpServletResponse r = submit(x, season, accepted(MODEL_X, null, both, note("keep cash"), false));

        assertThat(refused(r, 422)).isEqualTo("MEMORY_SCHEMA_INVALID");
        assertThat(json(r).has("leaks")).isFalse();
        assertThat(json(r).get("violations").get(0).get("keyword").asString()).isEqualTo("minItems");
        assertThat(rows(x)).isEqualTo(NONE);
    }

    @Test
    void the_other_shape_errors_name_a_pointer_and_a_keyword() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);
        Map<String, Object> unknownKey = reflection("mark");
        unknownKey.put("hindsight", "the crash");
        Map<String, Object> badType = reflection("mark");
        badType.put("mistakes", List.of(Map.of("type", "BAD", "text", "t")));

        MockHttpServletResponse a = submit(x, season, accepted(MODEL_X, null, unknownKey, note("r"), false));
        MockHttpServletResponse b = submit(x, season, accepted(MODEL_X, null, badType, note("r"), false));
        MockHttpServletResponse c = submit(x, season, accepted(MODEL_X, null, reflection("mark"),
                Map.of("schema", "operating-note/1", "rules", List.of()), false));

        assertThat(refused(a, 422)).isEqualTo("MEMORY_SCHEMA_INVALID");
        assertThat(json(a).get("violations").get(0).get("pointer").asString()).isEqualTo("/reflection");
        assertThat(json(a).get("violations").get(0).get("keyword").asString()).isEqualTo("additionalProperties");
        assertThat(a.getContentAsString()).doesNotContain("hindsight");
        assertThat(refused(b, 422)).isEqualTo("MEMORY_SCHEMA_INVALID");
        assertThat(json(b).get("violations").get(0).get("pointer").asString()).isEqualTo("/reflection/mistakes/0/type");
        assertThat(refused(c, 422)).isEqualTo("MEMORY_SCHEMA_INVALID");
        assertThat(json(c).get("violations").get(0).get("pointer").asString()).isEqualTo("/playbook/rules");
        assertThat(json(c).get("violations").get(0).get("keyword").asString()).isEqualTo("minItems");
        assertThat(rows(x)).isEqualTo(NONE);
    }

    @Test
    void a_nul_and_a_lone_surrogate_are_shape_errors_not_a_database_error() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);
        String template = MAPPER.writeValueAsString(accepted(MODEL_X, null, reflection("mark"), note("PLACEHOLDER"), false));

        MockHttpServletResponse nul = submitRaw(x, season, template.replace("PLACEHOLDER", "a\\u0000b"));
        MockHttpServletResponse high = submitRaw(x, season, template.replace("PLACEHOLDER", "a\\ud800b"));
        MockHttpServletResponse low = submitRaw(x, season, template.replace("PLACEHOLDER", "a\\udc00b"));

        for (MockHttpServletResponse r : List.of(nul, high, low)) {
            assertThat(refused(r, 422)).isEqualTo("MEMORY_SCHEMA_INVALID");
            assertThat(json(r).get("violations").get(0).get("pointer").asString()).isEqualTo("/playbook/rules/0");
            assertThat(json(r).get("violations").get(0).get("keyword").asString()).isEqualTo("encoding");
        }
        assertThat(rows(x)).isEqualTo(NONE);
        assertThat(created(submitRaw(x, season, template.replace("PLACEHOLDER", "a\\ud83d\\ude00b"))).get("version").asInt())
                .as("a surrogate pair is a character").isOne();
    }

    @Test
    void the_filter_refuses_year_event_fingerprint_and_length_and_never_echoes_the_text() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);
        Map<String, Object> year = reflection("mark");
        year.put("good", List.of("2008년에 배운 것", "b", "c"));
        Map<String, Object> event = reflection("mark");
        event.put("ruleCandidates", List.of("리먼 사태를 기억한다"));
        Map<String, Object> fingerprint = reflection("mark");
        fingerprint.put("good", List.of("-20%, +8%, +6%", "b", "c"));
        Map<String, Object> oversize = reflection("mark");
        oversize.put("good", List.of("x".repeat(120), "y".repeat(120), "z".repeat(120)));
        oversize.put("mistakes", List.of(Map.of("type", "OTHER", "text", "w".repeat(120)),
                Map.of("type", "OTHER", "text", "v".repeat(120))));

        MockHttpServletResponse a = submit(x, season, accepted(MODEL_X, null, year, note("keep cash"), false));
        MockHttpServletResponse b = submit(x, season, accepted(MODEL_X, null, event, note("keep cash"), false));
        MockHttpServletResponse c = submit(x, season, accepted(MODEL_X, null, fingerprint, note("keep cash"), false));
        MockHttpServletResponse d = submit(x, season, accepted(MODEL_X, null, oversize, note("keep cash"), false));
        MockHttpServletResponse e = submit(x, season, accepted(MODEL_X, null, reflection("mark"), note("54,000원에 산다"), false));

        assertThat(refused(a, 422)).isEqualTo("MEMORY_LEAK");
        assertThat(json(a).get("leaks").get(0).propertyNames()).containsExactly("pointer", "code");
        assertThat(json(a).get("leaks").get(0).get("pointer").asString()).startsWith("/reflection/good/0#s");
        assertThat(json(a).get("leaks").get(0).get("code").asString()).isEqualTo("YEAR");
        assertThat(a.getContentAsString()).doesNotContain("2008").doesNotContain("배운 것");
        assertThat(refused(b, 422)).isEqualTo("MEMORY_LEAK");
        assertThat(json(b).get("leaks").get(0).get("code").asString()).isEqualTo("EVENT_TERM");
        assertThat(json(b).get("leaks").get(0).get("pointer").asString()).startsWith("/reflection/ruleCandidates/0");
        assertThat(b.getContentAsString()).doesNotContain("리먼");
        assertThat(refused(c, 422)).isEqualTo("MEMORY_LEAK");
        assertThat(json(c).get("leaks").get(0).get("code").asString()).isEqualTo("FINGERPRINT");
        assertThat(refused(d, 422)).isEqualTo("MEMORY_LEAK");
        assertThat(json(d).get("leaks").get(0).get("code").asString()).isEqualTo("OVERSIZE");
        assertThat(json(d).get("leaks").get(0).get("pointer").asString()).isEqualTo("/reflection");
        assertThat(refused(e, 422)).isEqualTo("MEMORY_LEAK");
        assertThat(json(e).get("leaks").get(0).get("pointer").asString()).startsWith("/playbook/rules/0#s");
        assertThat(json(e).get("leaks").get(0).get("code").asString()).isEqualTo("PRICE");
        assertThat(e.getContentAsString()).doesNotContain("54,000");
        assertThat(rows(x)).isEqualTo(NONE);
        assertThat(created(submit(x, season, accepted(MODEL_X, null, "ok"))).get("version").asInt()).isOne();
    }

    // ---- request rules (400) and the carried note ---------------------------------------------------------------

    @Test
    void a_wrong_request_is_a_400_and_writes_nothing() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);
        List<Map<String, Object>> bad = new ArrayList<>();
        Map<String, Object> noPlaybook = accepted(MODEL_X, null, "a");
        noPlaybook.put("playbook", null);
        bad.add(noPlaybook);
        Map<String, Object> acceptedWithCodes = accepted(MODEL_X, null, "a");
        acceptedWithCodes.put("discardCodes", List.of("YEAR"));
        bad.add(acceptedWithCodes);
        Map<String, Object> noCodes = discarded(MODEL_X, List.of("YEAR"));
        noCodes.put("discardCodes", List.of());
        bad.add(noCodes);
        Map<String, Object> repeated = discarded(MODEL_X, List.of("YEAR", "YEAR"));
        bad.add(repeated);
        Map<String, Object> discardedWithBase = discarded(MODEL_X, List.of("YEAR"));
        discardedWithBase.put("baseVersion", 1);
        bad.add(discardedWithBase);
        Map<String, Object> discardedWithNote = discarded(MODEL_X, List.of("YEAR"));
        discardedWithNote.put("playbook", note("a"));
        bad.add(discardedWithNote);
        Map<String, Object> turnKind = accepted(MODEL_X, null, "a");
        turnKind.put("calls", List.of(call(MODEL_X, AgentCallKind.TURN_DECISION, 1)));
        bad.add(turnKind);
        Map<String, Object> noReflectionCall = accepted(MODEL_X, null, "a");
        noReflectionCall.put("calls", List.of(call(MODEL_X, AgentCallKind.PLAYBOOK, 1)));
        bad.add(noReflectionCall);
        Map<String, Object> skipped = accepted(MODEL_X, null, "a");
        skipped.put("calls", List.of(call(MODEL_X, AgentCallKind.REFLECTION, 2)));
        bad.add(skipped);
        Map<String, Object> otherModel = accepted(MODEL_X, null, "a");
        otherModel.put("calls", List.of(call("fake/other", AgentCallKind.REFLECTION, 1)));
        bad.add(otherModel);
        Map<String, Object> noLedger = accepted(MODEL_X, null, "a");
        Map<String, Object> c = call(MODEL_X, AgentCallKind.REFLECTION, 1);
        c.put("ledgerCallId", null);
        noLedger.put("calls", List.of(c));
        bad.add(noLedger);
        Map<String, Object> secret = accepted(MODEL_X, null, "a");
        Map<String, Object> s = call(MODEL_X, AgentCallKind.REFLECTION, 1);
        s.put("responseText", "key sk-or-v1-0123456789abcdef0123456789abcdef");
        secret.put("calls", List.of(s));
        bad.add(secret);
        Map<String, Object> sameLedger = accepted(MODEL_X, null, "a");
        Map<String, Object> one = call(MODEL_X, AgentCallKind.REFLECTION, 1);
        Map<String, Object> two = call(MODEL_X, AgentCallKind.REFLECTION, 2);
        two.put("ledgerCallId", one.get("ledgerCallId"));
        sameLedger.put("calls", List.of(one, two));
        bad.add(sameLedger);
        Map<String, Object> many = accepted(MODEL_X, null, "a");
        List<Map<String, Object>> twentyOne = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            twentyOne.add(call(MODEL_X, AgentCallKind.REFLECTION, i));
        }
        many.put("calls", twentyOne);
        bad.add(many);
        Map<String, Object> zeroBase = accepted(MODEL_X, 0, "a");
        bad.add(zeroBase);

        for (Map<String, Object> body : bad) {
            assertThat(refused(submit(x, season, body), 400)).as(MAPPER.writeValueAsString(body)).isEqualTo("INVALID_ARGUMENT");
        }
        assertThat(refused(submitRaw(x, season, "{\"baseVersion\":null,\"outcome\":\"MAYBE\",\"calls\":[]}"), 400))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(rows(x)).isEqualTo(NONE);
    }

    @Test
    void a_turn_route_does_not_take_a_memory_call() throws Exception {
        long x = agent(MODEL_X);
        long season = start(x, MODEL_X, null);
        Map<String, Object> body = Map.of("calls", List.of(call(MODEL_X, AgentCallKind.REFLECTION, 1)));

        MockHttpServletResponse r = post(mvc, AGENTS + "/" + x + "/seasons/" + season + "/turns/0/calls",
                MAPPER.writeValueAsString(body));

        assertThat(refused(r, 400)).isEqualTo("INVALID_ARGUMENT");
        assertThat(rows(x)).isEqualTo(NONE);
    }

    @Test
    void a_carried_note_is_the_previous_rules_or_for_the_first_version_the_rule_candidates() throws Exception {
        long x = agent(MODEL_X);
        long s1 = finished(x, MODEL_X);
        long s2 = finished(x, MODEL_X);
        Map<String, Object> ruleCandidates = reflection("mark");
        ruleCandidates.put("ruleCandidates", List.of("rule from the reflection"));

        assertThat(refused(submit(x, s1, accepted(MODEL_X, null, ruleCandidates, note("something else"), true)), 400))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(rows(x)).isEqualTo(NONE);
        created(submit(x, s1, accepted(MODEL_X, null, ruleCandidates, note("rule from the reflection"), true)));
        assertThat(jdbc.queryForObject("SELECT playbook_carried FROM agent_memory WHERE member_id = ? AND version = 1",
                Boolean.class, x)).isTrue();

        assertThat(refused(submit(x, s2, accepted(MODEL_X, 1, reflection("two"), note("new rule"), true)), 400))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(rows(x).memories()).isOne();
        created(submit(x, s2, accepted(MODEL_X, 1, reflection("two"), note("rule from the reflection"), true)));
        assertThat(rows(x).memories()).isEqualTo(2);
    }

    // ---- discard -----------------------------------------------------------------------------------------------

    @Test
    void a_discarded_step_keeps_the_calls_and_the_codes_and_makes_no_version() throws Exception {
        long x = agent(MODEL_X);
        long s1 = finished(x, MODEL_X);
        long s2 = finished(x, MODEL_X);

        JsonNode made = created(submit(x, s1, discarded(MODEL_X, List.of("YEAR", "SCHEMA"))));

        assertThat(made.get("outcome").asString()).isEqualTo("DISCARDED");
        assertThat(made.get("version").isNull()).isTrue();
        assertThat(rows(x)).isEqualTo(new Rows(1, 0, 2));
        var row = jdbc.queryForMap("SELECT outcome, discard_codes::text AS codes, body, filter_version FROM agent_reflection "
                + "WHERE season_id = ?", s1);
        assertThat(row.get("outcome")).isEqualTo("DISCARDED");
        assertThat(MAPPER.readTree((String) row.get("codes")).toString()).isEqualTo("[\"YEAR\",\"SCHEMA\"]");
        assertThat(row.get("body")).isNull();
        assertThat((String) row.get("filter_version")).startsWith("mf1-");
        assertThat(json(get(AGENTS + "/" + x + "/memory")).get("latestVersion").isNull()).isTrue();
        assertThat(refused(submit(x, s1, discarded(MODEL_X, List.of("YEAR"))), 409)).isEqualTo("MEMORY_DONE");

        assertThat(created(submit(x, s2, accepted(MODEL_X, null, "after"))).get("version").asInt())
                .as("a discarded season leaves the chain where it was").isOne();
        assertThat(jdbc.queryForObject("SELECT source_season_id FROM agent_memory WHERE member_id = ? AND version = 1",
                Long.class, x)).isEqualTo(s2);
    }

    // ---- concurrency and the lock ---------------------------------------------------------------------------------

    @Test
    void two_submits_at_once_for_one_season_make_exactly_one_version() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);

        List<String> answers = raceTwo(() -> submit(x, season, accepted(MODEL_X, null, "left")),
                () -> submit(x, season, accepted(MODEL_X, null, "right")));

        assertThat(answers).containsExactlyInAnyOrder("201", "MEMORY_DONE");
        assertThat(rows(x)).isEqualTo(new Rows(1, 1, 3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory WHERE member_id = ? AND version = 1", Long.class, x))
                .isOne();
    }

    @Test
    void two_seasons_of_one_agent_submitted_at_once_with_the_same_base_make_one_version() throws Exception {
        long x = agent(MODEL_X);
        long s1 = finished(x, MODEL_X);
        long s2 = finished(x, MODEL_X);

        List<String> answers = raceTwo(() -> submit(x, s1, accepted(MODEL_X, null, "left")),
                () -> submit(x, s2, accepted(MODEL_X, null, "right")));

        assertThat(answers).hasSize(2).contains("201").filteredOn(a -> !a.equals("201")).singleElement()
                .isIn("MEMORY_ORDER", "MEMORY_BASE_STALE");
        assertThat(rows(x).memories()).isOne();
        assertThat(rows(x).reflections()).isOne();
        assertThat(jdbc.queryForObject("SELECT source_season_id FROM agent_memory WHERE member_id = ?", Long.class, x))
                .as("the first in finish order wins the chain").isEqualTo(s1);
    }

    private interface Call {
        MockHttpServletResponse run() throws Exception;
    }

    /** Per call: "201" when it was created, else the code of the refusal. */
    private List<String> raceTwo(Call first, Call second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<String>> futures = new ArrayList<>();
            for (Call call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    MockHttpServletResponse r = call.run();
                    return r.getStatus() == 201 ? "201" : json(r).get("code").asString();
                }));
            }
            List<String> out = new ArrayList<>();
            for (Future<String> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void the_submit_locks_the_agent_row_before_it_reads_the_chain() throws Exception {
        long x = agent(MODEL_X);
        long season = finished(x, MODEL_X);

        SqlRecorder.start();
        MockHttpServletResponse r = submit(x, season, accepted(MODEL_X, null, "mark"));
        List<String> sql = SqlRecorder.stop().stream().map(String::toLowerCase).toList();

        assertThat(r.getStatus()).isEqualTo(201);
        int lock = indexOf(sql, "agent_profile", "for no key update");
        int chain = indexOf(sql, "agent_memory", "order by");
        assertThat(lock).as(sql.toString()).isNotNegative();
        assertThat(chain).isNotNegative();
        assertThat(lock).as("the lock comes first").isLessThan(chain);
    }

    private static int indexOf(List<String> sql, String... fragments) {
        for (int i = 0; i < sql.size(); i++) {
            String statement = sql.get(i);
            if (List.of(fragments).stream().allMatch(statement::contains)) {
                return i;
            }
        }
        return -1;
    }
}
