package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.HEADER;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.TOKEN;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.support.DefaultBearerConfig.ANONYMOUS;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The persona version pin of the agent season start and the config read of the runner (AG-04 PR B): one hash per
 * (persona, version), no start below the newest version, and a refused start leaves no season and no version row.
 */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class AgentPersonaPinTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    private long count(String sql, Object... args) {
        em.flush();
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long agent() throws Exception {
        return AgentRunnerSupport.register(mvc, REGISTER_BODY);
    }

    private static String body(int version, String sha) {
        return START_BODY.replace("\"personaVersion\": 1", "\"personaVersion\": " + version)
                .replace("\"personaSha256\": \"" + SHA_A + "\"", "\"personaSha256\": \"" + sha + "\"");
    }

    private MockHttpServletResponse start(long agent, String body) {
        return post(mvc, AGENTS + "/" + agent + "/seasons", body);
    }

    private MockHttpServletResponse config(long agent, long season, String token) {
        var request = mvc.get().uri(AGENTS + "/" + agent + "/seasons/" + season + "/config").requestAttr(ANONYMOUS, true);
        if (token != null) {
            request = request.header(HEADER, token);
        }
        return request.exchange().getResponse();
    }

    private void refused(MockHttpServletResponse r, String code, long agent, long seasonsBefore, long versionsBefore)
            throws Exception {
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo(code);
        assertThat(count("SELECT count(*) FROM season WHERE member_id = ?", agent)).isEqualTo(seasonsBefore);
        assertThat(count("SELECT count(*) FROM season_agent_config WHERE member_id = ?", agent)).isEqualTo(seasonsBefore);
        assertThat(count("SELECT count(*) FROM persona_version WHERE persona_key = 'value-investor'"))
                .isEqualTo(versionsBefore);
    }

    @Test
    void the_first_start_records_the_persona_version_and_a_repeat_with_the_same_hash_is_accepted() throws Exception {
        long agent = agent();
        assertThat(count("SELECT count(*) FROM persona_version WHERE persona_key = 'value-investor'")).isZero();

        assertThat(start(agent, START_BODY).getStatus()).isEqualTo(201);
        assertThat(start(agent, START_BODY).getStatus()).isEqualTo(201);

        em.flush();
        assertThat(jdbc.queryForList("SELECT version, sha256, display_name FROM persona_version WHERE persona_key = 'value-investor'"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("version", 1)
                        .containsEntry("sha256", SHA_A).containsEntry("display_name", "Value"));
    }

    @Test
    void another_hash_for_a_recorded_version_is_PERSONA_VERSION_CONFLICT_and_leaves_nothing() throws Exception {
        long agent = agent();
        assertThat(start(agent, START_BODY).getStatus()).isEqualTo(201);

        refused(start(agent, body(1, SHA_B)), "PERSONA_VERSION_CONFLICT", agent, 1, 1);
    }

    @Test
    void the_conflict_holds_across_agents_of_one_persona() throws Exception {
        long first = agent();
        long second = AgentRunnerSupport.register(mvc,
                AgentRunnerSupport.registerBody("value-investor", "OPENROUTER", "fake/model-2", "가치 둘"));
        assertThat(start(first, START_BODY).getStatus()).isEqualTo(201);

        MockHttpServletResponse r = start(second, body(1, SHA_B).replace("fake/model-1", "fake/model-2"));
        refused(r, "PERSONA_VERSION_CONFLICT", second, 0, 1);
    }

    @Test
    void a_version_below_the_newest_recorded_one_is_PERSONA_VERSION_STALE() throws Exception {
        long agent = agent();
        assertThat(start(agent, START_BODY).getStatus()).isEqualTo(201);
        assertThat(start(agent, body(2, SHA_B)).getStatus()).isEqualTo(201);

        // version 1 with its own recorded hash is still below version 2
        refused(start(agent, START_BODY), "PERSONA_VERSION_STALE", agent, 2, 2);
        refused(start(agent, body(1, SHA_C)), "PERSONA_VERSION_CONFLICT", agent, 2, 2);
    }

    @Test
    void a_new_version_with_the_bytes_of_an_older_one_is_PERSONA_VERSION_CONFLICT() throws Exception {
        long agent = agent();
        assertThat(start(agent, START_BODY).getStatus()).isEqualTo(201);

        refused(start(agent, body(2, SHA_A)), "PERSONA_VERSION_CONFLICT", agent, 1, 1);
    }

    @Test
    void a_version_may_skip_numbers_but_not_go_down() throws Exception {
        long agent = agent();
        assertThat(start(agent, body(3, SHA_C)).getStatus()).isEqualTo(201);

        refused(start(agent, body(2, SHA_B)), "PERSONA_VERSION_STALE", agent, 1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " Value", "Value ", "가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하"})
    void a_display_name_outside_the_rules_is_400(String name) throws Exception {
        long agent = agent();

        MockHttpServletResponse r = start(agent, START_BODY.replace("\"personaDisplayName\": \"Value\"",
                "\"personaDisplayName\": \"" + name + "\""));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(count("SELECT count(*) FROM persona_version WHERE persona_key = 'value-investor'")).isZero();
    }

    @Test
    void the_config_read_returns_what_the_start_recorded() throws Exception {
        long agent = agent();
        long season = json(start(agent, START_BODY)).get("id").asLong();

        MockHttpServletResponse r = config(agent, season, TOKEN);

        assertThat(r.getStatus()).isEqualTo(200);
        JsonNode c = json(r);
        assertThat(c.get("personaKey").asString()).isEqualTo("value-investor");
        assertThat(c.get("personaVersion").asInt()).isEqualTo(1);
        assertThat(c.get("personaSha256").asString()).isEqualTo(SHA_A);
        assertThat(c.get("modelRoute").asString()).isEqualTo("OPENROUTER");
        assertThat(c.get("modelRequested").asString()).isEqualTo("fake/model-1");
        assertThat(c.get("promptTemplateVersion").asString()).isEqualTo("p1");
        assertThat(c.get("runnerVersion").asString()).isEqualTo("r1");
        assertThat(c.get("memoryVersion").isNull()).isTrue();
        assertThat(new java.math.BigDecimal(c.get("temperature").asString())).isEqualByComparingTo("0.7");
        assertThat(c.get("modelSeed").isNull()).isTrue();
        assertThat(c.get("maxTokens").asInt()).isEqualTo(2000);
        assertThat(c.get("turnTimeoutMs").asInt()).isEqualTo(60000);
        assertThat(c.get("budgetCalls").asInt()).isEqualTo(100);
        assertThat(new java.math.BigDecimal(c.get("budgetUsd").asString())).isEqualByComparingTo("1.5");
        assertThat(c.propertyNames()).doesNotContain("seed", "memberId", "seasonId", "createdAt", "personaDisplayName");
    }

    @Test
    void the_config_of_another_agents_or_a_missing_season_is_404_and_a_missing_token_401() throws Exception {
        long first = agent();
        long second = AgentRunnerSupport.register(mvc,
                AgentRunnerSupport.registerBody("value-investor", "OPENROUTER", "fake/model-2", "가치 둘"));
        long season = json(start(first, START_BODY)).get("id").asLong();

        assertThat(config(second, season, TOKEN).getStatus()).isEqualTo(404);
        assertThat(config(first, season + 1_000_000, TOKEN).getStatus()).isEqualTo(404);
        assertThat(config(first, season, null).getStatus()).isEqualTo(401);
    }
}
