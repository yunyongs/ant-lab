package antlab.auth;

import java.time.Duration;

/** The result of a login: what goes in the body and what goes in the cookie. */
public record IssuedTokens(String accessToken, long expiresInSeconds, String refreshValue, Duration refreshTtl) {

    @Override
    public String toString() {
        return "IssuedTokens[***]";
    }
}
