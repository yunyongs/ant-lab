package antlab.member;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** RatingHistory append-only repository: insert, read by id, and read a member's rows by sequence number. */
public interface RatingHistoryRepository extends AppendOnlyRepository<RatingHistory, Long> {

    /** A member's rows of one pillar, newest first. */
    @Query("select r from RatingHistory r where r.memberId = :memberId and r.pillar = :pillar order by r.seqNo desc")
    List<RatingHistory> findNewestFirst(@Param("memberId") Long memberId, @Param("pillar") RatingPillar pillar);

    /** The member's latest row of a pillar, if any. */
    @Query("select r from RatingHistory r where r.memberId = :memberId and r.pillar = :pillar"
            + " and r.seqNo = (select max(x.seqNo) from RatingHistory x where x.memberId = :memberId and x.pillar = :pillar)")
    Optional<RatingHistory> findLatest(@Param("memberId") Long memberId, @Param("pillar") RatingPillar pillar);

    boolean existsBySeasonId(Long seasonId);
}
