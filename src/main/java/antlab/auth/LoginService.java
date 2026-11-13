package antlab.auth;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.Member;
import antlab.member.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

    private final MemberRepository members;
    private final RefreshTokenRepository tokens;
    private final PasswordCheck passwords;
    private final AccessTokenIssuer issuer;
    private final AuthProperties properties;
    private final Clock clock;

    public LoginService(MemberRepository members, RefreshTokenRepository tokens, PasswordCheck passwords,
            AccessTokenIssuer issuer, AuthProperties properties, Clock clock) {
        this.members = members;
        this.tokens = tokens;
        this.passwords = passwords;
        this.issuer = issuer;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedTokens login(String email, String password) {
        // The only clock read of the request: expires_at and the token's iat come from the same instant.
        Instant now = clock.instant();
        Member member = members.findByEmail(email).orElse(null);
        if (!passwords.matches(password, member == null ? null : member.getPassword())) {
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS, "이메일이나 비밀번호가 맞지 않다");
        }
        String raw = RefreshTokens.newRawValue();
        tokens.save(new RefreshToken(member.getId(), UUID.randomUUID(), null, RefreshTokens.hash(raw),
                now.plus(properties.refreshTokenTtl())));
        return new IssuedTokens(issuer.issue(member, now), properties.accessTokenTtl().toSeconds(), raw,
                properties.refreshTokenTtl());
    }
}
