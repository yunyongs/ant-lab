package antlab.scoring;

import antlab.asset.Currency;
import antlab.asset.Position;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;

/**
 * Base-date position judgement from the history tables: the Java counterpart of {@code db/12_calc_position_def.sql} ({@code pos_window},
 * {@code pos_ret}, {@code pos_mkt}, {@code pos_series}, {@code pos_judge}). It builds the D35 window, the 50/50 market series over every
 * month block in the window and the KRW returns, and leaves the metrics, the pool ranking and the classification to {@link PositionMeter}.
 *
 * <p>Only months from the window start to the window end are read, and the window ends the month before the base date's month, so nothing
 * from the base-date month or later can reach the result. A missing in-window input (a USD return month without an FX change, a month
 * block without a proxy return) stops the calculation, as {@code pos_fail} does; it is never replaced by zero. Pure: no database, no writes.
 */
public final class BaseDatePositionCalculator {

    private BaseDatePositionCalculator() {
    }

    /**
     * @param pool the assets of one base date (the {@code asset_position} rows of that date); the result has the same keys
     * @throws IllegalStateException when an in-window input is missing
     */
    public static <K> Map<K, BaseDateJudgement> judge(LocalDate baseDate, Map<K, AssetSeries> pool, PositionHistory history) {
        return judgeDetailed(baseDate, pool, history).rows();
    }

    /**
     * The judgements of one base date together with the market maximum drawdown that the pool classification used.
     *
     * @param marketMaxDrawdown drawdown of the 50/50 market series over every month block in the window; null when the window has no block
     */
    public record Detailed<K>(Map<K, BaseDateJudgement> rows, BigDecimal marketMaxDrawdown) {
    }

    /** Same as {@link #judge} and also returns the market drawdown, so a parity test can compare it with the SQL {@code pos_metric} value. */
    public static <K> Detailed<K> judgeDetailed(LocalDate baseDate, Map<K, AssetSeries> pool, PositionHistory history) {
        Objects.requireNonNull(baseDate, "baseDate");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(history, "history");
        YearMonth windowEnd = YearMonth.from(baseDate).minusMonths(ReportRules.WINDOW_END_LAG);
        YearMonth windowStart = windowEnd.minusMonths(ReportRules.WINDOW_MONTHS - 1L);
        NavigableSet<YearMonth> blocks = history.monthBlocks().subSet(windowStart, true, windowEnd, true);

        List<BigDecimal> market = new ArrayList<>();
        for (YearMonth month : blocks) {
            market.add(PositionMeter.marketReturn(krwReturn(history.krProxy(), month, history),
                    krwReturn(history.usProxy(), month, history)));
        }

        Map<K, PositionMeter.Metrics> metrics = new LinkedHashMap<>();
        for (Map.Entry<K, AssetSeries> entry : pool.entrySet()) {
            Objects.requireNonNull(entry.getValue(), "asset series");
            List<BigDecimal> returns = new ArrayList<>(blocks.size());
            for (YearMonth month : blocks) {
                returns.add(entry.getValue().localReturns().containsKey(month) ? krwReturn(entry.getValue(), month, history) : null);
            }
            metrics.put(entry.getKey(), PositionMeter.measure(returns, market, ReportRules.BASE_MIN_MONTHS, ReportRules.BASE_MIN_UP,
                    ReportRules.BASE_MIN_DOWN));
        }

        BigDecimal marketMaxDrawdown = PositionMeter.maxDrawdown(market);
        Map<K, Position> positions = metrics.values().stream().anyMatch(PositionMeter.Metrics::judgeable)
                ? PositionMeter.classifyPool(metrics, marketMaxDrawdown)
                : Map.of();

        Map<K, BaseDateJudgement> out = new LinkedHashMap<>();
        for (Map.Entry<K, PositionMeter.Metrics> entry : metrics.entrySet()) {
            PositionMeter.Metrics m = entry.getValue();
            out.put(entry.getKey(), m.judgeable()
                    ? new BaseDateJudgement(null, windowStart, windowEnd, m.monthsUsed(), m.up(), m.down(), m.volatility(),
                            m.maxDrawdown(), positions.get(entry.getKey()), m.nUp(), m.nDown())
                    : BaseDateJudgement.unjudged(m.reason(), m.nUp(), m.nDown()));
        }
        return new Detailed<>(out, marketMaxDrawdown);
    }

    /** KRW return of one stored month; a USD month needs its FX change, and the series must hold the month (proxies: else the run stops). */
    private static BigDecimal krwReturn(AssetSeries series, YearMonth month, PositionHistory history) {
        BigDecimal local = series.localReturns().get(month);
        if (local == null) {
            throw new IllegalStateException("market proxy return is missing for a window month");
        }
        boolean krw = series.currency() == Currency.KRW;
        BigDecimal fx = krw ? null : history.fxChange().getOrDefault(series.currency(), Map.of()).get(month);
        if (!krw && fx == null) {
            throw new IllegalStateException("USD return month has no FX change");
        }
        return PositionMeter.krwReturn(krw, local, fx);
    }
}
