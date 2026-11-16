package antlab.api.agentrunner;

import static antlab.api.agentrunner.AgentRunnerSupport.AGENTS;
import static antlab.api.agentrunner.AgentRunnerSupport.REGISTER_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.START_BODY;
import static antlab.api.agentrunner.AgentRunnerSupport.TOKEN;
import static antlab.api.agentrunner.AgentRunnerSupport.json;
import static antlab.api.agentrunner.AgentRunnerSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Fail closed: while the token or the owner is not usable, every runner request answers 401, whatever it sends. */
abstract class UnusableRunnerConfigTests {

    @Autowired
    MockMvcTester mvc;

    private void assertUnauthenticated(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(json(r).get("code").asString()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void even_the_test_token_is_refused() throws Exception {
        assertUnauthenticated(post(mvc, AGENTS, TOKEN, REGISTER_BODY, true));
        assertUnauthenticated(post(mvc, AGENTS + "/1/seasons", TOKEN, START_BODY, true));
    }

    @Test
    void an_empty_or_missing_header_is_refused() throws Exception {
        assertUnauthenticated(post(mvc, AGENTS, "", REGISTER_BODY, true));
        assertUnauthenticated(post(mvc, AGENTS, null, REGISTER_BODY, true));
        assertUnauthenticated(post(mvc, AGENTS, " ", REGISTER_BODY, true));
    }
}
