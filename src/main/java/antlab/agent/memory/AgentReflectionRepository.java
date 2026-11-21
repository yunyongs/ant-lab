package antlab.agent.memory;

import antlab.common.persistence.AppendOnlyRepository;

/** AgentReflection append-only repository: insert and read by season id; no update or delete path. */
public interface AgentReflectionRepository extends AppendOnlyRepository<AgentReflection, Long> {

    boolean existsBySeasonId(Long seasonId);
}
