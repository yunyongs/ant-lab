package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.Set;

/**
 * Request of {@code POST /v1/paths}. The seed is a signed 64-bit number sent as a decimal string and never printed.
 * Overlap avoidance ({@code recentMonths}, {@code maxOverlap}) is not sent: the engine default is "no constraint".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnginePathRequest(
        @JsonProperty(required = true) String engineVersion,
        @JsonProperty(required = true) String seed,
        @JsonProperty(required = true) int length,
        @JsonProperty(required = true) LocalDate baseDate,
        @JsonProperty(required = true) boolean conditional) {

    static final Set<String> VERSIONS = Set.of("p0-boot6-v1", "m2-sb6-v1");
    static final Set<Integer> LENGTHS = Set.of(12, 24, 60, 120);

    public EnginePathRequest {
        if (!VERSIONS.contains(engineVersion)) {
            throw new IllegalArgumentException("unknown engine version");
        }
        EngineWire.match("seed", seed, EngineWire.SEED);
        Long.parseLong(seed);
        if (!LENGTHS.contains(length)) {
            throw new IllegalArgumentException("length must be 12, 24, 60 or 120");
        }
        EngineWire.required(baseDate, "baseDate");
        if (conditional) {
            throw new IllegalArgumentException("conditional paths are not available yet");
        }
    }

    public static EnginePathRequest of(String engineVersion, long seed, int length, LocalDate baseDate) {
        return new EnginePathRequest(engineVersion, Long.toString(seed), length, baseDate, false);
    }

    @Override
    public String toString() {
        return "EnginePathRequest[" + engineVersion + ", " + length + " turns]";
    }
}
