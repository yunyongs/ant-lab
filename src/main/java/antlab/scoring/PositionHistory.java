package antlab.scoring;

import antlab.asset.Currency;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;

/**
 * The history tables a base-date judgement reads besides the pool: every {@code month_block} month, the {@code fx_month} changes per
 * currency, and the two market proxy series ({@link ReportRules#BENCH_KR_TICKER}, {@link ReportRules#BENCH_US_TICKER}).
 * The caller may pass the whole history; the calculator reads only the base date's window.
 */
public record PositionHistory(NavigableSet<YearMonth> monthBlocks, Map<Currency, Map<YearMonth, BigDecimal>> fxChange,
        AssetSeries krProxy, AssetSeries usProxy) {

    public PositionHistory {
        Objects.requireNonNull(monthBlocks, "monthBlocks");
        Objects.requireNonNull(fxChange, "fxChange");
        Objects.requireNonNull(krProxy, "krProxy");
        Objects.requireNonNull(usProxy, "usProxy");
    }
}
