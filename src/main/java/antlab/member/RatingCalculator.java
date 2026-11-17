package antlab.member;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.List;

/**
 * Manager rating: the weighted mean of a member's last five counted season scores, weights 5, 4, 3, 2, 1 from the
 * newest back. The mean is divided once and rounded HALF_UP once to 2 decimals.
 */
public final class RatingCalculator {

    /** The most seasons the weighted mean covers. */
    public static final int WINDOW = 5;
    /** The rating is shown from this many counted seasons on; it is stored from the first. */
    public static final int SHOWN_FROM = 3;

    /** The rating after a season and how many seasons it covered. */
    public record Rating(BigDecimal rating, int seasonsUsed) {
    }

    private RatingCalculator() {
    }

    /** Whether the rating row with this sequence number may be shown. */
    public static boolean isShown(int seqNo) {
        return seqNo >= SHOWN_FROM;
    }

    /**
     * @param countedScoresOldestFirst scores of the counted seasons in sequence order, the newest last; not empty
     */
    public static Rating rate(List<BigDecimal> countedScoresOldestFirst) {
        if (countedScoresOldestFirst == null || countedScoresOldestFirst.isEmpty()) {
            throw new IllegalArgumentException("a rating needs at least one counted season");
        }
        for (BigDecimal score : countedScoresOldestFirst) {
            if (score == null || score.signum() < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("season scores must be within 0..100");
            }
        }
        int used = Math.min(WINDOW, countedScoresOldestFirst.size());
        int first = countedScoresOldestFirst.size() - used;
        BigDecimal weighted = BigDecimal.ZERO;
        long weightSum = 0;
        for (int i = 0; i < used; i++) {
            long weight = WINDOW - used + 1L + i; // the newest gets WINDOW (5), each older season one less
            weighted = weighted.add(countedScoresOldestFirst.get(first + i).multiply(BigDecimal.valueOf(weight)));
            weightSum += weight;
        }
        BigDecimal rating = MoneyMath.round2(MoneyMath.scoreDivide(weighted, BigDecimal.valueOf(weightSum)));
        return new Rating(rating, used);
    }
}
