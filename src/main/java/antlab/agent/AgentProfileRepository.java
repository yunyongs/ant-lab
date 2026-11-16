package antlab.agent;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** AgentProfile repository: insert, read, and the identity lookup the runner registration needs. No delete. */
public interface AgentProfileRepository extends Repository<AgentProfile, Long> {

    AgentProfile save(AgentProfile profile);

    Optional<AgentProfile> findById(Long memberId);

    Optional<AgentProfile> findByOwnerMemberIdAndPersonaKeyAndModelRouteAndModelId(Long ownerMemberId,
            String personaKey, ModelRoute modelRoute, String modelId);
}
