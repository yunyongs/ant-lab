package antlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.StringUtils;

/** The runner token and owner have no default in any profile; only the environment can set them. No Spring context. */
class AgentRunnerConfigTest {

    private static StandardEnvironment environment(String profile, Map<String, Object> envLike) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(
                new MapPropertySource("trackedConfigOnly", Map.of("spring.config.location", "optional:classpath:/")));
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("envLike", envLike));
        if (profile.isEmpty()) {
            ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), null);
        } else {
            ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), null, profile);
        }
        return env;
    }

    @Test
    void no_profile_has_a_token_or_owner_in_tracked_config() {
        for (String profile : new String[] {"", "local", "prod"}) {
            StandardEnvironment env = environment(profile, Map.of("ANTLAB_DB_URL", "jdbc:postgresql://db:5432/x"));
            // Boolean only: a failure must not echo the property value.
            assertThat(StringUtils.hasLength(env.getProperty("antlab.agent-runner.token")))
                    .as("profile '%s' has a runner token in tracked config", profile).isFalse();
            assertThat(StringUtils.hasLength(env.getProperty("antlab.agent-runner.owner-member-id")))
                    .as("profile '%s' has a runner owner in tracked config", profile).isFalse();
        }
    }

    @Test
    void the_environment_names_set_them_and_the_weekly_limit_defaults_to_5() {
        StandardEnvironment env = environment("prod", Map.of("ANTLAB_DB_URL", "jdbc:postgresql://db:5432/x",
                "ANTLAB_AGENT_RUNNER_TOKEN", "from-env", "ANTLAB_AGENT_RUNNER_OWNER_ID", "7"));
        assertThat(env.getProperty("antlab.agent-runner.token")).isEqualTo("from-env");
        assertThat(env.getProperty("antlab.agent-runner.owner-member-id")).isEqualTo("7");
        assertThat(env.getProperty("antlab.agent-runner.max-seasons-per-week")).isEqualTo("5");
    }
}
