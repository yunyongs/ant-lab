package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Request of {@code POST /v1/projections}: fixed holdings projected over {@code n} paths. Every ratio is a decimal string.
 * The season seed is never printed. A portfolio must add up to exactly 1 (cash plus weights), which the engine also checks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EngineProjectionRequest(
        @JsonProperty(required = true) String seasonSeed,
        @JsonProperty(required = true) LocalDate baseDate,
        @JsonProperty(required = true) int horizonTurns,
        @JsonProperty(required = true) int n,
        @JsonProperty(required = true) String riskLimit,
        @JsonProperty(required = true) List<Integer> summaryTurns,
        @JsonProperty(required = true) List<Portfolio> portfolios) {

    static final int MAX_PORTFOLIOS = 6;
    static final int MAX_HOLDINGS = 200;
    static final int MAX_SUMMARY_TURNS = 20;

    public record Portfolio(
            @JsonProperty(required = true) String key,
            @JsonProperty(required = true) String cash,
            @JsonProperty(required = true) List<Holding> weights) {

        public Portfolio {
            EngineWire.match("key", key, EngineWire.KEY);
            EngineWire.match("cash", cash, EngineWire.SHARE);
            weights = EngineWire.list(weights, "weights");
            if (weights.size() > MAX_HOLDINGS) {
                throw new IllegalArgumentException("a portfolio holds at most " + MAX_HOLDINGS + " assets");
            }
            Set<String> assets = new HashSet<>();
            BigDecimal total = new BigDecimal(cash);
            for (Holding holding : weights) {
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

    public record Holding(
            @JsonProperty(required = true) String asset,
            @JsonProperty(required = true) String weight) {

        public Holding {
            EngineWire.match("asset", asset, EngineWire.ASSET);
            EngineWire.match("weight", weight, EngineWire.SHARE);
        }
    }

    public EngineProjectionRequest {
        EngineWire.match("seasonSeed", seasonSeed, EngineWire.SEED);
        Long.parseLong(seasonSeed);
        EngineWire.required(baseDate, "baseDate");
        if (horizonTurns != 60 && horizonTurns != 120) {
            throw new IllegalArgumentException("horizonTurns must be 60 or 120");
        }
        if (n < 100 || n > 5000) {
            throw new IllegalArgumentException("n must be 100..5000");
        }
        EngineWire.match("riskLimit", riskLimit, EngineWire.RISK_LIMIT);
        summaryTurns = EngineWire.list(summaryTurns, "summaryTurns");
        if (summaryTurns.isEmpty() || summaryTurns.size() > MAX_SUMMARY_TURNS) {
            throw new IllegalArgumentException("summaryTurns holds 1.." + MAX_SUMMARY_TURNS + " turns");
        }
        if (summaryTurns.stream().anyMatch(turn -> turn < 1 || turn > 120)) {
            throw new IllegalArgumentException("a summary turn is 1..120");
        }
        portfolios = EngineWire.list(portfolios, "portfolios");
        if (portfolios.isEmpty() || portfolios.size() > MAX_PORTFOLIOS) {
            throw new IllegalArgumentException("portfolios holds 1.." + MAX_PORTFOLIOS + " entries");
        }
        if (portfolios.stream().map(Portfolio::key).distinct().count() != portfolios.size()) {
            throw new IllegalArgumentException("portfolio keys must be unique");
        }
    }

    @Override
    public String toString() {
        return "EngineProjectionRequest[" + portfolios.size() + " portfolios, " + horizonTurns + " turns, n=" + n + "]";
    }
}
