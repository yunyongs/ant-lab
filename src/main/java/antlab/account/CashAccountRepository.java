package antlab.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 현금 계좌 저장소. 시즌 락을 얻은 다음에 {@link #findBySeasonIdForUpdate} 로 계좌 행을 잠근다(락 순서 season → cash_account). */
public interface CashAccountRepository extends Repository<CashAccount, Long> {

    CashAccount save(CashAccount account);

    Optional<CashAccount> findById(Long seasonId);

    /** 계좌 행을 비관적 쓰기 락으로 읽는다. PostgreSQL 에서는 {@code FOR NO KEY UPDATE}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CashAccount a where a.seasonId = :seasonId")
    Optional<CashAccount> findBySeasonIdForUpdate(@Param("seasonId") Long seasonId);
}
