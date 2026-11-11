package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** FormationChange 추가 전용 저장소 — 넣기와 번호로 읽기만. 조회 메서드는 쓰는 기능이 더한다. */
public interface FormationChangeRepository extends AppendOnlyRepository<FormationChange, Long> {

    /** 그 시즌·턴의 마지막 전술 변경 — 기록 시각 내림차순, 같으면 번호 내림차순(SQL tr_last_change 와 같은 정렬). */
    @Query("""
            select f from FormationChange f where f.season.id = :seasonId and f.turnNo = :turnNo
            order by f.createdAt desc, f.id desc limit 1""")
    Optional<FormationChange> findLastBySeasonIdAndTurnNo(@Param("seasonId") Long seasonId,
            @Param("turnNo") int turnNo);

    /**
     * 턴 {@code turnNo} <b>이전</b>({@code turn_no < turnNo})의 마지막 전술 변경 — 그 턴 시작 시점의 목표 포메이션 원천.
     * 번호(id) 내림차순이다: db/26 {@code sr_turn_base} 와 같은 정렬이며, 위의 기록 시각 정렬과는 다르다(그대로 둔다).
     */
    @Query("""
            select f from FormationChange f where f.season.id = :seasonId and f.turnNo < :turnNo
            order by f.id desc limit 1""")
    Optional<FormationChange> findLastBefore(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);
}
