package antlab.collect.ecos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The server holds no ECOS key until OPS decides where it may live (DEPLOY 2), so prod refuses to start with the collector on. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
@ConditionalOnProperty(prefix = "antlab.collect.ecos", name = "enabled", havingValue = "true")
class EcosProdGuard {

    EcosProdGuard() {
        throw new IllegalStateException(
                "antlab.collect.ecos.enabled=true is not allowed with the prod profile: no ECOS key on the server (DEPLOY 2)");
    }
}
