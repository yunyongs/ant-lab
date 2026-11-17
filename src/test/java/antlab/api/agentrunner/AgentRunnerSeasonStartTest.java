package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.TestMembers;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** POST /internal/agent-runner/v1/agents/{agentId}/seasons: the human start path, run for the agent, plus its config. */
@IntegrationTest
@Transactional
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class AgentRunnerSeasonStartTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestMembers members;
    @Autowired
    EntityManager em;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    /** The test transaction never commits, so pending JPA inserts are flushed before plain SQL looks at the tables. */
    private long count(String sql, Object... args) {
        em.flush();
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long agent() throws Exception {
        return AgentRunnerSupport.register(mvc, REGISTER_BODY);
    }

    private MockHttpServletResponse start(long agent, String body) {
        return post(mvc, AGENTS + "/" + agent + "/seasons", body);
    }

    @Test
    void a_started_season_belongs_to_the_agent_and_has_its_config() throws Exception {
        long agent = agent();
        MockHttpServletResponse r = start(agent, START_BODY);

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode season = json(r);
        long id = season.get("id").asLong();
        assertThat(season.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(season.get("pathLength").asInt()).isEqualTo(12);
        assertThat(season.get("riskLimit").asString()).isEqualTo("-0.20000000");
        assertThat(season.get("cashBalance").asString()).isEqualTo(season.get("initialCash").asString());
        assertThat(count("SELECT member_id FROM season WHERE id = ?", id)).isEqualTo(agent);
        assertThat(count("SELECT count(*) FROM cash_account WHERE season_id = ?", id)).isOne();
        assertThat(count("SELECT count(*) FROM cash_ledger WHERE season_id = ?", id)).isOne();
        assertThat(count("SELECT count(*) FROM season_path WHERE season_id = ?", id)).isEqualTo(12);

        em.flush();
        Map<String, Object> config = jdbc.queryForMap("SELECT * FROM season_agent_config WHERE season_id = ?", id);
        assertThat(config).containsEntry("member_id", agent).containsEntry("persona_key", "value-investor")
                .containsEntry("persona_version", 1).containsEntry("persona_sha256", "a".repeat(64))
                .containsEntry("model_route", "OPENROUTER").containsEntry("model_requested", "fake/model-1")
                .containsEntry("max_tokens", 2000).containsEntry("turn_timeout_ms", 60000)
                .containsEntry("prompt_template_version", "p1").containsEntry("runner_version", "r1")
                .containsEntry("budget_calls", 100).containsEntry("model_seed", null)
                .containsEntry("memory_version", null);
        assertThat(config.get("temperature")).hasToString("0.700");
        assertThat(config.get("budget_usd")).hasToString("1.50000000");
    }

    @Test
    void the_season_is_not_visible_to_the_owner_on_the_human_api() throws Exception {
        long agent = agent();
        long id = json(start(agent, START_BODY)).get("id").asLong();
        // the owner's own token sees only the owner's seasons; the agent's season is another member's
        var r = mvc.get().uri("/api/v1/seasons/" + id).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(404);
    }

    @Test
    void the_response_carries_no_config_and_no_member() throws Exception {
        JsonNode season = json(start(agent(), START_BODY));
        assertThat(season.propertyNames()).doesNotContain("config", "memberId", "seed", "engineVersion", "personaSha256");
    }

    @Test
    void the_start_rules_of_a_human_apply_to_the_agent() throws Exception {
        long agent = agent();
        for (int i = 0; i < 3; i++) {
            assertThat(start(agent, START_BODY).getStatus()).isEqualTo(201);
        }
        MockHttpServletResponse fourth = start(agent, START_BODY);
        assertThat(fourth.getStatus()).isEqualTo(409);
        assertThat(json(fourth).get("code").asString()).isEqualTo("SEASON_LIMIT");
        assertThat(count("SELECT count(*) FROM season WHERE member_id = ?", agent)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM season_agent_config WHERE member_id = ?", agent)).isEqualTo(3);
    }

    @Test
    void another_agent_has_its_own_season_count() throws Exception {
        long first = agent();
        long second = AgentRunnerSupport.register(mvc,
                AgentRunnerSupport.registerBody("momentum-trader", "OPENROUTER", "fake/model-1", "모멘텀"));
        for (int i = 0; i < 3; i++) {
            start(first, START_BODY);
        }
        assertThat(start(second, START_BODY).getStatus()).isEqualTo(201);
    }

    @Test
    void the_persona_and_route_come_from_the_registration_not_the_body() throws Exception {
        long agent = agent();
        String body = START_BODY.replace("{\"riskLimit\"",
                "{\"personaKey\": \"other\", \"modelRoute\": \"OLLAMA\", \"riskLimit\"");
        long id = json(start(agent, body)).get("id").asLong();
        em.flush();
        assertThat(jdbc.queryForMap("SELECT persona_key, model_route FROM season_agent_config WHERE season_id = ?", id))
                .containsEntry("persona_key", "value-investor").containsEntry("model_route", "OPENROUTER");
    }

    @Test
    void a_retired_agent_is_409_AGENT_RETIRED_and_leaves_no_rows() throws Exception {
        long agent = agent();
        em.flush();
        jdbc.update("UPDATE agent_profile SET status = 'RETIRED' WHERE member_id = ?", agent);
        em.clear();
        long seasons = count("SELECT count(*) FROM season");

        MockHttpServletResponse r = start(agent, START_BODY);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(json(r).get("code").asString()).isEqualTo("AGENT_RETIRED");
        assertThat(count("SELECT count(*) FROM season")).isEqualTo(seasons);
        assertThat(count("SELECT count(*) FROM season_agent_config")).isZero();
    }

    @Test
    void a_season_on_another_model_than_the_registered_one_is_400_and_leaves_no_rows() throws Exception {
        long agent = agent();
        long seasons = count("SELECT count(*) FROM season");

        MockHttpServletResponse r = start(agent, START_BODY.replace("\"modelRequested\": \"fake/model-1\"",
                "\"modelRequested\": \"fake/model-2\""));
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(count("SELECT count(*) FROM season")).isEqualTo(seasons);
        assertThat(count("SELECT count(*) FROM season_agent_config")).isZero();
        // the registered model itself still starts
        assertThat(start(agent, START_BODY).getStatus()).isEqualTo(201);
    }

    @Test
    void a_human_another_owners_agent_and_a_missing_id_all_get_the_same_404() throws Exception {
        long agent = agent();
        long human = members.defaultId();
        long otherOwner = jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES "
                + "('other-owner@example.invalid', '다른 소유자', '!') RETURNING id", Long.class);
        long foreignAgent = jdbc.queryForObject("INSERT INTO member (name, kind) VALUES ('남의 에이전트', 'AGENT') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO agent_profile (member_id, owner_member_id, persona_key, model_route, model_id) "
                + "VALUES (?, ?, 'value-investor', 'OPENROUTER', 'fake/model-1')", foreignAgent, otherOwner);

        List<MockHttpServletResponse> answers = new ArrayList<>();
        for (String id : new String[] {String.valueOf(human), String.valueOf(foreignAgent), "987654321", "0", "-1",
                "abc", "1e3", "99999999999999999999"}) {
            answers.add(post(mvc, AGENTS + "/" + id + "/seasons", START_BODY));
        }
        for (MockHttpServletResponse r : answers) {
            assertThat(r.getStatus()).isEqualTo(404);
            assertThat(json(r).get("code").asString()).isEqualTo("NOT_FOUND");
        }
        List<String> bodies = answers.stream().map(r -> {
            try {
                JsonNode n = json(r);
                return n.get("title").asString() + "|" + n.get("detail").asString() + "|" + n.get("status").asInt();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct().toList();
        assertThat(bodies).as("one shape for every reason").hasSize(1);
        assertThat(count("SELECT count(*) FROM season WHERE member_id IN (?, ?, ?)", agent, human, foreignAgent)).isZero();
    }

    @Test
    void a_missing_agent_is_404_before_the_body_is_read() throws Exception {
        MockHttpServletResponse r = post(mvc, AGENTS + "/987654321/seasons", "not json");
        assertThat(r.getStatus()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"personaSha256\": \"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"",
            "\"personaSha256\": \"abc\"",
            "\"personaVersion\": 0",
            "\"modelRequested\": \" fake\"",
            "\"modelRequested\": \"\"",
            "\"temperature\": 2.5",
            "\"temperature\": -0.1",
            "\"temperature\": 0.7001",
            "\"maxTokens\": 0",
            "\"turnTimeoutMs\": -1",
            "\"promptTemplateVersion\": \"\"",
            "\"runnerVersion\": \"rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr\"",
            "\"memoryVersion\": 0",
            "\"budgetCalls\": 0",
            "\"budgetUsd\": -1"})
    void a_config_outside_the_table_rules_is_400_and_leaves_no_rows(String replacement) throws Exception {
        long agent = agent();
        String key = replacement.substring(1, replacement.indexOf('"', 1));
        String body = START_BODY.replaceAll("\"" + key + "\": [^,}]+", java.util.regex.Matcher.quoteReplacement(replacement));
        if (body.equals(START_BODY)) {
            body = START_BODY.replace("\"config\": {", "\"config\": {" + replacement + ", ");
        }

        MockHttpServletResponse r = start(agent, body);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(count("SELECT count(*) FROM season WHERE member_id = ?", agent)).isZero();
    }

    @Test
    void a_missing_config_or_season_field_is_400() throws Exception {
        long agent = agent();
        assertThat(start(agent, "{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30, "
                + "\"midfielder\": 30, \"forward\": 30}}").getStatus()).isEqualTo(400);
        assertThat(start(agent, START_BODY.replace("\"riskLimit\": \"-0.20\"", "\"riskLimit\": \"-0.25\"")).getStatus())
                .isEqualTo(400);
        assertThat(start(agent, START_BODY.replace("\"goalkeeper\": 10", "\"goalkeeper\": 11")).getStatus()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM season WHERE member_id = ?", agent)).isZero();
    }

    @Test
    void the_season_start_of_the_owner_is_unaffected() throws Exception {
        agent();
        var r = mvc.post().uri("/api/v1/seasons").contentType("application/json")
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30, "
                        + "\"midfielder\": 30, \"forward\": 30}}").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM season_agent_config")).isZero();
        assertThat(count("SELECT member_id FROM season WHERE id = ?", json(r).get("id").asLong()))
                .isEqualTo(members.defaultId());
        assertThat(OWNER_ID).isNotEqualTo(members.defaultId());
    }
}
