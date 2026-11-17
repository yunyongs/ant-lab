package antlab.history;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * The stored columns of {@code asset_month_attention} for one asset-month, ready to insert as they are.
 * {@code attentionZ} and {@code attentionIndex} are scale 8 and null together; {@code streak} is signed.
 */
public record AttentionValue(YearMonth month, int windowMonths, BigDecimal attentionZ, int streak,
        BigDecimal attentionIndex) {
}
