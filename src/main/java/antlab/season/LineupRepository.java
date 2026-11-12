package antlab.season;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

/** 라인업 저장소 — 가변 엔티티라 넣기와 읽기. 삭제는 없다. 열린 구간 조회 등은 쓰는 기능이 더한다. */
public interface LineupRepository extends Repository<Lineup, Long> {

    Lineup save(Lineup lineup);

    Optional<Lineup> findById(Long id);

    /** 시즌·종목의 열린 구간(끝 턴이 비어 있는 행). */
    @Query("select l from Lineup l where l.season.id = :seasonId and l.assetId = :assetId and l.endTurn is null")
    Optional<Lineup> findOpenBySeasonIdAndAssetId(@Param("seasonId") Long seasonId, @Param("assetId") Long assetId);

    /** 시즌의 라인업 구간 전부(닫힌 구간·길이 없는 구간 포함), 번호 순 — 성적표가 선언 구간을 한 번에 읽는다(P0-38). */
    @Query("select l from Lineup l where l.season.id = :seasonId order by l.id asc")
    List<Lineup> findAllBySeasonId(@Param("seasonId") Long seasonId);

    /** 턴 {@code turnNo} 이전에 시작해 아직 열려 있는 구간 전부 — 그 턴 스냅샷의 포지션별 평가액 입력(erd D26). */
    @Query("select l from Lineup l where l.season.id = :seasonId and l.endTurn is null and l.startTurn < :turnNo")
    List<Lineup> findOpenBySeasonIdStartedBefore(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);
}
