package antlab.agent;

/** An agent of the owner with its member name, as the query returns it. */
public record AgentListRow(Long agentId, String displayName, String personaKey, ModelRoute modelRoute, String modelId,
        AgentStatus status) {
}
