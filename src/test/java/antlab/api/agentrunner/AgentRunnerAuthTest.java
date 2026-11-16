package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.BASE;
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
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Who may call the runner paths: only the holder of the service token, and a human token never counts. The two
 * credentials do not cross: the runner token opens nothing under /api/v1.
 */
@IntegrationTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
@Sql({"/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
class AgentRunnerAuthTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestTokens tokens;
    @Autowired
    TestMembers members;

    @BeforeEach
    void owner() {
        ensureOwner(jdbc);
    }

    static Stream<Arguments> wrongTokens() {
        return Stream.of(
                Arguments.of("no header", null),
                Arguments.of("empty", ""),
                Arguments.of("blank", " "),
                Arguments.of("wrong value", "x".repeat(TOKEN.length())),
                Arguments.of("one character short", TOKEN.substring(0, TOKEN.length() - 1)),
                Arguments.of("one character longer", TOKEN + "x"),
                Arguments.of("trailing space", TOKEN + " "),
                Arguments.of("leading space", " " + TOKEN),
                Arguments.of("other letter case", TOKEN.toUpperCase()));
    }

    private void assertUnauthenticated(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(json(r).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.getContentAsString()).doesNotContain(TOKEN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongTokens")
    void registration_refuses_a_missing_or_wrong_token(String label, String token) throws Exception {
        assertUnauthenticated(post(mvc, AGENTS, token, REGISTER_BODY, true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_profile", Long.class)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wrongTokens")
    void season_start_refuses_a_missing_or_wrong_token(String label, String token) throws Exception {
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        assertUnauthenticated(post(mvc, AGENTS + "/" + agent + "/seasons", token, START_BODY, true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season WHERE member_id = ?", Long.class, agent)).isZero();
    }

    @Test
    void a_wrong_token_is_refused_before_the_agent_is_looked_up() throws Exception {
        // no oracle: a wrong token answers 401 for an id that exists and for one that does not
        long agent = AgentRunnerSupport.register(mvc, REGISTER_BODY);
        assertUnauthenticated(post(mvc, AGENTS + "/" + agent + "/seasons", "wrong", START_BODY, true));
        assertUnauthenticated(post(mvc, AGENTS + "/987654321/seasons", "wrong", START_BODY, true));
    }

    @Test
    void a_human_user_token_does_not_open_a_runner_path() throws Exception {
        String user = "Bearer " + tokens.user(members.defaultId());
        var r = mvc.post().uri(AGENTS).contentType("application/json").content(REGISTER_BODY)
                .header("Authorization", user).exchange().getResponse();
        assertUnauthenticated(r);
    }

    @Test
    void a_human_admin_token_does_not_open_a_runner_path() throws Exception {
        String admin = "Bearer " + tokens.admin(members.defaultId());
        var r = mvc.post().uri(AGENTS).contentType("application/json").content(REGISTER_BODY)
                .header("Authorization", admin).exchange().getResponse();
        assertUnauthenticated(r);
    }

    @Test
    void the_default_bearer_of_the_test_client_alone_is_refused() throws Exception {
        var r = mvc.post().uri(AGENTS).contentType("application/json").content(REGISTER_BODY).exchange()
                .getResponse();
        assertUnauthenticated(r);
    }

    @Test
    void with_both_credentials_the_runner_token_decides_and_the_human_token_is_ignored() throws Exception {
        String user = "Bearer " + tokens.user(members.defaultId());
        var withToken = mvc.post().uri(AGENTS).contentType("application/json").content(REGISTER_BODY)
                .header("Authorization", user).header(HEADER, TOKEN).exchange().getResponse();
        assertThat(withToken.getStatus()).isEqualTo(201);
        var wrongToken = mvc.post().uri(AGENTS).contentType("application/json").content(REGISTER_BODY)
                .header("Authorization", user).header(HEADER, "wrong").exchange().getResponse();
        assertUnauthenticated(wrongToken);
    }

    @Test
    void a_broken_human_token_next_to_a_valid_runner_token_is_not_read() throws Exception {
        var r = mvc.post().uri(AGENTS).contentType("application/json").content(REGISTER_BODY)
                .header("Authorization", "Bearer not-a-token").header(HEADER, TOKEN).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
    }

    @Test
    void the_runner_token_opens_nothing_under_the_public_api() throws Exception {
        for (String uri : new String[] {"/api/v1/seasons", "/api/v1/seasons/1", "/api/v1/seasons/1/holdings"}) {
            var r = mvc.get().uri(uri).header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
            assertUnauthenticated(r);
        }
        var post = mvc.post().uri("/api/v1/seasons").contentType("application/json").content("{}")
                .header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange().getResponse();
        assertUnauthenticated(post);
    }

    @Test
    void the_runner_header_beside_a_human_token_changes_nothing_on_the_public_api() throws Exception {
        var r = mvc.get().uri("/api/v1/seasons").header(HEADER, "wrong").exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
    }

    @Test
    void an_unknown_runner_path_is_401_without_the_token_and_404_with_it() throws Exception {
        for (String uri : new String[] {BASE + "/nothing", "/internal/agent-runner/other", "/internal/agent-runner"}) {
            assertUnauthenticated(post(mvc, uri, null, "{}", true));
            var r = post(mvc, uri, TOKEN, "{}", true);
            assertThat(r.getStatus()).as(uri).isEqualTo(404);
            assertThat(json(r).get("code").asString()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void other_internal_paths_are_not_runner_paths() throws Exception {
        var r = mvc.get().uri("/internal/other").header(HEADER, TOKEN).requestAttr(ANONYMOUS, true).exchange()
                .getResponse();
        assertUnauthenticated(r);
    }

    @Test
    void the_token_never_reaches_the_log_or_a_problem_body(CapturedOutput output) throws Exception {
        for (String token : new String[] {null, "wrong", TOKEN.substring(1), TOKEN}) {
            MockHttpServletResponse r = post(mvc, AGENTS, token, "{\"bad\": ", true);
            assertThat(r.getContentAsString()).doesNotContain(TOKEN);
        }
        post(mvc, AGENTS, TOKEN, REGISTER_BODY, true);
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void a_problem_body_for_a_refused_token_is_the_common_shape() throws Exception {
        MockHttpServletResponse r = post(mvc, AGENTS, "wrong", REGISTER_BODY, true);
        JsonNode body = json(r);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
    }
}
