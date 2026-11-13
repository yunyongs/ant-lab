package antlab.auth;

import antlab.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Mutable entity: stores only the token hash. {@link #revoke} is the only state change. */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "parent_id", nullable = true)
    private Long parentId;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = true)
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 14, nullable = true)
    private RevokeReason revokedReason;

    public RefreshToken(long memberId, UUID familyId, Long parentId, String tokenHash, Instant expiresAt) {
        this.memberId = memberId;
        this.familyId = familyId;
        this.parentId = parentId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public void revoke(RevokeReason reason, Instant at) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(at, "at");
        if (revokedAt != null) {
            throw new IllegalStateException("이미 폐기된 토큰이다: " + revokedReason);
        }
        this.revokedAt = at;
        this.revokedReason = reason;
    }
}
