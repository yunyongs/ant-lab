package antlab.api.security;

import antlab.auth.AgentActingAuthentication;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A verified runner that acts for no agent yet (registration). It identifies no member, so no service accepts it as one. */
final class RunnerAuthentication extends AbstractAuthenticationToken {

    RunnerAuthentication() {
        super(List.of(new SimpleGrantedAuthority(AgentActingAuthentication.RUNNER_AUTHORITY)));
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return "agent-runner";
    }
}
