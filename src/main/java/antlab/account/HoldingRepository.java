package antlab.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 보유 저장소 — 가변 엔티티라 넣기와 읽기. 삭제는 없다(전량 매도 뒤에도 행을 남긴다). 조회 메서드는 쓰는 기능이 더한다. */
public interface HoldingRepository extends Repository<Holding, Long> {

    Holding save(Holding holding);

    Optional<Holding> findById(Long id);

    /** 시즌·종목의 보유 한 행. 잠그지 않는다 — 시즌 행 락 아래에서만 바뀌므로 이미 직렬화되어 있다. */
    Optional<Holding> findBySeasonIdAndAssetId(Long seasonId, Long assetId);

    /** 시즌의 보유 전부(수량 0 포함) — 턴 스냅샷이 한 번에 읽는다. */
    List<Holding> findBySeasonId(Long seasonId);

    /**
     * 수량 > 0 인 보유 × 그 턴 가격 × 열린 라인업(구간 시작 턴과 무관 — 이번 턴에 산 종목 포함)을 한 문장으로, 종목 번호 순.
     * 가격·라인업이 없으면 null 로 와서 서비스가 데이터 손상으로 멈춘다(안쪽 조인이면 조용히 빠진다).
     */
    @Query("""
            select new antlab.account.HoldingViewRow(h.assetId, h.quantity, h.avgPrice, tp.price, l.declaredPosition)
            from Holding h
            left join TurnPrice tp on tp.season.id = h.seasonId and tp.assetId = h.assetId and tp.turnNo = :turnNo
            left join Lineup l on l.season.id = h.seasonId and l.assetId = h.assetId and l.endTurn is null
            where h.seasonId = :seasonId and h.quantity > 0
            order by h.assetId""")
    List<HoldingViewRow> findViewRows(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);
}
