package antlab.scoring;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** The four position slots of a turn contribution. */
@Schema(description = "The four positions. Attacker covers the three attacker kinds; goalkeeper includes cash / 네 "
        + "포지션. 공격수는 공격수 세 종류를 합한 것이고 골키퍼는 현금을 포함한다")
public record PositionContributionsDto(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Goalkeeper (cash included) / 골키퍼(현금 포함)")
        PositionContributionDto goalkeeper,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Defender / 수비수")
        PositionContributionDto defender,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Midfielder / 미드필더")
        PositionContributionDto midfielder,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Attacker / 공격수")
        PositionContributionDto forward) {
}
