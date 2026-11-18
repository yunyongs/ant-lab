package antlab.collect.fred;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The server holds no FRED key until OPS decides where it may live (DEPLOY 2), so prod refuses to start with the collector on. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
@ConditionalOnProperty(prefix = "antlab.collect.fred", name = "enabled", havingValue = "true")
class FredProdGuard {

    FredProdGuard() {
        throw new IllegalStateException(
                "antlab.collect.fred.enabled=true is not allowed with the prod profile: no FRED key on the server (DEPLOY 2)");
    }
}
