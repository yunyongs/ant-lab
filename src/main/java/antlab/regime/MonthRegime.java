package antlab.regime;

import antlab.history.AxisDirection;
import antlab.history.RegimePool;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 14 label columns of one {@code month_block} row, as the database holds them or as the engine answered. Any part may be
 * null (a month without data has no axes or probabilities). The month is replay-only: {@link #toString()} hides everything.
 */
record MonthRegime(
        String month,
        AxisValue rate,
        AxisValue growth,
        AxisValue fx,
        AxisValue sentiment,
        List<BigDecimal> probabilities,
        RegimePool label,
        Map<String, BigDecimal> indicators) {

    /** The label columns in a fixed order; the writer, the verification and the role script grant exactly these. */
    static final List<String> COLUMNS = List.of("rate_dir", "growth_dir", "fx_dir", "sentiment_dir", "rate_strength", "growth_strength",
            "fx_strength", "sentiment_strength", "prob_p1", "prob_p2", "prob_p3", "prob_p4", "regime_label", "indicator_z");

    record AxisValue(AxisDirection direction, BigDecimal strength) {
    }

    MonthRegime {
        Objects.requireNonNull(month, "month");
        if (probabilities != null && probabilities.size() != 4) {
            throw new IllegalArgumentException("four probabilities");
        }
        probabilities = probabilities == null ? null : List.copyOf(probabilities);
        indicators = indicators == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(indicators));
    }

    /** Column name to value (null where the column is or would be NULL), in {@link #COLUMNS} order. */
    Map<String, Object> columns() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rate_dir", rate == null ? null : rate.direction());
        out.put("growth_dir", growth == null ? null : growth.direction());
        out.put("fx_dir", fx == null ? null : fx.direction());
        out.put("sentiment_dir", sentiment == null ? null : sentiment.direction());
        out.put("rate_strength", rate == null ? null : rate.strength());
        out.put("growth_strength", growth == null ? null : growth.strength());
        out.put("fx_strength", fx == null ? null : fx.strength());
        out.put("sentiment_strength", sentiment == null ? null : sentiment.strength());
        for (int i = 0; i < 4; i++) {
            out.put("prob_p" + (i + 1), probabilities == null ? null : probabilities.get(i));
        }
        out.put("regime_label", label);
        out.put("indicator_z", indicators);
        return out;
    }

    /** Equality of two column values: numbers by value (0.50 equals 0.5), JSON objects key by key. */
    static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            return x.keySet().stream().allMatch(key -> sameValue(x.get(key), y.get(key)));
        }
        return a.equals(b);
    }

    @Override
    public String toString() {
        return "MonthRegime[...]";
    }
}
