package antlab.agent.memory;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.List;
import java.util.Optional;

/** AgentMemory append-only repository: insert and the lookups of the version pin and the compare-and-set. */
public interface AgentMemoryRepository extends AppendOnlyRepository<AgentMemory, AgentMemoryId> {

    boolean existsByMemberIdAndVersion(Long memberId, int version);

    Optional<AgentMemory> findFirstByMemberIdOrderByVersionDesc(Long memberId);

    /** The versions {@code from..to} of one agent, oldest first (the playbook of the newest and the reflections of all). */
    List<AgentMemory> findByMemberIdAndVersionBetweenOrderByVersionAsc(Long memberId, int from, int to);
}
