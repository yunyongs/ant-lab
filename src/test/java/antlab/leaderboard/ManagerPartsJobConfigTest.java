package antlab.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.client.EngineProperties;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The schedule exists only with the flag on, ticks with a fixed delay from the setting, and refuses to start when the
 * engine timeout of the parts call is below 30 s. The service is a shell here: nothing ticks during the test.
 */
class ManagerPartsJobConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ManagerPartsJobProperties.class)
    static class Settings {
    }

    private static EngineProperties engine(Duration regimeTimeout) {
        return new EngineProperties("", "", Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15), regimeTimeout,
                EngineProperties.PathSourceMode.JAVA, "m2-sb6-v1", 2000);
    }

    private static ApplicationContextRunner runner(Duration regimeTimeout) {
        EngineProperties properties = engine(regimeTimeout);
        return new ApplicationContextRunner()
                .withUserConfiguration(Settings.class, ManagerPartsJobConfig.class)
                .withBean(EngineProperties.class, () -> properties)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(ManagerPartsService.class,
                        () -> new ManagerPartsService(null, null, null, null, null, null, properties, null));
    }

    @Test
    void withTheFlagOffThereIsNoJobAndNoSchedule() {
        runner(Duration.ofSeconds(60)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ManagerPartsJob.class);
            assertThat(context).doesNotHaveBean(ManagerPartsJobConfig.class);
            assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class);
        });
        runner(Duration.ofSeconds(60)).withPropertyValues("antlab.leaderboard.parts-job.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ManagerPartsJob.class));
    }

    @Test
    void withTheFlagOnOneFixedDelayTaskTicksAtTheSetting() {
        runner(Duration.ofSeconds(60))
                .withPropertyValues("antlab.leaderboard.parts-job.enabled=true", "antlab.leaderboard.parts-job.delay=90s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ManagerPartsJob.class);
                    var tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                            .flatMap(holder -> holder.getScheduledTasks().stream()).map(ScheduledTask::getTask).toList();
                    assertThat(tasks).singleElement().isInstanceOfSatisfying(FixedDelayTask.class, task -> {
                        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(90));
                        assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(90));
                    });
                });
    }

    @Test
    void aTimeoutBelowThirtySecondsStopsTheStartWhileTheJobIsOn() {
        runner(Duration.ofSeconds(29)).withPropertyValues("antlab.leaderboard.parts-job.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("regime-timeout of at least 30s");
        });
        runner(Duration.ofSeconds(29)).run(context -> assertThat(context).hasNotFailed());
    }
}
