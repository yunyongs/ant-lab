package antlab.regime;

import antlab.engine.client.EngineRegimeRequest;
import antlab.engine.client.EngineRegimeResult;
import antlab.engine.client.EngineRegimeResult.AxisReading;
import antlab.regime.MonthRegime.AxisValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that must hold before one value is written: the answer is for the version and the months that were asked, in
 * that order, each month's status agrees with what it carries, the four probabilities add up to 1 within the database CHECK
 * tolerance, all sixteen indicator keys are there, and the coverage counts agree with the statuses. A month that is not
 * EVALUATED carries no column value, so its columns stay NULL and a later run can fill them. The wire shapes (digit counts,
 * enums) are enforced by the client records. Pure: no I/O. A failure names the position of the month, never the month or a value.
 */
final class RegimeAnswerCheck {

    /** Equal to ck_month_block_prob_sum: the sum of four values rounded to four digits may be 0.0002 off. */
    static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.0002");

    private RegimeAnswerCheck() {
    }

    static final class AnswerRejectedException extends RuntimeException {
        AnswerRejectedException(String message) {
            super(message);
        }
    }

    /** The answer as column values, one entry per requested month in request order; throws {@link AnswerRejectedException}. */
    static List<MonthRegime> check(EngineRegimeRequest request, EngineRegimeResult answer) {
        if (!request.labelVersion().equals(answer.labelVersion())) {
            throw reject("label version differs from the request");
        }
        if (answer.months().size() != request.months().size()) {
            throw reject("answered " + answer.months().size() + " months for " + request.months().size() + " requested");
        }
        List<MonthRegime> out = new ArrayList<>(request.months().size());
        for (int i = 0; i < request.months().size(); i++) {
            EngineRegimeResult.Month month = answer.months().get(i);
            if (!request.months().get(i).equals(month.month())) {
                throw reject("month " + i + " is not the month requested at that position");
            }
            out.add(one(i, month));
        }
        long evaluated = answer.months().stream().filter(m -> m.status() == EngineRegimeResult.Status.EVALUATED).count();
        long carried = answer.months().stream().filter(m -> m.status() == EngineRegimeResult.Status.CARRIED).count();
        long noData = answer.months().stream().filter(m -> m.status() == EngineRegimeResult.Status.NO_DATA).count();
        var coverage = answer.coverage();
        if (coverage.evaluated() != evaluated || coverage.carried() != carried || coverage.noData() != noData) {
            throw reject("the coverage counts do not match the month statuses");
        }
        return List.copyOf(out);
    }

    private static MonthRegime one(int index, EngineRegimeResult.Month month) {
        boolean evaluated = month.status() == EngineRegimeResult.Status.EVALUATED;
        boolean hasProbabilities = month.probabilities() != null;
        if (evaluated != hasProbabilities || evaluated != (month.label() != null) || evaluated != (month.phase() != null)) {
            throw reject("month " + index + ": probabilities, label and phase must be present exactly when the status is EVALUATED");
        }
        if (!evaluated && (month.axes().rate() != null || month.axes().growth() != null)) {
            throw reject("month " + index + ": rate and growth axes must be empty when the status is not EVALUATED");
        }
        if (!month.indicators().keySet().equals(java.util.Set.copyOf(EngineRegimeResult.INDICATOR_KEYS))) {
            throw reject("month " + index + ": the indicator keys are not exactly the contract keys");
        }
        List<BigDecimal> probabilities = null;
        if (hasProbabilities) {
            var p = month.probabilities();
            probabilities = List.of(new BigDecimal(p.p1()), new BigDecimal(p.p2()), new BigDecimal(p.p3()), new BigDecimal(p.p4()));
            BigDecimal gap = probabilities.stream().reduce(BigDecimal.ZERO, BigDecimal::add).subtract(BigDecimal.ONE).abs();
            if (gap.compareTo(SUM_TOLERANCE) > 0) {
                throw reject("month " + index + ": the probabilities do not add up to 1");
            }
        }
        if (!evaluated) {
            return new MonthRegime(month.month(), null, null, null, null, null, null, null);
        }
        Map<String, BigDecimal> indicators = new LinkedHashMap<>();
        for (String key : EngineRegimeResult.INDICATOR_KEYS) {
            String value = month.indicators().get(key);
            indicators.put(key, value == null ? null : new BigDecimal(value));
        }
        return new MonthRegime(month.month(), axis(month.axes().rate()), axis(month.axes().growth()), axis(month.axes().fx()),
                axis(month.axes().sentiment()), probabilities, month.label(), indicators);
    }

    private static AxisValue axis(AxisReading reading) {
        return reading == null ? null : new AxisValue(reading.direction(), new BigDecimal(reading.strength()));
    }

    private static AnswerRejectedException reject(String reason) {
        return new AnswerRejectedException(reason);
    }
}
