package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ScoringDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void theDefaultDisciplineSourceAnswersEmpty() {
        runner.withUserConfiguration(ScoringDefaults.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(DisciplineSource.class);
            assertThat(ctx.getBean(DisciplineSource.class).of(1L)).isEmpty();
        });
    }

    @Test
    void aRealDisciplineSourceReplacesTheDefault() {
        runner.withUserConfiguration(RealSource.class, ScoringDefaults.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(DisciplineSource.class);
            assertThat(ctx.getBean(DisciplineSource.class).of(1L)).isPresent();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RealSource {
        @Bean
        DisciplineSource real() {
            return seasonId -> Optional.of(new DisciplineFacts(null, null));
        }
    }
}
