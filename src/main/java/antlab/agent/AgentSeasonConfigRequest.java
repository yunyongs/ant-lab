package antlab.agent;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The settings an agent season starts with, written once as {@code season_agent_config}. Persona key and model route
 * come from the agent's profile, not from here. The rules mirror the CHECK constraints of that table.
 */
public record AgentSeasonConfigRequest(
        @Min(1)
        int personaVersion,
        @NotNull @Pattern(regexp = "^[0-9a-f]{64}$")
        String personaSha256,
        @NotBlank @Size(max = 50) @Pattern(regexp = "^\\S(.*\\S)?$")
        String personaDisplayName,
        @NotNull @Size(max = 200) @Pattern(regexp = "^\\S(.*\\S)?$")
        String modelRequested,
        @NotNull @DecimalMin("0") @DecimalMax("2") @Digits(integer = 1, fraction = 3)
        BigDecimal temperature,
        Long modelSeed,
        @Positive
        int maxTokens,
        @Positive
        int turnTimeoutMs,
        @NotBlank @Size(max = 32)
        String promptTemplateVersion,
        @NotBlank @Size(max = 32)
        String runnerVersion,
        @Min(1)
        Integer memoryVersion,
        @Positive
        int budgetCalls,
        @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 8)
        BigDecimal budgetUsd) {
}
