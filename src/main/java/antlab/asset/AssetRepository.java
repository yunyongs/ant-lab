package antlab.asset;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 종목 조회 전용 저장소. {@code Repository} 만 상속해 save·delete 를 받지 않는다(SPRING_JPA §4.4). */
public interface AssetRepository extends Repository<Asset, Long> {

    Optional<Asset> findById(Long id);

    /** 번호 목록으로 한 문장에 읽는다 — 성적표가 풀 종목의 자연 키(시장, 티커)를 붙인다(P0-38). */
    List<Asset> findAllById(Iterable<Long> ids);
}
