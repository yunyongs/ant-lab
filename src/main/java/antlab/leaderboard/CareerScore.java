package antlab.leaderboard;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The season score S and the career score L (AG-06, PLAN §6.9). S is the weighted sum of a season's four parts, exact.
 * L is taken over the newest m ≤ 10 counted seasons: the mean after dropping k = floor(m / 10) lowest and k highest S,
 * minus half the sample standard deviation (n − 1) of all m, with the variance in the scatter form
 * {@code (m·Σx² − (Σx)²) / (m·(m − 1))}; divisions and the root at 60 significant digits, L rounded to 2 places once.
 * The same rule produces {@code engine/tests/fixtures/career_l_vectors.json}.
 */
public final class CareerScore {

    /** The most seasons L looks at. */
    public static final int NEWEST = 10;

    private static final BigDecimal HALF = new BigDecimal("0.5");

    /**
     * @param trimEachSide k, the number of lowest and of highest S left out of the mean
     * @param l {@code lExact} rounded HALF_UP to 2 places
     */
    public record Result(int m, int trimEachSide, BigDecimal trimmedMean, BigDecimal sampleSd, BigDecimal lExact,
            BigDecimal l) {
    }

    private CareerScore() {
    }

    /** S = Σ weight × part, unrounded. */
    public static BigDecimal seasonScore(ManagerScoreFormula f, BigDecimal partReturn, BigDecimal partRiskAdjusted,
            BigDecimal partRisk, BigDecimal partProcess) {
        return f.wReturn().multiply(partReturn).add(f.wRiskAdjusted().multiply(partRiskAdjusted))
                .add(f.wRisk().multiply(partRisk)).add(f.wProcess().multiply(partProcess));
    }

    /** L of the given S values (2 to 10 of them, any order). */
    public static Result of(List<BigDecimal> scores) {
        int m = scores.size();
        if (m < 2 || m > NEWEST) {
            throw new IllegalArgumentException("L needs 2 to " + NEWEST + " seasons, got " + m);
        }
        int k = m / NEWEST;
        List<BigDecimal> sorted = new ArrayList<>(scores);
        sorted.sort(BigDecimal::compareTo);
        BigDecimal kept = BigDecimal.ZERO;
        for (int i = k; i < m - k; i++) {
            kept = kept.add(sorted.get(i));
        }
        BigDecimal trimmedMean = MoneyMath.divideSignificant(kept, BigDecimal.valueOf(m - 2L * k));

        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal squares = BigDecimal.ZERO;
        for (BigDecimal x : scores) {
            sum = sum.add(x);
            squares = squares.add(x.multiply(x));
        }
        BigDecimal scatter = BigDecimal.valueOf(m).multiply(squares).subtract(sum.multiply(sum));
        BigDecimal sd = scatter.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : MoneyMath.sqrtSignificant(MoneyMath.divideSignificant(scatter, BigDecimal.valueOf((long) m * (m - 1))));
        BigDecimal lExact = trimmedMean.subtract(HALF.multiply(sd));
        return new Result(m, k, trimmedMean, sd, lExact, MoneyMath.round2(lExact));
    }
}
