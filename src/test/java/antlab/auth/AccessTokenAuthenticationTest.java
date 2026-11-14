package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

class AccessTokenAuthenticationTest {

    private final JwtAuthenticationConverter converter = new AccessTokenDecoderConfig().accessTokenAuthentication();

    private Authentication convert(String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("42").claim("role", role)
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();
        return converter.convert(jwt);
    }

    /** Security 7 also adds a FACTOR_BEARER authority; the role authorities are the ones that must be exact. */
    private static List<String> roles(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).toList();
    }

    @Test
    void 역할은_ROLE_접두사_권한_하나가_되고_이름은_sub다() {
        var admin = convert("ADMIN");
        assertThat(roles(admin)).containsExactly("ROLE_ADMIN");
        assertThat(admin.getName()).isEqualTo("42");
        assertThat(roles(convert("USER"))).containsExactly("ROLE_USER");
    }
}
