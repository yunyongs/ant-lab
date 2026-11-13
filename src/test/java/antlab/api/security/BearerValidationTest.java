package antlab.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.auth.ScriptedClockConfig;
import antlab.support.IntegrationTest;
import antlab.support.ScriptedClock;
import antlab.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The probe path matches no controller: a token the chain accepts reaches the dispatcher and answers 404, any failure is
 * the security 401 before it.
 */
@IntegrationTest
@Import({ScriptedClockConfig.class, TestTokens.class})
@ExtendWith(OutputCaptureExtension.class)
class BearerValidationTest {

    private static final String PROBE = "/api/v1/no-such-path";
    private static final Instant NOW = Instant.parse("2031-05-06T07:08:09Z");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    TestTokens tokens;
    @Autowired
    ScriptedClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    private MvcTestResult get(String authorization) {
        var request = mvc.get().uri(PROBE);
        return (authorization == null ? request : request.header("Authorization", authorization)).exchange();
    }

    private MvcTestResult bearer(String token) {
        return get("Bearer " + token);
    }

    /** Scripts the one clock read the validator makes, so the token's own times can sit exactly at the boundary. */
    private MvcTestResult bearerAt(Instant now, String token) {
        clock.script(now);
        return bearer(token);
    }

    private String with(Consumer<Map<String, Object>> header, Consumer<Map<String, Object>> claims) {
        Map<String, Object> h = tokens.header();
        Map<String, Object> c = tokens.claims(7, "USER");
        header.accept(h);
        claims.accept(c);
        return tokens.sign(h, c);
    }

    private String claim(Consumer<Map<String, Object>> claims) {
        return with(h -> { }, claims);
    }

    private static String body(MvcTestResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static void assertDenied(MvcTestResult r) {
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    private static void assertAccepted(MvcTestResult r) {
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void 헤더가_없거나_비었거나_베어러가_아니면_401이다() {
        assertDenied(get(null));
        assertDenied(get("Bearer"));
        assertDenied(get("Bearer "));
        assertDenied(bearer("not.a.jwt"));
        assertDenied(get("Basic dXNlcjpwYXNz"));
    }

    @Test
    void 쿼리_매개변수의_토큰은_읽지_않는다() {
        assertThat(mvc.get().uri(PROBE).queryParam("access_token", tokens.user(7)).exchange().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void 헤더가_두_개이면_500이_아니라_401이다() {
        var result = mvc.get().uri(PROBE).header("Authorization", "Bearer " + tokens.user(7))
                .header("Authorization", "Bearer " + tokens.user(8)).exchange();
        assertDenied(result);
    }

    @Test
    void 스킴_이름의_대소문자는_가리지_않는다() {
        assertAccepted(get("bearer " + tokens.user(7)));
    }

    @Test
    void 만료_시각_경계는_지금_초_전까지만_유효하다() {
        long now = NOW.getEpochSecond();
        assertDenied(bearerAt(NOW, claim(c -> c.put("exp", now - 1))));
        assertDenied(bearerAt(NOW, claim(c -> c.put("exp", now))));
        assertAccepted(bearerAt(NOW, claim(c -> c.put("exp", now + 1))));
    }

    @Test
    void 다른_키로_서명하거나_본문을_바꾸면_401이다() {
        assertDenied(bearer(tokens.signedByOtherKey(tokens.header(), tokens.claims(7, "USER"))));
        assertDenied(bearer(tokens.tamper(7)));
    }

    @Test
    void alg가_none이면_401이다() {
        assertDenied(bearer(with(h -> h.put("alg", "none"), c -> { })));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS384", "HS512", "RS256", "ES256", "PS256"})
    void 다른_알고리즘_헤더는_우리_키_바이트로_서명해도_401이다(String alg) {
        assertDenied(bearer(with(h -> h.put("alg", alg), c -> { })));
    }

    @Test
    void iss가_없거나_다르면_401이고_antlab만_통과한다() {
        assertDenied(bearer(claim(c -> c.remove("iss"))));
        assertDenied(bearer(claim(c -> c.put("iss", "antlab2"))));
        assertDenied(bearer(claim(c -> c.put("iss", "https://antlab"))));
        assertAccepted(bearer(claim(c -> c.put("iss", "antlab"))));
    }

    @Test
    void typ가_없거나_JWT가_아니면_401이다() {
        assertDenied(bearer(with(h -> h.remove("typ"), c -> { })));
        assertDenied(bearer(with(h -> h.put("typ", "at+jwt"), c -> { })));
        assertDenied(bearer(with(h -> h.put("typ", "jwt"), c -> { })));
    }

    @Test
    void sub가_회원_번호가_아니면_401이다() {
        for (String bad : new String[] {"abc", "0", "-1", "01", "9223372036854775808"}) {
            assertDenied(bearer(claim(c -> c.put("sub", bad))));
        }
        assertDenied(bearer(claim(c -> c.remove("sub"))));
    }

    @Test
    void role이_없거나_USER_ADMIN이_아니면_401이다() {
        for (String bad : new String[] {"ROOT", "admin"}) {
            assertDenied(bearer(claim(c -> c.put("role", bad))));
        }
        assertDenied(bearer(claim(c -> c.remove("role"))));
    }

    @Test
    void iat가_없거나_미래이면_401이다() {
        long now = NOW.getEpochSecond();
        assertDenied(bearer(claim(c -> c.remove("iat"))));
        assertDenied(bearerAt(NOW, claim(c -> c.put("iat", now + 1))));
        assertAccepted(bearerAt(NOW, claim(c -> c.put("iat", now))));
    }

    @Test
    void exp가_없으면_401이다() {
        assertDenied(bearer(claim(c -> c.remove("exp"))));
    }

    @Test
    void nbf가_미래이면_401이고_지금이면_통과한다() {
        long now = NOW.getEpochSecond();
        assertDenied(bearerAt(NOW, claim(c -> c.put("nbf", now + 1))));
        assertAccepted(bearerAt(NOW, claim(c -> c.put("nbf", now))));
    }

    @Test
    void kid는_없어도_아무_값이어도_통과한다() {
        assertAccepted(bearer(tokens.user(7)));
        assertAccepted(bearer(with(h -> h.put("kid", "anything"), c -> { })));
    }

    @Test
    void 시즌_경로는_토큰이_없으면_그대로이고_잘못된_토큰이면_401이다() {
        assertThat(mvc.get().uri("/api/v1/seasons").exchange().getResponse().getStatus()).isEqualTo(200);
        var valid = mvc.get().uri("/api/v1/seasons").header("Authorization", "Bearer " + tokens.user(7)).exchange();
        assertThat(valid.getResponse().getStatus()).isEqualTo(200);
        var garbage = mvc.get().uri("/api/v1/seasons").header("Authorization", "Bearer garbage").exchange();
        assertThat(garbage.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(garbage)).contains("UNAUTHENTICATED");
    }

    @Test
    void 모든_실패_본문은_익명_401과_같고_토큰과_클레임이_응답과_로그에_없다(CapturedOutput output) {
        String anonymous = body(get(null));
        String leaky = claim(c -> c.put("iss", "leak-marker-issuer"));
        String[] failing = {
                body(bearer("not.a.jwt")),
                body(bearer(tokens.tamper(7))), body(bearer(tokens.signedByOtherKey(tokens.header(), tokens.claims(7, "USER")))),
                body(bearer(with(h -> h.put("alg", "none"), c -> { }))), body(bearer(leaky)),
                body(bearer(claim(c -> c.put("sub", "abc")))), body(bearerAt(NOW, claim(c -> c.put("exp", NOW.getEpochSecond()))))};
        for (String failure : failing) {
            assertThat(failure).isEqualTo(anonymous);
        }
        var denied = bearer(leaky);
        assertThat(denied.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(denied.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(anonymous).contains("UNAUTHENTICATED");
        assertThat(body(denied)).doesNotContain(leaky).doesNotContain("leak-marker-issuer");
        assertThat(output.getAll()).doesNotContain(leaky).doesNotContain("leak-marker-issuer");
    }
}
