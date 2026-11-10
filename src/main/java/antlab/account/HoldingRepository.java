package antlab.account;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 보유 저장소 — 가변 엔티티라 넣기와 읽기. 삭제는 없다(전량 매도 뒤에도 행을 남긴다). 조회 메서드는 쓰는 기능이 더한다. */
public interface HoldingRepository extends Repository<Holding, Long> {

    Holding save(Holding holding);

    Optional<Holding> findById(Long id);
}
