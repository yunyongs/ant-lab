package antlab.scoring;

import antlab.common.money.MoneyMath;
import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.projection.PricedHolding;
import antlab.projection.ProjectionBasis;
import antlab.projection.ProjectionInput;
import antlab.projection.ProjectionInputQuery;
import antlab.season.AbandonRules;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads everything one season score needs in a single read-only {@code REPEATABLE_READ} snapshot (the report sources, the
 * discipline ratios, the start team and its prices at the last turn must come from one commit). It holds no lock, writes
 * nothing and ends before the engine is called, so no connection is held while the engine works.
 */
@Component
public class ScoreInputReader {

    private final SeasonRepository seasons;
    private final MemberRepository members;
    private final SeasonReportService reports;
    private final DisciplineSource discipline;
    private final ProjectionInputQuery projectionInputs;
    private final ScoreInputQuery prices;

    public ScoreInputReader(SeasonRepository seasons, MemberRepository members, SeasonReportService reports,
            DisciplineSource discipline, ProjectionInputQuery projectionInputs, ScoreInputQuery prices) {
        this.seasons = seasons;
        this.members = members;
        this.reports = reports;
        this.discipline = discipline;
        this.projectionInputs = projectionInputs;
        this.prices = prices;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScoreBasis read(long seasonId) {
        Season season = seasons.findById(seasonId).orElseThrow(() -> new IllegalStateException("score.season_missing"));
        if (season.getStatus() != SeasonStatus.FINISHED) {
            throw new IllegalStateException("score.season_not_finished");
        }
        Member member = members.findById(season.getMemberId()).orElseThrow(() -> new IllegalStateException("score.member_missing"));
        SeasonReportSources sources = reports.sources(season);
        DisciplineRatios ratios = discipline.of(seasonId).orElse(null);

        ProjectionBasis start = projectionInputs.start(seasonId);
        ProjectionInput startTeam = ProjectionInput.of(start.cash(), start.holdings());
        BigDecimal startEndValue = valueAtLastTurn(seasonId, season.getCurrentTurnNo(), start);
        boolean notStarted = AbandonRules.notStarted(member.getKind(), season.getAbandonedTurnNo());
        return new ScoreBasis(seasonId, season.getMemberId(), season.getSeed(), season.getBaseDate(), season.getPathLength(),
                notStarted, sources, ratios, startTeam, startEndValue);
    }

    /** Cash of the start team plus its start quantities at the last turn's stored prices, in won (no rounding). */
    private BigDecimal valueAtLastTurn(long seasonId, int lastTurn, ProjectionBasis start) {
        List<PricedHolding> held = start.holdings().stream().filter(h -> h.quantity() > 0).toList();
        Map<String, BigDecimal> last = prices.pricesAt(seasonId, lastTurn, held.stream().map(PricedHolding::assetKey).toList());
        List<BigDecimal> valuations = held.stream().map(h -> {
            BigDecimal price = last.get(h.assetKey());
            if (price == null) {
                throw new IllegalStateException("score.missing_price");
            }
            return MoneyMath.valuation(price, h.quantity());
        }).toList();
        return MoneyMath.totalValue(start.cash(), valuations);
    }
}
