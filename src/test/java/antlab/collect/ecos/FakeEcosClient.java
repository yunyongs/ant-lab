package antlab.collect.ecos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A scripted ECOS client: answers by "STAT/ITEM", remembers what was asked, never touches a network. */
public final class FakeEcosClient implements EcosSeriesClient {

    public record Call(String statCode, String itemCode, EcosCycle cycle, LocalDate from, LocalDate to) {
    }

    private final Map<String, Supplier<List<EcosObservation>>> answers = new HashMap<>();
    public final List<Call> calls = new ArrayList<>();

    public static EcosObservation obs(String date, String value) {
        return new EcosObservation(LocalDate.parse(date), new BigDecimal(value));
    }

    public FakeEcosClient answer(String sourceSeriesId, EcosObservation... rows) {
        answers.put(sourceSeriesId, () -> List.of(rows));
        return this;
    }

    /** Answers like {@link #answer} but runs {@code beforeAnswer} first (a test moves its clock while the request is out). */
    public FakeEcosClient answerAfter(String sourceSeriesId, Runnable beforeAnswer, EcosObservation... rows) {
        answers.put(sourceSeriesId, () -> {
            beforeAnswer.run();
            return List.of(rows);
        });
        return this;
    }

    public FakeEcosClient fail(String sourceSeriesId, String code, String message) {
        answers.put(sourceSeriesId, () -> {
            throw new EcosException(code, message);
        });
        return this;
    }

    public FakeEcosClient crash(String sourceSeriesId, RuntimeException error) {
        answers.put(sourceSeriesId, () -> {
            throw error;
        });
        return this;
    }

    @Override
    public List<EcosObservation> observations(String statCode, String itemCode, EcosCycle cycle, LocalDate from, LocalDate to) {
        calls.add(new Call(statCode, itemCode, cycle, from, to));
        Supplier<List<EcosObservation>> answer = answers.get(statCode + "/" + itemCode);
        if (answer == null) {
            throw new IllegalStateException("no scripted answer for " + statCode + "/" + itemCode);
        }
        return answer.get();
    }

    @Override
    public int requestsMade() {
        return calls.size();
    }
}
