package antlab.agent.memory;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.Optional;

/** AgentMemory append-only repository: insert and the lookups of the version pin and the compare-and-set. */
public interface AgentMemoryRepository extends AppendOnlyRepository<AgentMemory, AgentMemoryId> {

    boolean existsByMemberIdAndVersion(Long memberId, int version);

    Optional<AgentMemory> findFirstByMemberIdOrderByVersionDesc(Long memberId);
}
