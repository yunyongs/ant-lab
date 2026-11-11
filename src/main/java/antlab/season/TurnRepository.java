package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Turn 추가 전용 저장소 — 넣기와 번호로 읽기, 고점 계산용 조회. */
public interface TurnRepository extends AppendOnlyRepository<Turn, Long> {

    /** 그 턴 이전 턴들의 총 평가액 최댓값(낙폭의 고점 입력). 이전 턴이 없으면 비어 있다. */
    @Query("select max(t.totalValue) from Turn t where t.season.id = :seasonId and t.turnNo < :turnNo")
    Optional<BigDecimal> findMaxTotalValueBefore(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);
}
