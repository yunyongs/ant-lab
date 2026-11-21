package antlab.agent.memory;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** One memory version as the runner reads it: the memory itself plus how it was written. */
public record AgentMemoryVersionDto(
        @Schema(requiredMode = RequiredMode.REQUIRED)
        AgentMemoryDto memory,
        @ExposedFrom("agent_memory.playbook_carried")
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "True when the rewrite failed and the previous operating note was kept")
        boolean playbookCarried,
        @ExposedFrom("agent_memory.created_at")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        Instant createdAt) {
}
