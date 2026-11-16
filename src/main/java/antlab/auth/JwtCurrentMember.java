package antlab.auth;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.member.Member;
import antlab.member.MemberKind;
import antlab.member.MemberRepository;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The only {@link CurrentMember} in every profile: the HUMAN member named by the access token's {@code sub}, or the
 * AGENT member a verified runner acts for ({@link AgentActingAuthentication}). The validator guarantees that
 * {@code sub} is a positive decimal long, so parsing cannot fail. A member row of the wrong kind, a subject without a
 * member row, and a call with no usable authentication in the security context (not reachable over HTTP, where the
 * protected paths require authentication) all fail closed with the same 401.
 */
@Component
public class JwtCurrentMember implements CurrentMember {

    private record Subject(long id, MemberKind kind) {
    }

    private final MemberRepository members;

    public JwtCurrentMember(MemberRepository members) {
        this.members = members;
    }

    @Override
    public Optional<Long> existingId() {
        Subject subject = subject();
        if (!members.existsByIdAndKind(subject.id(), subject.kind())) {
            throw unknownMember();
        }
        return Optional.of(subject.id());
    }

    @Override
    public long ensureId() {
        Subject subject = subject();
        Member member = members.findByIdForUpdate(subject.id()).orElseThrow(JwtCurrentMember::unknownMember);
        if (member.getKind() != subject.kind()) {
            throw unknownMember();
        }
        return member.getId();
    }

    private static Subject subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return new Subject(Long.parseLong(token.getToken().getSubject()), MemberKind.HUMAN);
        }
        if (authentication instanceof AgentActingAuthentication acting) {
            return new Subject(acting.agentMemberId(), MemberKind.AGENT);
        }
        throw unknownMember();
    }

    private static DomainException unknownMember() {
        return new DomainException(ErrorCode.UNAUTHENTICATED, "인증이 필요하다");
    }
}
