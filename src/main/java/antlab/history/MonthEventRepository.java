package antlab.history;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 달 사건 태그 조회 전용 저장소. {@code Repository} 만 상속해 save·delete 를 받지 않는다(SPRING_JPA §4.4). */
public interface MonthEventRepository extends Repository<MonthEvent, Long> {

    Optional<MonthEvent> findById(Long id);

    /** Every distinct title ([복기 전용]): only the memory leak filter reads it, to treat an event title as a leak term. */
    @Query("select distinct e.title from MonthEvent e")
    List<String> findRevealedTitles();
}
