package antlab.scoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans of the scoring package whose value is a fixed rule, so that a test can replace the rule (for example with fake tickers). */
@Configuration(proxyBeanMethods = false)
public class ScoringConfig {

    @Bean
    RepresentativeTable representativeTable() {
        return RepresentativeTable.REP_V1;
    }
}
