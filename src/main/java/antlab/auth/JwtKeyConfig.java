package antlab.auth;

import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** The one signing key bean; the app does not start without a valid key. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class JwtKeyConfig {

    static final int MIN_KEY_BYTES = 32;
    static final Duration MAX_ACCESS_TTL = Duration.ofHours(1);
    static final Duration MAX_REFRESH_TTL = Duration.ofDays(30);

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        String secret = properties.jwtSecret();
        byte[] key = null;
        if (secret != null && !secret.isBlank()) {
            try {
                key = Base64.getDecoder().decode(secret.strip());
            } catch (IllegalArgumentException e) {
                key = null;
            }
        }
        if (key == null || key.length < MIN_KEY_BYTES) {
            // Neither the value nor the decoder's message goes into the failure: both could echo the secret.
            throw new IllegalStateException("ANTLAB_JWT_SECRET (antlab.auth.jwt-secret) must be set to the Base64 of at least "
                    + MIN_KEY_BYTES + " bytes, for example the output of: openssl rand -base64 32");
        }
        checkLifetimes(properties.accessTokenTtl(), properties.refreshTokenTtl());
        return new SecretKeySpec(key, "HmacSHA256");
    }

    /**
     * Fail fast on token lifetimes: access in (0, 1 hour], refresh longer than access and at most 30 days.
     * Done here, not in the record constructor, so a failure is reported by this message only.
     */
    static void checkLifetimes(Duration access, Duration refresh) {
        if (access == null || access.isZero() || access.isNegative() || access.compareTo(MAX_ACCESS_TTL) > 0) {
            throw new IllegalStateException(
                    "antlab.auth.access-token-ttl must be set, positive and at most " + MAX_ACCESS_TTL);
        }
        if (refresh == null || refresh.compareTo(access) <= 0 || refresh.compareTo(MAX_REFRESH_TTL) > 0) {
            throw new IllegalStateException("antlab.auth.refresh-token-ttl must be set, longer than the access token TTL"
                    + " and at most " + MAX_REFRESH_TTL);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).build();
    }
}
