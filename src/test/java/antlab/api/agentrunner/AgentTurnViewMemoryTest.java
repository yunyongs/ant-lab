package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
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

import antlab.auth.AgentActingAuthentication;
import antlab.season.TurnFacade;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * agent.memory in the agent turn view (AG-05 D): the season reads the one memory version it pinned at its start, built from
 * that version only, at every turn, whatever is written after it. Memory rows are inserted directly (their writing is
 * tested with the submit route); the seasons here are only played as far as the view needs.
 */
@IntegrationTest
@TestPropertySource(properties = "antlab.agent-runner.max-seasons-per-week=60")
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql", "/fixtures/replay_macro.sql"})
@Sql(scripts = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentTurnViewMemoryTest {

    private static final String MODEL = "fake/model-x";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TurnFacade turns;

    @BeforeEach
    void setUp() {
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
        jdbc.update("DELETE FROM persona_version WHERE persona_key = 'value-investor'");
    }

    private long agent() throws Exception {
        return AgentRunnerSupport.register(mvc, registerBody("value-investor", "OPENROUTER", MODEL, "기억 에이전트"));
    }

    private long start(long agent, Integer pin) throws Exception {
        return start(agent, MODEL, pin);
    }

    private long start(long agent, String model, Integer pin) throws Exception {
        String body = startBody(model, null, null);
        if (pin != null) {
            body = body.replace("\"runnerVersion\": \"r1\",", "\"runnerVersion\": \"r1\", \"memoryVersion\": " + pin + ",");
        }
        MockHttpServletResponse r = post(mvc, AGENTS + "/" + agent + "/seasons", body);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return json(r).get("id").asLong();
    }

    /** Version {@code n} written from a fresh season of the agent: reflection mark "r<n>", rules "rule <n>.1" and "rule <n>.2". */
    private void writeVersion(long agent, int n) throws Exception {
        writeVersion(agent, MODEL, n);
    }

    private void writeVersion(long agent, String model, int n) throws Exception {
        long source = start(agent, model, null);
        jdbc.update("INSERT INTO agent_reflection (season_id, member_id, outcome, body, filter_version) "
                + "VALUES (?, ?, 'ACCEPTED', ?::jsonb, 'mf1-test')", source, agent,
                "{\"schema\": \"season-reflection/1\", \"good\": [\"r" + n + " a\", \"r" + n + " b\", \"r" + n + " c\"],"
                        + " \"mistakes\": [], \"ruleCandidates\": [\"r" + n + " rule\"]}");
        jdbc.update("INSERT INTO agent_memory (member_id, version, source_season_id, playbook, playbook_carried, filter_version) "
                + "VALUES (?, ?, ?, ?::jsonb, false, 'mf1-test')", agent, n, source,
                "{\"schema\": \"operating-note/1\", \"rules\": [\"rule " + n + ".1\", \"rule " + n + ".2\"]}");
        // a settled source keeps the agent under the limit of active seasons
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", source);
    }

    private JsonNode memory(long agent, long season) throws Exception {
        MockHttpServletResponse r = mvc.get().uri(AGENTS + "/" + agent + "/seasons/" + season + "/turn-view")
                .header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json(r).get("agent").get("memory");
    }

    private static List<String> goodOf(JsonNode reflections) {
        List<String> out = new ArrayList<>();
        reflections.forEach(r -> out.add(r.get("good").get(0).asString()));
        return out;
    }

    private <T> T acting(long agent, Supplier<T> call) {
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agent));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void a_season_without_a_pin_has_no_memory_even_when_the_agent_has_versions() throws Exception {
        long agent = agent();
        writeVersion(agent, 1);
        long season = start(agent, null);

        assertThat(memory(agent, season).isNull()).isTrue();
    }

    @Test
    void the_pinned_version_is_built_from_that_version_only_and_stays_after_newer_versions_are_written() throws Exception {
        long agent = agent();
        writeVersion(agent, 1);
        writeVersion(agent, 2);
        long season = start(agent, 2);

        JsonNode before = memory(agent, season);
        assertThat(before.get("version").asInt()).isEqualTo(2);
        assertThat(before.get("playbook")).extracting(JsonNode::asString).containsExactly("rule 2.1", "rule 2.2");
        assertThat(goodOf(before.get("reflections"))).containsExactly("r1 a", "r2 a");

        writeVersion(agent, 3);
        writeVersion(agent, 4);
        assertThat(jdbc.queryForObject("SELECT max(version) FROM agent_memory WHERE member_id = ?", Integer.class, agent))
                .isEqualTo(4);

        assertThat(memory(agent, season)).as("the same view after v3 and v4 exist").isEqualTo(before);
        acting(agent, () -> turns.advance(season, 0));
        assertThat(memory(agent, season)).as("and at the next turn").isEqualTo(before);
    }

    @Test
    void a_version_reads_its_note_and_the_reflections_of_itself_and_the_two_before_it() throws Exception {
        long agent = agent();
        for (int n = 1; n <= 4; n++) {
            writeVersion(agent, n);
        }
        long first = start(agent, 1);
        long latest = start(agent, 4);

        JsonNode one = memory(agent, first);
        assertThat(one.get("version").asInt()).isOne();
        assertThat(goodOf(one.get("reflections"))).containsExactly("r1 a");
        JsonNode four = memory(agent, latest);
        assertThat(four.get("playbook")).extracting(JsonNode::asString).containsExactly("rule 4.1", "rule 4.2");
        assertThat(goodOf(four.get("reflections"))).containsExactly("r2 a", "r3 a", "r4 a");
    }

    @Test
    void the_memory_part_names_only_the_version_the_note_and_the_reflections() throws Exception {
        long agent = agent();
        writeVersion(agent, 1);
        long season = start(agent, 1);

        JsonNode memory = memory(agent, season);
        Set<String> keys = new TreeSet<>();
        memory.propertyNames().forEach(keys::add);
        assertThat(keys).containsExactly("playbook", "reflections", "version");
        Set<String> reflectionKeys = new TreeSet<>();
        memory.get("reflections").get(0).propertyNames().forEach(reflectionKeys::add);
        assertThat(reflectionKeys).containsExactly("good", "mistakes", "ruleCandidates", "schema");
    }

    @Test
    void another_agents_memory_is_never_read_into_the_view() throws Exception {
        long agent = agent();
        long other = AgentRunnerSupport.register(mvc,
                registerBody("value-investor", "OPENROUTER", "fake/model-y", "다른 에이전트"));
        writeVersion(other, "fake/model-y", 1);
        writeVersion(agent, 1);
        jdbc.update("UPDATE agent_memory SET playbook = '{\"schema\": \"operating-note/1\", \"rules\": [\"other rule\"]}'::jsonb "
                + "WHERE member_id = ?", other);
        long season = start(agent, 1);

        assertThat(memory(agent, season).get("playbook")).extracting(JsonNode::asString)
                .containsExactly("rule 1.1", "rule 1.2");
    }
}
