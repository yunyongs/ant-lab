package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Answer of {@code GET /v1/info}. */
public record EngineInfo(
        @JsonProperty(required = true) String service,
        @JsonProperty(required = true) String apiVersion,
        @JsonProperty(required = true) String version) {

    public EngineInfo {
        EngineWire.required(service, "service");
        EngineWire.required(apiVersion, "apiVersion");
        EngineWire.required(version, "version");
    }
}
