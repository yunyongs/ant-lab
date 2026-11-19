package antlab.collect.fred;

import antlab.collect.kis.KisClientConfig;
import antlab.collect.series.SeriesCatalog;
import antlab.observability.CollectMetrics;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The client and the job exist only when the FRED collector is switched on. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FredProperties.class)
@ConditionalOnProperty(prefix = "antlab.collect.fred", name = "enabled", havingValue = "true")
class FredJobConfig {

    @Bean
    FredClientFactory fredClientFactory(FredProperties properties, Clock clock, ObjectProvider<CollectMetrics> metrics) {
        return FredClientFactory.create(KisClientConfig.transportBuilder(), properties, clock, metrics.getIfAvailable(CollectMetrics::none));
    }

    @Bean
    SeriesCatalog seriesCatalog() {
        return SeriesCatalog.fromClasspath();
    }

    @Bean
    FredSeriesJob fredSeriesJob(FredClientFactory factory, SeriesCatalog catalog, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, FredProperties properties, ObjectProvider<CollectMetrics> metrics) {
        return FredSeriesJob.create(factory::newClient, catalog, jdbc, transactions, clock, properties, metrics.getIfAvailable(CollectMetrics::none));
    }
}
