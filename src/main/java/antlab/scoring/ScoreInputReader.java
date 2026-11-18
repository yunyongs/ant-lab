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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads everything one season score needs in a single read-only {@code REPEATABLE_READ} snapshot (the report sources, the
 * discipline ratios, the noise share, the start team and its prices at the last turn must come from one commit; the noise
 * share costs four statements per judged turn). It holds no lock, writes
 * nothing and ends before the engine is called, so no connection is held while the engine works.
 */
@Component
public class ScoreInputReader {

    private static final Logger LOG = LoggerFactory.getLogger(ScoreInputReader.class);

    private final SeasonRepository seasons;
    private final MemberRepository members;
    private final SeasonReportService reports;
    private final DisciplineSource discipline;
    private final NoiseReactionSource noise;
    private final ProjectionInputQuery projectionInputs;
    private final ScoreInputQuery prices;

    public ScoreInputReader(SeasonRepository seasons, MemberRepository members, SeasonReportService reports,
            DisciplineSource discipline, NoiseReactionSource noise, ProjectionInputQuery projectionInputs, ScoreInputQuery prices) {
        this.seasons = seasons;
        this.members = members;
        this.reports = reports;
        this.discipline = discipline;
        this.noise = noise;
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
        BigDecimal noiseShare = noiseShare(seasonId);

        ProjectionBasis start = projectionInputs.start(seasonId);
        ProjectionInput startTeam = ProjectionInput.of(start.cash(), start.holdings());
        BigDecimal startEndValue = valueAtLastTurn(seasonId, season.getCurrentTurnNo(), start);
        boolean notStarted = AbandonRules.notStarted(member.getKind(), season.getAbandonedTurnNo());
        return new ScoreBasis(seasonId, season.getMemberId(), season.getSeed(), season.getBaseDate(), season.getPathLength(),
                notStarted, sources, ratios, noiseShare, startTeam, startEndValue);
    }

    /**
     * The noise item is one of nine; a season whose rows contradict each other must not keep the other eight from being
     * scored for ever, so the failure is logged with the season id and the item stays empty (a never-traded season stores
     * the same null). Only {@link IllegalStateException}, the contradiction signal, is caught: a transient database failure
     * must fail the call so that nothing is stored and a later request scores the season. The source is plain JDBC and opens
     * no transaction, so catching here cannot mark this snapshot rollback-only.
     */
    private BigDecimal noiseShare(long seasonId) {
        try {
            return noise.noiseShare(seasonId).orElse(null);
        } catch (IllegalStateException inconsistent) {
            LOG.error("score.noise_unreadable seasonId={}", seasonId, inconsistent);
            return null;
        }
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
