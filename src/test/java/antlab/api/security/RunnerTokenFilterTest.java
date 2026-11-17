package antlab.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import antlab.agent.AgentActingResolver;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** The filter alone, without Spring: what it answers when the MVC resolver writes nothing. */
class RunnerTokenFilterTest {

    private static final String PATH = "/internal/agent-runner/v1/agents/7/seasons";

    private final RunnerTokenCheck tokens = mock(RunnerTokenCheck.class);
    private final AgentActingResolver agents = mock(AgentActingResolver.class);
    private final SecurityProblemHandler problems = mock(SecurityProblemHandler.class);
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final RunnerTokenFilter filter = new RunnerTokenFilter(tokens, agents, problems, resolver);

    @Test
    void an_unresolved_agent_is_404_even_when_the_resolver_writes_nothing() throws Exception {
        when(tokens.matches(any())).thenReturn(true);
        when(agents.resolve("7")).thenReturn(Optional.empty());
        when(resolver.resolveException(any(), any(), any(), any())).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(any(), any());
    }
}
