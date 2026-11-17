package antlab.collect.price;

import antlab.collect.kis.KisQuoteClientFactory;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The job exists only when the collector is switched on (the KIS client factory has the same condition). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PriceJobProperties.class)
@ConditionalOnProperty(prefix = "antlab.collect.kis", name = "enabled", havingValue = "true")
class CollectJobConfig {

    @Bean
    KisTargetCatalog kisTargetCatalog() {
        return KisTargetCatalog.fromClasspath();
    }

    @Bean
    KisDailyPriceJob kisDailyPriceJob(KisQuoteClientFactory factory, KisTargetCatalog catalog, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, PriceJobProperties properties) {
        return KisDailyPriceJob.create(factory::newClient, catalog, jdbc, transactions, clock, properties);
    }
}
