package antlab.agent.memory;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** Where an agent stands: its latest memory version and which finished seasons still wait for a reflection. */
public record AgentMemoryStateDto(
        @ExposedFrom("agent_memory.version")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Latest memory version; null when the agent has none")
        Integer latestVersion,
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Finished, eligible seasons without an outcome whose review is released, in finish order")
        List<Long> pendingSeasonIds,
        @ExposedFrom("season.id")
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Finished, eligible seasons without an outcome that an open league or group still holds")
        int heldSeasonCount) {
}
