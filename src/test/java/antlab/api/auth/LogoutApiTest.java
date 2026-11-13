package antlab.api.auth;

import static antlab.api.auth.AuthFlow.BASE;
import static antlab.api.auth.AuthFlow.cookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.auth.RefreshTokens;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import antlab.support.IntegrationTest;
import antlab.support.ScriptedClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Import(ScriptedClockConfig.class)
@Transactional
class LogoutApiTest {

    private static final String EMAIL = "leaver@example.test";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SignupService signups;
    @Autowired
    ScriptedClock clock;

    AuthFlow flow;

    @BeforeEach
    void member() {
        clock.reset();
        flow = new AuthFlow(mvc, clock);
        signups.signup(new SignupRequest(EMAIL, "leaver", AuthFlow.PASSWORD));
    }

    private Map<String, Object> row(String raw) {
        return jdbc.queryForMap("select * from refresh_token where token_hash = ?", RefreshTokens.hash(raw));
    }

    private long rows() {
        return jdbc.queryForObject("select count(*) from refresh_token", Long.class);
    }

    private static void assertClearing(MvcTestResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).startsWith("antlab_refresh=;")
                .contains("Max-Age=0", "Path=/api/v1/auth", "Secure", "HttpOnly", "SameSite=Strict")
                .doesNotContain("Domain");
    }

    @Test
    void 살아_있는_쿠키는_204와_지우는_쿠키를_주고_행은_LOGOUT이며_이어서_갱신은_401이다() {
        String cookie = flow.loginCookie(BASE, EMAIL);
        Instant later = BASE.plusSeconds(30);

        assertClearing(flow.logout(later, cookie));

        Map<String, Object> row = row(cookie);
        assertThat(row.get("revoked_reason")).isEqualTo("LOGOUT");
        assertThat(((Timestamp) row.get("revoked_at")).toInstant()).isEqualTo(later);
        assertThat(flow.refresh(BASE.plusSeconds(60), cookie).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 쿠키가_없거나_모르는_값이거나_두_번째여도_204와_지우는_쿠키이고_쓰기가_없다() {
        String cookie = flow.loginCookie(BASE, EMAIL);
        assertClearing(flow.logout(BASE.plusSeconds(10), cookie));
        long before = rows();
        long revoked = jdbc.queryForObject("select count(*) from refresh_token where revoked_at is not null", Long.class);

        assertClearing(flow.logout(BASE.plusSeconds(20), null));
        assertClearing(flow.logout(BASE.plusSeconds(30), RefreshTokens.newRawValue()));
        assertClearing(flow.logout(BASE.plusSeconds(40), cookie));

        assertThat(rows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where revoked_at is not null", Long.class))
                .isEqualTo(revoked);
    }

    @Test
    void 한_묶음을_로그아웃해도_같은_회원의_다른_묶음은_산다() {
        String first = flow.loginCookie(BASE, EMAIL);
        String second = flow.loginCookie(BASE, EMAIL);

        assertClearing(flow.logout(BASE.plusSeconds(10), first));

        assertThat(row(first).get("revoked_reason")).isEqualTo("LOGOUT");
        assertThat(row(second).get("revoked_at")).isNull();
        assertThat(flow.refresh(BASE.plusSeconds(20), second).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 회전한_토큰으로_로그아웃하면_살아_있는_토큰이_REUSE_DETECTED가_된다() {
        String c1 = flow.loginCookie(BASE, EMAIL);
        String c2 = cookieValue(flow.refresh(BASE.plusSeconds(60), c1));

        assertClearing(flow.logout(BASE.plusSeconds(120), c1));

        assertThat(row(c2).get("revoked_reason")).isEqualTo("REUSE_DETECTED");
        assertThat(row(c1).get("revoked_reason")).isEqualTo("ROTATED");
    }

    @Test
    void 만료된_살아_있는_토큰도_로그아웃이_LOGOUT으로_폐기한다() {
        String cookie = flow.loginCookie(BASE, EMAIL);

        assertClearing(flow.logout(BASE.plus(Duration.ofDays(30)), cookie));

        assertThat(row(cookie).get("revoked_reason")).isEqualTo("LOGOUT");
    }

    @Test
    void 시계가_토큰의_발급_시각보다_앞서도_로그아웃은_204이고_폐기_시각은_발급_시각이다() {
        String cookie = flow.loginCookie(null, EMAIL);
        Instant created = ((Timestamp) row(cookie).get("created_at")).toInstant();

        assertClearing(flow.logout(created.minusSeconds(1), cookie));

        assertThat(((Timestamp) row(cookie).get("revoked_at")).toInstant()).isEqualTo(created);
    }

    @Test
    void 로그인_갱신_로그아웃은_자격_없이_닿는다() {
        String cookie = flow.loginCookie(BASE, EMAIL);
        assertThat(flow.refresh(BASE.plusSeconds(5), cookie).getResponse().getStatus()).isEqualTo(200);
        assertThat(flow.logout(BASE.plusSeconds(6), null).getResponse().getStatus()).isEqualTo(204);
    }
}
