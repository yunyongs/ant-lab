package antlab.scoring;

import java.math.BigDecimal;
import antlab.common.money.MoneyMath;
import java.util.List;

/**
 * Sharpe ratio of a monthly return series: mean / sample standard deviation (n - 1), risk-free rate 0, not annualised.
 * The ratio is 0 when the deviation is 0 (all returns equal), never a division error. The variance numerator is
 * computed exactly, so "all equal" is detected exactly instead of through rounded intermediate values.
 */
public final class Sharpe {

    private Sharpe() {
    }

    /** The unrounded Sharpe ratio at working scale. Needs at least two returns. */
    static BigDecimal exact(List<BigDecimal> returns) {
        if (returns == null || returns.size() < 2 || returns.stream().anyMatch(r -> r == null)) {
            throw new IllegalArgumentException("sharpe needs at least two non-null returns");
        }
        BigDecimal n = BigDecimal.valueOf(returns.size());
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal sumSquares = BigDecimal.ZERO;
        for (BigDecimal r : returns) {
            sum = sum.add(r);
            sumSquares = sumSquares.add(r.multiply(r));
        }
        // n * sum(x^2) - (sum x)^2 = n * (n - 1) * variance, an exact value that is 0 only when every return is equal.
        BigDecimal scatter = n.multiply(sumSquares).subtract(sum.multiply(sum));
        if (scatter.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal variance = MoneyMath.divideSignificant(scatter, n.multiply(n.subtract(BigDecimal.ONE)));
        BigDecimal stddev = MoneyMath.sqrtSignificant(variance);
        BigDecimal mean = MoneyMath.divideSignificant(sum, n);
        return MoneyMath.scoreDivide(mean, stddev);
    }

    /** The Sharpe ratio as stored: 8 decimals, rounded once. */
    public static BigDecimal stored(List<BigDecimal> returns) {
        return MoneyMath.round8(exact(returns));
    }
}
