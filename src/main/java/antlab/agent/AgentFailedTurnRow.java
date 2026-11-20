package antlab.agent;

/** The turns of one agent whose final model call produced no usable decision. */
public record AgentFailedTurnRow(Long agentId, long failedTurns) {
}
