package antlab.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token settings. The secret is checked in {@link JwtKeyConfig}, not by binding validation: a binding failure report
 * prints the rejected value.
 */
@ConfigurationProperties("antlab.auth")
public record AuthProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    @Override
    public String toString() {
        return "AuthProperties[jwtSecret=***, accessTokenTtl=" + accessTokenTtl + ", refreshTokenTtl=" + refreshTokenTtl + "]";
    }
}
