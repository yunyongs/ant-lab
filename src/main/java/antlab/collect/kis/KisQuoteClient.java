package antlab.collect.kis;

import antlab.collect.PriceBasis;
import java.time.LocalDate;
import java.util.List;

/**
 * Quote lookup only: the implementation can send exactly three kinds of request (token, domestic daily, overseas daily).
 * One instance is one run: it owns the request counter and the per-run cap. Results are sorted by date, one close per date.
 */
public interface KisQuoteClient {

    List<DailyClose> domesticDaily(String ticker, LocalDate from, LocalDate to, PriceBasis basis);

    /** Always the adjusted series (MODP=1). */
    List<DailyClose> overseasDaily(String ticker, KisExchange exchange, LocalDate from, LocalDate to);

    /** Requests sent by this instance, token issuance included. */
    int requestsMade();
}
