package antlab.scoring;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.Turn;
import antlab.season.TurnRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turn contribution lookup — read-only, no locks. Every source of a played turn k (fills of orders before k, turn
 * prices 0..k, turn rows k − 1 and k, the lineup spans open at k) is fixed once the season has reached k, so the
 * default isolation is enough. Another member's season answers 404 as if it did not exist, the same as the turn
 * lookup. Statements: current member 1 + season 1 + turn rows 1 + holdings 1 + trading cost 1 + benchmark prices 1 = 6,
 * independent of the number of holdings.
 */
@Service
public class TurnContributionService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final TurnRepository turns;
    private final TurnContributionQuery query;
    private final SeasonReportQuery reportQuery;

    public TurnContributionService(CurrentMember currentMember, SeasonRepository seasons, TurnRepository turns,
            TurnContributionQuery query, SeasonReportQuery reportQuery) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.turns = turns;
        this.query = query;
        this.reportQuery = reportQuery;
    }

    /** A turn above the current one is 409; a number below 1 is refused with 400 before the call. */
    @Transactional(readOnly = true)
    public TurnContributionDto get(Long seasonId, int turnNo) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        if (turnNo > season.getCurrentTurnNo()) {
            throw new DomainException(ErrorCode.TURN_NOT_REACHED, "아직 진행하지 않은 턴이다");
        }
        long id = season.getId();
        List<Turn> rows = turns.findBySeasonIdAndTurnNoBetween(id, turnNo - 1, turnNo);
        TurnContributionCalculator.Values current = rows.stream().filter(t -> t.getTurnNo() == turnNo).findFirst()
                .map(TurnContributionService::values)
                .orElseThrow(() -> new IllegalStateException("턴 스냅샷이 없다"));
        TurnContributionCalculator.Values previous = turnNo == 1 ? null
                : rows.stream().filter(t -> t.getTurnNo() == turnNo - 1).findFirst()
                        .map(TurnContributionService::values)
                        .orElseThrow(() -> new IllegalStateException("직전 턴의 스냅샷이 없다"));
        TurnContributionCalculator.SeasonTerms terms = new TurnContributionCalculator.SeasonTerms(id,
                season.getInitialCash(), season.getFeeRateKr(), season.getFeeRateUs(), season.getFxSpreadRate());
        return TurnContributionCalculator.calculate(terms, turnNo, previous, current, query.lines(id, turnNo),
                query.tradingCost(id, turnNo), reportQuery.benchmarkPrices(id, turnNo));
    }

    private static TurnContributionCalculator.Values values(Turn turn) {
        return new TurnContributionCalculator.Values(turn.getTotalValue(), turn.getHoldingsValue(),
                turn.getGoalkeeperValue(), turn.getDefenderValue(), turn.getMidfielderValue(), turn.getForwardValue());
    }
}
