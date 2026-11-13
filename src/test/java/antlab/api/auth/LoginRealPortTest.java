package antlab.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.RealPortTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The raw Set-Cookie header over a real HTTP connection, as a browser would see it. */
class LoginRealPortTest extends RealPortTest {

    private static final String EMAIL = "realport-login@example.test";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from refresh_token where member_id in (select id from member where email = ?)", EMAIL);
        jdbc.update("delete from member where email = ?", EMAIL);
    }

    @Test
    void 쿠키_헤더는_실제_연결에서도_HttpOnly_Secure_SameSite_Strict_Path를_가진다() throws Exception {
        jdbc.update("insert into member (email, name, password) values (?, 'rp', ?)", EMAIL, encoder.encode("correct-horse-battery"));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + EMAIL + "\",\"password\":\"correct-horse-battery\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).startsWith("antlab_refresh=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");
    }
}
