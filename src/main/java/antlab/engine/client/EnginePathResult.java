package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Answer of {@code POST /v1/paths}. The months are replay-only data: they stay inside the engine package and must not
 * reach a public or agent response, so {@code toString} leaves them out.
 */
public record EnginePathResult(
        @JsonProperty(required = true) String engineVersion,
        @JsonProperty(required = true) List<String> months,
        @JsonProperty(required = true) List<Block> blocks,
        @JsonProperty(required = true) int overlapMonths,
        @JsonProperty(required = true) boolean overlapMet,
        @JsonProperty(required = true) int attempt,
        @JsonProperty(required = true) String historyDigest) {

    public enum Tier { ALIGNED, NEAR, FREE, JOIN }

    public record Block(
            @JsonProperty(required = true) int startTurn,
            @JsonProperty(required = true) int drawnLength,
            @JsonProperty(required = true) int length,
            @JsonProperty(required = true) Tier tier,
            @JsonProperty(required = true) boolean conditioned,
            @JsonProperty(required = true) boolean matched) {

        public Block {
            EngineWire.required(tier, "tier");
        }
    }

    public EnginePathResult {
        EngineWire.required(engineVersion, "engineVersion");
        months = EngineWire.list(months, "months");
        months.forEach(month -> EngineWire.match("months[]", month, EngineWire.MONTH));
        blocks = EngineWire.list(blocks, "blocks");
        EngineWire.match("historyDigest", historyDigest, EngineWire.DIGEST);
    }

    @Override
    public String toString() {
        return "EnginePathResult[" + engineVersion + ", " + months.size() + " turns]";
    }
}
