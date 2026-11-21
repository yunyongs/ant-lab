package antlab.agent.memory;

import antlab.agent.ReflectionOutcome;
import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Append-only: one released memory version of an agent, the operating note ({@code operating-note/1}, JSON text)
 * written after an accepted reflection of {@code sourceSeasonId}. The composite foreign key to that reflection
 * (outcome fixed to ACCEPTED) is what keeps a discarded reflection from making a version; the column
 * {@code source_outcome} is left to its database default for that reason. The key is assigned, so the entity
 * implements {@link Persistable}.
 */
@Entity
@Immutable
@IdClass(AgentMemoryId.class)
@Table(name = "agent_memory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentMemory extends CreatedEntity implements Persistable<AgentMemoryId> {

    @Id
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Id
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "source_season_id", nullable = false)
    private Long sourceSeasonId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_outcome", length = 9, nullable = false, insertable = false, updatable = false)
    private ReflectionOutcome sourceOutcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "playbook", nullable = false)
    private String playbook;

    @Column(name = "playbook_carried", nullable = false)
    private boolean playbookCarried;

    @Column(name = "filter_version", length = 32, nullable = false)
    private String filterVersion;

    @Transient
    private boolean fresh = true;

    public AgentMemory(long memberId, int version, long sourceSeasonId, String playbookJson, boolean playbookCarried,
            String filterVersion) {
        this.memberId = memberId;
        this.version = version;
        this.sourceSeasonId = sourceSeasonId;
        this.playbook = playbookJson;
        this.playbookCarried = playbookCarried;
        this.filterVersion = filterVersion;
    }

    @Override
    public AgentMemoryId getId() {
        return new AgentMemoryId(memberId, version);
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
