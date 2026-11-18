package antlab.engine.client;

import antlab.history.AxisDirection;
import antlab.history.RegimePool;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Answer of {@code POST /v1/regime/evaluate}. Strengths, indicator values and probabilities are plain decimal strings the
 * engine rounded once (eight and four fractional digits), so they stay strings here and reach the database unchanged.
 * Months and values are replay-only: {@link #toString()} prints counts only. Rules that span fields (echo of the request,
 * status against probabilities, the sum of the probabilities) are checked by the caller, not by these records.
 */
public record EngineRegimeResult(
        @JsonProperty(required = true) String labelVersion,
        @JsonProperty(required = true) String regimeVersion,
        @JsonProperty(required = true) String inputDigest,
        @JsonProperty(required = true) List<Month> months,
        @JsonProperty(required = true) Coverage coverage) {

    /** The indicator keys of docs/api/engine.yaml, in the engine's order. */
    public static final List<String> INDICATOR_KEYS = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14",
            "14US", "14KR");
    static final Set<String> RULE_DATED_SERIES = Set.of("US_DGS2", "US_CPILFESL", "KR_KTB3Y", "KR_BASE_RATE", "KR_CPI", "US_ICSA",
            "US_SAHM", "US_T10Y3M", "KR_CLI", "KR_EXPORTS", "KR_USDKRW", "US_DTWEXBGS", "US_VIX", "US_BAA10Y", "KR_CORP_AA");

    static final Pattern STRENGTH = Pattern.compile("^[0-9]{1,4}\\.[0-9]{8}$");
    static final Pattern INDICATOR_VALUE = Pattern.compile("^-?[0-9]{1,4}\\.[0-9]{8}$");
    static final Pattern PROBABILITY = Pattern.compile("^(0\\.[0-9]{4}|1\\.0000)$");

    public enum Status { EVALUATED, CARRIED, NO_DATA }

    public enum Phase { WATCH, ALERT, TRANSITION, CONVICTION }

    public record Month(
            @JsonProperty(required = true) String month,
            @JsonProperty(required = true) Status status,
            @JsonProperty(required = true) Axes axes,
            @JsonProperty(required = true) Probabilities probabilities,
            @JsonProperty(required = true) RegimePool label,
            @JsonProperty(required = true) Phase phase,
            @JsonProperty(required = true) Map<String, String> indicators,
            @JsonProperty(required = true) Quality quality) {

        public Month {
            EngineWire.match("month", month, EngineRegimeRequest.REQUEST_MONTH);
            EngineWire.required(status, "status");
            EngineWire.required(axes, "axes");
            EngineWire.required(quality, "quality");
            indicators = Collections.unmodifiableMap(new LinkedHashMap<>(EngineWire.required(indicators, "indicators")));
            indicators.forEach((key, value) -> {
                if (!INDICATOR_KEYS.contains(key)) {
                    throw new IllegalArgumentException("unknown indicator key");
                }
                if (value != null) {
                    EngineWire.match("indicator", value, INDICATOR_VALUE);
                }
            });
        }

        @Override
        public String toString() {
            return "Month[...]";
        }
    }

    public record Axes(
            @JsonProperty(required = true) AxisReading rate,
            @JsonProperty(required = true) AxisReading growth,
            @JsonProperty(required = true) AxisReading fx,
            @JsonProperty(required = true) AxisReading sentiment) {
    }

    public record AxisReading(
            @JsonProperty(required = true) AxisDirection direction,
            @JsonProperty(required = true) String strength) {

        public AxisReading {
            EngineWire.required(direction, "direction");
            EngineWire.match("strength", strength, STRENGTH);
        }
    }

    public record Probabilities(
            @JsonProperty(required = true) String p1,
            @JsonProperty(required = true) String p2,
            @JsonProperty(required = true) String p3,
            @JsonProperty(required = true) String p4) {

        public Probabilities {
            EngineWire.match("p1", p1, PROBABILITY);
            EngineWire.match("p2", p2, PROBABILITY);
            EngineWire.match("p3", p3, PROBABILITY);
            EngineWire.match("p4", p4, PROBABILITY);
        }
    }

    public record Quality(
            @JsonProperty(required = true) boolean ok,
            @JsonProperty(required = true) List<String> stale,
            @JsonProperty(required = true) List<String> unavailable) {

        public Quality {
            stale = EngineWire.list(stale, "stale");
            unavailable = EngineWire.list(unavailable, "unavailable");
        }
    }

    public record Coverage(
            @JsonProperty(required = true) int evaluated,
            @JsonProperty(required = true) int carried,
            @JsonProperty(required = true) int noData,
            @JsonProperty(required = true) Map<String, Integer> indicators,
            @JsonProperty(required = true) Map<String, Integer> ruleDatedRows,
            @JsonProperty(required = true) boolean dtwexLinked) {

        public Coverage {
            if (evaluated < 0 || carried < 0 || noData < 0) {
                throw new IllegalArgumentException("a count is not negative");
            }
            indicators = Map.copyOf(EngineWire.required(indicators, "indicators"));
            ruleDatedRows = Map.copyOf(EngineWire.required(ruleDatedRows, "ruleDatedRows"));
            if (!INDICATOR_KEYS.containsAll(indicators.keySet()) || !RULE_DATED_SERIES.containsAll(ruleDatedRows.keySet())) {
                throw new IllegalArgumentException("unknown coverage key");
            }
        }
    }

    public EngineRegimeResult {
        EngineWire.required(labelVersion, "labelVersion");
        EngineWire.required(regimeVersion, "regimeVersion");
        EngineWire.match("inputDigest", inputDigest, EngineWire.DIGEST);
        months = EngineWire.list(months, "months");
        EngineWire.required(coverage, "coverage");
    }

    @Override
    public String toString() {
        return "EngineRegimeResult[" + months.size() + " months]";
    }
}
