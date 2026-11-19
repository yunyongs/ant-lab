package antlab.collect.ecos;

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

/** The client and the job exist only when the ECOS collector is switched on. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EcosProperties.class)
@ConditionalOnProperty(prefix = "antlab.collect.ecos", name = "enabled", havingValue = "true")
class EcosJobConfig {

    @Bean
    EcosClientFactory ecosClientFactory(EcosProperties properties, Clock clock, ObjectProvider<CollectMetrics> metrics) {
        return EcosClientFactory.create(KisClientConfig.transportBuilder(), properties, clock, metrics.getIfAvailable(CollectMetrics::none));
    }

    /** Reads the catalog itself: a shared SeriesCatalog bean would be a second candidate when the FRED job is on too. */
    @Bean
    EcosSeriesJob ecosSeriesJob(EcosClientFactory factory, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            Clock clock, EcosProperties properties, ObjectProvider<CollectMetrics> metrics) {
        return EcosSeriesJob.create(factory::newClient, SeriesCatalog.fromClasspath(), jdbc, transactions, clock, properties,
                metrics.getIfAvailable(CollectMetrics::none));
    }
}
