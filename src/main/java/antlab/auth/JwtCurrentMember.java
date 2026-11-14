package antlab.auth;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.member.MemberRepository;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The member named by the access token's {@code sub}, used when the fixed member is off. The validator guarantees
 * that {@code sub} is a positive decimal long, so parsing cannot fail. A subject without a member row answers 401
 * like any other bad token; a request with no JWT in the security context fails closed with 503.
 */
@Component
@ConditionalOnProperty(prefix = "antlab.single-member", name = "enabled", havingValue = "false")
public class JwtCurrentMember implements CurrentMember {

    private final MemberRepository members;

    public JwtCurrentMember(MemberRepository members) {
        this.members = members;
    }

    @Override
    public Optional<Long> existingId() {
        long id = subject();
        if (!members.existsById(id)) {
            throw unknownMember();
        }
        return Optional.of(id);
    }

    @Override
    public long ensureId() {
        return members.findByIdForUpdate(subject()).orElseThrow(JwtCurrentMember::unknownMember).getId();
    }

    private static long subject() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            throw new DomainException(ErrorCode.MEMBER_UNAVAILABLE, "현재 회원을 알 수 없다");
        }
        return Long.parseLong(token.getToken().getSubject());
    }

    private static DomainException unknownMember() {
        return new DomainException(ErrorCode.UNAUTHENTICATED, "인증이 필요하다");
    }
}
