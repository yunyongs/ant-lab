package antlab.common.persistence;

import java.util.Optional;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * 추가 전용 엔티티의 저장소 모양: 넣기와 번호로 읽기만 있다.
 * {@code delete*}·{@code saveAll} 이 없어서 원장 삭제가 컴파일되지 않는다(SPRING_JPA §4.4).
 */
@NoRepositoryBean
public interface AppendOnlyRepository<T, ID> extends Repository<T, ID> {

    <S extends T> S save(S entity);

    Optional<T> findById(ID id);
}
