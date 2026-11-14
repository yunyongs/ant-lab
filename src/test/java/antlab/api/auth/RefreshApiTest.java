package antlab.api.auth;

import static antlab.api.auth.AuthFlow.BASE;
import static antlab.api.auth.AuthFlow.cookieValue;
import static antlab.api.auth.AuthFlow.json;
import static antlab.api.auth.AuthFlow.text;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.auth.RefreshTokens;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import antlab.support.IntegrationTest;
import antlab.support.ScriptedClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@Import(ScriptedClockConfig.class)
@Transactional
class RefreshApiTest {

    private static final String EMAIL = "rotate@example.test";
    private static final Duration TTL = Duration.ofDays(14);

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SignupService signups;
    @Autowired
    ScriptedClock clock;
    @Autowired
    Environment env;

    AuthFlow flow;

    @BeforeEach
    void member() {
        clock.reset();
        flow = new AuthFlow(mvc, clock);
        signups.signup(new SignupRequest(EMAIL, "rotator", AuthFlow.PASSWORD));
    }

    private Map<String, Object> row(String raw) {
        return jdbc.queryForMap("select * from refresh_token where token_hash = ?", RefreshTokens.hash(raw));
    }

    private long rows() {
        return jdbc.queryForObject("select count(*) from refresh_token", Long.class);
    }

    private long live() {
        return jdbc.queryForObject("select count(*) from refresh_token where revoked_at is null", Long.class);
    }

    private static Instant at(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    /** Erd rule A1: a ROTATED token has exactly one child, and a token with a child is ROTATED. */
    private long a1Violations() {
        return jdbc.queryForObject("select count(*) from refresh_token t where (t.revoked_reason = 'ROTATED')"
                + " <> exists (select 1 from refresh_token c where c.parent_id = t.id)", Long.class);
    }

    private void assertRejectedWithoutWrite(MvcTestResult result, long rowsBefore) {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(result).get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(rows()).isEqualTo(rowsBefore);
    }

    @Test
    void 살아_있는_쿠키는_200과_새_쿠키를_주고_옛_행은_ROTATED_자식은_14일_뒤까지_산다() {
        String c1 = flow.loginCookie(BASE, EMAIL);
        Instant later = BASE.plus(Duration.ofHours(1));
        MvcTestResult result = flow.refresh(later, c1);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn");
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        String raw = result.getResponse().getHeader("Set-Cookie");
        assertThat(raw).contains("Path=/api/v1/auth", "Max-Age=1209600", "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain");
        String c2 = cookieValue(result);
        assertThat(c2).hasSize(43).isNotEqualTo(c1);
        assertThat(text(result)).doesNotContain(c2);

        Map<String, Object> old = row(c1);
        Map<String, Object> child = row(c2);
        assertThat(old.get("revoked_reason")).isEqualTo("ROTATED");
        assertThat(at(old.get("revoked_at"))).isEqualTo(later);
        assertThat(child.get("parent_id")).isEqualTo(old.get("id"));
        assertThat(child.get("member_id")).isEqualTo(old.get("member_id"));
        assertThat(child.get("family_id")).isEqualTo(old.get("family_id"));
        assertThat(child.get("revoked_at")).isNull();
        assertThat(at(child.get("expires_at"))).isEqualTo(later.plus(TTL));
        assertThat(child.values().stream().map(String::valueOf)).doesNotContain(c2);
    }

    @Test
    void 새_접근_토큰은_이_키로_검증되고_sub는_회원_번호다() {
        String c1 = flow.loginCookie(BASE, EMAIL);
        String token = json(flow.refresh(BASE.plusSeconds(60), c1)).get("accessToken").asString();
        byte[] key = Base64.getDecoder().decode(env.getProperty("antlab.auth.jwt-secret"));
        Jwt jwt = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256)
                .build().decode(token);
        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(jdbc.queryForObject("select id from member where email = ?", Long.class, EMAIL)));
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getIssuedAt()).isEqualTo(BASE.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void 세_번_이어_회전해도_살아_있는_행은_하나이고_A1이_참이다() {
        String cookie = flow.loginCookie(BASE, EMAIL);
        for (int i = 1; i <= 3; i++) {
            MvcTestResult result = flow.refresh(BASE.plus(Duration.ofHours(i)), cookie);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            cookie = cookieValue(result);
        }
        assertThat(rows()).isEqualTo(4);
        assertThat(live()).isEqualTo(1);
        assertThat(a1Violations()).isZero();
    }

    @Test
    void 쿠키가_없거나_모르는_값이면_같은_401이고_쓰기가_없다() {
        flow.loginCookie(BASE, EMAIL);
        long before = rows();
        MvcTestResult none = flow.refresh(BASE.plusSeconds(1), null);
        assertRejectedWithoutWrite(none, before);
        MvcTestResult unknown = flow.refresh(BASE.plusSeconds(2), RefreshTokens.newRawValue());
        assertRejectedWithoutWrite(unknown, before);
        assertThat(text(unknown)).isEqualTo(text(none));
        assertThat(live()).isEqualTo(1);
    }

    @Test
    void 만료는_now가_expires_at_이상이면_401이고_행은_그대로다_직전은_200이다() {
        String cookie = flow.loginCookie(BASE, EMAIL);
        Instant expiresAt = BASE.plus(TTL);
        long before = rows();

        assertRejectedWithoutWrite(flow.refresh(expiresAt.plusSeconds(1), cookie), before);
        assertRejectedWithoutWrite(flow.refresh(expiresAt, cookie), before);
        assertThat(row(cookie).get("revoked_at")).isNull();

        assertThat(flow.refresh(expiresAt.minusMillis(1), cookie).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 로그아웃으로_폐기된_토큰은_401이고_쓰기가_없다() {
        String cookie = flow.loginCookie(BASE, EMAIL);
        assertThat(flow.logout(BASE.plusSeconds(10), cookie).getResponse().getStatus()).isEqualTo(204);
        long before = rows();
        assertRejectedWithoutWrite(flow.refresh(BASE.plusSeconds(20), cookie), before);
        assertThat(row(cookie).get("revoked_reason")).isEqualTo("LOGOUT");
    }

    @Test
    void 이미_회전한_토큰을_다시_내면_묶음이_폐기되고_이유를_알리지_않는_같은_401이다() {
        String c1 = flow.loginCookie(BASE, EMAIL);
        MvcTestResult first = flow.refresh(BASE.plusSeconds(60), c1);
        String c2 = cookieValue(first);
        long before = rows();
        String reference = text(flow.refresh(BASE.plusSeconds(61), null));

        MvcTestResult replay = flow.refresh(BASE.plusSeconds(120), c1);
        assertRejectedWithoutWrite(replay, before);
        assertThat(text(replay)).isEqualTo(reference);
        Map<String, Object> second = row(c2);
        assertThat(second.get("revoked_reason")).isEqualTo("REUSE_DETECTED");
        assertThat(at(second.get("revoked_at"))).isEqualTo(BASE.plusSeconds(120));
        assertThat(live()).isZero();

        MvcTestResult afterwards = flow.refresh(BASE.plusSeconds(180), c2);
        assertRejectedWithoutWrite(afterwards, before);
        assertThat(text(afterwards)).isEqualTo(reference);
        assertThat(a1Violations()).isZero();
    }

    @Test
    void 만료된_회전_토큰의_재사용도_묶음을_폐기한다() {
        String c1 = flow.loginCookie(BASE, EMAIL);
        String c2 = cookieValue(flow.refresh(BASE.plusSeconds(60), c1));
        Instant farFuture = BASE.plus(Duration.ofDays(60));

        assertThat(flow.refresh(farFuture, c1).getResponse().getStatus()).isEqualTo(401);
        assertThat(row(c2).get("revoked_reason")).isEqualTo("REUSE_DETECTED");
    }

    @Test
    void 시계가_새_토큰의_발급_시각보다_앞서도_폐기_시각은_발급_시각_아래로_내려가지_않는다() {
        String cookie = flow.loginCookie(null, EMAIL);
        Instant created = at(row(cookie).get("created_at"));

        MvcTestResult result = flow.refresh(created.minusSeconds(1), cookie);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(at(row(cookie).get("revoked_at"))).isEqualTo(created);
    }

    @Test
    void 다른_회원의_묶음은_건드리지_않는다() {
        signups.signup(new SignupRequest("other@example.test", "other", AuthFlow.PASSWORD));
        String mine = flow.loginCookie(BASE, EMAIL);
        String other = flow.loginCookie(BASE, "other@example.test");
        String rotated = cookieValue(flow.refresh(BASE.plusSeconds(60), mine));
        flow.refresh(BASE.plusSeconds(120), mine); // a replay ends only its own family

        assertThat(row(other).get("revoked_at")).isNull();
        assertThat(row(rotated).get("revoked_reason")).isEqualTo("REUSE_DETECTED");
        assertThat(live()).isEqualTo(1);
    }
}
