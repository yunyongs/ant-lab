package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The build pins config lookup to the classpath, so a personal config/application-local.yml never changes test results. */
class ConfigIsolationTest {

    @Test
    void testTaskPinsConfigLocationToClasspath() {
        assertThat(System.getProperty("spring.config.location")).isEqualTo("optional:classpath:/");
    }
}
