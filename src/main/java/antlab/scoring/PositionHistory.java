package antlab.scoring;

import antlab.asset.Currency;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The history tables a base-date judgement reads besides the pool: every {@code month_block} month, the {@code fx_month} changes per
 * currency, and the two market proxy series ({@link ReportRules#BENCH_KR_TICKER}, {@link ReportRules#BENCH_US_TICKER}).
 * The caller may pass the whole history; the calculator reads only the base date's window. Everything is copied on construction (the month
 * blocks into natural ascending order, whatever comparator the caller's set used) and the accessors are read-only.
 */
public record PositionHistory(NavigableSet<YearMonth> monthBlocks, Map<Currency, Map<YearMonth, BigDecimal>> fxChange,
        AssetSeries krProxy, AssetSeries usProxy) {

    public PositionHistory {
        Objects.requireNonNull(monthBlocks, "monthBlocks");
        Objects.requireNonNull(fxChange, "fxChange");
        Objects.requireNonNull(krProxy, "krProxy");
        Objects.requireNonNull(usProxy, "usProxy");
        NavigableSet<YearMonth> blocks = new TreeSet<>();
        blocks.addAll(monthBlocks);
        monthBlocks = Collections.unmodifiableNavigableSet(blocks);
        Map<Currency, Map<YearMonth, BigDecimal>> fx = new HashMap<>();
        for (Map.Entry<Currency, Map<YearMonth, BigDecimal>> entry : fxChange.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "currency");
            Objects.requireNonNull(entry.getValue(), "fx changes");
            fx.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        fxChange = Collections.unmodifiableMap(fx);
    }
}
