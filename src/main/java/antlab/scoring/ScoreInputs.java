package antlab.scoring;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the season score calculator reads, as plain values. Item inputs are null when they could not be measured;
 * the matching item is then null and drops out of the process mean. The two rank counts are null together when no
 * path distribution exists.
 *
 * @param returns            the team's monthly returns for turns 1..T (base = initial cash)
 * @param benchmarkReturns   the benchmark's monthly returns on the same base, same length as {@code returns}
 * @param reportTurns        T, the number of scored turns
 * @param riskBreachTurns    turns whose drawdown was below the declared limit
 * @param riskBreachDepth    deepest breach as a ratio (0 or negative), null if unknown
 * @param riskLimit          the declared drawdown limit (negative), null if unknown
 * @param maxSingleWeight    the largest single-asset weight over the season, 0..1
 * @param turnoverAnnual     annualised turnover as a ratio (1 = 100 %)
 * @param measuredFormationDeviation mean deviation from the target formation on season-measured positions, a fraction in 0..2
 * @param roleFit            share of exposure whose declared position matched the measured one, 0..1
 * @param nudgeShare         share of traded value that followed a platform nudge, 0..1, null with no trades
 * @param noiseShare         share of traded value that followed the noise, 0..1, null with no trades or when it could not be read
 * @param discipline         trade discipline facts, null when nothing measures them
 * @param sharpeDiffCounts   rank of the observed Sharpe difference among the path distribution, null if none
 * @param endMultipleCounts  rank of the held start team's end multiple among the path distribution, null if none
 */
public record ScoreInputs(
        List<BigDecimal> returns,
        List<BigDecimal> benchmarkReturns,
        int reportTurns,
        Integer riskBreachTurns,
        BigDecimal riskBreachDepth,
        BigDecimal riskLimit,
        BigDecimal maxSingleWeight,
        BigDecimal turnoverAnnual,
        BigDecimal measuredFormationDeviation,
        BigDecimal roleFit,
        BigDecimal nudgeShare,
        BigDecimal noiseShare,
        DisciplineRatios discipline,
        RankCounts sharpeDiffCounts,
        RankCounts endMultipleCounts) {
}
