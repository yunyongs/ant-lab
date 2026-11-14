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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/** The token's member, the only current member: what each call returns, and the lock it takes. */
@IntegrationTest
@Transactional
class JwtCurrentMemberTest {

    private static final long NO_SUCH_MEMBER = 999_999_999L;

    @Autowired
    JwtCurrentMember currentMember;
    @Autowired
    MemberRepository members;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(long subject) {
        Jwt jwt = Jwt.withTokenValue("not-a-real-token").header("alg", "HS256").subject(String.valueOf(subject))
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z")).expiresAt(Instant.parse("2026-01-01T00:15:00Z")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Autowired
    JdbcTemplate jdbc;

    private long members() {
        return jdbc.queryForObject("SELECT count(*) FROM member", Long.class);
    }

    private Member newMember() {
        return members.save(new Member("jwt-" + UUID.randomUUID() + "@example.test", "가짜 회원", "hash"));
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void 인증이_없으면_두_메서드가_UNAUTHENTICATED이다() {
        assertThatThrownBy(currentMember::existingId).satisfies(e -> assertCode(e, ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(currentMember::ensureId).satisfies(e -> assertCode(e, ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void JWT가_아닌_인증도_UNAUTHENTICATED이다() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1", "x", List.of()));
        assertThatThrownBy(currentMember::existingId).satisfies(e -> assertCode(e, ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(currentMember::ensureId).satisfies(e -> assertCode(e, ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void 있는_회원의_sub는_그_번호를_돌려준다() {
        long id = newMember().getId();
        authenticateAs(id);
        assertThat(currentMember.existingId()).isEqualTo(Optional.of(id));
        assertThat(currentMember.ensureId()).isEqualTo(id);
    }

    @Test
    void 없는_회원의_sub는_두_메서드가_UNAUTHENTICATED이다() {
        authenticateAs(NO_SUCH_MEMBER);
        long rows = members();
        assertThatThrownBy(currentMember::existingId).satisfies(e -> assertCode(e, ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(currentMember::ensureId).satisfies(e -> assertCode(e, ErrorCode.UNAUTHENTICATED));
        assertThat(members()).as("no member row is created").isEqualTo(rows);
    }

    @Test
    void 쓰기_조회는_회원_행을_FOR_NO_KEY_UPDATE로_잠그고_회원을_만들지_않는다() {
        long id = newMember().getId();
        authenticateAs(id);
        long rows = members();

        SqlRecorder.start();
        currentMember.ensureId();
        List<String> sqls = SqlRecorder.stop();

        assertThat(sqls).hasSize(1);
        String sql = sqls.get(0).toLowerCase(Locale.ROOT);
        assertThat(sql).contains("from member").contains("for no key update");
        assertThat(members()).as("no member row is created").isEqualTo(rows);
    }

    @Test
    void 읽기_조회는_행을_잠그지_않는다() {
        authenticateAs(newMember().getId());
        SqlRecorder.start();
        currentMember.existingId();
        List<String> sqls = SqlRecorder.stop();
        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0).toLowerCase(Locale.ROOT)).doesNotContain("for no key update").doesNotContain("for update");
    }
}
