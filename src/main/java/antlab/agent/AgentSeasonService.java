package antlab.agent;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.SeasonDto;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStartService;
import antlab.season.SeasonStatus;
import java.util.List;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts a season as an agent: the human start path runs unchanged for the acting agent member, and the season
 * config row is written in the same transaction, so a rejected config leaves no season behind.
 */
@Service
public class AgentSeasonService {

    private static final Duration WEEK = Duration.ofDays(7);

    private final CurrentMember currentMember;
    private final AgentProfileRepository profiles;
    private final SeasonAgentConfigRepository configs;
    private final SeasonStartService starts;
    private final SeasonRepository seasons;
    private final AgentRunnerProperties properties;
    private final Clock clock;

    public AgentSeasonService(CurrentMember currentMember, AgentProfileRepository profiles,
            SeasonAgentConfigRepository configs, SeasonStartService starts, SeasonRepository seasons,
            AgentRunnerProperties properties, Clock clock) {
        this.currentMember = currentMember;
        this.profiles = profiles;
        this.configs = configs;
        this.starts = starts;
        this.seasons = seasons;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public SeasonDto start(long agentId, StartAgentSeasonRequest request) {
        // Locks the agent's member row: two starts of one agent cannot both pass the limit checks.
        if (currentMember.ensureId() != agentId) {
            throw notFound();
        }
        AgentProfile profile = profiles.findById(agentId).orElseThrow(AgentSeasonService::notFound);
        if (profile.getStatus() == AgentStatus.RETIRED) {
            throw new DomainException(ErrorCode.AGENT_RETIRED, "은퇴한 에이전트는 시즌을 시작할 수 없다");
        }
        // An agent is its (persona, model) pair: a season on another model would score on this agent's row.
        if (!profile.getModelId().equals(request.config().modelRequested())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT,
                    "시즌 설정의 모델이 에이전트에 등록된 모델과 다르다");
        }
        // A pause must not be a way to re-roll: while a season of this agent is paused or being abandoned it must first be
        // resumed, or abandoned to its end so that it is scored. An overdue pause is not converted here.
        if (seasons.countByMemberIdAndStatusIn(agentId, List.of(SeasonStatus.PAUSED, SeasonStatus.ABANDONING)) > 0) {
            throw new DomainException(ErrorCode.SEASON_LIMIT,
                    "일시 정지 중이거나 포기 중인 시즌이 있는 에이전트는 새 시즌을 시작할 수 없다 — 재개하거나 끝까지 포기한다");
        }
        if (seasons.countByMemberIdAndCreatedAtGreaterThanEqual(agentId, clock.instant().minus(WEEK))
                >= properties.maxSeasonsPerWeek()) {
            throw new DomainException(ErrorCode.SEASON_LIMIT, "에이전트가 한 주에 시작할 수 있는 시즌 수를 넘었다");
        }
        SeasonDto season = starts.start(request.season());
        AgentSeasonConfigRequest c = request.config();
        configs.save(new SeasonAgentConfig(season.id(), agentId, profile.getPersonaKey(), c.personaVersion(),
                c.personaSha256(), profile.getModelRoute(), c.modelRequested(), c.temperature(), c.modelSeed(),
                c.maxTokens(), c.turnTimeoutMs(), c.promptTemplateVersion(), c.runnerVersion(), c.memoryVersion(),
                c.budgetCalls(), c.budgetUsd()));
        return season;
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
