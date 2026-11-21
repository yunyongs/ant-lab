package antlab.agent.memory;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What a season reads of its agent's memory: the operating note of one version and the reflections that made that
 * version and the two before it (oldest first). Only the agent's own, already filtered text.
 */
public record AgentMemoryDto(
        @ExposedFrom("agent_memory.version")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        int version,
        @ExposedFrom("agent_memory.playbook")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rules of the operating note of this version")
        List<String> playbook,
        @ExposedFrom("agent_reflection.body")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "season-reflection/1 bodies, oldest first, at most 3")
        List<JsonNode> reflections) {
}
