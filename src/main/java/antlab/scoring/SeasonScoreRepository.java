package antlab.scoring;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** SeasonScore append-only repository: insert, read by season id, and read a member's scores. */
public interface SeasonScoreRepository extends AppendOnlyRepository<SeasonScore, Long> {

    boolean existsBySeasonId(Long seasonId);

    /** A member's scores, oldest season first; the rating order is decided by the rating rows, not by this list. */
    @Query("select s from SeasonScore s where s.memberId = :memberId order by s.seasonId")
    List<SeasonScore> findByMemberId(@Param("memberId") Long memberId);

    @Query("select s from SeasonScore s where s.seasonId = :seasonId and s.memberId = :memberId")
    Optional<SeasonScore> findBySeasonIdAndMemberId(@Param("seasonId") Long seasonId, @Param("memberId") Long memberId);
}
