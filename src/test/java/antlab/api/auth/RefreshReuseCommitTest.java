package antlab.api.auth;

import static antlab.api.auth.AuthFlow.BASE;
import static antlab.api.auth.AuthFlow.cookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.auth.RefreshTokens;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import antlab.support.IntegrationTest;
import antlab.support.ScriptedClock;
import antlab.support.SeasonAccountCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Not transactional: the request commits for real, so a read on another connection shows whether the reuse revoke survived
 * the 401. A domain exception thrown inside the service would roll the revoke back and fail this test.
 */
@IntegrationTest
@Import(ScriptedClockConfig.class)
class RefreshReuseCommitTest {

    private static final String EMAIL = "commit@example.test";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SignupService signups;
    @Autowired
    ScriptedClock clock;

    long memberId;

    @BeforeEach
    void member() {
        clock.reset();
        memberId = signups.signup(new SignupRequest(EMAIL, "committer", AuthFlow.PASSWORD)).id();
    }

    @AfterEach
    void cleanup() {
        SeasonAccountCleaner.delete(jdbc, List.of(), List.of(memberId), List.of(), List.of(), List.of());
    }

    @Test
    void 재사용을_알리는_401_뒤에도_REUSE_DETECTED_폐기는_커밋되어_있다() {
        AuthFlow flow = new AuthFlow(mvc, clock);
        String c1 = flow.loginCookie(BASE, EMAIL);
        String c2 = cookieValue(flow.refresh(BASE.plusSeconds(60), c1));

        assertThat(flow.refresh(BASE.plusSeconds(120), c1).getResponse().getStatus()).isEqualTo(401);

        // a new JdbcTemplate call outside any transaction: its own connection, so only committed data is visible
        assertThat(jdbc.queryForObject("select revoked_reason from refresh_token where token_hash = ?", String.class,
                RefreshTokens.hash(c2))).isEqualTo("REUSE_DETECTED");
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where member_id = ? and revoked_at is null", Long.class, memberId)).isZero();
    }
}
