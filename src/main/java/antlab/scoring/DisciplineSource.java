package antlab.scoring;

import java.util.Optional;

/**
 * Where the discipline facts of a season come from. Nothing measures them yet, so the default bean answers empty and
 * the discipline item stays null; the feature that measures them provides the real bean.
 */
public interface DisciplineSource {

    Optional<DisciplineRatios> of(long seasonId);
}
