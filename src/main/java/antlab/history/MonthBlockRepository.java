package antlab.history;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 월 블록 조회 전용 저장소. {@code Repository} 만 상속해 save·delete 를 받지 않는다(SPRING_JPA §4.4). */
public interface MonthBlockRepository extends Repository<MonthBlock, Long> {

    Optional<MonthBlock> findById(Long id);

    /**
     * 경로 엔진용 달 목록(month_start 오름차순, 하한 포함·상한 제외). 엔진 내부 전용 —
     * month_start 는 [복기 전용]이라 응답에 싣지 않는다.
     */
    @Query("select new antlab.history.ReplayMonth(m.id, m.monthStart) from MonthBlock m "
            + "where m.monthStart >= :from and m.monthStart < :to order by m.monthStart")
    List<ReplayMonth> findReplayMonths(@Param("from") LocalDate fromInclusive, @Param("to") LocalDate toExclusive);
}
