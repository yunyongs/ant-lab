package antlab.agent.memory;

import antlab.agent.ReflectionOutcome;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** What the season's memory step became: the recorded outcome and, when accepted, the new memory version. */
public record SubmitSeasonMemoryResultDto(
        @ExposedFrom("agent_reflection.outcome")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        ReflectionOutcome outcome,
        @ExposedFrom("agent_memory.version")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "The new memory version; null when the reflection was discarded")
        Integer version) {
}
