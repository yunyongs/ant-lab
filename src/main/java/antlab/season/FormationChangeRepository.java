package antlab.season;

import antlab.common.persistence.AppendOnlyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** FormationChange 추가 전용 저장소 — 넣기와 번호로 읽기만. 조회 메서드는 쓰는 기능이 더한다. */
public interface FormationChangeRepository extends AppendOnlyRepository<FormationChange, Long> {

    /**
     * 그 시즌·턴의 마지막 전술 변경 — 번호(id) 내림차순. 주문이 INSERT 때 묶을 변경이다.
     * 정상 쓰기에서는 시즌 잠금과 시각 가드가 같은 턴의 번호 순서와 시각 순서를 같게 만든다(SQL tr_last_change 의
     * 기록 시각 정렬과 같은 결과). 시각을 변조한 데이터까지 같다고 주장하지 않는다 — 그 검출은 대사 규칙 16 의 몫이다.
     */
    @Query("""
            select f from FormationChange f where f.season.id = :seasonId and f.turnNo = :turnNo
            order by f.id desc limit 1""")
    Optional<FormationChange> findLastBySeasonIdAndTurnNo(@Param("seasonId") Long seasonId,
            @Param("turnNo") int turnNo);

    /**
     * 시즌의 전술 변경 전부, 번호(id) 오름차순 — 성적표가 한 번 읽어 메모리에서 턴마다 목표를 고른다(P0-38).
     * 턴 t 시작 시점의 목표는 이 목록에서 {@code turn_no < t} 인 마지막 항목이며 {@code findLastBefore} 와 같은 규칙이다.
     */
    @Query("select f from FormationChange f where f.season.id = :seasonId order by f.id asc")
    List<FormationChange> findAllBySeasonId(@Param("seasonId") Long seasonId);

    /** 시즌 전체의 마지막 전술 변경 — 번호(id) 내림차순. 현재 포메이션 사슬의 끝(규칙 15)이다. */
    @Query("""
            select f from FormationChange f where f.season.id = :seasonId
            order by f.id desc limit 1""")
    Optional<FormationChange> findLastBySeasonId(@Param("seasonId") Long seasonId);

    /** 그 시즌·턴의 전술 변경 중 가장 늦은 기록 시각(없으면 빈 값) — 이벤트 시각 가드의 입력. */
    @Query("select max(f.createdAt) from FormationChange f where f.season.id = :seasonId and f.turnNo = :turnNo")
    Optional<Instant> findLatestCreatedAt(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);

    /**
     * 턴 {@code turnNo} <b>이전</b>({@code turn_no < turnNo})의 마지막 전술 변경 — 그 턴 시작 시점의 목표 포메이션 원천.
     * 번호(id) 내림차순이다: db/26 {@code sr_turn_base} 와 같은 정렬이며, 위의 기록 시각 정렬과는 다르다(그대로 둔다).
     */
    @Query("""
            select f from FormationChange f where f.season.id = :seasonId and f.turnNo < :turnNo
            order by f.id desc limit 1""")
    Optional<FormationChange> findLastBefore(@Param("seasonId") Long seasonId, @Param("turnNo") int turnNo);
}
