package antlab.agent;

/** What the runner learns about an agent. */
public record AgentDto(long agentId, String displayName, String personaKey, ModelRoute modelRoute, String modelId,
        AgentStatus status, long ownerMemberId) {
}
