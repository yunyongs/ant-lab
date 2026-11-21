package antlab.history;

import antlab.asset.PoolProperties;
import org.springframework.stereotype.Component;

/** Refuses to start when the replay start does not fit the base date (the two settings are bound separately). */
@Component
class ReplayStartGuard {

    ReplayStartGuard(HistoryProperties history, PoolProperties pool) {
        history.requireBefore(pool.baseDate());
    }
}
