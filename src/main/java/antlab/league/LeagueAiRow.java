package antlab.league;

import antlab.agent.ModelRoute;

/** The agent side of a seat as the query returns it; {@code ownerMemberId} is used for the table and never exposed. */
public record LeagueAiRow(Long seasonId, Long ownerMemberId, String personaKey, int personaVersion,
        ModelRoute modelRoute, String modelId, Integer memoryVersion) {
}
