package antlab.leaderboard;

import antlab.engine.client.EngineProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The manager parts schedule, present only with {@code antlab.leaderboard.parts-job.enabled=true}. The default scheduler
 * has one thread and the task has a fixed delay, so two ticks never overlap; the advisory lock in the service keeps an
 * administrator run and a tick apart. Startup fails when the engine timeout of the parts call is below
 * {@link ManagerPartsService#MIN_ENGINE_TIMEOUT}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "antlab.leaderboard.parts-job", name = "enabled", havingValue = "true")
class ManagerPartsJobConfig implements SchedulingConfigurer {

    private final ManagerPartsJobProperties properties;
    private final ManagerPartsJob job;

    ManagerPartsJobConfig(ManagerPartsJobProperties properties, ManagerPartsService service, Clock clock,
            EngineProperties engine) {
        if (engine.regimeTimeout().compareTo(ManagerPartsService.MIN_ENGINE_TIMEOUT) < 0) {
            throw new IllegalStateException("antlab.leaderboard.parts-job needs antlab.engine.regime-timeout of at least "
                    + ManagerPartsService.MIN_ENGINE_TIMEOUT.toSeconds() + "s");
        }
        this.properties = properties;
        this.job = new ManagerPartsJob(service, clock, properties);
    }

    @Bean
    ManagerPartsJob managerPartsJob() {
        return job;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // the first tick waits one delay too, so a starting api is not busy with the engine at once
        registrar.addFixedDelayTask(new FixedDelayTask(job::tick, properties.delay(), properties.delay()));
    }
}
