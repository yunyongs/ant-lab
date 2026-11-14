package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.ScriptedClock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AccessTokenValidatorTest {

    private static final Instant NOW = Instant.parse("2031-05-06T07:08:09Z");
    private final ScriptedClock clock = new ScriptedClock();
    private final AccessTokenValidator validator = new AccessTokenValidator(clock);

    private boolean accepts(Consumer<Jwt.Builder> change) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "HS256").header("typ", "JWT")
                .issuer("antlab").subject("42").claim("role", "USER")
                .issuedAt(NOW.minusSeconds(10)).expiresAt(NOW.plusSeconds(10));
        change.accept(b);
        clock.script(NOW);
        return !validator.validate(b.build()).hasErrors();
    }

    @Test
    void 모든_규칙을_지킨_토큰을_받는다() {
        assertThat(accepts(b -> { })).isTrue();
        assertThat(accepts(b -> b.claim("role", "ADMIN"))).isTrue();
        assertThat(accepts(b -> b.subject("9223372036854775807"))).isTrue();
    }

    @Test
    void 만료_시각은_지금_초_전까지만_유효하고_지금과_같으면_만료다() {
        assertThat(accepts(b -> b.expiresAt(NOW.plusSeconds(1)))).isTrue();
        assertThat(accepts(b -> b.expiresAt(NOW))).isFalse();
        assertThat(accepts(b -> b.expiresAt(NOW.minusSeconds(1)))).isFalse();
    }

    @Test
    void 발급_시각은_지금_이하여야_하고_허용_오차가_없다() {
        assertThat(accepts(b -> b.issuedAt(NOW).expiresAt(NOW.plusSeconds(1)))).isTrue();
        assertThat(accepts(b -> b.issuedAt(NOW.plusSeconds(1)).expiresAt(NOW.plusSeconds(60)))).isFalse();
    }

    @Test
    void nbf는_있을_때만_보고_지금_이하여야_한다() {
        assertThat(accepts(b -> b.notBefore(NOW))).isTrue();
        assertThat(accepts(b -> b.notBefore(NOW.plusSeconds(1)))).isFalse();
    }

    @Test
    void 시각_클레임이_없으면_거절한다() {
        assertThat(accepts(b -> b.claims(c -> c.remove("iat")))).isFalse();
        assertThat(accepts(b -> b.claims(c -> c.remove("exp")))).isFalse();
    }

    @Test
    void iss는_문자열_antlab만_받는다() {
        assertThat(accepts(b -> b.claims(c -> c.remove("iss")))).isFalse();
        assertThat(accepts(b -> b.issuer("antlab2"))).isFalse();
        assertThat(accepts(b -> b.issuer("https://antlab"))).isFalse();
    }

    @Test
    void typ는_JWT만_받는다() {
        assertThat(accepts(b -> b.headers(h -> h.remove("typ")))).isFalse();
        assertThat(accepts(b -> b.header("typ", "at+jwt"))).isFalse();
        assertThat(accepts(b -> b.header("typ", "jwt"))).isFalse();
    }

    @Test
    void kid는_없어도_아무_값이어도_받는다() {
        assertThat(accepts(b -> b.header("kid", "anything"))).isTrue();
    }

    @Test
    void sub는_앞자리_0_없는_양의_long_숫자만_받는다() {
        for (String bad : new String[] {"abc", "0", "-1", "01", "9223372036854775808", "1.5", " 1", ""}) {
            assertThat(accepts(b -> b.subject(bad))).as("sub=" + bad).isFalse();
        }
        assertThat(accepts(b -> b.claims(c -> c.remove("sub")))).isFalse();
    }

    @Test
    void role은_대문자_USER_ADMIN만_받는다() {
        for (String bad : new String[] {"ROOT", "admin", "user", "ROLE_USER", ""}) {
            assertThat(accepts(b -> b.claim("role", bad))).as("role=" + bad).isFalse();
        }
        assertThat(accepts(b -> b.claims(c -> c.remove("role")))).isFalse();
        assertThat(accepts(b -> b.claim("role", List.of("USER")))).isFalse();
    }

    @Test
    void 실패_메시지에_클레임_값이_들어가지_않는다() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").header("typ", "JWT").issuer("secret-issuer-value")
                .subject("42").claim("role", "USER").issuedAt(NOW).expiresAt(NOW.plusSeconds(5)).build();
        clock.script(NOW);
        var errors = validator.validate(jwt).getErrors();
        assertThat(errors).isNotEmpty().allSatisfy(e -> assertThat(String.valueOf(e.getDescription()))
                .doesNotContain("secret-issuer-value"));
    }
}
