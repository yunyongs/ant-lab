package antlab.agent;

import antlab.common.exposure.ExposedFrom;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * What an agent sees beside the human turn screen: which reply format to answer in, its own recent fills, and what the
 * engine rejected in its previous decision. {@code memory} is always null until agent memory exists; its type changes then, and the exposure check starts to
 * look inside it.
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
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"null"},
                description = "Reserved for agent memory; always null for now")
        Void memory) {

    public static final String SCHEMA = "turn-decision/1";
}
