package antlab.agent;

import java.math.BigDecimal;

/**
 * The settings an agent season was started with, as recorded. The runner compares them with its own before it sends
 * anything: a run with another persona, model, route or prompt template must not continue the season.
 */
public record AgentSeasonConfigDto(
        String personaKey,
        int personaVersion,
        String personaSha256,
        ModelRoute modelRoute,
        String modelRequested,
        String promptTemplateVersion,
        String runnerVersion,
        Integer memoryVersion,
        BigDecimal temperature,
        Long modelSeed,
        int maxTokens,
        int turnTimeoutMs,
        int budgetCalls,
        BigDecimal budgetUsd) {

    static AgentSeasonConfigDto of(SeasonAgentConfig c) {
        return new AgentSeasonConfigDto(c.getPersonaKey(), c.getPersonaVersion(), c.getPersonaSha256(),
                c.getModelRoute(), c.getModelRequested(), c.getPromptTemplateVersion(), c.getRunnerVersion(),
                c.getMemoryVersion(), c.getTemperature(), c.getModelSeed(), c.getMaxTokens(), c.getTurnTimeoutMs(),
                c.getBudgetCalls(), c.getBudgetUsd());
    }
}
