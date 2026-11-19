package antlab.agent;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.Member;
import antlab.member.MemberKind;
import antlab.member.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers an agent for the configured owner, idempotently on (owner, persona, route, model): a changed model is a
 * new agent. The owner row is locked first, which serializes two concurrent registrations of the same agent so the
 * second one finds the first one's row instead of failing on the unique key.
 */
@Service
public class AgentRegistrationService {

    private final MemberRepository members;
    private final AgentProfileRepository profiles;
    private final AgentRunnerProperties properties;

    public AgentRegistrationService(MemberRepository members, AgentProfileRepository profiles,
            AgentRunnerProperties properties) {
        this.members = members;
        this.profiles = profiles;
        this.properties = properties;
    }

    @Transactional
    public AgentRegistration register(RegisterAgentRequest request) {
        if (!properties.ownerConfigured()) {
            throw unauthenticated();
        }
        long ownerId = properties.ownerMemberId();
        Member owner = members.findByIdForUpdate(ownerId).orElseThrow(AgentRegistrationService::unauthenticated);
        if (owner.getKind() != MemberKind.HUMAN) {
            throw unauthenticated();
        }
        var existing = profiles.findByOwnerMemberIdAndPersonaKeyAndModelRouteAndModelId(ownerId,
                request.personaKey(), request.modelRoute(), request.modelId());
        if (existing.isPresent()) {
            AgentProfile profile = existing.get();
            Member agent = members.findById(profile.getMemberId()).orElseThrow();
            return new AgentRegistration(dto(agent, profile), false);
        }
        Member agent = members.save(Member.agent(request.displayName()));
        AgentProfile profile = profiles.save(new AgentProfile(agent.getId(), ownerId, request.personaKey(),
                request.modelRoute(), request.modelId()));
        return new AgentRegistration(dto(agent, profile), true);
    }

    private static AgentDto dto(Member agent, AgentProfile profile) {
        return new AgentDto(agent.getId(), agent.getName(), profile.getPersonaKey(), profile.getModelRoute(),
                profile.getModelId(), profile.getStatus(), profile.getOwnerMemberId());
    }

    private static DomainException unauthenticated() {
        return new DomainException(ErrorCode.UNAUTHENTICATED, "인증이 필요하다");
    }
}
