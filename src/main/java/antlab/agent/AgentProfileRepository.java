package antlab.agent;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** AgentProfile repository: insert, read, and the identity lookup the runner registration needs. No delete. */
public interface AgentProfileRepository extends Repository<AgentProfile, Long> {

    AgentProfile save(AgentProfile profile);

    Optional<AgentProfile> findById(Long memberId);

    /**
     * The agent's row under a write lock, the serialization point of one agent's memory writes (a version chain is
     * written one at a time). Hibernate sends {@code FOR NO KEY UPDATE} on PostgreSQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AgentProfile p where p.memberId = :memberId")
    Optional<AgentProfile> findByIdForUpdate(@Param("memberId") Long memberId);

    Optional<AgentProfile> findByOwnerMemberIdAndPersonaKeyAndModelRouteAndModelId(Long ownerMemberId,
            String personaKey, ModelRoute modelRoute, String modelId);

    /** The owner's agents with their member names, in one query, oldest agent first. */
    @Query("""
            select new antlab.agent.AgentListRow(p.memberId, m.name, p.personaKey, p.modelRoute, p.modelId, p.status)
            from AgentProfile p join Member m on m.id = p.memberId
            where p.ownerMemberId = :ownerId order by p.memberId
            """)
    List<AgentListRow> findRowsByOwner(@Param("ownerId") Long ownerId);
}
