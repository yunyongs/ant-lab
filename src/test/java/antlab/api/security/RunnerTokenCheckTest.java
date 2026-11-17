package antlab.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.agent.AgentRunnerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The runner token comparison: exact match only, and nothing matches while the configuration is unusable. */
class RunnerTokenCheckTest {

    private static final String TOKEN = "fake-token-exactly-thirty-one-c!";

    private static RunnerTokenCheck check(String token, Long owner) {
        return new RunnerTokenCheck(new AgentRunnerProperties(token, owner, 5));
    }

    @Test
    void only_the_exact_token_matches() {
        RunnerTokenCheck check = check(TOKEN, 1L);
        assertThat(check.matches(TOKEN)).isTrue();
        assertThat(check.matches(TOKEN + " ")).isFalse();
        assertThat(check.matches(" " + TOKEN)).isFalse();
        assertThat(check.matches(TOKEN.substring(1))).isFalse();
        assertThat(check.matches(TOKEN.substring(0, TOKEN.length() - 1))).isFalse();
        assertThat(check.matches(TOKEN + TOKEN)).isFalse();
        assertThat(check.matches(TOKEN.toUpperCase())).isFalse();
        assertThat(check.matches("")).isFalse();
        assertThat(check.matches(null)).isFalse();
    }

    @Test
    void a_token_differing_in_one_character_does_not_match() {
        RunnerTokenCheck check = check(TOKEN, 1L);
        for (int i = 0; i < TOKEN.length(); i++) {
            char[] chars = TOKEN.toCharArray();
            chars[i] = chars[i] == 'z' ? 'y' : 'z';
            assertThat(check.matches(new String(chars))).as("position " + i).isFalse();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "short", "fake-token-exactly-thirty-one-c"})
    void an_unusable_configured_token_matches_nothing_not_even_itself(String configured) {
        RunnerTokenCheck check = check(configured, 1L);
        assertThat(configured == null ? check.matches(null) : check.matches(configured)).isFalse();
        assertThat(check.matches("")).isFalse();
        assertThat(check.matches(null)).isFalse();
        assertThat(check.matches(TOKEN)).isFalse();
    }

    @Test
    void a_token_of_exactly_the_minimum_length_is_usable() {
        assertThat(TOKEN).hasSize(AgentRunnerProperties.MIN_TOKEN_LENGTH);
        assertThat(check(TOKEN, 1L).matches(TOKEN)).isTrue();
    }

    @Test
    void without_an_owner_nothing_matches() {
        assertThat(check(TOKEN, null).matches(TOKEN)).isFalse();
        assertThat(check(TOKEN, 0L).matches(TOKEN)).isFalse();
        assertThat(check(TOKEN, -5L).matches(TOKEN)).isFalse();
    }

    @Test
    void the_properties_never_print_the_token() {
        assertThat(new AgentRunnerProperties(TOKEN, 1L, 5).toString()).doesNotContain(TOKEN).contains("token=***");
    }
}
