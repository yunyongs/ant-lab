package antlab.engine.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The engine's {@code application/problem+json} body. Only {@link #code()} and the field names of {@link #errors()}
 * are ever used; {@code detail} is the engine's own sentence and is never forwarded or logged.
 */
public record EngineProblem(
        @JsonProperty(required = true) String title,
        @JsonProperty(required = true) int status,
        @JsonProperty(required = true) String detail,
        @JsonProperty(required = true) String instance,
        @JsonProperty(required = true) EngineErrorCode code,
        List<FieldViolation> errors) {

    public record FieldViolation(
            @JsonProperty(required = true) String field,
            @JsonProperty(required = true) String reason) {

        public FieldViolation {
            EngineWire.required(field, "field");
            EngineWire.required(reason, "reason");
        }
    }

    public EngineProblem {
        EngineWire.required(title, "title");
        EngineWire.required(detail, "detail");
        EngineWire.required(instance, "instance");
        EngineWire.required(code, "code");
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    @Override
    public String toString() {
        return "EngineProblem[" + status + " " + code + "]";
    }
}
