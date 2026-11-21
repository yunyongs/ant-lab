package antlab.agent;

/** The stored result of a turn's final decision call without the request and reply texts (up to 256 KiB each). */
public record AgentFinalCallRow(int turnNo, DecisionOutcome outcome, String parsed, String rejects) {
}
