package antlab.account;

import antlab.common.persistence.AppendOnlyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Order 추가 전용 저장소 — 넣기와 번호로 읽기만. 조회 메서드는 쓰는 기능이 더한다. */
public interface OrderRepository extends AppendOnlyRepository<Order, Long> {

    /** 그 시즌·턴의 주문 중 가장 늦은 기록 시각(없으면 빈 값) — 이벤트 시각 가드의 입력. */
    @Query("select max(o.createdAt) from Orders o where o.seasonId = :seasonId and o.turnNo = :turnNo")
    Optional<Instant> findLatestCreatedAt(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);

    /** Fills of the turns {@code fromTurn..toTurn} of a season, oldest first, with the asset's market and ticker. */
    @Query("""
            select new antlab.account.RecentTradeRow(o.turnNo, o.side, a.market, a.ticker, e.quantity, e.fillPrice,
                e.fee, o.source)
            from Orders o
            join Execution e on e.orderId = o.id
            join Asset a on a.id = o.assetId
            where o.seasonId = :seasonId and o.turnNo between :fromTurn and :toTurn
            order by o.turnNo, o.id, e.id""")
    List<RecentTradeRow> findTrades(@Param("seasonId") Long seasonId, @Param("fromTurn") int fromTurn,
            @Param("toTurn") int toTurn);
}
