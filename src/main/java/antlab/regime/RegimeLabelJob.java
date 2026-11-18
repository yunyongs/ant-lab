package antlab.regime;

import antlab.engine.client.EngineClient;
import antlab.engine.client.EngineRegimeRequest;
import antlab.engine.client.EngineRegimeResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Labels the months of {@code month_block} with the engine's regime answer. One engine call for all months, no database
 * lock while it runs, the whole answer checked, then one fill-only transaction (see {@link MonthRegimeWriter}). Any failure
 * throws before or instead of a partial write; the caller turns it into an exit code.
 */
@Component
class RegimeLabelJob {

    /** What a successful run did; counts only, because months and values are replay-only. */
    record Outcome(int rows, int filled, int unchanged) {
    }

    /** The months of the table cannot go into one request (more than the engine takes, or outside 1900-2099). */
    static final class RequestRefusedException extends RuntimeException {
        RequestRefusedException() {
            super("month_block cannot be labelled in one request: 1.." + EngineRegimeRequest.MAX_MONTHS
                    + " months between 1900 and 2099 are accepted");
        }
    }

    private final EngineClient engine;
    private final MonthRegimeWriter writer;

    RegimeLabelJob(EngineClient engine, MonthRegimeWriter writer) {
        this.engine = engine;
        this.writer = writer;
    }

    Outcome run() {
        List<String> months = writer.months();
        if (months.isEmpty()) {
            return new Outcome(0, 0, 0);
        }
        EngineRegimeRequest request;
        try {
            request = new EngineRegimeRequest(EngineRegimeRequest.LABEL_VERSION, months);
        } catch (IllegalArgumentException e) {
            throw new RequestRefusedException();
        }
        EngineRegimeResult answer = engine.evaluateRegime(request);
        List<MonthRegime> checked = RegimeAnswerCheck.check(request, answer);
        MonthRegimeWriter.Result result = writer.fill(checked);
        return new Outcome(months.size(), result.filled(), result.unchanged());
    }
}
