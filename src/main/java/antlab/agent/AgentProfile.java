package antlab.agent;

import antlab.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * Mutable entity: the agent member's profile. Its key is the member id (assigned, not generated), so it implements
 * {@link Persistable} to make {@code save()} insert instead of merge. {@code member_kind} and {@code owner_kind} are
 * filled by column defaults and pinned by the composite foreign keys, so only an AGENT member can have a profile
 * and only a HUMAN member can own one. {@link #retire()} is the only state change.
 */
@Entity
@Table(name = "agent_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentProfile extends AuditedEntity implements Persistable<Long> {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "member_kind", length = 5, nullable = false, insertable = false, updatable = false)
    private String memberKind;

    @Column(name = "owner_member_id", nullable = false, updatable = false)
    private Long ownerMemberId;

    @Column(name = "owner_kind", length = 5, nullable = false, insertable = false, updatable = false)
    private String ownerKind;

    @Column(name = "persona_key", length = 64, nullable = false, updatable = false)
    private String personaKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_route", length = 10, nullable = false, updatable = false)
    private ModelRoute modelRoute;

    @Column(name = "model_id", length = 200, nullable = false, updatable = false)
    private String modelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 7, nullable = false)
    private AgentStatus status;

    @Transient
    private boolean fresh = true;

    public AgentProfile(long memberId, long ownerMemberId, String personaKey, ModelRoute modelRoute, String modelId) {
        this.memberId = memberId;
        this.ownerMemberId = ownerMemberId;
        this.personaKey = Objects.requireNonNull(personaKey, "personaKey");
        this.modelRoute = Objects.requireNonNull(modelRoute, "modelRoute");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.status = AgentStatus.ACTIVE;
    }

    public void retire() {
        if (status == AgentStatus.RETIRED) {
            throw new IllegalStateException("already retired: " + memberId);
        }
        this.status = AgentStatus.RETIRED;
    }

    @Override
    public Long getId() {
        return memberId;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.fresh = false;
    }
}
