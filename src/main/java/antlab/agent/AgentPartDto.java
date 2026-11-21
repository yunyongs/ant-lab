package antlab.agent;

import antlab.agent.memory.AgentMemoryDto;
import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * What an agent sees beside the human turn screen: which reply format to answer in, its own recent fills, and what the
 * engine rejected in its previous decision, and {@code memory}: the one memory version the season pinned at its start
 * (null when it pinned none). The exposure check looks inside {@code memory}.
 */
public record AgentPartDto(
        @ExposedFrom({})
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Reply format to answer in")
        String schema,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Own fills of the last turns, oldest first")
        List<RecentTradeDto> recentTrades,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Units the engine rejected in the previous turn's decision")
        List<RejectDto> lastRejects,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                description = "The memory version this season was pinned to at its start; null without a pin")
        AgentMemoryDto memory) {

    public static final String SCHEMA = "turn-decision/1";
}
