package antlab.agent;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in member's agents with the season counts PLAN §6.9 assigns to AG-04. Four grouped queries whatever the
 * number of agents. An overdue pause is derived with the injected clock and counted as an unfinished abandon; nothing is
 * written.
 */
@Service
public class AgentListQuery {

    private final CurrentMember currentMember;
    private final AgentProfileRepository profiles;
    private final SeasonRepository seasons;
    private final AgentCallRepository calls;
    private final PersonaVersionRepository personaVersions;
    private final Clock clock;

    public AgentListQuery(CurrentMember currentMember, AgentProfileRepository profiles, SeasonRepository seasons,
            AgentCallRepository calls, PersonaVersionRepository personaVersions, Clock clock) {
        this.currentMember = currentMember;
        this.profiles = profiles;
        this.seasons = seasons;
        this.calls = calls;
        this.personaVersions = personaVersions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AgentListDto list() {
        long me = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다"));
        List<AgentListRow> agents = profiles.findRowsByOwner(me);
        if (agents.isEmpty()) {
            return new AgentListDto(List.of());
        }
        List<Long> ids = agents.stream().map(AgentListRow::agentId).toList();
        Instant cutoff = clock.instant().minus(Season.PAUSE_LIMIT);
        Map<Long, AgentSeasonCountRow> counts = seasons.findAgentSeasonCounts(ids, cutoff).stream()
                .collect(Collectors.toMap(AgentSeasonCountRow::agentId, Function.identity()));
        Map<Long, Long> failed = calls.findFailedTurnCounts(ids).stream()
                .collect(Collectors.toMap(AgentFailedTurnRow::agentId, AgentFailedTurnRow::failedTurns));
        List<String> keys = agents.stream().map(AgentListRow::personaKey).distinct().toList();
        Map<String, Integer> latest = personaVersions.findLatestVersions(keys).stream()
                .collect(Collectors.toMap(AgentLatestPersonaRow::personaKey, AgentLatestPersonaRow::version));
        return new AgentListDto(agents.stream().map(a -> {
            AgentSeasonCountRow c = counts.get(a.agentId());
            return new AgentSummaryDto(a.agentId(), a.displayName(), a.personaKey(), a.modelRoute(), a.modelId(),
                    a.status(), latest.get(a.personaKey()), n(c == null ? 0 : c.started()),
                    n(c == null ? 0 : c.counted()), n(c == null ? 0 : c.voided()),
                    n(c == null ? 0 : c.unfinishedAbandon()), n(c == null ? 0 : c.inProgress()),
                    n(c == null ? 0 : c.paused()), n(c == null ? 0 : c.halted()), n(failed.getOrDefault(a.agentId(), 0L)));
        }).toList());
    }

    private static int n(long value) {
        return Math.toIntExact(value);
    }
}
