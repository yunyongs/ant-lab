package antlab.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 보유 저장소 — 가변 엔티티라 넣기와 읽기. 삭제는 없다(전량 매도 뒤에도 행을 남긴다). 조회 메서드는 쓰는 기능이 더한다. */
public interface HoldingRepository extends Repository<Holding, Long> {

    Holding save(Holding holding);

    Optional<Holding> findById(Long id);

    /** 시즌·종목의 보유 한 행. 잠그지 않는다 — 시즌 행 락 아래에서만 바뀌므로 이미 직렬화되어 있다. */
    Optional<Holding> findBySeasonIdAndAssetId(Long seasonId, Long assetId);

    /** 시즌의 보유 전부(수량 0 포함) — 턴 스냅샷이 한 번에 읽는다. */
    List<Holding> findBySeasonId(Long seasonId);
}
