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
        if (season.getStatus() == SeasonStatus.HALTED) {
            // Answered here, not passed on: a resume between this check and the turn path must not skip the decision check.
            throw new DomainException(ErrorCode.SEASON_HALTED, "시즌이 대사 불일치로 멈춰 있다");
        }
        if (season.getStatus() == SeasonStatus.PAUSED) {
            // A paused season can resume, so the turn path must not be left to answer it: a resume between this check and
            // the facade would skip the decision check.
            throw new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "일시 정지된 시즌이다");
        }
        if (season.getStatus() != SeasonStatus.IN_PROGRESS) {
            // ABANDONING, FINISHED and VOID never return to IN_PROGRESS, so the turn path may answer SEASON_NOT_ACTIVE.
            return;
        }
        // A number ahead of the turn read here must not pass: another advance could commit before the facade locks the
        // season, and the facade would then advance a turn that has no decision.
        if (expectedTurnNo > season.getCurrentTurnNo()) {
            throw new DomainException(ErrorCode.TURN_MISMATCH, "시즌의 현재 턴과 전환이 본 턴이 다르다");
        }
        // Final rows are never deleted, so every turn below the current one has one; only the current turn can lack it.
        if (calls.findFinalBySeasonIdAndTurnNo(seasonId, expectedTurnNo, AgentCallKind.TURN_DECISION).isEmpty()) {
            throw new DomainException(ErrorCode.DECISION_MISSING, "이 턴에는 아직 최종 결정이 없다");
        }
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없다");
    }
}
