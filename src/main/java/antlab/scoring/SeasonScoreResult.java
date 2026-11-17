package antlab.scoring;

import java.math.BigDecimal;

/**
 * The stored values of one season score, already rounded for their columns (scores 2 decimals, Sharpe values and
 * percentiles 8 decimals). Result score, score and both percentiles are null together when no distribution exists.
 */
public record SeasonScoreResult(
        String scoreVersion,
        BigDecimal score,
        BigDecimal resultScore,
        BigDecimal processScore,
        BigDecimal sharpe,
        BigDecimal benchSharpe,
        BigDecimal sharpeDiff,
        BigDecimal sharpeDiffPercentile,
        BigDecimal luckPercentile,
        BigDecimal riskScore,
        BigDecimal disciplineScore,
        BigDecimal diversificationScore,
        BigDecimal turnoverScore,
        BigDecimal consistencyScore,
        BigDecimal formationScore,
        BigDecimal roleScore,
        BigDecimal noiseScore,
        BigDecimal nudgeScore,
        int processItems) {
}
