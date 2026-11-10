package antlab.season;

import jakarta.persistence.LockModeType;
import java.util.List;
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

    long countByMemberIdAndStatusNot(Long memberId, SeasonStatus status);

    Optional<Season> findByIdAndMemberId(Long id, Long memberId);

    /** 회원의 시즌 전부와 잔액을 질의 한 번으로, 시즌 번호 내림차순. */
    @Query("""
            select new antlab.season.SeasonWithBalance(s, a.balance)
            from Season s join CashAccount a on a.seasonId = s.id
            where s.memberId = :memberId order by s.id desc
            """)
    List<SeasonWithBalance> findWithBalanceByMemberId(@Param("memberId") Long memberId);
}
