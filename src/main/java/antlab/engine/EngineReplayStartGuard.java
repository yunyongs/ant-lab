package antlab.engine;

import antlab.engine.client.EngineProperties;
import antlab.history.HistoryProperties;
import org.springframework.stereotype.Component;

/**
 * The Python engine uses its own replay start for paths and for the projection months. With the engine wired (engine path
 * source or a URL, which also enables projections) another setting would draw seasons and projections from different ranges,
 * so startup fails until the engine reads the setting (follow-up of MVP-06 B2).
 */
@Component
class EngineReplayStartGuard {

    EngineReplayStartGuard(HistoryProperties history, EngineProperties engine) {
        boolean wired = engine.pathSource() == EngineProperties.PathSourceMode.ENGINE || !engine.url().isBlank();
        if (wired && !EngineProperties.ENGINE_REPLAY_START.equals(history.replayStart())) {
            throw new IllegalStateException("antlab.history.replay-start " + history.replayStart()
                    + " differs from the replay start the engine uses (" + EngineProperties.ENGINE_REPLAY_START
                    + "); unset antlab.engine.url or keep the default until the engine reads the setting");
        }
    }
}
