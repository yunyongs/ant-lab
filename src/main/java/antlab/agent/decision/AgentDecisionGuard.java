package antlab.agent.decision;

import antlab.agent.AgentCallKind;
import antlab.agent.AgentCallRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only check in front of a turn advance of an agent season: the current turn must have a final decision. */
@Service
public class AgentDecisionGuard {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final AgentCallRepository calls;

    public AgentDecisionGuard(CurrentMember currentMember, SeasonRepository seasons, AgentCallRepository calls) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.calls = calls;
    }

    /**
     * Throws DECISION_MISSING when the season is in progress on {@code expectedTurnNo} and that turn has no final call.
     * A season that is not in progress, or a stale turn number, passes: the turn path answers those with its own codes.
     */
    @Transactional(readOnly = true)
    public void requireDecision(long agentId, long seasonId, int expectedTurnNo) {
        if (currentMember.existingId().filter(id -> id == agentId).isEmpty()) {
            throw notFound();
        }
        Season season = seasons.findByIdAndMemberId(seasonId, agentId).orElseThrow(AgentDecisionGuard::notFound);
        if (season.getStatus() == SeasonStatus.IN_PROGRESS && season.getCurrentTurnNo() == expectedTurnNo
                && calls.findFinalBySeasonIdAndTurnNo(seasonId, expectedTurnNo, AgentCallKind.TURN_DECISION).isEmpty()) {
            throw new DomainException(ErrorCode.DECISION_MISSING, "이 턴에는 아직 최종 결정이 없다");
        }
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
