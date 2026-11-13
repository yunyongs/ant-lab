package antlab.auth;

import antlab.member.Member;
import antlab.member.MemberRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh rotation and logout. Both follow erd §9: look up the presented hash, lock the family root, only then read the
 * presented row, and check {@code ROTATED} before expiry.
 */
@Service
public class TokenRotationService {

    private final RefreshTokenRepository tokens;
    private final MemberRepository members;
    private final AccessTokenIssuer issuer;
    private final AuthProperties properties;
    private final Clock clock;
    private final EntityManager entityManager;

    public TokenRotationService(RefreshTokenRepository tokens, MemberRepository members, AccessTokenIssuer issuer,
            AuthProperties properties, Clock clock, EntityManager entityManager) {
        this.entityManager = entityManager;
        this.tokens = tokens;
        this.members = members;
        this.issuer = issuer;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RefreshResult refresh(String raw) {
        Instant now = clock.instant();
        Optional<RefreshToken> presented = lockAndRead(raw);
        if (presented.isEmpty()) {
            return RefreshResult.rejected();
        }
        RefreshToken token = presented.get();
        if (token.getRevokedReason() == RevokeReason.ROTATED) {
            revokeLive(token.getFamilyId(), RevokeReason.REUSE_DETECTED, now);
            return RefreshResult.rejected();
        }
        if (token.getRevokedAt() != null || !now.isBefore(token.getExpiresAt())) {
            return RefreshResult.rejected();
        }
        token.revoke(RevokeReason.ROTATED, revokedAt(token, now));
        // The old row must be revoked before the child is inserted: uq_refresh_token_family_live allows one live row.
        entityManager.flush();
        String next = RefreshTokens.newRawValue();
        tokens.save(new RefreshToken(token.getMemberId(), token.getFamilyId(), token.getId(), RefreshTokens.hash(next),
                now.plus(properties.refreshTokenTtl())));
        Member member = members.findById(token.getMemberId()).orElseThrow();
        return new RefreshResult.Issued(new IssuedTokens(issuer.issue(member, now),
                properties.accessTokenTtl().toSeconds(), next, properties.refreshTokenTtl()));
    }

    /** Ends the login of the presented token. Unknown or empty values do nothing. */
    @Transactional
    public void logout(String raw) {
        Instant now = clock.instant();
        Optional<RefreshToken> presented = lockAndRead(raw);
        if (presented.isEmpty()) {
            return;
        }
        RefreshToken token = presented.get();
        RevokeReason reason = token.getRevokedReason() == RevokeReason.ROTATED
                ? RevokeReason.REUSE_DETECTED : RevokeReason.LOGOUT;
        revokeLive(token.getFamilyId(), reason, now);
    }

    private Optional<RefreshToken> lockAndRead(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String hash = RefreshTokens.hash(raw);
        Optional<UUID> family = tokens.findFamilyIdByTokenHash(hash);
        if (family.isEmpty()) {
            return Optional.empty();
        }
        tokens.findFamilyRootForUpdate(family.get());
        // The first entity load of the request happens here, under the lock, so it sees the committed state.
        return tokens.findByTokenHash(hash);
    }

    private void revokeLive(UUID family, RevokeReason reason, Instant now) {
        tokens.findLiveInFamily(family).ifPresent(live -> live.revoke(reason, revokedAt(live, now)));
        entityManager.flush();
    }

    /** The request's clock read can precede the DB-assigned created_at of a token a concurrent request just issued. */
    private static Instant revokedAt(RefreshToken token, Instant now) {
        return now.isBefore(token.getCreatedAt()) ? token.getCreatedAt() : now;
    }
}
