package antlab.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderTest {

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void bcrypt_접두로_저장하고_같은_비밀번호만_맞는다() {
        String hash = encoder.encode("correct-horse-battery");
        assertThat(hash).startsWith("{bcrypt}$2");
        assertThat(encoder.matches("correct-horse-battery", hash)).isTrue();
        assertThat(encoder.matches("wrong-horse-battery", hash)).isFalse();
    }

    @Test
    void noop_접두_값은_대조하지_않는다() {
        assertThatThrownBy(() -> encoder.matches("pw", "{noop}pw")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void MD5와_sha256_접두_값도_대조하지_않는다() {
        assertThatThrownBy(() -> encoder.matches("pw", "{MD5}abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.matches("pw", "{sha256}abc")).isInstanceOf(IllegalArgumentException.class);
    }
}
