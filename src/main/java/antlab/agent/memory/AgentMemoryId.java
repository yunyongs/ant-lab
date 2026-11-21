package antlab.agent.memory;

import java.io.Serializable;

/** Key of {@link AgentMemory}: the agent and the memory version. */
public record AgentMemoryId(Long memberId, int version) implements Serializable {
}
