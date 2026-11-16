package antlab.agent;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** AgentCall append-only repository: insert, read by id, and read by season and turn. */
public interface AgentCallRepository extends AppendOnlyRepository<AgentCall, Long> {

    /** The turn's final call (outcome recorded), if the turn has been decided. */
    @Query("select c from AgentCall c where c.seasonId = :seasonId and c.turnNo = :turnNo and c.finalCall = true")
    Optional<AgentCall> findFinalBySeasonIdAndTurnNo(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);

    /** All attempts of a turn in attempt order. */
    @Query("select c from AgentCall c where c.seasonId = :seasonId and c.turnNo = :turnNo order by c.attemptNo asc")
    List<AgentCall> findAllBySeasonIdAndTurnNo(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);
}
