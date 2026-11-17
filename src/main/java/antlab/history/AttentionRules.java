package antlab.history;

import java.math.BigDecimal;

/**
 * Constants of the attention index (erd 5.3 note, D33). The single SQL copy is CTE {@code k} in
 * {@code db/13_calc_attention_def.sql}; {@code AttentionRulesSqlParityTest} pins the two together.
 */
public final class AttentionRules {

    /** Trailing window size: the previous 12 consecutive months, the current month excluded (F2, F3). */
    public static final int WINDOW_MONTHS = 12;
    /** A raw sample standard deviation below this leaves z NULL (F5). */
    public static final BigDecimal SD_FLOOR = new BigDecimal("0.0001");
    /** Absolute clip of z before the single 8-digit rounding (F6). */
    public static final BigDecimal Z_CAP = new BigDecimal("3");
    /** Index bonus per streak month beyond the first (F8). */
    public static final BigDecimal BONUS_STEP = new BigDecimal("0.25");
    /** Streak length at which the bonus stops growing (F8). */
    public static final int STREAK_CAP = 5;

    private AttentionRules() {
    }
}
