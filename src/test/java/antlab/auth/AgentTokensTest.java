package antlab.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.common.error.DomainException;
import antlab.member.Member;
import antlab.member.MemberRepository;
import antlab.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Agents get no access or refresh tokens: they act only through the runner. */
@IntegrationTest
@Transactional
class AgentTokensTest {

    @Autowired
    AccessTokenIssuer issuer;
    @Autowired
    TokenRotationService rotation;
    @Autowired
    LoginService logins;
    @Autowired
    RefreshTokenRepository refreshTokens;
    @Autowired
    MemberRepository members;

    @Test
    void no_access_token_is_signed_for_an_agent_member() {
        Member agent = members.save(Member.agent("에이전트"));
        assertThatThrownBy(() -> issuer.issue(agent, Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_refresh_token_row_of_an_agent_cannot_be_rotated_into_an_access_token() {
        Member agent = members.save(Member.agent("에이전트"));
        String raw = RefreshTokens.newRawValue();
        refreshTokens.save(new RefreshToken(agent.getId(), UUID.randomUUID(), null, RefreshTokens.hash(raw),
                Instant.now().plus(Duration.ofDays(1))));

        assertThatThrownBy(() -> rotation.refresh(raw)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void login_by_email_cannot_reach_an_agent_member() {
        members.save(Member.agent("에이전트"));
        assertThatThrownBy(() -> logins.login("agent@example.invalid", "any-password-123"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getCode().name()).isEqualTo("INVALID_CREDENTIALS"));
    }
}
