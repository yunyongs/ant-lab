package antlab.asset;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 종목 그룹 조회 전용 저장소. {@code Repository} 만 상속해 save·delete 를 받지 않는다(SPRING_JPA §4.4). */
public interface AssetGroupRepository extends Repository<AssetGroup, Long> {

    Optional<AssetGroup> findById(Long id);
}
