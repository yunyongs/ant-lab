package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/** The current member of an acting runner: the agent it acts for, and the kinds never cross. */
@IntegrationTest
@Transactional
class JwtCurrentMemberAgentTest {

    private static final long NO_SUCH_MEMBER = 999_999_999L;

    @Autowired
    JwtCurrentMember currentMember;
    @Autowired
    MemberRepository members;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private long agent() {
        return members.save(Member.agent("에이전트")).getId();
    }

    private long human() {
        return members.save(new Member("human-" + UUID.randomUUID() + "@example.test", "사람", "hash")).getId();
    }

    private static void actAs(long agentId) {
        SecurityContextHolder.getContext().setAuthentication(new AgentActingAuthentication(agentId));
    }

    private static void bearerFor(long subject) {
        Jwt jwt = Jwt.withTokenValue("not-a-real-token").header("alg", "HS256").subject(String.valueOf(subject))
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z")).expiresAt(Instant.parse("2026-01-01T00:15:00Z")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static void assertUnauthenticated(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void an_acting_runner_is_the_agent_member() {
        long agent = agent();
        actAs(agent);
        assertThat(currentMember.existingId()).isEqualTo(Optional.of(agent));
        assertThat(currentMember.ensureId()).isEqualTo(agent);
    }

    @Test
    void acting_for_a_human_member_id_is_refused() {
        actAs(human());
        assertThatThrownBy(currentMember::existingId).satisfies(JwtCurrentMemberAgentTest::assertUnauthenticated);
        assertThatThrownBy(currentMember::ensureId).satisfies(JwtCurrentMemberAgentTest::assertUnauthenticated);
    }

    @Test
    void acting_for_a_missing_member_is_refused() {
        actAs(NO_SUCH_MEMBER);
        assertThatThrownBy(currentMember::existingId).satisfies(JwtCurrentMemberAgentTest::assertUnauthenticated);
        assertThatThrownBy(currentMember::ensureId).satisfies(JwtCurrentMemberAgentTest::assertUnauthenticated);
    }

    @Test
    void a_human_token_naming_an_agent_member_is_refused() {
        bearerFor(agent());
        assertThatThrownBy(currentMember::existingId).satisfies(JwtCurrentMemberAgentTest::assertUnauthenticated);
        assertThatThrownBy(currentMember::ensureId).satisfies(JwtCurrentMemberAgentTest::assertUnauthenticated);
    }

    @Test
    void a_human_token_naming_a_human_still_works() {
        long human = human();
        bearerFor(human);
        assertThat(currentMember.existingId()).isEqualTo(Optional.of(human));
        assertThat(currentMember.ensureId()).isEqualTo(human);
    }

    @Test
    void an_acting_agent_write_locks_the_member_row_in_one_statement() {
        long agent = agent();
        actAs(agent);
        SqlRecorder.start();
        currentMember.ensureId();
        List<String> sqls = SqlRecorder.stop();
        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0).toLowerCase(Locale.ROOT)).contains("from member").contains("for no key update");
    }

    @Test
    void the_acting_authentication_holds_no_credential() {
        AgentActingAuthentication acting = new AgentActingAuthentication(7);
        assertThat(acting.getCredentials()).isNull();
        assertThat(acting.isAuthenticated()).isTrue();
        assertThat(acting.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_AGENT_RUNNER");
    }
}
