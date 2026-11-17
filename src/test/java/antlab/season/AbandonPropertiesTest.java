package antlab.season;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The per-request cap is validated at startup: 1..60 (60 = the longest path), and the shipped default is 60. */
class AbandonPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AbandonProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void valuesInRangeStart() {
        for (String value : new String[] {"1", "12", "60"}) {
            runner.withPropertyValues("antlab.abandon.max-turns-per-request=" + value).run(ctx -> {
                assertThat(ctx).as(value).hasNotFailed();
                assertThat(ctx.getBean(AbandonProperties.class).maxTurnsPerRequest()).isEqualTo(Integer.parseInt(value));
            });
        }
    }

    @Test
    void zeroNegativeAndOverSixtyFailStartup() {
        for (String value : new String[] {"0", "-1", "61"}) {
            runner.withPropertyValues("antlab.abandon.max-turns-per-request=" + value)
                    .run(ctx -> assertThat(ctx).as(value).hasFailed());
        }
    }

    @Test
    void aMissingValueFailsStartupBecauseZeroIsOutOfRange() {
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void applicationYmlDefaultIsSixty() throws Exception {
        var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var sources = loader.load("application", new org.springframework.core.io.ClassPathResource("application.yml"));
        assertThat(sources.get(0).getProperty("antlab.abandon.max-turns-per-request")).isEqualTo(60);
    }
}
