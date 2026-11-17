package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Answer of {@code POST /v1/projections}. Multiples, drawdowns and probabilities are exact eight-digit ratios; no won
 * amount and no month leaves the engine. The history digest is stored by the caller, not recomputed.
 */
public record EngineProjectionResult(
        @JsonProperty(required = true) String projectionVersion,
        @JsonProperty(required = true) String engineVersion,
        @JsonProperty(required = true) int n,
        @JsonProperty(required = true) int horizonTurns,
        @JsonProperty(required = true) String historyDigest,
        @JsonProperty(required = true) List<PortfolioResult> portfolios) {

    public record PortfolioResult(
            @JsonProperty(required = true) String key,
            @JsonProperty(required = true) List<Band> bands,
            @JsonProperty(required = true) List<Summary> summaries) {

        public PortfolioResult {
            EngineWire.required(key, "key");
            bands = EngineWire.list(bands, "bands");
            summaries = EngineWire.list(summaries, "summaries");
        }
    }

    public record Band(
            @JsonProperty(required = true) int turn,
            @JsonProperty(required = true) BigDecimal p05,
            @JsonProperty(required = true) BigDecimal p25,
            @JsonProperty(required = true) BigDecimal p50,
            @JsonProperty(required = true) BigDecimal p75,
            @JsonProperty(required = true) BigDecimal p95) {

        public Band {
            EngineWire.multiple(p05, "p05");
            EngineWire.multiple(p25, "p25");
            EngineWire.multiple(p50, "p50");
            EngineWire.multiple(p75, "p75");
            EngineWire.multiple(p95, "p95");
        }
    }

    public record Summary(
            @JsonProperty(required = true) int turn,
            @JsonProperty(required = true) EndQuantiles end,
            @JsonProperty(required = true) MddQuantiles mdd,
            @JsonProperty(required = true) int breachCount,
            @JsonProperty(required = true) BigDecimal breachProbability) {

        public Summary {
            EngineWire.required(end, "end");
            EngineWire.required(mdd, "mdd");
            EngineWire.probability(breachProbability, "breachProbability");
        }
    }

    public record EndQuantiles(
            @JsonProperty(required = true) BigDecimal p05,
            @JsonProperty(required = true) BigDecimal p25,
            @JsonProperty(required = true) BigDecimal p50,
            @JsonProperty(required = true) BigDecimal p75,
            @JsonProperty(required = true) BigDecimal p95) {

        public EndQuantiles {
            EngineWire.multiple(p05, "p05");
            EngineWire.multiple(p25, "p25");
            EngineWire.multiple(p50, "p50");
            EngineWire.multiple(p75, "p75");
            EngineWire.multiple(p95, "p95");
        }
    }

    public record MddQuantiles(
            @JsonProperty(required = true) BigDecimal p05,
            @JsonProperty(required = true) BigDecimal p10,
            @JsonProperty(required = true) BigDecimal p25,
            @JsonProperty(required = true) BigDecimal p50,
            @JsonProperty(required = true) BigDecimal p75,
            @JsonProperty(required = true) BigDecimal p95) {

        public MddQuantiles {
            EngineWire.drawdown(p05, "p05");
            EngineWire.drawdown(p10, "p10");
            EngineWire.drawdown(p25, "p25");
            EngineWire.drawdown(p50, "p50");
            EngineWire.drawdown(p75, "p75");
            EngineWire.drawdown(p95, "p95");
        }
    }

    public EngineProjectionResult {
        EngineWire.required(projectionVersion, "projectionVersion");
        EngineWire.required(engineVersion, "engineVersion");
        EngineWire.match("historyDigest", historyDigest, EngineWire.DIGEST);
        portfolios = EngineWire.list(portfolios, "portfolios");
    }
}
