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

    /**
     * 회원 조건을 포함해 시즌 행을 한 번에 잠근다 — 소유 확인과 잠금을 한 조회로 한다. 잠그지 않은 조회로 시즌을 먼저
     * 영속성 컨텍스트에 올리면 뒤의 잠금 조회가 같은 객체를 돌려줘 옛 상태를 볼 수 있기 때문이다(P0-31 실측 M1).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Season s where s.id = :id and s.memberId = :memberId")
    Optional<Season> findByIdAndMemberIdForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);

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
