package antlab.leaderboard;

import antlab.common.money.MoneyMath;
import antlab.engine.client.EngineManagerPartsRequest;
import antlab.engine.client.EngineManagerPartsRequest.Settings;
import antlab.engine.client.EngineManagerPartsRequest.StartFormation;
import antlab.engine.client.EngineManagerPartsRequest.Team;
import antlab.engine.client.EngineManagerPartsResult;
import antlab.scoring.RatingExclusion;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import java.math.BigDecimal;
import java.util.List;

/**
 * Pure steps from a season's stored facts to the engine request and to the stored row. Every number goes out as the plain
 * string of the stored or unrounded value: Java rounds nothing on the way to the engine (returns at the score working
 * scale, Sharpe and the risk item at their stored places) and stores the answer as sent.
 */
final class ManagerPartsRequestFactory {

    private ManagerPartsRequestFactory() {
    }

    /**
     * The exclusion a season gets without an engine call, or null when the engine must be asked: a human season that
     * did not start ({@code season_score.rating_exclusion}), and a path length the engine has no reference for.
     */
    static ManagerPartsExclusion knownExclusion(RatingExclusion scoreExclusion, int pathLength) {
        if (scoreExclusion == RatingExclusion.NOT_STARTED) {
            return ManagerPartsExclusion.NOT_STARTED;
        }
        if (!EngineManagerPartsRequest.PATH_LENGTHS.contains(pathLength)) {
            return ManagerPartsExclusion.NO_REFERENCE;
        }
        return null;
    }

    /** @throws IllegalArgumentException when a stored value does not fit the wire (a Java-side fault) */
    static EngineManagerPartsRequest request(ManagerPartsBasis b) {
        SeasonFacts s = b.settings();
        Settings settings = new Settings(s.pathLength(), s.initialCash().toPlainString(), s.feeRateKr().toPlainString(),
                s.feeRateUs().toPlainString(), s.fxSpreadRate().toPlainString(), s.riskLimit().toPlainString(),
                new StartFormation(s.startGoalkeeper(), s.startDefender(), s.startMidfielder(), s.startForward()),
                b.memberKind());
        ManagerPartsBasis.Score score = b.score();
        Team team = new Team(plain(b.totals()), plain(b.returns()), plain(b.benchmarkReturns()),
                score.sharpe().toPlainString(), score.benchSharpe().toPlainString(),
                score.riskScore() == null ? null : score.riskScore().toPlainString());
        return new EngineManagerPartsRequest(EngineManagerPartsRequest.REFERENCE_VERSION, b.baseDate(), b.months(), settings,
                team);
    }

    /** Final value / initial cash at 8 places (display only). */
    static BigDecimal finalMultiple(ManagerPartsBasis b) {
        if (b.totals().isEmpty()) {
            throw new IllegalStateException("manager_parts.no_turns");
        }
        return MoneyMath.ratio(b.totals().get(b.totals().size() - 1), b.settings().initialCash());
    }

    static SeasonManagerParts excluded(ManagerPartsBasis b, ManagerPartsExclusion exclusion) {
        return new SeasonManagerParts(b.seasonId(), b.memberId(), EngineManagerPartsRequest.REFERENCE_VERSION, exclusion, null,
                b.score().processScore(), b.score().scoreVersion(), finalMultiple(b));
    }

    static SeasonManagerParts ranked(ManagerPartsBasis b, EngineManagerPartsResult a) {
        var c = a.components();
        var p = a.percentiles();
        SeasonManagerParts.Ranked ranked = new SeasonManagerParts.Ranked(a.referenceKey(), a.referenceSize(), a.botVersion(),
                a.engineVersion(), dec(c.excessCagr()), dec(c.sharpeDiff()), dec(c.sortinoDiff()), dec(c.calmarDiff()),
                dec(c.mdd()), dec(c.benchMdd()), dec(p.excessCagr()), dec(p.sharpeDiff()), dec(p.sortinoDiff()),
                dec(p.calmarDiff()), p.riskItem() == null ? null : dec(p.riskItem()), dec(p.mdd()), dec(a.parts().ret()),
                dec(a.parts().riskAdjusted()), dec(a.parts().risk()));
        return new SeasonManagerParts(b.seasonId(), b.memberId(), a.referenceVersion(), null, ranked, b.score().processScore(),
                b.score().scoreVersion(), finalMultiple(b));
    }

    private static List<String> plain(List<BigDecimal> values) {
        return values.stream().map(BigDecimal::toPlainString).toList();
    }

    private static BigDecimal dec(String wire) {
        return new BigDecimal(wire);
    }
}
