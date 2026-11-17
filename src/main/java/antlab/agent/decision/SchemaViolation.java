package antlab.agent.decision;

/** Where a reply broke the shape of {@code turn-decision/1}: a JSON pointer and the keyword; never the offending value. */
public record SchemaViolation(String pointer, String keyword) {
}
