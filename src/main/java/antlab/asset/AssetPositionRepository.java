package antlab.asset;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 종목 기준일 판정 조회 전용 저장소. {@code Repository} 만 상속해 save·delete 를 받지 않는다(SPRING_JPA §4.4). */
public interface AssetPositionRepository extends Repository<AssetPosition, Long> {

    Optional<AssetPosition> findById(Long id);

    /**
     * 기준일 풀 전체를 SQL 한 번으로 읽는다(종목·그룹과 번호로 조인, 연관 매핑 없음).
     * 순서는 정하지 않는다 — 정렬은 서비스가 코드 단위로 한다(DB 콜레이션에 기대지 않는다).
     */
    @Query("""
            select new antlab.asset.PoolAssetDto(
                a.id, a.market, a.ticker, a.name, a.assetType, a.returnBasis, a.proxyIndexName,
                g.id, g.name, g.kind,
                p.systemPosition, p.upParticipation, p.downParticipation, p.volatility, p.maxDrawdown)
            from AssetPosition p
            join Asset a on a.id = p.assetId
            join AssetGroup g on g.id = a.assetGroupId
            where p.baseDate = :baseDate
            """)
    List<PoolAssetDto> findPoolByBaseDate(@Param("baseDate") LocalDate baseDate);

    /** 기준일 풀의 판정 행 전부(시즌 시작이 기준가·환율로 0턴 가격을 만든다). 순서는 종목 번호 오름차순. */
    @Query("select p from AssetPosition p where p.baseDate = :baseDate order by p.assetId")
    List<AssetPosition> findByBaseDate(@Param("baseDate") LocalDate baseDate);
}
