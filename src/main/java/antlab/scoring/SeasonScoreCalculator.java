package antlab.scoring;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Season score, formula {@code s63-v3}: score = 0.5 x result + 0.5 x process, a pure function of {@link ScoreInputs}.
 * Result = 100 x the mid-rank percentile of the Sharpe difference among the engine paths. Process = the equal-weight
 * mean of the item scores that have a value. Every part is kept unrounded at working scale; each stored value is
 * rounded HALF_UP once, from the unrounded parts. The thresholds are builder values (validated later), kept here.
 */
public final class SeasonScoreCalculator {

    public static final String VERSION = "s63-v3";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SHARPE_CAP = new BigDecimal("999999.99999999");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal DIVERSIFICATION_FREE = new BigDecimal("0.10");
    private static final BigDecimal DIVERSIFICATION_SPAN = new BigDecimal("0.40");
    private static final BigDecimal TURNOVER_ZERO_AT = BigDecimal.valueOf(2);
    private static final BigDecimal FORMATION_ZERO_AT = new BigDecimal("0.50");
    private static final BigDecimal FORMATION_MAX = BigDecimal.valueOf(2);

    private SeasonScoreCalculator() {
    }

    public static SeasonScoreResult calculate(ScoreInputs in) {
        validate(in);

        BigDecimal sharpe = Sharpe.exact(in.returns());
        BigDecimal benchSharpe = Sharpe.exact(in.benchmarkReturns());
        BigDecimal sharpeDiff = sharpe.subtract(benchSharpe);

        List<BigDecimal> items = new ArrayList<>();
        BigDecimal risk = collect(items, risk(in));
        BigDecimal discipline = collect(items, discipline(in.discipline()));
        BigDecimal diversification = collect(items, diversification(in.maxSingleWeight()));
        BigDecimal turnover = collect(items, turnover(in.turnoverAnnual()));
        BigDecimal formation = collect(items, formation(in.measuredFormationDeviation()));
        BigDecimal role = collect(items, role(in.roleFit()));
        BigDecimal noise = collect(items, noise(in.noiseShare()));
        BigDecimal nudge = collect(items, nudge(in.nudgeShare()));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("at least one process item is needed");
        }
        BigDecimal process = mean(items);

        BigDecimal resultExact = null;
        BigDecimal score = null;
        BigDecimal diffPercentile = null;
        BigDecimal luckPercentile = null;
        if (in.sharpeDiffCounts() != null) {
            resultExact = HUNDRED.multiply(Percentile.exact(in.sharpeDiffCounts()));
            score = round2(HALF.multiply(resultExact).add(HALF.multiply(process)));
            diffPercentile = Percentile.stored(in.sharpeDiffCounts());
            luckPercentile = Percentile.stored(in.endMultipleCounts());
        }
        return new SeasonScoreResult(VERSION, score, resultExact == null ? null : round2(resultExact), round2(process),
                capSharpe(sharpe), capSharpe(benchSharpe), capSharpe(sharpeDiff), diffPercentile, luckPercentile,
                round2(risk), round2(discipline), round2(diversification), round2(turnover),
                null, round2(formation), round2(role), round2(noise), round2(nudge), items.size());
    }

    /** Why the season stays out of the rating, if it does; not-started wins over no-score. */
    public static RatingExclusion exclusion(boolean notStarted, SeasonScoreResult result) {
        if (notStarted) {
            return RatingExclusion.NOT_STARTED;
        }
        return result.score() == null ? RatingExclusion.NO_SCORE : null;
    }

    // ------------------------------------------------------------------ items (unrounded, null when unmeasured)

    /** 100 x (1 - 0.5 x breach turns / report turns - 0.5 x clamp(|depth| / |limit|)). */
    private static BigDecimal risk(ScoreInputs in) {
        if (in.riskBreachTurns() == null || in.riskBreachDepth() == null || in.riskLimit() == null) {
            return null;
        }
        BigDecimal turnShare = quotient(BigDecimal.valueOf(in.riskBreachTurns()), BigDecimal.valueOf(in.reportTurns()));
        BigDecimal depthShare = clamp01(quotient(in.riskBreachDepth().abs(), in.riskLimit().abs()));
        return HUNDRED.multiply(BigDecimal.ONE.subtract(HALF.multiply(turnShare)).subtract(HALF.multiply(depthShare)));
    }

    /** 100 x (1 - mean of the measured panic-sell and chase-buy ratios). */
    private static BigDecimal discipline(DisciplineRatios facts) {
        if (facts == null) {
            return null;
        }
        List<BigDecimal> ratios = new ArrayList<>();
        if (facts.panicSellRatio() != null) {
            ratios.add(facts.panicSellRatio());
        }
        if (facts.chaseBuyRatio() != null) {
            ratios.add(facts.chaseBuyRatio());
        }
        return ratios.isEmpty() ? null : HUNDRED.multiply(BigDecimal.ONE.subtract(mean(ratios)));
    }

    /** Full marks up to a 10 % single weight, zero from 50 %. */
    private static BigDecimal diversification(BigDecimal maxSingleWeight) {
        if (maxSingleWeight == null) {
            return null;
        }
        requireRange(maxSingleWeight, BigDecimal.ONE);
        BigDecimal excess = quotient(maxSingleWeight.subtract(DIVERSIFICATION_FREE), DIVERSIFICATION_SPAN);
        return HUNDRED.multiply(BigDecimal.ONE.subtract(clamp01(excess)));
    }

    /** Zero at 200 % annual turnover. */
    private static BigDecimal turnover(BigDecimal turnoverAnnual) {
        if (turnoverAnnual == null) {
            return null;
        }
        requireNonNegative(turnoverAnnual);
        return HUNDRED.multiply(BigDecimal.ONE.subtract(clamp01(quotient(turnoverAnnual, TURNOVER_ZERO_AT))));
    }

    /** Zero at a mean formation deviation of 0.50. */
    private static BigDecimal formation(BigDecimal deviation) {
        if (deviation == null) {
            return null;
        }
        requireRange(deviation, FORMATION_MAX);
        return HUNDRED.multiply(BigDecimal.ONE.subtract(clamp01(quotient(deviation, FORMATION_ZERO_AT))));
    }

    private static BigDecimal role(BigDecimal roleFit) {
        if (roleFit == null) {
            return null;
        }
        requireRange(roleFit, BigDecimal.ONE);
        return HUNDRED.multiply(roleFit);
    }

    /** 100 x (1 - noise share): zero when every trade followed the noise. */
    private static BigDecimal noise(BigDecimal noiseShare) {
        if (noiseShare == null) {
            return null;
        }
        requireRange(noiseShare, BigDecimal.ONE);
        return HUNDRED.multiply(BigDecimal.ONE.subtract(noiseShare));
    }

    private static BigDecimal nudge(BigDecimal nudgeShare) {
        if (nudgeShare == null) {
            return null;
        }
        requireRange(nudgeShare, BigDecimal.ONE);
        return HUNDRED.multiply(BigDecimal.ONE.subtract(nudgeShare));
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal collect(List<BigDecimal> items, BigDecimal item) {
        if (item != null) {
            items.add(item);
        }
        return item;
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        return quotient(sum, BigDecimal.valueOf(values.size()));
    }

    private static BigDecimal quotient(BigDecimal numerator, BigDecimal denominator) {
        return MoneyMath.scoreDivide(numerator, denominator);
    }

    private static BigDecimal clamp01(BigDecimal x) {
        return x.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static BigDecimal round2(BigDecimal value) {
        return value == null ? null : MoneyMath.round2(value);
    }

    /**
     * Rounds a Sharpe value to its column scale and caps it to the column range (NUMERIC(14,8) holds under 10^6), so a
     * tiny but non-zero deviation cannot make the INSERT fail for ever. The raw value belongs in the inputs record.
     */
    private static BigDecimal capSharpe(BigDecimal value) {
        return round8(value).max(SHARPE_CAP.negate()).min(SHARPE_CAP);
    }

    private static BigDecimal round8(BigDecimal value) {
        return MoneyMath.round8(value);
    }

    private static void requireRange(BigDecimal value, BigDecimal max) {
        if (value.signum() < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("item input out of range");
        }
    }

    private static void requireNonNegative(BigDecimal value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("item input must not be negative");
        }
    }

    private static void validate(ScoreInputs in) {
        if (in == null || in.returns() == null || in.benchmarkReturns() == null) {
            throw new IllegalArgumentException("returns are required");
        }
        if (in.reportTurns() < 1) {
            throw new IllegalArgumentException("report turns must be at least 1");
        }
        if (in.returns().size() != in.reportTurns() || in.benchmarkReturns().size() != in.reportTurns()) {
            throw new IllegalArgumentException("return series must have one value per report turn");
        }
        if ((in.sharpeDiffCounts() == null) != (in.endMultipleCounts() == null)) {
            throw new IllegalArgumentException("rank counts must be given together or not at all");
        }
        if (in.sharpeDiffCounts() != null && in.sharpeDiffCounts().n() != in.endMultipleCounts().n()) {
            throw new IllegalArgumentException("both rank counts must come from the same number of paths");
        }
        if (in.riskBreachTurns() != null && (in.riskBreachTurns() < 0 || in.riskBreachTurns() > in.reportTurns())) {
            throw new IllegalArgumentException("risk breach turns out of range");
        }
        if (in.riskBreachDepth() != null && in.riskBreachDepth().signum() > 0) {
            throw new IllegalArgumentException("risk breach depth must not be positive");
        }
        if (in.riskLimit() != null && in.riskLimit().signum() == 0) {
            throw new IllegalArgumentException("risk limit must not be zero");
        }
    }
}
