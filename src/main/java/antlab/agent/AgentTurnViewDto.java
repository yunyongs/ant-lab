package antlab.agent;

import antlab.account.HoldingsDto;
import antlab.asset.PoolAssetDto;
import antlab.season.SeasonDto;
import antlab.season.TurnPricesDto;
import antlab.season.TurnResultDto;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * The turn screen of an agent: the values of the human turn endpoints, unchanged, plus {@link AgentPartDto}.
 * Nothing the human turn screen does not show is added.
 */
public record AgentTurnViewDto(
        @Schema(requiredMode = RequiredMode.REQUIRED)
        SeasonDto season,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"object", "null"},
                description = "Result of the current turn; null at turn 0")
        TurnResultDto lastTurn,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        HoldingsDto holdings,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        TurnPricesDto prices,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        List<PoolAssetDto> pool,
        @Schema(requiredMode = RequiredMode.REQUIRED)
        AgentPartDto agent) {
}
