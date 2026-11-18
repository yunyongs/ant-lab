package antlab.scoring;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Defaults for the scoring ports that nothing implements yet. A real bean of the same type replaces them. */
@Configuration(proxyBeanMethods = false)
class ScoringDefaults {

    @Bean
    @ConditionalOnMissingBean(DisciplineSource.class)
    DisciplineSource emptyDisciplineSource() {
        return seasonId -> Optional.empty();
    }

    @Bean
    @ConditionalOnMissingBean(NoiseReactionSource.class)
    NoiseReactionSource emptyNoiseReactionSource() {
        return seasonId -> Optional.empty();
    }
}
