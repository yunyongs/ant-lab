package antlab.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

    @Test
    void theDefaultNoiseSourceAnswersEmpty() {
        runner.withUserConfiguration(ScoringDefaults.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(NoiseReactionSource.class);
            assertThat(ctx.getBean(NoiseReactionSource.class).noiseShare(1L)).isEmpty();
        });
    }

    @Test
    void aRealNoiseSourceReplacesTheDefault() {
        runner.withUserConfiguration(RealNoise.class, ScoringDefaults.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(NoiseReactionSource.class);
            assertThat(ctx.getBean(NoiseReactionSource.class).noiseShare(1L)).contains(new BigDecimal("0.50000000"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RealNoise {
        @Bean
        NoiseReactionSource real() {
            return seasonId -> Optional.of(new BigDecimal("0.50000000"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RealSource {
        @Bean
        DisciplineSource real() {
            return seasonId -> Optional.of(new DisciplineRatios(null, null));
        }
    }
}
