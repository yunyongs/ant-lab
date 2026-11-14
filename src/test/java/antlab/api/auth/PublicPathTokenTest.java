package antlab.api.auth;

import static antlab.api.auth.AuthFlow.BASE;
import static antlab.api.auth.AuthFlow.cookieValue;
import static antlab.api.auth.AuthFlow.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.member.SignupRequest;
import antlab.member.SignupService;
import antlab.support.IntegrationTest;
import antlab.support.ScriptedClock;
import antlab.support.TestTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/** A stale or forged {@code Authorization} header must not block the paths a client uses to get a new token. */
@IntegrationTest
@Import({ScriptedClockConfig.class, TestTokens.class})
@Transactional
class PublicPathTokenTest {

    private static final String EMAIL = "stale@example.test";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    SignupService signups;
    @Autowired
    ScriptedClock clock;
    @Autowired
    TestTokens tokens;

    AuthFlow flow;

    @BeforeEach
    void member() {
        clock.reset();
        flow = new AuthFlow(mvc, clock);
        signups.signup(new SignupRequest(EMAIL, "stale", AuthFlow.PASSWORD));
    }

    private String expired() {
        var claims = tokens.claims(7, "USER");
        claims.put("exp", TestTokens.ISSUED.plusSeconds(1).getEpochSecond());
        return tokens.sign(tokens.header(), claims);
    }

    private String header(String kind) {
        return "Bearer " + ("expired".equals(kind) ? expired() : "garbage");
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "garbage"})
    void 낡은_토큰이_있어도_로그인은_200이다(String kind) {
        clock.script(BASE);
        MvcTestResult result = mvc.post().uri("/api/v1/auth/login").header("Authorization", header(kind))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + AuthFlow.PASSWORD + "\"}").exchange();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "garbage"})
    void 낡은_토큰이_있어도_유효한_쿠키로_갱신은_200이고_로그아웃은_204다(String kind) {
        String cookie = flow.loginCookie(BASE, EMAIL);
        clock.script(BASE.plusSeconds(1));
        MvcTestResult refreshed = mvc.post().uri("/api/v1/auth/refresh").header("Authorization", header(kind))
                .accept(MediaType.APPLICATION_JSON).cookie(new Cookie("antlab_refresh", cookie)).exchange();
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        clock.script(BASE.plusSeconds(2));
        MvcTestResult out = mvc.post().uri("/api/v1/auth/logout").header("Authorization", header(kind))
                .accept(MediaType.APPLICATION_JSON).cookie(new Cookie("antlab_refresh", cookieValue(refreshed))).exchange();
        assertThat(out.getResponse().getStatus()).isEqualTo(204);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "garbage"})
    void 낡은_토큰이_있어도_가입은_201이고_공개_경로는_401이_아니다(String kind) {
        MvcTestResult signup = mvc.post().uri("/api/v1/auth/signup").header("Authorization", header(kind))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"fresh@example.test\",\"name\":\"fresh\",\"password\":\"" + AuthFlow.PASSWORD + "\"}")
                .exchange();
        assertThat(signup.getResponse().getStatus()).isEqualTo(201);
        assertThat(mvc.get().uri("/api/v1/pool").header("Authorization", header(kind)).exchange().getResponse().getStatus())
                .isNotEqualTo(401);
        assertThat(mvc.get().uri("/livez").header("Authorization", header(kind)).exchange().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void 로그인이_준_접근_토큰은_디코더가_받아들인다() {
        MvcTestResult login = flow.login(null, EMAIL);
        String token = json(login).get("accessToken").asString();
        assertThat(mvc.get().uri("/api/v1/no-such-path").header("Authorization", "Bearer " + token).exchange()
                .getResponse().getStatus()).isEqualTo(404);
    }
}
