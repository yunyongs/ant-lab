package antlab.scoring;

import antlab.asset.Currency;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.NavigableMap;
import java.util.Objects;

/**
 * One asset's currency and its local-currency monthly returns as stored ({@code asset_month_return.local_return}, scale 8, never stripped).
 * A month without an entry is a month without a stored return.
 */
public record AssetSeries(Currency currency, NavigableMap<YearMonth, BigDecimal> localReturns) {

    public AssetSeries {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(localReturns, "localReturns");
        for (var entry : localReturns.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "month");
            Objects.requireNonNull(entry.getValue(), "local return");
        }
    }
}
