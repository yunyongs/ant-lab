package antlab.collect;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only lookup for stored closes. The point-in-time read (largest vintage_no with observed_at at or before
 * an instant) is not here yet: it selects [복기 전용] columns, so it must follow the reveal naming rule and is
 * added with its first consumer (M1-09) once the reveal-caller list of RevealedRepositoryNamingTest is settled.
 */
public interface AssetDailyPriceRepository extends Repository<AssetDailyPrice, Long> {

    Optional<AssetDailyPrice> findById(Long id);
}
