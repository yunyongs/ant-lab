package antlab.account;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** CashLedger 추가 전용 저장소 — 넣기와 번호로 읽기만. 조회 메서드는 쓰는 기능이 더한다. */
public interface CashLedgerRepository extends AppendOnlyRepository<CashLedger, Long> {

    /** 시즌 계좌의 가장 마지막 원장 한 행(번호가 가장 큰 행). */
    @Query("select l from CashLedger l where l.cashAccount.seasonId = :seasonId order by l.id desc limit 1")
    Optional<CashLedger> findLatestBySeasonId(@Param("seasonId") Long seasonId);
}
