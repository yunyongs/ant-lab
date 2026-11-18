package antlab.scoring;

import antlab.common.money.MoneyMath;
import antlab.engine.client.EnginePercentileRequest;
import antlab.engine.client.EnginePercentileRequest.Observed;
import antlab.engine.client.EnginePercentileRequest.Portfolio;
import antlab.engine.client.EngineProjectionRequest.Holding;
import antlab.projection.ProjectionInput;
import antlab.scoring.SeasonReportSources.TurnFact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure steps between the stored season facts and the score: the observed values the engine ranks, the engine request,
 * and the calculator input. Nothing here reads a database, a clock or the engine, so each step is tested by hand values.
 */
final class ScoreAssembler {

    /** What the season did, in the two numbers that are ranked, plus the raw Sharpe values kept for audits. */
    record Observation(List<BigDecimal> returns, List<BigDecimal> benchmarkReturns, BigDecimal sharpe, BigDecimal benchSharpe,
            BigDecimal sharpeDiff, BigDecimal staticEndMultiple) {
    }

    /** The wire holds six integer digits for the Sharpe difference; a larger value is capped (it ranks above every path). */
    private static final BigDecimal WIRE_SHARPE_CAP = new BigDecimal("999999.999999999999");

    private static final String BENCH_KR = ReportRules.BENCH_KR_MARKET + ":" + ReportRules.BENCH_KR_TICKER;
    private static final String BENCH_US = ReportRules.BENCH_US_MARKET + ":" + ReportRules.BENCH_US_TICKER;

    private ScoreAssembler() {
    }

    /**
     * Monthly team returns on the report's base (turn 0 = initial cash, so the team-building fees count), the benchmark
     * returns on the same base, both Sharpe values unrounded, and the start team's end multiple held to the last turn.
     */
    static Observation observe(ScoreBasis basis, List<BigDecimal> benchmarkReturns) {
        List<BigDecimal> returns = teamReturns(basis.sources());
        BigDecimal sharpe = Sharpe.exact(returns);
        BigDecimal benchSharpe = Sharpe.exact(benchmarkReturns);
        BigDecimal staticMultiple = MoneyMath.scoreDivide(basis.startEndValue(), basis.startTeam().totalValue());
        return new Observation(returns, benchmarkReturns, sharpe, benchSharpe, sharpe.subtract(benchSharpe), staticMultiple);
    }

    static List<BigDecimal> teamReturns(SeasonReportSources src) {
        List<BigDecimal> out = new ArrayList<>(src.turns().size());
        BigDecimal previous = src.season().initialCash();
        for (TurnFact turn : src.turns()) {
            out.add(MoneyMath.scoreDivide(turn.totalValue().subtract(previous), previous));
            previous = turn.totalValue();
        }
        return out;
    }

    /** The request for one season: the start team and the benchmark held still, the two observed values as decimal strings. */
    static EnginePercentileRequest request(ScoreBasis basis, Observation season, int pathCount) {
        ProjectionInput team = basis.startTeam();
        List<Holding> teamWeights = team.shares().stream().map(s -> new Holding(s.asset(), s.weight().toPlainString())).toList();
        List<Holding> benchmarkWeights = List.of(
                new Holding(BENCH_KR, ReportRules.BENCH_KR_WEIGHT.toPlainString()),
                new Holding(BENCH_US, ReportRules.BENCH_US_WEIGHT.toPlainString()));
        Observed observed = new Observed(MoneyMath.round12(season.staticEndMultiple()).toPlainString(),
                MoneyMath.round12(season.sharpeDiff().max(WIRE_SHARPE_CAP.negate()).min(WIRE_SHARPE_CAP)).toPlainString());
        return new EnginePercentileRequest(Long.toString(basis.seed()), basis.baseDate(), basis.pathLength(), pathCount,
                new Portfolio(team.cashShare().toPlainString(), teamWeights),
                new Portfolio(BigDecimal.ZERO.toPlainString(), benchmarkWeights), observed);
    }

    /** The calculator input; the two rank counts are both null when no path distribution exists. */
    static ScoreInputs inputs(SeasonReportDto report, Observation season, ScoreBasis basis, RankCounts sharpeDiffCounts,
            RankCounts endMultipleCounts) {
        return new ScoreInputs(season.returns(), season.benchmarkReturns(), report.reportTurns(), report.riskBreachTurns(),
                report.riskBreachDepth(), report.riskLimit(), report.maxSingleWeight(), report.turnoverAnnual(),
                report.formationDeviation(), report.roleFit(), report.nudgeShare(), basis.noiseShare(), basis.discipline(),
                sharpeDiffCounts, endMultipleCounts);
    }
}
