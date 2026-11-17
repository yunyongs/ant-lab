package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** TurnFeed append-only repository — insert, read by id, and the stored feed of one turn. */
public interface TurnFeedRepository extends AppendOnlyRepository<TurnFeed, Long> {

    /** The feed of one turn in display order. The season is matched by its foreign key value, without a join. */
    @Query("select f from TurnFeed f where f.season.id = :seasonId and f.turnNo = :turnNo order by f.seq")
    List<TurnFeed> findBySeasonIdAndTurnNoOrderBySeq(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);

    /**
     * One stored item by its public reference (turn, display order) within one season, for the order source rule. The season
     * is part of the lookup key, so another season's item is simply not found. Named for the reveal-only column it reads
     * ({@code kind}); the caller gets a yes/no for "alert" and nothing else about it.
     */
    @Query("select new antlab.season.FeedSourceRow(f.id, f.assetId, case when f.kind = antlab.season.FeedKind.NUDGE then true else false end) "
            + "from TurnFeed f where f.season.id = :seasonId and f.turnNo = :turnNo and f.seq = :seq")
    Optional<FeedSourceRow> findRevealedSourceItem(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo,
            @Param("seq") int seq);
}
