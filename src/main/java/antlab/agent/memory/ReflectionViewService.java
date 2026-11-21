package antlab.agent.memory;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.scoring.SeasonReportDto;
import antlab.scoring.SeasonReportService;
import antlab.scoring.SeasonScore;
import antlab.scoring.SeasonScoreDto;
import antlab.scoring.SeasonScoreRepository;
import antlab.scoring.SeasonScoreService;
import antlab.season.Season;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Builds what an agent sees of a finished season before it writes its reflection. <b>No transaction here</b>: the score
 * service must run outside one (it calls the engine), so each step opens its own short read-only transaction. The gate
 * comes first and nothing of the season is read before it passes. The score is scored on demand; while it is not stored
 * yet the answer is the transient SCORE_PENDING.
 */
@Service
public class ReflectionViewService {

    private final CurrentMember currentMember;
    private final MemoryEligibility eligibility;
    private final MemoryReadService memories;
    private final SeasonScoreService scoring;
    private final SeasonScoreRepository scores;
    private final SeasonReportService reports;
    private final ReflectionTurnReader turns;

    public ReflectionViewService(CurrentMember currentMember, MemoryEligibility eligibility, MemoryReadService memories,
            SeasonScoreService scoring, SeasonScoreRepository scores, SeasonReportService reports,
            ReflectionTurnReader turns) {
        this.currentMember = currentMember;
        this.eligibility = eligibility;
        this.memories = memories;
        this.scoring = scoring;
        this.scores = scores;
        this.reports = reports;
        this.turns = turns;
    }

    public ReflectionViewDto get(long agentId, long seasonId) {
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
        }
        Season season = eligibility.requireReflectable(agentId, seasonId);
        scoring.ensureMember(agentId);
        SeasonScore score = scores.findBySeasonIdAndMemberId(seasonId, agentId)
                .orElseThrow(() -> new DomainException(ErrorCode.SCORE_PENDING, "점수를 아직 계산하지 못했다. 잠시 뒤 다시 시도한다"));
        SeasonReportDto report = reports.get(seasonId);
        Optional<AgentMemory> base = memories.latest(agentId);
        return new ReflectionViewDto(base.map(AgentMemory::getVersion).orElse(null),
                base.map(m -> memories.rules(m.getPlaybook())).orElse(List.of()), season.getPathLength(), report,
                SeasonScoreDto.of(score), turns.turns(seasonId, season.getPathLength()));
    }
}
