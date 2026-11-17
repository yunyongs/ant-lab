package antlab.projection;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two database steps of {@link ProjectionService}, each in a short transaction of its own so that no connection or
 * lock is held while the engine works. A duplicate insert fails here, rolls this transaction back, and the caller reads
 * the winner's row in a fresh one.
 */
@Component
public class ProjectionStore {

    private final ProjectionRepository projections;

    public ProjectionStore(ProjectionRepository projections) {
        this.projections = projections;
    }

    @Transactional(readOnly = true)
    public Optional<Projection> find(long seasonId, ProjectionPhase phase) {
        return projections.findBySeasonIdAndPhase(seasonId, phase);
    }

    /** One INSERT; the unique key (season, phase) makes a second writer fail with a unique violation. */
    @Transactional
    public Projection insert(Projection projection) {
        return projections.save(projection);
    }
}
