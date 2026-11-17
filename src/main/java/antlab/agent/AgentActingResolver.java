package antlab.agent;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the agent id of a runner path into the agent member the runner may act for: the id must name an agent profile
 * owned by the configured owner. A human member, another owner's agent and a missing id all give an empty result, so the
 * caller cannot tell them apart. A retired agent still resolves; the operations decide what a retired agent may do.
 */
@Service
public class AgentActingResolver {

    private final AgentProfileRepository profiles;
    private final AgentRunnerProperties properties;

    public AgentActingResolver(AgentProfileRepository profiles, AgentRunnerProperties properties) {
        this.profiles = profiles;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Optional<Long> resolve(String agentIdText) {
        if (!properties.ownerConfigured() || agentIdText == null || !agentIdText.matches("[1-9][0-9]{0,17}")) {
            return Optional.empty();
        }
        return profiles.findById(Long.parseLong(agentIdText))
                .filter(profile -> profile.getOwnerMemberId().equals(properties.ownerMemberId()))
                .map(AgentProfile::getMemberId);
    }
}
