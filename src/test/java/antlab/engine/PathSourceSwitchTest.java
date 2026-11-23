package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import antlab.engine.client.EngineClient;
import antlab.engine.client.EngineProperties;
import antlab.history.HistoryProperties;
import antlab.history.MonthBlockRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Exactly one path source exists: the engine by default (M2-09), the in-process bootstrap only when asked for. */
class PathSourceSwitchTest {

    private static EngineProperties properties(EngineProperties.PathSourceMode mode) {
        return new EngineProperties("http://engine.test", "a".repeat(32), Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofSeconds(15), Duration.ofSeconds(60), mode, "m2-sb6-v1", 2000);
    }

    private ApplicationContextRunner runner(EngineProperties.PathSourceMode mode) {
        EngineClient client = mock(EngineClient.class);
        return new ApplicationContextRunner()
                .withUserConfiguration(PathSourceConfig.class, BootstrapPathSource.class)
                .withBean(MonthBlockRepository.class, () -> mock(MonthBlockRepository.class))
                .withBean(EngineClient.class, () -> client)
                .withBean(HistoryProperties.class, HistoryProperties::defaults)
                .withBean(EngineProperties.class, () -> properties(mode));
    }

    @Test
    void 설정이_없으면_엔진_경로_출처만_있고_자바_부트스트랩은_없다() {
        runner(EngineProperties.PathSourceMode.ENGINE).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PathSource.class).hasSingleBean(EnginePathSource.class);
            assertThat(context).doesNotHaveBean(BootstrapPathSource.class);
        });
    }

    @Test
    void java로_적으면_자바_부트스트랩만_있고_엔진은_부르지_않는다() {
        runner(EngineProperties.PathSourceMode.JAVA).withPropertyValues("antlab.engine.path-source=java").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PathSource.class).hasSingleBean(BootstrapPathSource.class);
            assertThat(context).doesNotHaveBean(EnginePathSource.class);
            org.mockito.Mockito.verifyNoInteractions(context.getBean(EngineClient.class));
        });
    }

    @Test
    void engine이면_엔진_경로_출처만_있다() {
        runner(EngineProperties.PathSourceMode.ENGINE).withPropertyValues("antlab.engine.path-source=engine").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PathSource.class).hasSingleBean(EnginePathSource.class);
            assertThat(context).doesNotHaveBean(BootstrapPathSource.class);
        });
    }

    @Test
    void 대문자_ENGINE도_엔진이다() {
        runner(EngineProperties.PathSourceMode.ENGINE).withPropertyValues("antlab.engine.path-source=ENGINE").run(context -> {
            assertThat(context).hasSingleBean(PathSource.class).hasSingleBean(EnginePathSource.class);
            assertThat(context).doesNotHaveBean(BootstrapPathSource.class);
        });
    }
}
