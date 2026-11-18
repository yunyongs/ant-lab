package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Request of {@code POST /v1/regime/evaluate}: the months to label, as {@code YYYY-MM} in strictly increasing order. The
 * months are replay-only data, so {@link #toString()} never prints them.
 */
public record EngineRegimeRequest(
        @JsonProperty(required = true) String labelVersion,
        @JsonProperty(required = true) List<String> months) {

    public static final String LABEL_VERSION = "m2-rl-v1";
    public static final int MAX_MONTHS = 480;
    /** The engine accepts the years 1900-2099 only. */
    static final Pattern REQUEST_MONTH = Pattern.compile("^(19|20)[0-9]{2}-(0[1-9]|1[0-2])$");

    public EngineRegimeRequest {
        if (!LABEL_VERSION.equals(labelVersion)) {
            throw new IllegalArgumentException("unknown label version");
        }
        months = EngineWire.list(months, "months");
        if (months.isEmpty() || months.size() > MAX_MONTHS) {
            throw new IllegalArgumentException("months holds 1.." + MAX_MONTHS + " entries");
        }
        String previous = null;
        for (String month : months) {
            EngineWire.match("month", month, REQUEST_MONTH);
            // fixed-width YYYY-MM sorts like the calendar
            if (previous != null && previous.compareTo(month) >= 0) {
                throw new IllegalArgumentException("months must be strictly increasing");
            }
            previous = month;
        }
    }

    @Override
    public String toString() {
        return "EngineRegimeRequest[" + months.size() + " months]";
    }
}
