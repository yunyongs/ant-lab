package antlab.api.auth;

import antlab.support.ScriptedClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Scripted clock for the login tests: one scripted value is the request's "now". */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedClockConfig {

    @Bean
    @Primary
    ScriptedClock scriptedClock() {
        return new ScriptedClock();
    }
}
