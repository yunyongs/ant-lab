package antlab.agent;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * One part of the previous decision that the engine rejected, as the engine recorded it. It never carries model text:
 * {@code unit} is one of a fixed set, {@code code} an upper-case code, {@code pointer} a path of plain names.
 * Two forms exist. A unit reject names the unit (and the order's index for ORDER). A reply-level reject, such as a
 * reply that failed the format check, has no unit: {@code unit} and {@code index} are null and {@code pointer} says
 * where in the reply the engine found the fault.
 */
public record RejectDto(
        @ExposedFrom("agent_call.rejects")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                allowableValues = {"FORMATION", "ORDER", "THESES"},
                description = "FORMATION, ORDER or THESES; null for a reply-level reject")
        String unit,
        @ExposedFrom("agent_call.rejects")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Position in the reply's orders array (0-39) for ORDER; null otherwise")
        Integer index,
        @ExposedFrom("agent_call.rejects")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        String code,
        @ExposedFrom("agent_call.rejects")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Where in the reply the fault is, a path such as /orders/0/quantity; only for a "
                        + "reply-level reject")
        String pointer) {
}
