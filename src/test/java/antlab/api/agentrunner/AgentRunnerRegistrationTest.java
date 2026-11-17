package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.OWNER_ID;
import static antlab.api.agentrunner.AgentRunnerSupport.ensureOwner;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static antlab.api.agentrunner.AgentRunnerSupport.registerBody;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** POST /internal/agent-runner/v1/agents: idempotent on (owner, persona, route, model); an agent has no credentials. */
@IntegrationTest
@Transactional
class AgentRunnerRegistrationTest {

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

    /** The test transaction never commits, so pending JPA inserts are flushed before plain SQL looks at the tables. */
    private long count(String sql, Object... args) {
        em.flush();
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void a_new_agent_is_201_and_an_agent_member_without_credentials() throws Exception {
        long members = count("SELECT count(*) FROM member");
        MockHttpServletResponse r = post(mvc, AGENTS, REGISTER_BODY);

        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode body = json(r);
        assertThat(body.propertyNames()).containsExactly("agentId", "displayName", "personaKey", "modelRoute",
                "modelId", "status");
        assertThat(body.get("displayName").asString()).isEqualTo("가치 투자자");
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        long agent = body.get("agentId").asLong();
        em.flush();
        Map<String, Object> member = jdbc.queryForMap("SELECT kind, email, password, role FROM member WHERE id = ?", agent);
        assertThat(member).containsEntry("kind", "AGENT").containsEntry("email", null).containsEntry("password", null)
                .containsEntry("role", "USER");
        Map<String, Object> profile = jdbc.queryForMap("SELECT owner_member_id, persona_key, model_route, model_id, status "
                + "FROM agent_profile WHERE member_id = ?", agent);
        assertThat(profile).containsEntry("owner_member_id", OWNER_ID).containsEntry("persona_key", "value-investor")
                .containsEntry("model_route", "OPENROUTER").containsEntry("model_id", "fake/model-1")
                .containsEntry("status", "ACTIVE");
        assertThat(count("SELECT count(*) FROM member")).isEqualTo(members + 1);
        assertThat(count("SELECT count(*) FROM refresh_token WHERE member_id = ?", agent)).isZero();
    }

    @Test
    void the_same_agent_again_is_200_with_the_same_id_and_no_new_rows() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        long members = count("SELECT count(*) FROM member");
        long profiles = count("SELECT count(*) FROM agent_profile");

        MockHttpServletResponse again = post(mvc, AGENTS, REGISTER_BODY);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again).get("agentId").asLong()).isEqualTo(agent);
        assertThat(count("SELECT count(*) FROM member")).isEqualTo(members);
        assertThat(count("SELECT count(*) FROM agent_profile")).isEqualTo(profiles);
    }

    @Test
    void a_new_display_name_does_not_make_a_new_agent_and_does_not_rename_it() throws Exception {
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        MockHttpServletResponse again = post(mvc, AGENTS,
                registerBody("value-investor", "OPENROUTER", "fake/model-1", "다른 이름"));
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again).get("agentId").asLong()).isEqualTo(agent);
        assertThat(json(again).get("displayName").asString()).isEqualTo("가치 투자자");
    }

    @Test
    void a_different_model_route_or_persona_is_a_different_agent() throws Exception {
        long first = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        long otherModel = AgentRunnerSupport.register(mvc,
                registerBody("value-investor", "OPENROUTER", "fake/model-2", "가치 투자자"));
        long otherRoute = AgentRunnerSupport.register(mvc,
                registerBody("value-investor", "OLLAMA", "fake/model-1", "가치 투자자"));
        long otherPersona = AgentRunnerSupport.register(mvc,
                registerBody("momentum-trader", "OPENROUTER", "fake/model-1", "모멘텀"));
        assertThat(List.of(first, otherModel, otherRoute, otherPersona)).doesNotHaveDuplicates();
        assertThat(count("SELECT count(*) FROM agent_profile WHERE owner_member_id = ?", OWNER_ID)).isEqualTo(4);
    }

    @Test
    void the_owner_is_the_configured_one_whatever_the_body_says() throws Exception {
        String body = REGISTER_BODY.replace("{", "{\"ownerMemberId\": 1, ");
        long agent = AgentRunnerSupport.register(mvc, body);
        assertThat(count("SELECT owner_member_id FROM agent_profile WHERE member_id = ?", agent)).isEqualTo(OWNER_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"personaKey\": \"Value-Investor\", \"modelRoute\": \"OPENROUTER\", \"modelId\": \"m\", \"displayName\": \"n\"}",
            "{\"personaKey\": \"-value\", \"modelRoute\": \"OPENROUTER\", \"modelId\": \"m\", \"displayName\": \"n\"}",
            "{\"personaKey\": \"value--investor\", \"modelRoute\": \"OPENROUTER\", \"modelId\": \"m\", \"displayName\": \"n\"}",
            "{\"personaKey\": \"value\", \"modelRoute\": \"OTHER\", \"modelId\": \"m\", \"displayName\": \"n\"}",
            "{\"personaKey\": \"value\", \"modelRoute\": \"OPENROUTER\", \"modelId\": \" m\", \"displayName\": \"n\"}",
            "{\"personaKey\": \"value\", \"modelRoute\": \"OPENROUTER\", \"modelId\": \"m \", \"displayName\": \"n\"}",
            "{\"personaKey\": \"value\", \"modelRoute\": \"OPENROUTER\", \"modelId\": \"\", \"displayName\": \"n\"}",
            "{\"personaKey\": \"value\", \"modelRoute\": \"OPENROUTER\", \"modelId\": \"m\", \"displayName\": \"\"}",
            "{\"personaKey\": \"value\", \"modelRoute\": \"OPENROUTER\", \"modelId\": \"m\", \"displayName\": \" \"}",
            "[]",
            "not json"})
    void an_invalid_body_is_400_and_creates_nothing(String body) throws Exception {
        long members = count("SELECT count(*) FROM member");
        MockHttpServletResponse r = post(mvc, AGENTS, body);
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(count("SELECT count(*) FROM member")).isEqualTo(members);
        assertThat(count("SELECT count(*) FROM agent_profile")).isZero();
    }

    @Test
    void over_long_values_are_400() throws Exception {
        assertThat(post(mvc, AGENTS, registerBody("a".repeat(65), "OPENROUTER", "m", "n")).getStatus()).isEqualTo(400);
        assertThat(post(mvc, AGENTS, registerBody("a", "OPENROUTER", "m".repeat(201), "n")).getStatus()).isEqualTo(400);
        assertThat(post(mvc, AGENTS, registerBody("a", "OPENROUTER", "m", "n".repeat(51))).getStatus()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM agent_profile")).isZero();
    }

    @Test
    void a_wrong_method_on_the_agents_path_is_405_for_the_token_holder() {
        var r = mvc.get().uri(AGENTS).header(AgentRunnerSupport.HEADER, AgentRunnerSupport.TOKEN)
                .requestAttr(antlab.support.DefaultBearerConfig.ANONYMOUS, true).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(405);
    }

    @Test
    void an_unconfigured_owner_row_is_401_not_a_server_error() throws Exception {
        jdbc.update("DELETE FROM member WHERE id = ?", OWNER_ID);
        MockHttpServletResponse r = post(mvc, AGENTS, REGISTER_BODY);
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(json(r).get("code").asString()).isEqualTo("UNAUTHENTICATED");
    }
}
