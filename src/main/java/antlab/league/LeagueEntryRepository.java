package antlab.league;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** LeagueEntry append-only repository: insert and the reads of one league's seats. */
public interface LeagueEntryRepository extends AppendOnlyRepository<LeagueEntry, LeagueEntryId> {

    long countByLeagueId(Long leagueId);

    boolean existsByLeagueIdAndMemberId(Long leagueId, Long memberId);

    Optional<LeagueEntry> findFirstByLeagueIdOrderBySeasonId(Long leagueId);

    Optional<LeagueEntry> findBySeasonId(Long seasonId);

    /** True while the season's league is still open: the review of that season is held until the league closes. */
    @Query("""
            select count(e) > 0 from LeagueEntry e join League l on l.id = e.leagueId
            where e.seasonId = :seasonId and l.status = antlab.league.LeagueStatus.OPEN
            """)
    boolean existsOpenLeagueForSeason(@Param("seasonId") Long seasonId);

    /** The seats of a league with the member and the season, in one query, oldest season first. */
    @Query("""
            select new antlab.league.LeagueEntryRow(e.seasonId, m.id, m.kind, m.name, s)
            from LeagueEntry e join Member m on m.id = e.memberId join Season s on s.id = e.seasonId
            where e.leagueId = :leagueId order by e.seasonId
            """)
    List<LeagueEntryRow> findRowsByLeagueId(@Param("leagueId") Long leagueId);

    /** The agent side of the given seats, in one query. Seats of human members have no row. */
    @Query("""
            select new antlab.league.LeagueAiRow(c.seasonId, p.ownerMemberId, p.personaKey, c.personaVersion,
                p.modelRoute, p.modelId, c.memoryVersion)
            from SeasonAgentConfig c join AgentProfile p on p.memberId = c.memberId
            where c.seasonId in :seasonIds
            """)
    List<LeagueAiRow> findAiRowsBySeasonIds(@Param("seasonIds") Collection<Long> seasonIds);

    /** The last-turn valuation of the FINISHED ones among the given seasons, in one query. */
    @Query("""
            select new antlab.league.LeagueFinalValueRow(s.id, t.totalValue)
            from Turn t join t.season s
            where s.id in :seasonIds and s.status = antlab.season.SeasonStatus.FINISHED and t.turnNo = s.pathLength
            """)
    List<LeagueFinalValueRow> findFinalValues(@Param("seasonIds") Collection<Long> seasonIds);
}
