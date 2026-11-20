package antlab.agent;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the config of one agent season for the runner's mismatch check. */
@Service
public class AgentSeasonConfigQueryService {

    private final CurrentMember currentMember;
    private final SeasonAgentConfigRepository configs;

    public AgentSeasonConfigQueryService(CurrentMember currentMember, SeasonAgentConfigRepository configs) {
        this.currentMember = currentMember;
        this.configs = configs;
    }

    @Transactional(readOnly = true)
    public AgentSeasonConfigDto get(long agentId, long seasonId) {
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw notFound();
        }
        // One answer for a missing season, a human season and a season of another agent.
        SeasonAgentConfig config = configs.findById(seasonId).filter(c -> c.getMemberId() == agentId)
                .orElseThrow(AgentSeasonConfigQueryService::notFound);
        return AgentSeasonConfigDto.of(config);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
