package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Answer of {@code POST /v1/percentiles}: integer counts only, never a rounded value. {@code below} paths are strictly
 * smaller than the observed value and {@code equal} paths are exactly equal, so {@code below + equal <= n}. A count
 * outside that range, or {@code n} outside 100..5000, is contract drift and is refused as a bad answer.
 */
public record EnginePercentileResult(
        @JsonProperty(required = true) String projectionVersion,
        @JsonProperty(required = true) String engineVersion,
        @JsonProperty(required = true) int n,
        @JsonProperty(required = true) int horizonTurns,
        @JsonProperty(required = true) String historyDigest,
        @JsonProperty(required = true) Counts endMultiple,
        @JsonProperty(required = true) Counts sharpeDiff) {

    /** How many paths are strictly below and exactly equal to one observed value. */
    public record Counts(
            @JsonProperty(required = true) int below,
            @JsonProperty(required = true) int equal) {

        public Counts {
            if (below < 0 || below > 5000 || equal < 0 || equal > 5000) {
                throw new IllegalArgumentException("a count is outside 0..5000");
            }
        }
    }

    public EnginePercentileResult {
        EngineWire.required(projectionVersion, "projectionVersion");
        EngineWire.required(engineVersion, "engineVersion");
        EngineWire.match("historyDigest", historyDigest, EngineWire.DIGEST);
        if (n < 100 || n > 5000) {
            throw new IllegalArgumentException("n must be 100..5000");
        }
        if (!EnginePercentileRequest.supportsHorizon(horizonTurns)) {
            throw new IllegalArgumentException("horizonTurns must be one of 12, 24, 60, 120");
        }
        EngineWire.required(endMultiple, "endMultiple");
        EngineWire.required(sharpeDiff, "sharpeDiff");
        if (endMultiple.below() + endMultiple.equal() > n || sharpeDiff.below() + sharpeDiff.equal() > n) {
            throw new IllegalArgumentException("below + equal must not exceed n");
        }
    }
}
