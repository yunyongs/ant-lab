package antlab.league;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** League repository: insert, read, and the row lock the close (and, in C2, every entry) takes first. No delete. */
public interface LeagueRepository extends Repository<League, Long> {

    League save(League league);

    Optional<League> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from League l where l.id = :id")
    Optional<League> findByIdForUpdate(@Param("id") Long id);
}
