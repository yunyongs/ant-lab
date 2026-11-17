package antlab.history;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Java port of {@code db/13_calc_attention_def.sql} (erd 5.3, D33): the attention index of one asset for every month it has a return.
 * Each row uses only that asset's returns up to its own month, so recalculating after new months arrive never changes older rows.
 * The calculator is pure: it reads no table and writes none. Mean, sample standard deviation and the division follow
 * PostgreSQL {@code numeric} scales in {@link MoneyMath}, so the values equal the SQL values digit for digit.
 */
public final class AttentionCalculator {

    private AttentionCalculator() {
    }

    /**
     * @param returns one asset's returns, strictly ascending by month with no duplicates; a missing calendar month ends a segment
     * @return one value per input month, in the same order
     */
    public static List<AttentionValue> series(List<MonthReturn> returns) {
        validate(returns);
        int n = returns.size();
        List<AttentionValue> out = new ArrayList<>(n);
        int segmentStart = 0;
        for (int i = 0; i < n; i++) {
            MonthReturn current = returns.get(i);
            if (i > 0 && !returns.get(i - 1).month().plusMonths(1).equals(current.month())) {
                segmentStart = i;
            }
            int from = Math.max(segmentStart, i - AttentionRules.WINDOW_MONTHS);
            int windowMonths = i - from;
            int streak = streak(returns, out, segmentStart, i);
            BigDecimal z = windowMonths == AttentionRules.WINDOW_MONTHS ? z(returns, from, i) : null;
            out.add(new AttentionValue(current.month(), windowMonths, z, streak, index(z, streak)));
        }
        return List.copyOf(out);
    }

    private static void validate(List<MonthReturn> returns) {
        if (returns == null) {
            throw new IllegalArgumentException("returns is required");
        }
        YearMonth previous = null;
        for (MonthReturn row : returns) {
            if (row == null) {
                throw new IllegalArgumentException("returns must not contain null");
            }
            if (previous != null && !row.month().isAfter(previous)) {
                throw new IllegalArgumentException("returns must be strictly ascending by month");
            }
            previous = row.month();
        }
    }

    /** F7: signed run of months in the same direction inside the segment; a zero return is 0 and ends the run. */
    private static int streak(List<MonthReturn> returns, List<AttentionValue> done, int segmentStart, int i) {
        int sign = returns.get(i).localReturn().signum();
        if (sign == 0) {
            return 0;
        }
        boolean continues = i > segmentStart && returns.get(i - 1).localReturn().signum() == sign;
        int length = continues ? Math.abs(done.get(i - 1).streak()) + 1 : 1;
        return sign * length;
    }

    /** F3–F6: z of month i against the 12 months before it, or null when the standard deviation is under the floor. */
    private static BigDecimal z(List<MonthReturn> returns, int from, int i) {
        List<BigDecimal> window = new ArrayList<>(AttentionRules.WINDOW_MONTHS);
        for (int j = from; j < i; j++) {
            window.add(returns.get(j).localReturn().abs());
        }
        BigDecimal sd = MoneyMath.sampleStddev(window);
        if (sd.compareTo(AttentionRules.SD_FLOOR) < 0) {
            return null;
        }
        BigDecimal mean = MoneyMath.pgAvg(window);
        BigDecimal raw = MoneyMath.pgDivide(returns.get(i).localReturn().abs().subtract(mean), sd);
        BigDecimal cap = AttentionRules.Z_CAP;
        BigDecimal clipped = raw.compareTo(cap) > 0 ? cap : raw.compareTo(cap.negate()) < 0 ? cap.negate() : raw;
        return MoneyMath.round8(clipped);
    }

    /** F8: z plus 0.25 per streak month beyond the first, capped at the fifth; no bonus when the streak is 0. */
    private static BigDecimal index(BigDecimal z, int streak) {
        if (z == null || streak == 0) {
            return z;
        }
        int steps = Math.min(Math.abs(streak), AttentionRules.STREAK_CAP) - 1;
        return z.add(AttentionRules.BONUS_STEP.multiply(BigDecimal.valueOf(steps)));
    }
}
