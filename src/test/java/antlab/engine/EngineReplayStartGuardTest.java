package antlab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.engine.client.EngineProperties;
import antlab.history.HistoryProperties;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A replay start other than the engine's is refused only while the engine is wired (URL set or engine path source). */
class EngineReplayStartGuardTest {

    private static final LocalDate OTHER = LocalDate.of(1997, 1, 1);

    private static EngineProperties engine(String url, EngineProperties.PathSourceMode mode) {
        String token = mode == EngineProperties.PathSourceMode.ENGINE || !url.isEmpty() ? "a".repeat(32) : "";
        return new EngineProperties(url, token, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15),
                Duration.ofSeconds(60), mode, "m2-sb6-v1", 2000);
    }

    private ApplicationContextRunner runner(LocalDate start, EngineProperties engine) {
        return new ApplicationContextRunner().withUserConfiguration(EngineReplayStartGuard.class)
                .withBean(HistoryProperties.class, () -> new HistoryProperties(start))
                .withBean(EngineProperties.class, () -> engine);
    }

    @Test
    void the_default_start_starts_with_or_without_the_engine() {
        runner(EngineProperties.ENGINE_REPLAY_START, engine("", EngineProperties.PathSourceMode.JAVA))
                .run(c -> assertThat(c).hasNotFailed());
        runner(EngineProperties.ENGINE_REPLAY_START, engine("http://engine.test", EngineProperties.PathSourceMode.ENGINE))
                .run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    void another_start_without_the_engine_starts() {
        runner(OTHER, engine("", EngineProperties.PathSourceMode.JAVA)).run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    void another_start_with_a_url_fails_even_on_the_java_path_source() {
        runner(OTHER, engine("http://engine.test", EngineProperties.PathSourceMode.JAVA)).run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).rootCause().hasMessageContaining("1997-01-01").hasMessageContaining("2007-01-01");
        });
    }

    @Test
    void another_start_with_the_engine_path_source_fails() {
        runner(OTHER, engine("http://engine.test", EngineProperties.PathSourceMode.ENGINE))
                .run(c -> assertThat(c).hasFailed());
    }
}
