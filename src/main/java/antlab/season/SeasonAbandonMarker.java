package antlab.season;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.member.MemberKind;
import antlab.member.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions of the abandon request: the irreversible mark, and a plain re-read. They live in their own
 * bean so that the loop in {@link SeasonAbandonService}, which has no transaction, reaches them through the proxy.
 */
@Service
public class SeasonAbandonMarker {

    /** What the abandon request needs to know about the season; {@code markedNow} is true only for the marking request. */
    public record State(SeasonStatus status, int currentTurnNo, int pathLength, Integer abandonedTurnNo,
            MemberKind memberKind, boolean markedNow) {

        boolean notStarted() {
            return AbandonRules.notStarted(memberKind, abandonedTurnNo);
        }
    }

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final MemberRepository members;

    public SeasonAbandonMarker(CurrentMember currentMember, SeasonRepository seasons, MemberRepository members) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.members = members;
    }

    /**
     * Marks an in-progress season as abandoned at its current turn under the same season lock as orders. Anything
     * already abandoned is left as it is (a repeat); a halted season is refused and nothing is marked.
     */
    @Transactional
    public State mark(long seasonId, int expectedTurnNo) {
        long memberId = currentMember.existingId().orElseThrow(SeasonAbandonMarker::notFound);
        Season season = seasons.findByIdAndMemberIdForUpdate(seasonId, memberId).orElseThrow(SeasonAbandonMarker::notFound);
        boolean markedNow = false;
        switch (season.getStatus()) {
            case HALTED -> throw new DomainException(ErrorCode.SEASON_HALTED,
                    "대사가 거듭 맞지 않아 시즌을 멈췄다 — 관리자 확인이 필요하다");
            case FINISHED -> {
                if (season.getAbandonedTurnNo() == null) {
                    throw new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "이미 끝난 시즌이다");
                }
            }
            case ABANDONING -> {
                // A repeat: the mark exists, so the turn the member saw no longer matters.
            }
            case IN_PROGRESS -> {
                if (season.getCurrentTurnNo() != expectedTurnNo) {
                    throw new DomainException(ErrorCode.TURN_MISMATCH, "시즌의 현재 턴과 포기가 본 턴이 다르다");
                }
                season.abandon();
                markedNow = true;
            }
        }
        return state(season, memberId, markedNow);
    }

    /** Plain re-read between auto turns; the member kind is already known from the mark, so it is not read again. */
    @Transactional(readOnly = true)
    public State read(long seasonId, MemberKind memberKind) {
        long memberId = currentMember.existingId().orElseThrow(SeasonAbandonMarker::notFound);
        Season season = seasons.findByIdAndMemberId(seasonId, memberId).orElseThrow(SeasonAbandonMarker::notFound);
        return state(season, memberKind, false);
    }

    private State state(Season season, long memberId, boolean markedNow) {
        MemberKind kind = members.existsByIdAndKind(memberId, MemberKind.AGENT) ? MemberKind.AGENT : MemberKind.HUMAN;
        return state(season, kind, markedNow);
    }

    private static State state(Season season, MemberKind kind, boolean markedNow) {
        return new State(season.getStatus(), season.getCurrentTurnNo(), season.getPathLength(),
                season.getAbandonedTurnNo(), kind, markedNow);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다");
    }
}
