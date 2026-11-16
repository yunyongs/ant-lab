package antlab.agent;

/** The registered agent and whether this call created it (201) or found it (200). */
public record AgentRegistration(AgentDto agent, boolean created) {
}
