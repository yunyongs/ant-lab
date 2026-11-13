package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordCheckTest {

    private final PasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
    private final PasswordCheck check = new PasswordCheck(encoder);

    @Test
    void bcrypt_해시만_맞을_수_있다() {
        String stored = encoder.encode("correct-horse-battery");
        assertThat(check.matches("correct-horse-battery", stored)).isTrue();
        assertThat(check.matches("wrong", stored)).isFalse();
    }

    @Test
    void 없는_값_고정_회원_표지_다른_형식은_맞지_않는다() {
        assertThat(check.matches("x", null)).isFalse();
        assertThat(check.matches("!", "!")).isFalse();
        assertThat(check.matches("pw", "{noop}pw")).isFalse();
    }
}
