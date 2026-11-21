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

    /**
     * Review only: every stored item of a finished season as shown, with its stored kind, the event tag it was built from and
     * the number of orders that reference it, turn then display order. Reads {@code [복기 전용]} values, hence the name;
     * {@code SeasonReviewService} is the only caller and calls it after the FINISHED check. No row or event id is projected.
     */
    @Query("select new antlab.season.RevealedFeedRow(f.turnNo, f.seq, f.kind, f.assetId, f.assetGroupId, f.body, "
            + "me.category, me.eventType, me.phase, me.title, me.hindsight, me.source, "
            + "(select count(o) from Orders o where o.turnFeedId = f.id)) "
            + "from TurnFeed f left join MonthEvent me on me.id = f.monthEventId "
            + "where f.season.id = :seasonId order by f.turnNo, f.seq")
    List<RevealedFeedRow> findRevealedFeed(@Param("seasonId") long seasonId);

    /**
     * Reflection view only: the feed items that orders of a finished season cited (order turn, display number, stored kind),
     * in order. Reads the {@code [복기 전용]} kind, hence the name; {@code ReflectionTurnReader} is the only caller and calls
     * it after the FINISHED and release checks.
     */
    @Query("select new antlab.season.CitedFeedRow(o.turnNo, f.turnNo, f.seq, f.kind) from Orders o join TurnFeed f on f.id = o.turnFeedId "
            + "where o.seasonId = :seasonId order by o.turnNo, o.id")
    List<CitedFeedRow> findRevealedCitedItems(@Param("seasonId") long seasonId);
}
