package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static antlab.api.agentrunner.AgentRunnerSupport.startBody;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.AgentCall;
import antlab.agent.AgentCallKind;
import antlab.agent.AgentCallRepository;
import antlab.agent.AgentCallStatus;
import antlab.support.IntegrationTest;
import antlab.support.SeasonAccountCleaner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * What the database keeps of a call text: the stored request and reply must be byte-for-byte what the model got and said.
 * A lone surrogate cannot be encoded as UTF-8, so the driver would store a replacement; the route refuses such a text
 * before it reaches the table (AG-05 D, follow-up of the memory submit review).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class AgentCallTextTest {

    private static final String MODEL = "fake/model-x";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AgentCallRepository calls;

    @BeforeEach
    void setUp() {
        ensureOwner(jdbc);
    }

    @AfterEach
    void cleanUp() {
        List<Long> seasonIds = jdbc.queryForList("SELECT s.id FROM season s JOIN agent_profile p ON p.member_id = s.member_id "
                + "WHERE p.owner_member_id = ?", Long.class, OWNER_ID);
        List<Long> memberIds = new ArrayList<>(jdbc.queryForList(
                "SELECT member_id FROM agent_profile WHERE owner_member_id = ?", Long.class, OWNER_ID));
        memberIds.add(OWNER_ID);
        SeasonAccountCleaner.delete(jdbc, seasonIds, memberIds, List.of(), List.of(), List.of());
        jdbc.update("DELETE FROM persona_version WHERE persona_key = 'value-investor'");
    }

    private long season(long agent) throws Exception {
        return json(post(mvc, AGENTS + "/" + agent + "/seasons", startBody(MODEL, null, null))).get("id").asLong();
    }

    private static String flushBody(String requestJson, String responseJson, String modelJson) {
        return "{\"calls\": [{\"attemptNo\": 1, \"kind\": \"TURN_DECISION\", \"ledgerCallId\": \"" + UUID.randomUUID()
                + "\", \"status\": \"OK\", \"modelRequested\": " + modelJson + ", \"requestText\": " + requestJson
                + ", \"responseText\": " + responseJson + ", \"latencyMs\": 12}]}";
    }

    @Test
    void the_driver_does_not_keep_a_lone_surrogate_so_the_stored_text_would_differ_from_the_sent_one() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, registerBody("value-investor", "OPENROUTER", MODEL, "문자"));
        long season = season(agent);
        String sent = "a\uD800b";

        calls.save(new AgentCall(season, 0, 1, AgentCallKind.TURN_DECISION, UUID.randomUUID(), AgentCallStatus.OK, 200,
                MODEL, MODEL, "fake", sent, "reply", null, 10, 5, new BigDecimal("0.00010000"), 100, null, null, null));

        assertThat(jdbc.queryForObject("SELECT request_text FROM agent_call WHERE season_id = ?", String.class, season))
                .as("the driver stores a question mark for a character it cannot encode").isEqualTo("a?b");
    }

    @Test
    void a_call_text_with_a_lone_surrogate_is_refused_and_a_surrogate_pair_is_stored_as_sent() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, registerBody("value-investor", "OPENROUTER", MODEL, "문자"));
        long season = season(agent);
        String uri = AGENTS + "/" + agent + "/seasons/" + season + "/turns/0/calls";
        String ok = "\"fine\"";

        for (String bad : new String[] {"\"a\\ud800b\"", "\"a\\udc00b\"", "\"x\\ud800\"", "\"\\udc00\\ud800\""}) {
            MockHttpServletResponse inRequest = post(mvc, uri, AgentRunnerSupport.TOKEN, flushBody(bad, ok, "\"" + MODEL + "\""), true);
            assertThat(inRequest.getStatus()).as("request " + bad).isEqualTo(400);
            MockHttpServletResponse inResponse = post(mvc, uri, AgentRunnerSupport.TOKEN, flushBody(ok, bad, "\"" + MODEL + "\""), true);
            assertThat(inResponse.getStatus()).as("response " + bad).isEqualTo(400);
            assertThat(inResponse.getContentAsString()).doesNotContain("ud800").doesNotContain("udc00");
        }
        MockHttpServletResponse inModel = post(mvc, uri, AgentRunnerSupport.TOKEN,
                flushBody(ok, ok, "\"" + MODEL + "\\ud800\""), true);
        assertThat(inModel.getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_call WHERE season_id = ?", Long.class, season)).isZero();

        String pair = "\"a\\ud83d\\ude00b\"";
        assertThat(post(mvc, uri, AgentRunnerSupport.TOKEN, flushBody(pair, pair, "\"" + MODEL + "\""), true).getStatus())
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT request_text FROM agent_call WHERE season_id = ?", String.class, season))
                .isEqualTo("a\uD83D\uDE00b");
    }
}
