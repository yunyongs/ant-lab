package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RefreshTokensTest {

    @Test
    void SHA_256_고정_벡터와_같다() {
        assertThat(RefreshTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(RefreshTokens.hash("anything")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void 원본_값은_43자_URL_안전_문자이고_겹치지_않는다() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String raw = RefreshTokens.newRawValue();
            assertThat(raw).matches("^[A-Za-z0-9_-]{43}$");
            seen.add(raw);
        }
        assertThat(seen).hasSize(1000);
    }
}
