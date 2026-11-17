package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** TurnFeed append-only repository — insert, read by id, and the stored feed of one turn. */
public interface TurnFeedRepository extends AppendOnlyRepository<TurnFeed, Long> {

    /** The feed of one turn in display order. The season is matched by its foreign key value, without a join. */
    @Query("select f from TurnFeed f where f.season.id = :seasonId and f.turnNo = :turnNo order by f.seq")
    List<TurnFeed> findBySeasonIdAndTurnNoOrderBySeq(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);
}
