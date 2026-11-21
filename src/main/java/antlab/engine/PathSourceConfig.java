package antlab.engine;

import antlab.engine.client.EngineClient;
import antlab.engine.client.EngineProperties;
import antlab.history.HistoryProperties;
import antlab.history.MonthBlockRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where season paths come from. {@code antlab.engine.path-source=java} (the default) keeps the in-process
 * {@link BootstrapPathSource}; {@code engine} asks the Python engine. Exactly one of the two beans exists.
 */
@Configuration(proxyBeanMethods = false)
class PathSourceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "antlab.engine", name = "path-source", havingValue = "engine")
    PathSource enginePathSource(EngineClient client, EngineProperties properties, MonthBlockRepository monthBlocks,
            HistoryProperties history) {
        return new EnginePathSource(client, properties, monthBlocks, history);
    }
}
