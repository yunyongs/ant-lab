package antlab.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.ScriptedClock;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Login, refresh and logout over MockMvc. A non-null instant is the request's scripted "now" (the service reads once). */
final class AuthFlow {

    static final String PASSWORD = "correct-horse-battery";
    static final Instant BASE = Instant.parse("2031-05-06T07:08:09Z");
    private static final Pattern COOKIE = Pattern.compile("^antlab_refresh=([A-Za-z0-9_-]*);");

    private final MockMvcTester mvc;
    private final ScriptedClock clock;

    AuthFlow(MockMvcTester mvc, ScriptedClock clock) {
        this.mvc = mvc;
        this.clock = clock;
    }

    MvcTestResult login(Instant at, String email) {
        script(at);
        return mvc.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").exchange();
    }

    MvcTestResult refresh(Instant at, String cookie) {
        script(at);
        var request = mvc.post().uri("/api/v1/auth/refresh").accept(MediaType.APPLICATION_JSON);
        return (cookie == null ? request : request.cookie(new Cookie("antlab_refresh", cookie))).exchange();
    }

    MvcTestResult logout(Instant at, String cookie) {
        script(at);
        var request = mvc.post().uri("/api/v1/auth/logout").accept(MediaType.APPLICATION_JSON);
        return (cookie == null ? request : request.cookie(new Cookie("antlab_refresh", cookie))).exchange();
    }

    /** Logs in and returns the cookie value, asserting the login worked. */
    String loginCookie(Instant at, String email) {
        MvcTestResult result = login(at, email);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return cookieValue(result);
    }

    private void script(Instant at) {
        if (at != null) {
            clock.script(at);
        }
    }

    static String cookieValue(MvcTestResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).as("Set-Cookie").isNotNull();
        Matcher m = COOKIE.matcher(header);
        assertThat(m.find()).as(header).isTrue();
        return m.group(1);
    }

    static String text(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(MvcTestResult result) {
        return JsonMapper.shared().readTree(text(result));
    }
}
