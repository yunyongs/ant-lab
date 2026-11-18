package antlab.ranking;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ranking board lookup: read-only, no locks, nothing stored. The board is recomputed from the facts of the asked turn on every call, so
 * the same turn always answers the same. Another member's season answers 404 like a missing one; the season status does not matter.
 * Statements: member 1, season 1, then the four of {@link RankingInputReader}, whatever the size of the pool.
 */
@Service
public class RankingQueryService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final RankingInputReader reader;

    public RankingQueryService(CurrentMember currentMember, SeasonRepository seasons, RankingInputReader reader) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.reader = reader;
    }

    /** A turn above the current one is 409; a number below 1 is refused with 400 before this is called. */
    @Transactional(readOnly = true)
    public RankingBoardDto get(Long seasonId, int turnNo) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        if (turnNo > season.getCurrentTurnNo()) {
            throw new DomainException(ErrorCode.TURN_NOT_REACHED, "아직 진행하지 않은 턴이다");
        }
        return RankingBoardDto.from(RankingBoardCalculator.board(reader.read(season.getId(), turnNo)));
    }
}
