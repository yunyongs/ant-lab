package antlab.api.security;

import antlab.agent.AgentActingResolver;
import antlab.auth.AgentActingAuthentication;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The only authentication of the runner chain. A request is accepted when the {@value #HEADER} header carries the
 * configured service token; the {@code Authorization} header is never read. Under {@code /agents/{agentId}} the agent
 * must also resolve for the configured owner, and the security context then holds an
 * {@link AgentActingAuthentication}, so the services run for that agent unchanged. The agent id is checked here and
 * nowhere else.
 *
 * <p>Created by {@link AgentRunnerSecurityConfig}, not as a bean, so the servlet container does not register it a second time.
 */
final class RunnerTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Antlab-Runner-Token";

    private static final Pattern AGENT_PATH = Pattern.compile("^/internal/agent-runner/v1/agents/([^/]+)(?:/.*)?$");

    private final RunnerTokenCheck tokens;
    private final AgentActingResolver agents;
    private final SecurityProblemHandler problems;
    private final HandlerExceptionResolver resolver;

    RunnerTokenFilter(RunnerTokenCheck tokens, AgentActingResolver agents, SecurityProblemHandler problems,
            HandlerExceptionResolver resolver) {
        this.tokens = tokens;
        this.agents = agents;
        this.problems = problems;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!tokens.matches(request.getHeader(HEADER))) {
            SecurityContextHolder.clearContext();
            problems.commence(request, response, new BadCredentialsException("runner token"));
            return;
        }
        Matcher agentPath = AGENT_PATH.matcher(pathWithinApplication(request));
        if (agentPath.matches()) {
            Optional<Long> agent = agents.resolve(agentPath.group(1));
            if (agent.isEmpty()) {
                SecurityContextHolder.clearContext();
                // Fail closed: if the resolver writes nothing, still answer 404 instead of an empty 200.
                if (resolver.resolveException(request, response, null,
                        new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다")) == null
                        && !response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                }
                return;
            }
            install(new AgentActingAuthentication(agent.get()));
        } else {
            install(new RunnerAuthentication());
        }
        chain.doFilter(request, response);
    }

    private static void install(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
    }
}
