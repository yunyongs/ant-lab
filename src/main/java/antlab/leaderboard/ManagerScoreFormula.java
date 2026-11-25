package antlab.leaderboard;

import java.math.BigDecimal;

/** One {@code manager_score_formula} row: the version, its status and the four weights (2 places, summing to 1). */
public record ManagerScoreFormula(String version, ManagerScoreFormulaStatus status, BigDecimal wReturn,
        BigDecimal wRiskAdjusted, BigDecimal wRisk, BigDecimal wProcess) {
}
