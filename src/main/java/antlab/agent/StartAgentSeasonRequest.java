package antlab.agent;

import antlab.season.AllowedRiskLimit;
import antlab.season.FormationDto;
import antlab.season.StartSeasonRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** The fields of {@link StartSeasonRequest} plus the agent's season config. */
public record StartAgentSeasonRequest(
        @NotNull @Digits(integer = 1, fraction = 8) @AllowedRiskLimit
        BigDecimal riskLimit,
        @NotNull @Valid
        FormationDto formation,
        Integer pathLength,
        Long leagueId,
        @NotNull @Valid
        AgentSeasonConfigRequest config) {

    StartSeasonRequest season() {
        return new StartSeasonRequest(riskLimit, formation, pathLength, leagueId);
    }
}
