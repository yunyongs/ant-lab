package antlab.collect.price;

import antlab.collect.PriceBasis;
import antlab.collect.kis.DailyClose;
import antlab.collect.kis.KisException;
import antlab.collect.kis.KisExchange;
import antlab.collect.kis.KisQuoteClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A scripted quote client: answers by "MARKET:TICKER", remembers what was asked, never touches a network. */
final class FakeQuoteClient implements KisQuoteClient {

    record Call(String key, LocalDate from, LocalDate to, PriceBasis basis, KisExchange exchange) {
    }

    private final Map<String, Supplier<List<DailyClose>>> answers = new HashMap<>();
    final List<Call> calls = new ArrayList<>();

    static DailyClose close(String date, String price) {
        return new DailyClose(LocalDate.parse(date), new BigDecimal(price));
    }

    FakeQuoteClient answer(String key, DailyClose... closes) {
        answers.put(key, () -> List.of(closes));
        return this;
    }

    /** Runs {@code action} when the target is asked for (a pause in the middle of a run), then answers. */
    FakeQuoteClient answerAfter(String key, Runnable action, DailyClose... closes) {
        answers.put(key, () -> {
            action.run();
            return List.of(closes);
        });
        return this;
    }

    FakeQuoteClient fail(String key, String code, String message) {
        answers.put(key, () -> {
            throw new KisException(code, message);
        });
        return this;
    }

    FakeQuoteClient crash(String key, RuntimeException error) {
        answers.put(key, () -> {
            throw error;
        });
        return this;
    }

    @Override
    public List<DailyClose> domesticDaily(String ticker, LocalDate from, LocalDate to, PriceBasis basis) {
        return respond(new Call("KR:" + ticker, from, to, basis, null));
    }

    @Override
    public List<DailyClose> overseasDaily(String ticker, KisExchange exchange, LocalDate from, LocalDate to) {
        return respond(new Call("US:" + ticker, from, to, PriceBasis.ADJUSTED, exchange));
    }

    private List<DailyClose> respond(Call call) {
        calls.add(call);
        Supplier<List<DailyClose>> answer = answers.get(call.key());
        return answer == null ? List.of() : answer.get();
    }

    @Override
    public int requestsMade() {
        return calls.size();
    }
}
