package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Turn 추가 전용 저장소 — 넣기와 번호로 읽기, 고점 계산용 조회, 턴 전환·턴 조회의 직전 총액·범위 조회. */
public interface TurnRepository extends AppendOnlyRepository<Turn, Long> {

    /** 그 턴 이전 턴들의 총 평가액 최댓값(낙폭의 고점 입력). 이전 턴이 없으면 비어 있다. */
    @Query("select max(t.totalValue) from Turn t where t.season.id = :seasonId and t.turnNo < :turnNo")
    Optional<BigDecimal> findMaxTotalValueBefore(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);

    /** 한 턴의 총 평가액 한 건 — 턴 전환이 직전 턴 총액을 읽는다(1문장). 없으면 비어 있다. */
    @Query("select t.totalValue from Turn t where t.season.id = :seasonId and t.turnNo = :turnNo")
    Optional<BigDecimal> findTotalValueBySeasonIdAndTurnNo(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);

    /** 턴 번호 범위의 스냅샷(번호 오름차순, 많아야 둘) — 턴 조회가 그 턴과 직전 턴을 1문장으로 읽는다. */
    @Query("select t from Turn t where t.season.id = :seasonId and t.turnNo between :from and :to order by t.turnNo")
    List<Turn> findBySeasonIdAndTurnNoBetween(@Param("seasonId") Long seasonId, @Param("from") int from,
            @Param("to") int to);
}
