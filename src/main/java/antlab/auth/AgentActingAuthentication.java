package antlab.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The agent a verified runner acts for. Only the runner filter creates it, after the service token matched and the
 * agent was resolved for the configured owner; {@link JwtCurrentMember} reads the agent id from it. It carries no
 * credential.
 */
public final class AgentActingAuthentication extends AbstractAuthenticationToken {

    public static final String RUNNER_AUTHORITY = "ROLE_AGENT_RUNNER";

    private final long agentMemberId;

    public AgentActingAuthentication(long agentMemberId) {
        super(List.of(new SimpleGrantedAuthority(RUNNER_AUTHORITY)));
        this.agentMemberId = agentMemberId;
        setAuthenticated(true);
    }

    public long agentMemberId() {
        return agentMemberId;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return agentMemberId;
    }
}
