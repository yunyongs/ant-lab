package antlab.collect;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The server holds no KIS real key until OPS decides where it may live (DEPLOY 2), so prod refuses to start with the collector on. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
@ConditionalOnProperty(prefix = "antlab.collect.kis", name = "enabled", havingValue = "true")
class CollectProdGuard {

    CollectProdGuard() {
        throw new IllegalStateException(
                "antlab.collect.kis.enabled=true is not allowed with the prod profile: no KIS real key on the server (DEPLOY 2)");
    }
}
