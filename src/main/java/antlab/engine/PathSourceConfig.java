package antlab.engine;

import antlab.engine.client.EngineClient;
import antlab.engine.client.EngineProperties;
import antlab.history.HistoryProperties;
import antlab.history.MonthBlockRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where season paths come from. {@code antlab.engine.path-source=engine} (the default since M2-09) asks the
 * Python engine; {@code java} keeps the in-process {@link BootstrapPathSource} for the test profile and for a local
 * opt-out. Exactly one of the two beans exists. The engine mode needs the URL and token at startup and never falls
 * back to the Java bootstrap when the engine is down (season start answers 503 {@code ENGINE_UNAVAILABLE}).
 */
@Configuration(proxyBeanMethods = false)
class PathSourceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "antlab.engine", name = "path-source", havingValue = "engine", matchIfMissing = true)
    PathSource enginePathSource(EngineClient client, EngineProperties properties, MonthBlockRepository monthBlocks,
            HistoryProperties history) {
        return new EnginePathSource(client, properties, monthBlocks, history);
    }
}
