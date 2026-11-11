package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** TurnPrice 추가 전용 저장소 — 넣기, 번호로 읽기, 시즌·턴의 가격 목록. */
public interface TurnPriceRepository extends AppendOnlyRepository<TurnPrice, Long> {

    /** 한 종목의 그 턴 가격 한 건. 시즌 연관을 따라가지 않고 외래 키 값으로 조건을 건다. */
    @Query("select p from TurnPrice p where p.season.id = :seasonId and p.turnNo = :turnNo and p.assetId = :assetId")
    Optional<TurnPrice> findBySeasonIdAndTurnNoAndAssetId(@Param("seasonId") Long seasonId,
            @Param("turnNo") int turnNo, @Param("assetId") Long assetId);

    /** 한 턴의 풀 전체 가격(종목 번호 오름차순). 시즌 연관을 따라가지 않고 외래 키 값으로 조건을 건다. */
    @Query("select p from TurnPrice p where p.season.id = :seasonId and p.turnNo = :turnNo order by p.assetId")
    List<TurnPrice> findBySeasonIdAndTurnNoOrderByAssetIdAsc(@Param("seasonId") Long seasonId,
            @Param("turnNo") int turnNo);
}
