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
import antlab.scoring.SeasonScoreService;
import antlab.season.TurnFacade;
import antlab.support.EnginePercentileAnswers;
import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * The memory comparison (AG-05 D) over real same-seed sibling leagues, committing mode: one agent plays the same seed
 * unpinned and pinned to a version, the groups close, and the report reads the stored results. Group 1 has three seats
 * (a control pair among them), group 2 two. The answers must never show a seed, a date, a month block or a league number.
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.agent-runner.max-seasons-per-week=60")
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql", "/fixtures/replay_macro.sql"})
@Sql(scripts = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class MemoryComparisonTest {

    private static final int TURNS = 12;
    private static final String MODEL = "fake/model-x";
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();
    private static final Set<String> FORBIDDEN_KEYS = Set.of("seed", "baseDate", "monthBlockId", "monthBlock", "leagueId",
            "league", "engineVersion", "dataSnapshotDate", "createdAt", "closedAt", "date", "month", "year", "seasonId",
            "memberId", "agentId", "version", "path");

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
    SeasonScoreService scoring;
    @Autowired
    AgentCallRepository calls;

    @BeforeEach
    void setUp() {
        ensureOwner(jdbc);
        ENGINE.reset();
        ENGINE.respond("/v1/percentiles", 200, "application/json", EnginePercentileAnswers.middle(2000, TURNS));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        List<Long> seasonIds = jdbc.queryForList("SELECT s.id FROM season s JOIN agent_profile p ON p.member_id = s.member_id "
                + "WHERE p.owner_member_id = ?", Long.class, OWNER_ID);
        List<Long> memberIds = new ArrayList<>(jdbc.queryForList(
                "SELECT member_id FROM agent_profile WHERE owner_member_id = ?", Long.class, OWNER_ID));
        memberIds.add(OWNER_ID);
        for (Long member : memberIds) {
            jdbc.update("DELETE FROM rating_history WHERE member_id = ?", member);
            jdbc.update("DELETE FROM season_score WHERE member_id = ?", member);
        }
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
        jdbc.update("DELETE FROM persona_version WHERE persona_key = 'value-investor'");
    }

    // ---- a world to compare in --------------------------------------------------------------------------------

    /** One agent, a version 1 of its memory, group 1 with seats {none, v1, none} and group 2 with {none, v1}, all played. */
    private final class World {
        long agent;
        long root1;
        long sibling1;
        long sibling1b;
        long root2;
        long sibling2;
        long none1;
        long v1Season1;
        long none1b;
        long none2;
        long v1Season2;
    }

    private World world() throws Exception {
        World w = new World();
        w.agent = AgentRunnerSupport.register(mvc, registerBody("value-investor", "OPENROUTER", MODEL, "비교 에이전트"));
        writeVersionOne(w.agent);
        w.root1 = league("{\"name\": \"G1 root\"}");
        w.sibling1 = league("{\"name\": \"G1 s1\", \"sameSeedAs\": " + w.root1 + "}");
        w.sibling1b = league("{\"name\": \"G1 s2\", \"sameSeedAs\": " + w.root1 + "}");
        w.root2 = league("{\"name\": \"G2 root\"}");
        w.sibling2 = league("{\"name\": \"G2 s1\", \"sameSeedAs\": " + w.root2 + "}");
        // an agent may hold three active seasons: each seat is played to the end before the next one is entered
        w.none1 = played(w.agent, w.root1, null);
        w.v1Season1 = played(w.agent, w.sibling1, 1);
        w.none1b = played(w.agent, w.sibling1b, null);
        w.none2 = played(w.agent, w.root2, null);
        w.v1Season2 = played(w.agent, w.sibling2, 1);
        return w;
    }

    private void writeVersionOne(long agent) throws Exception {
        MockHttpServletResponse r = post(mvc, AGENTS + "/" + agent + "/seasons", startBody(MODEL, null, null));
        long source = json(r).get("id").asLong();
        jdbc.update("INSERT INTO agent_reflection (season_id, member_id, outcome, body, filter_version) "
                + "VALUES (?, ?, 'ACCEPTED', ?::jsonb, 'mf1-test')", source, agent,
                "{\"schema\": \"season-reflection/1\", \"good\": [\"a\", \"b\", \"c\"], \"mistakes\": [], \"ruleCandidates\": [\"x\"]}");
        jdbc.update("INSERT INTO agent_memory (member_id, version, source_season_id, playbook, playbook_carried, filter_version) "
                + "VALUES (?, 1, ?, '{\"schema\": \"operating-note/1\", \"rules\": [\"x\"]}'::jsonb, false, 'mf1-test')", agent, source);
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", source);
    }

    private long league(String body) throws Exception {
        MockHttpServletResponse r = post(mvc, BASE + "/leagues", body);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    private long played(long agent, long league, Integer pin) throws Exception {
        long season = enter(agent, league, pin);
        play(agent, season);
        return season;
    }

    private long enter(long agent, long league, Integer pin) throws Exception {
        String body = startBody(MODEL, league, null);
        if (pin != null) {
            body = body.replace("\"runnerVersion\": \"r1\",", "\"runnerVersion\": \"r1\", \"memoryVersion\": " + pin + ",");
        }
        MockHttpServletResponse r = post(mvc, AGENTS + "/" + agent + "/seasons", body);
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

    private void close(long... leagues) {
        for (long league : leagues) {
            assertThat(post(mvc, BASE + "/leagues/" + league + "/close", "").getStatus()).isEqualTo(200);
        }
    }

    private void closeAll(World w) {
        close(w.root1, w.sibling1, w.sibling1b, w.root2, w.sibling2);
    }

    private MockHttpServletResponse compareRaw(long agent, int a, int b) {
        return mvc.get().uri(AGENTS + "/" + agent + "/memory/comparison?versionA=" + a + "&versionB=" + b)
                .header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
    }

    private JsonNode compare(long agent, int a, int b) throws Exception {
        MockHttpServletResponse r = compareRaw(agent, a, b);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json(r);
    }

    /** Adds money to the final valuation of a season: cash and the goalkeeper slot move with the total, so the turn's own checks hold. */
    private void raise(long season) {
        jdbc.update("UPDATE turn SET total_value = total_value + 3000000, cash_balance = cash_balance + 3000000, "
                + "goalkeeper_value = goalkeeper_value + 3000000 WHERE season_id = ? AND turn_no = ?", season, TURNS);
    }

    private BigDecimal multiple(long season) {
        return jdbc.queryForObject("SELECT t.total_value / s.initial_cash FROM season s JOIN turn t ON t.season_id = s.id "
                + "AND t.turn_no = s.path_length WHERE s.id = ?", BigDecimal.class, season).setScale(8, RoundingMode.HALF_UP);
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new HashSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static void collectNames(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                out.add(name);
                collectNames(node.get(name), out);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectNames(child, out));
        }
    }

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    void a_group_counts_only_when_every_league_of_it_is_closed() throws Exception {
        World w = world();
        assertThat(compare(w.agent, 0, 1).get("groups")).as("nothing is closed").isEmpty();

        close(w.root1, w.sibling1);
        assertThat(compare(w.agent, 0, 1).get("groups")).as("the third league of group 1 is still open").isEmpty();

        close(w.sibling1b);
        JsonNode one = compare(w.agent, 0, 1);
        assertThat(one.get("groups")).hasSize(1);
        assertThat(one.get("groups").get(0).get("groupLabel").asString()).isEqualTo("G1");

        close(w.root2, w.sibling2);
        assertThat(compare(w.agent, 0, 1).get("groups")).hasSize(2);
    }

    @Test
    void each_group_row_holds_the_two_pinned_versions_the_multiples_and_the_stored_scores_and_no_path_fact() throws Exception {
        World w = world();
        closeAll(w);
        scoring.ensureMember(w.agent);

        JsonNode report = compare(w.agent, 0, 1);

        assertThat(report.get("versionA").asInt()).isZero();
        assertThat(report.get("versionB").asInt()).isOne();
        assertThat(report.get("groups")).hasSize(2);
        JsonNode g1 = report.get("groups").get(0);
        JsonNode g2 = report.get("groups").get(1);
        assertThat(g1.get("groupLabel").asString()).isEqualTo("G1");
        assertThat(g2.get("groupLabel").asString()).isEqualTo("G2");
        assertThat(g1.get("a").get("memoryVersion").isNull()).isTrue();
        assertThat(g1.get("b").get("memoryVersion").asInt()).isOne();
        assertThat(new BigDecimal(g1.get("a").get("valuationMultiple").asString())).isEqualByComparingTo(multiple(w.none1));
        assertThat(new BigDecimal(g1.get("b").get("valuationMultiple").asString())).isEqualByComparingTo(multiple(w.v1Season1));
        assertThat(new BigDecimal(g2.get("a").get("valuationMultiple").asString())).isEqualByComparingTo(multiple(w.none2));
        assertThat(new BigDecimal(g2.get("b").get("valuationMultiple").asString())).isEqualByComparingTo(multiple(w.v1Season2));

        JsonNode score = g1.get("b").get("score");
        assertThat(score.isObject()).isTrue();
        assertThat(keys(score.get("items"))).containsExactlyInAnyOrder("risk", "discipline",
                "diversification", "turnover", "consistency", "formation", "role", "noise", "nudge");
        BigDecimal stored = jdbc.queryForObject("SELECT score FROM season_score WHERE season_id = ?", BigDecimal.class, w.v1Season1);
        assertThat(new BigDecimal(score.get("score").asString())).isEqualByComparingTo(stored);
        assertThat(g1.get("a").get("personaVersion").asInt()).isOne();
        assertThat(g1.get("b").get("personaVersion").asInt()).isOne();
        jdbc.update("INSERT INTO persona_version (persona_key, version, sha256, display_name) VALUES ('value-investor', 2, ?, 'Value')",
                "b".repeat(64));
        jdbc.update("UPDATE season_agent_config SET persona_version = 2, persona_sha256 = ? WHERE season_id = ?", "b".repeat(64),
                w.v1Season1);
        assertThat(compare(w.agent, 0, 1).get("groups").get(0).get("b").get("personaVersion").asInt()).isEqualTo(2);
        assertThat(g1.get("a").get("failedTurns").asInt()).isZero();
        assertThat(g1.get("a").get("experiencedSeed").asBoolean()).isFalse();
        assertThat(g1.get("b").get("experiencedSeed").asBoolean()).isFalse();

        Set<String> names = new HashSet<>();
        collectNames(report, names);
        assertThat(names).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
        String text = report.toString();
        for (long league : new long[] {w.root1, w.root2}) {
            String seed = jdbc.queryForObject("SELECT seed FROM league WHERE id = ?", String.class, league);
            assertThat(text).doesNotContain(seed);
        }
    }

    @Test
    void the_summary_counts_the_groups_where_b_is_ahead_and_says_anecdotal_under_five_groups() throws Exception {
        World w = world();
        closeAll(w);
        raise(w.v1Season2);
        scoring.ensureMember(w.agent);

        JsonNode summary = compare(w.agent, 0, 1).get("summary");

        assertThat(summary.get("groups").asInt()).isEqualTo(2);
        assertThat(summary.get("anecdotal").asBoolean()).isTrue();
        BigDecimal gap2 = multiple(w.v1Season2).subtract(multiple(w.none2));
        BigDecimal gap1 = multiple(w.v1Season1).subtract(multiple(w.none1));
        assertThat(summary.get("bWins").asInt()).isEqualTo((gap1.signum() > 0 ? 1 : 0) + (gap2.signum() > 0 ? 1 : 0));
        assertThat(new BigDecimal(summary.get("meanDiff").get("valuationMultiple").asString()))
                .isEqualByComparingTo(gap1.add(gap2).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP));
        assertThat(keys(summary.get("meanDiff").get("items"))).hasSize(9);
    }

    @Test
    void swapping_the_versions_swaps_the_sides_and_the_sign_of_the_difference() throws Exception {
        World w = world();
        closeAll(w);
        raise(w.v1Season2);

        JsonNode forward = compare(w.agent, 0, 1);
        JsonNode backward = compare(w.agent, 1, 0);

        assertThat(backward.get("groups").get(1).get("a").get("memoryVersion").asInt()).isOne();
        assertThat(backward.get("groups").get(1).get("b").get("memoryVersion").isNull()).isTrue();
        assertThat(backward.get("groups").get(1).get("a")).isEqualTo(forward.get("groups").get(1).get("b"));
        BigDecimal forwardDiff = new BigDecimal(forward.get("summary").get("meanDiff").get("valuationMultiple").asString());
        BigDecimal backwardDiff = new BigDecimal(backward.get("summary").get("meanDiff").get("valuationMultiple").asString());
        assertThat(backwardDiff).isEqualByComparingTo(forwardDiff.negate());
        assertThat(forward.get("summary").get("bWins").asInt()).isEqualTo(1);
        assertThat(backward.get("summary").get("bWins").asInt()).isZero();
    }

    @Test
    void the_same_version_on_both_sides_compares_the_first_two_seasons_of_it_in_the_group() throws Exception {
        World w = world();
        closeAll(w);

        JsonNode control = compare(w.agent, 0, 0);

        assertThat(control.get("groups")).as("only group 1 has two unpinned seasons").hasSize(1);
        assertThat(new BigDecimal(control.get("groups").get(0).get("a").get("valuationMultiple").asString()))
                .isEqualByComparingTo(multiple(w.none1));
        assertThat(new BigDecimal(control.get("groups").get(0).get("b").get("valuationMultiple").asString()))
                .isEqualByComparingTo(multiple(w.none1b));
        assertThat(compare(w.agent, 1, 1).get("groups")).as("one season of version 1 per group").isEmpty();
    }

    @Test
    void failed_turns_count_the_final_calls_without_a_usable_decision() throws Exception {
        World w = world();
        closeAll(w);
        calls.save(new AgentCall(w.v1Season1, 0, 1, AgentCallKind.TURN_DECISION, UUID.randomUUID(), AgentCallStatus.OK, 200,
                MODEL, MODEL, "fake", "request", "reply", null, 10, 5, new BigDecimal("0.00010000"), 100,
                DecisionOutcome.INVALID, null, "e".repeat(64)));
        calls.save(new AgentCall(w.v1Season1, 1, 1, AgentCallKind.TURN_DECISION, UUID.randomUUID(), AgentCallStatus.OK, 200,
                MODEL, MODEL, "fake", "request", "reply", null, 10, 5, new BigDecimal("0.00010000"), 100,
                DecisionOutcome.NO_RESPONSE, null, "e".repeat(64)));

        JsonNode g1 = compare(w.agent, 0, 1).get("groups").get(0);

        assertThat(g1.get("b").get("failedTurns").asInt()).isEqualTo(2);
        assertThat(g1.get("a").get("failedTurns").asInt()).isZero();
    }

    @Test
    void an_abandoned_season_is_left_out() throws Exception {
        World w = world();
        closeAll(w);
        assertThat(compare(w.agent, 0, 1).get("groups")).hasSize(2);

        jdbc.update("UPDATE season SET abandoned_turn_no = 5 WHERE id = ?", w.v1Season1);

        JsonNode report = compare(w.agent, 0, 1);
        assertThat(report.get("groups")).hasSize(1);
        assertThat(report.get("groups").get(0).get("groupLabel").asString()).as("labels follow the rows shown").isEqualTo("G1");
        assertThat(new BigDecimal(report.get("groups").get(0).get("b").get("valuationMultiple").asString()))
                .isEqualByComparingTo(multiple(w.v1Season2));
    }

    @Test
    void a_seed_the_agent_met_outside_the_group_is_marked_on_both_sides_of_that_group() throws Exception {
        World w = world();
        closeAll(w);
        long seed = jdbc.queryForObject("SELECT seed FROM league WHERE id = ?", Long.class, w.root2);
        MockHttpServletResponse r = post(mvc, AGENTS + "/" + w.agent + "/seasons", startBody(MODEL, null, null));
        long outside = json(r).get("id").asLong();
        jdbc.update("UPDATE season SET seed = ? WHERE id = ?", seed, outside);

        JsonNode report = compare(w.agent, 0, 1);

        assertThat(report.get("groups").get(0).get("a").get("experiencedSeed").asBoolean()).isFalse();
        assertThat(report.get("groups").get(1).get("a").get("experiencedSeed").asBoolean()).isTrue();
        assertThat(report.get("groups").get(1).get("b").get("experiencedSeed").asBoolean()).isTrue();
    }

    @Test
    void another_agents_seasons_and_versions_are_never_read() throws Exception {
        World w = world();
        closeAll(w);
        long other = AgentRunnerSupport.register(mvc, registerBody("value-investor", "OPENROUTER", "fake/model-y", "다른 에이전트"));

        assertThat(compareRaw(other, 0, 1).getStatus()).as("the other agent has no version 1").isEqualTo(404);
        assertThat(compare(other, 0, 0).get("groups")).isEmpty();
        assertThat(compareRaw(w.agent, 0, 2).getStatus()).isEqualTo(404);
    }
}
