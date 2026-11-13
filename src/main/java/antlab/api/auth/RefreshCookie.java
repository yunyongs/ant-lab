package antlab.api.auth;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/** The refresh token cookie: invisible to scripts, sent only to the auth endpoints and only same-site. */
final class RefreshCookie {

    static final String NAME = "antlab_refresh";
    static final String PATH = "/api/v1/auth";

    private RefreshCookie() {
    }

    static ResponseCookie issue(String value, Duration ttl) {
        return builder(value).maxAge(ttl).build();
    }

    static ResponseCookie clear() {
        return builder("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder builder(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH);
    }
}
