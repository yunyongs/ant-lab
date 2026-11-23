package antlab.agent.detection;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A model's detection result as an agent row shows it: {@link NotTested} until a summary for the model is imported, then
 * {@link Tested}. It is report only and never filters or reorders a leaderboard row (PLAN §4.16, §6.9).
 */
public sealed interface DetectionResult {

    DetectionStatus status();

    record NotTested() implements DetectionResult {
        @Override
        public DetectionStatus status() {
            return DetectionStatus.NOT_TESTED;
        }
    }

    /**
     * The newest run of the model fixes the test version and sample layer; each window is the newest row of that version
     * and layer for its length, so {@code runAt} is the newest of the windows' run times. Windows are in ascending months.
     */
    record Tested(String testVersion, String sampleLayer, Instant runAt, List<Window> windows) implements DetectionResult {
        @Override
        public DetectionStatus status() {
            return DetectionStatus.TESTED;
        }
    }

    record Window(int months, int units, Instant runAt, List<Metric> metrics) {
    }

    /** One metric; metrics are in {@link DetectionMetric} order. */
    record Metric(DetectionMetric metric, int hits, int units, BigDecimal baseRate) {

        /** Hits over units, rounded once HALF_UP to 8 decimal places like the base rate. */
        public BigDecimal accuracy() {
            return MoneyMath.ratio(BigDecimal.valueOf(hits), BigDecimal.valueOf(units));
        }
    }
}
