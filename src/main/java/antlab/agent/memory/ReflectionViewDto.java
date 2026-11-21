package antlab.agent.memory;

import antlab.account.OrderSide;
import antlab.agent.RejectDto;
import antlab.common.exposure.ExposedFrom;
import antlab.scoring.SeasonReportDto;
import antlab.scoring.SeasonScoreDto;
import antlab.season.FeedKind;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * What an agent may see of a finished season when it writes its reflection: the report card and the stored score, its
 * own decisions with relative turn labels, and the kind of the feed items its orders cited. No date, month, event
 * name, seed or price is carried.
 */
public record ReflectionViewDto(
        @ExposedFrom("agent_memory.version")
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"integer", "null"},
                description = "The agent's latest memory version the new one is built on; null for the first")
        Integer baseVersion,
        @ExposedFrom("agent_memory.playbook")
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rules of the base operating note; empty without a base")
        List<String> basePlaybook,
        @ExposedFrom("season.path_length")
        @Schema(requiredMode = RequiredMode.REQUIRED)
        int pathLength,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        SeasonReportDto report,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        SeasonScoreDto score,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Decision turns 0 to pathLength - 1, in order")
        List<Turn> turns) {

    /** One decision turn of the season. */
    public record Turn(
            @ExposedFrom("turn.turn_no")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            int turnNo,
            @ExposedFrom({})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Relative label such as 1년차 7월")
            String label,
            @ExposedFrom("agent_call.parsed")
            @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                    description = "The agent's own stated reason for the turn; null when it made no usable decision")
            String reason,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            List<Fill> fills,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            List<RejectDto> rejects,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            List<Cited> cited) {
    }

    /** One fill: no price, because a price dates a month. */
    public record Fill(
            @ExposedFrom({"asset.market", "asset.ticker"})
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "MARKET:TICKER, for example KR:005930")
            String asset,
            @ExposedFrom("orders.side")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            OrderSide side,
            @ExposedFrom("execution.quantity")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            int quantity) {
    }

    /** A feed item that an order of the turn cited: its turn, display number and kind, never its text. */
    public record Cited(
            @ExposedFrom("turn_feed.turn_no")
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Turn the item was shown in; an order may cite an earlier turn's item")
            int feedTurnNo,
            @ExposedFrom("turn_feed.seq")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            int seq,
            @ExposedFrom("turn_feed.kind")
            @Schema(requiredMode = RequiredMode.REQUIRED)
            FeedKind kind) {
    }
}
