package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request of {@code POST /v1/percentiles}: where an observed season sits among {@code n} engine paths for the season-start
 * team and a benchmark, both held unchanged. Every number is a decimal string; the season seed is never printed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnginePercentileRequest(
        @JsonProperty(required = true) String seasonSeed,
        @JsonProperty(required = true) LocalDate baseDate,
        @JsonProperty(required = true) int horizonTurns,
        @JsonProperty(required = true) int n,
        @JsonProperty(required = true) Portfolio team,
        @JsonProperty(required = true) Portfolio benchmark,
        @JsonProperty(required = true) Observed observed) {

    static final Set<Integer> HORIZONS = Set.of(12, 24, 60, 120);
    static final int MAX_HOLDINGS = 200;
    /** {@code endMultiple}: up to 12 integer digits; {@code sharpeDiff}: up to 6 integer digits, signed (engine.yaml). */
    static final Pattern END_MULTIPLE = Pattern.compile("^[0-9]{1,12}(\\.[0-9]{1,12})?$");
    static final Pattern SHARPE_DIFF = Pattern.compile("^-?[0-9]{1,6}(\\.[0-9]{1,12})?$");

    /** A fixed-quantity portfolio as shares: cash plus weights add up to exactly 1 and an asset appears once. */
    public record Portfolio(
            @JsonProperty(required = true) String cash,
            @JsonProperty(required = true) List<EngineProjectionRequest.Holding> weights) {

        public Portfolio {
            EngineWire.match("cash", cash, EngineWire.SHARE);
            weights = EngineWire.list(weights, "weights");
            if (weights.size() > MAX_HOLDINGS) {
                throw new IllegalArgumentException("a portfolio holds at most " + MAX_HOLDINGS + " assets");
            }
            Set<String> assets = new HashSet<>();
            BigDecimal total = new BigDecimal(cash);
            for (EngineProjectionRequest.Holding holding : weights) {
                if (!assets.add(holding.asset())) {
                    throw new IllegalArgumentException("an asset must not repeat inside a portfolio");
                }
                total = total.add(new BigDecimal(holding.weight()));
            }
            if (total.compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException("cash plus weights must add up to exactly 1");
            }
        }
    }

    /** The two values to rank, as plain decimal strings. */
    public record Observed(
            @JsonProperty(required = true) String endMultiple,
            @JsonProperty(required = true) String sharpeDiff) {

        public Observed {
            EngineWire.match("endMultiple", endMultiple, END_MULTIPLE);
            EngineWire.match("sharpeDiff", sharpeDiff, SHARPE_DIFF);
        }
    }

    public EnginePercentileRequest {
        EngineWire.match("seasonSeed", seasonSeed, EngineWire.SEED);
        Long.parseLong(seasonSeed);
        EngineWire.required(baseDate, "baseDate");
        if (!HORIZONS.contains(horizonTurns)) {
            throw new IllegalArgumentException("horizonTurns must be one of 12, 24, 60, 120");
        }
        if (n < 100 || n > 5000) {
            throw new IllegalArgumentException("n must be 100..5000");
        }
        EngineWire.required(team, "team");
        EngineWire.required(benchmark, "benchmark");
        EngineWire.required(observed, "observed");
    }

    /** True when the engine has an endpoint for this season length. */
    public static boolean supportsHorizon(int horizonTurns) {
        return HORIZONS.contains(horizonTurns);
    }

    @Override
    public String toString() {
        return "EnginePercentileRequest[" + horizonTurns + " turns, n=" + n + "]";
    }
}
