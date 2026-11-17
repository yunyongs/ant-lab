package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The halt threshold is validated at startup: 1..100, and it has no silent default. */
class ReconciliationPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReconciliationProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void valuesInRangeStart() {
        for (String value : new String[] {"1", "3", "100"}) {
            runner.withPropertyValues("antlab.reconciliation.halt-after-failures=" + value).run(ctx -> {
                assertThat(ctx).as(value).hasNotFailed();
                assertThat(ctx.getBean(ReconciliationProperties.class).haltAfterFailures()).isEqualTo(Integer.parseInt(value));
            });
        }
    }

    @Test
    void zeroNegativeAndOverHundredFailStartup() {
        for (String value : new String[] {"0", "-1", "101"}) {
            runner.withPropertyValues("antlab.reconciliation.halt-after-failures=" + value)
                    .run(ctx -> assertThat(ctx).as(value).hasFailed());
        }
    }

    @Test
    void missingValueFailsStartupBecauseZeroIsOutOfRange() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void applicationYmlDefaultIsThree() throws Exception {
        var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var sources = loader.load("application", new org.springframework.core.io.ClassPathResource("application.yml"));
        assertThat(sources.get(0).getProperty("antlab.reconciliation.halt-after-failures")).isEqualTo(3);
    }
}
