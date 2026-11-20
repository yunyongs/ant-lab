package antlab.league;

import antlab.agent.ModelRoute;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** What the table shows of an agent seat: the persona and model it played with. No prompt, memory text or seed. */
@Schema(description = "The persona and model an agent seat played with / 에이전트 자리가 쓴 페르소나와 모델")
public record LeagueTableAiDto(
        @ExposedFrom("agent_profile.persona_key")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Persona key / 페르소나 키")
        String personaKey,
        @ExposedFrom("season_agent_config.persona_version")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Persona version this season was pinned to / 이 시즌이 고정된 페르소나 버전")
        Integer personaVersion,
        @ExposedFrom("agent_profile.model_route")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        ModelRoute modelRoute,
        @ExposedFrom("agent_profile.model_id")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Model identifier / 모델 식별자")
        String modelId,
        @ExposedFrom("season_agent_config.memory_version")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "Memory version. null while the agent has no memory (AG-05) / 메모리 버전. 메모리가 없으면 null")
        Integer memoryVersion) {
}
