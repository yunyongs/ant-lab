package antlab.api.auth;

import static antlab.api.auth.AuthFlow.cookieValue;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.auth.RefreshTokens;
import antlab.member.SignupRequest;
import antlab.member.SignupService;
import antlab.support.IntegrationTest;
import antlab.support.LockWaitSupport;
import antlab.support.SeasonAccountCleaner;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two requests of one family against a held family-root lock. The test connection locks the root, request X starts and is
 * seen waiting, request Y starts and is seen waiting, then the lock is released: PostgreSQL grants the row lock to X first, so
 * the order is fixed without sleeping. Real clock on purpose: Y's clock read precedes the child that X creates, which is
 * what the revoke-time guard is for.
 */
@IntegrationTest
class RefreshRaceTest {

    private static final String EMAIL = "race@example.test";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SignupService signups;
    @Autowired
    PlatformTransactionManager txManager;

    AuthFlow flow;
    long memberId;
    UUID family;
    String c1;
    String c2;

    @BeforeEach
    void familyWithOneRotation() {
        flow = new AuthFlow(mvc, null);
        memberId = signups.signup(new SignupRequest(EMAIL, "racer", AuthFlow.PASSWORD)).id();
        c1 = flow.loginCookie(null, EMAIL);
        c2 = cookieValue(flow.refresh(null, c1));
        family = jdbc.queryForObject("select family_id from refresh_token where member_id = ? limit 1", UUID.class,
                memberId);
    }

    @AfterEach
    void cleanup() {
        SeasonAccountCleaner.delete(jdbc, List.of(), List.of(memberId), List.of(), List.of(), List.of());
    }

    private List<MvcTestResult> raceBehindRootLock(Supplier<MvcTestResult> x, Supplier<MvcTestResult> y) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicReference<Future<MvcTestResult>> fx = new AtomicReference<>();
            AtomicReference<Future<MvcTestResult>> fy = new AtomicReference<>();
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                jdbc.queryForObject("select id from refresh_token where family_id = ? and parent_id is null"
                        + " for no key update", Long.class, family);
                long base = LockWaitSupport.waiters(jdbc);
                try {
                    fx.set(pool.submit(x::get));
                    LockWaitSupport.awaitWaitersAbove(jdbc, base, 1);
                    fy.set(pool.submit(y::get));
                    LockWaitSupport.awaitWaitersAbove(jdbc, base, 2);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                assertThat(fx.get().isDone()).as("X waits for the family root").isFalse();
                assertThat(fy.get().isDone()).as("Y waits for the family root").isFalse();
            });
            return List.of(fx.get().get(30, TimeUnit.SECONDS), fy.get().get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private long live() {
        return jdbc.queryForObject("select count(*) from refresh_token where family_id = ? and revoked_at is null",
                Long.class, family);
    }

    private void assertRuleA1() {
        assertThat(jdbc.queryForObject("select count(*) from refresh_token t where t.family_id = ? and (t.revoked_reason = 'ROTATED')"
                + " <> exists (select 1 from refresh_token c where c.parent_id = t.id)", Long.class, family)).isZero();
    }

    private String reason(String raw) {
        return jdbc.queryForObject("select revoked_reason from refresh_token where token_hash = ?", String.class,
                RefreshTokens.hash(raw));
    }

    @Test
    void 살아_있는_토큰의_갱신이_먼저면_성공하고_그다음_부모_토큰의_재사용이_그_자식을_폐기한다() throws Exception {
        List<MvcTestResult> r = raceBehindRootLock(() -> flow.refresh(null, c2), () -> flow.refresh(null, c1));

        assertThat(r.get(0).getResponse().getStatus()).isEqualTo(200);
        assertThat(r.get(1).getResponse().getStatus()).isEqualTo(401);
        assertThat(reason(cookieValue(r.get(0)))).isEqualTo("REUSE_DETECTED");
        assertThat(live()).isZero();
        assertRuleA1();
    }

    @Test
    void 부모_토큰의_재사용이_먼저면_살아_있던_토큰이_폐기되고_뒤따른_갱신도_401이며_500은_없다() throws Exception {
        List<MvcTestResult> r = raceBehindRootLock(() -> flow.refresh(null, c1), () -> flow.refresh(null, c2));

        assertThat(r.get(0).getResponse().getStatus()).isEqualTo(401);
        assertThat(r.get(1).getResponse().getStatus()).isEqualTo(401);
        assertThat(reason(c2)).isEqualTo("REUSE_DETECTED");
        assertThat(live()).isZero();
        assertRuleA1();
    }

    @Test
    void 같은_살아_있는_토큰을_두_번_내면_하나만_성공하고_묶음에_살아_있는_토큰이_남지_않는다() throws Exception {
        List<MvcTestResult> r = raceBehindRootLock(() -> flow.refresh(null, c2), () -> flow.refresh(null, c2));

        assertThat(r.get(0).getResponse().getStatus()).isEqualTo(200);
        assertThat(r.get(1).getResponse().getStatus()).isEqualTo(401);
        assertThat(reason(cookieValue(r.get(0)))).isEqualTo("REUSE_DETECTED");
        assertThat(live()).isZero();
        assertRuleA1();
    }

    @Test
    void 로그아웃과_갱신이_겹쳐도_묶음에_살아_있는_토큰이_남지_않고_500은_없다() throws Exception {
        List<MvcTestResult> r = raceBehindRootLock(() -> flow.refresh(null, c2), () -> flow.logout(null, c2));

        assertThat(r.get(0).getResponse().getStatus()).isEqualTo(200);
        assertThat(r.get(1).getResponse().getStatus()).isEqualTo(204);
        assertThat(live()).isZero();
        assertRuleA1();
    }
}
