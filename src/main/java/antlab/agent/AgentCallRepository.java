package antlab.agent;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** AgentCall append-only repository: insert, read by id, and read by season and turn. */
public interface AgentCallRepository extends AppendOnlyRepository<AgentCall, Long> {

    /** The turn's final call of a kind (outcome recorded), if the turn has been decided. */
    @Query("select c from AgentCall c where c.seasonId = :seasonId and c.turnNo = :turnNo and c.callKind = :kind"
            + " and c.finalCall = true")
    Optional<AgentCall> findFinalBySeasonIdAndTurnNo(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo,
            @Param("kind") AgentCallKind kind);

    /**
     * Only the stored reject list of the turn's final call of a kind: the request and reply texts (up to 256 KiB
     * each) stay in the database. Empty when the turn has no final call or the final call has no rejects.
     */
    @Query("select c.rejects from AgentCall c where c.seasonId = :seasonId and c.turnNo = :turnNo"
            + " and c.callKind = :kind and c.finalCall = true")
    Optional<String> findFinalRejects(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo,
            @Param("kind") AgentCallKind kind);

    /** True when a stored call already carries this ledger call id (unique across seasons). */
    boolean existsByLedgerCallId(java.util.UUID ledgerCallId);

    /** All attempts of a turn in attempt order. */
    @Query("select c from AgentCall c where c.seasonId = :seasonId and c.turnNo = :turnNo order by c.attemptNo asc")
    List<AgentCall> findAllBySeasonIdAndTurnNo(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);

    /** Turns whose final call ended without a usable decision, per agent, in one grouped query. */
    @Query("""
            select new antlab.agent.AgentFailedTurnRow(s.memberId, count(c))
            from AgentCall c join Season s on s.id = c.seasonId
            where s.memberId in :memberIds and c.finalCall = true
                and c.outcome in (antlab.agent.DecisionOutcome.NO_RESPONSE, antlab.agent.DecisionOutcome.INVALID)
            group by s.memberId
            """)
    List<AgentFailedTurnRow> findFailedTurnCounts(@Param("memberIds") Collection<Long> memberIds);
}
