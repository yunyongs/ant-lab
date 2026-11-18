package antlab.collect.fred;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A scripted FRED client: answers by source series id, remembers what was asked, never touches a network. */
public final class FakeFredClient implements FredSeriesClient {

    public record Call(String sourceSeriesId, LocalDate start, boolean allVintages) {
    }

    private final Map<String, Supplier<List<FredObservation>>> answers = new HashMap<>();
    public final List<Call> calls = new ArrayList<>();

    public static FredObservation obs(String date, String value, String realtimeStart, String realtimeEnd) {
        return new FredObservation(LocalDate.parse(date), ".".equals(value) ? null : new java.math.BigDecimal(value),
                LocalDate.parse(realtimeStart), LocalDate.parse(realtimeEnd));
    }

    public FakeFredClient answer(String sourceSeriesId, FredObservation... rows) {
        answers.put(sourceSeriesId, () -> List.of(rows));
        return this;
    }

    public FakeFredClient fail(String sourceSeriesId, String code, String message) {
        answers.put(sourceSeriesId, () -> {
            throw new FredException(code, message);
        });
        return this;
    }

    public FakeFredClient crash(String sourceSeriesId, RuntimeException error) {
        answers.put(sourceSeriesId, () -> {
            throw error;
        });
        return this;
    }

    @Override
    public List<FredObservation> observations(String sourceSeriesId, LocalDate observationStart, boolean allVintages) {
        calls.add(new Call(sourceSeriesId, observationStart, allVintages));
        Supplier<List<FredObservation>> answer = answers.get(sourceSeriesId);
        if (answer == null) {
            throw new IllegalStateException("no scripted answer for " + sourceSeriesId);
        }
        return answer.get();
    }

    @Override
    public int requestsMade() {
        return calls.size();
    }
}
