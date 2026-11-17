package antlab.collect.price;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The trade dates one run asks for. It ends yesterday in the market's own zone, so a session still in progress (or not
 * started) is never requested; it starts {@code lookbackDays} before the latest stored date, so a revision of recent closes
 * is seen, or {@code initialDays} before the end when nothing is stored yet.
 */
record PriceWindow(LocalDate from, LocalDate to) {

    static final ZoneId KR_ZONE = ZoneId.of("Asia/Seoul");
    static final ZoneId US_ZONE = ZoneId.of("America/New_York");

    static ZoneId zoneOf(String market) {
        return "KR".equals(market) ? KR_ZONE : US_ZONE;
    }

    static Optional<PriceWindow> of(Instant now, String market, LocalDate latestStored, int lookbackDays, int initialDays) {
        LocalDate to = LocalDate.ofInstant(now, zoneOf(market)).minusDays(1);
        LocalDate from = latestStored == null ? to.minusDays(initialDays) : latestStored.minusDays(lookbackDays);
        return from.isAfter(to) ? Optional.empty() : Optional.of(new PriceWindow(from, to));
    }
}
