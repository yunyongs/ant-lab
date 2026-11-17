package antlab.scoring;

import java.math.BigDecimal;
import antlab.common.money.MoneyMath;

/** Mid-rank percentile from integer counts: p = (below + equal / 2) / n, so a tie counts as half. */
public final class Percentile {

    private Percentile() {
    }

    /** The unrounded percentile in [0, 1], computed as (2 below + equal) / (2 n). */
    static BigDecimal exact(RankCounts counts) {
        BigDecimal numerator = BigDecimal.valueOf(2 * counts.below() + counts.equal());
        return MoneyMath.scoreDivide(numerator, BigDecimal.valueOf(2 * counts.n()));
    }

    /** The percentile as stored: 8 decimals, rounded once. */
    public static BigDecimal stored(RankCounts counts) {
        return MoneyMath.round8(exact(counts));
    }
}
