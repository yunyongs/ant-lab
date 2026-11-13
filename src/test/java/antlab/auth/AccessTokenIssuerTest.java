package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.member.Member;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AccessTokenIssuerTest {

    private static SecretKey key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static Member member() {
        Member m = new Member("a@example.test", "a", "{bcrypt}x");
        ReflectionTestUtils.setField(m, "id", 42L);
        return m;
    }

    private static AccessTokenIssuer issuer(SecretKey key) {
        return new AccessTokenIssuer(NimbusJwtEncoder.withSecretKey(key).build(),
                new AuthProperties("unused", Duration.ofMinutes(15), Duration.ofDays(14)));
    }

    @Test
    void 헤더와_클레임이_계획과_같고_키가_다르면_검증에_실패한다() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String token = issuer(key(1)).issue(member(), now);

        Jwt jwt = NimbusJwtDecoder.withSecretKey(key(1)).macAlgorithm(MacAlgorithm.HS256).build().decode(token);
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256").containsEntry("typ", "JWT");
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti", "role");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("antlab");
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofSeconds(900));
        assertThat(jwt.getId()).matches("^[0-9a-f-]{36}$");

        NimbusJwtDecoder other = NimbusJwtDecoder.withSecretKey(key(2)).macAlgorithm(MacAlgorithm.HS256).build();
        assertThatThrownBy(() -> other.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void 토큰마다_jti가_다르다() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        AccessTokenIssuer issuer = issuer(key(1));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(1)).macAlgorithm(MacAlgorithm.HS256).build();
        assertThat(decoder.decode(issuer.issue(member(), now)).getId())
                .isNotEqualTo(decoder.decode(issuer.issue(member(), now)).getId());
    }
}
