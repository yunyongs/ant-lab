package antlab.agent;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * One unit of the previous decision that the engine rejected, as the engine recorded it. The agent is told which unit
 * and why, so the reply can be corrected; it never carries model text.
 */
public record RejectDto(
        @ExposedFrom("agent_call.rejects")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "FORMATION, ORDER or THESES")
        String unit,
        @ExposedFrom("agent_call.rejects")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Position in the reply's orders array; null for the formation and theses units")
        Integer index,
        @ExposedFrom("agent_call.rejects")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String code) {
}
