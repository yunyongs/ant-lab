package antlab.season;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 시즌 저장소. 쓰기 작업의 첫 단계는 {@link #findByIdForUpdate} 로 시즌 행을 잠그는 것이다(SPRING_JPA §5.2). */
public interface SeasonRepository extends Repository<Season, Long> {

    Season save(Season season);

    Optional<Season> findById(Long id);

    /**
     * 시즌 행을 비관적 쓰기 락으로 읽는다. PostgreSQL 에서 Hibernate 는 {@code FOR NO KEY UPDATE} 를 낸다 —
     * 같은 행의 같은 락끼리 기다리므로 주문·턴 전환은 직렬화된다. 락 순서는 season → cash_account.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Season s where s.id = :id")
    Optional<Season> findByIdForUpdate(@Param("id") Long id);
}
