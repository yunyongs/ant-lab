package antlab.scoring;

import java.math.BigDecimal;

/**
 * Trade discipline facts of one season, each a ratio in [0, 1] or null when it could not be measured. The discipline
 * item score is 100 x (1 - mean of the non-null ratios); both null means no item.
 */
public record DisciplineFacts(BigDecimal panicSellRatio, BigDecimal chaseBuyRatio) {

    public DisciplineFacts {
        requireRatio(panicSellRatio);
        requireRatio(chaseBuyRatio);
    }

    private static void requireRatio(BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("discipline ratio must be within 0..1");
        }
    }
}
