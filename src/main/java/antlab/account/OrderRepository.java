package antlab.account;

import antlab.common.persistence.AppendOnlyRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Order 추가 전용 저장소 — 넣기와 번호로 읽기만. 조회 메서드는 쓰는 기능이 더한다. */
public interface OrderRepository extends AppendOnlyRepository<Order, Long> {

    /** 그 시즌·턴의 주문 중 가장 늦은 기록 시각(없으면 빈 값) — 이벤트 시각 가드의 입력. */
    @Query("select max(o.createdAt) from Orders o where o.seasonId = :seasonId and o.turnNo = :turnNo")
    Optional<Instant> findLatestCreatedAt(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);
}
