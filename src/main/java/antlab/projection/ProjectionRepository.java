package antlab.projection;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Projection append-only repository: insert, read by id, and read the one row of a season and phase. */
public interface ProjectionRepository extends AppendOnlyRepository<Projection, Long> {

    @Query("select p from Projection p where p.seasonId = :seasonId and p.phase = :phase")
    Optional<Projection> findBySeasonIdAndPhase(@Param("seasonId") Long seasonId, @Param("phase") ProjectionPhase phase);
}
