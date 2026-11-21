package antlab.agent.memory;

import antlab.agent.ReflectionOutcome;
import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
 * Append-only: what an agent wrote about one finished season, after the leak filter. An accepted row holds the
 * {@code season-reflection/1} body, a discarded row only the fixed discard codes. The key is the season id (assigned),
 * so the entity implements {@link Persistable}. {@code body} and {@code discardCodes} hold JSON text.
 */
@Entity
@Immutable
@Table(name = "agent_reflection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentReflection extends CreatedEntity implements Persistable<Long> {

    @Id
    @Column(name = "season_id")
    private Long seasonId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 9, nullable = false)
    private ReflectionOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = true)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "discard_codes", nullable = true)
    private String discardCodes;

    @Column(name = "filter_version", length = 32, nullable = false)
    private String filterVersion;

    @Transient
    private boolean fresh = true;

    /** An accepted row carries the body and no codes, a discarded row the codes and no body (the CHECKs say the same). */
    public AgentReflection(long seasonId, long memberId, ReflectionOutcome outcome, String body, String discardCodes,
            String filterVersion) {
        if ((outcome == ReflectionOutcome.ACCEPTED) != (body != null)
                || (outcome == ReflectionOutcome.DISCARDED) != (discardCodes != null)) {
            throw new IllegalArgumentException("body and discard codes do not fit the outcome " + outcome);
        }
        this.seasonId = seasonId;
        this.memberId = memberId;
        this.outcome = outcome;
        this.body = body;
        this.discardCodes = discardCodes;
        this.filterVersion = filterVersion;
    }

    public static AgentReflection accepted(long seasonId, long memberId, String bodyJson, String filterVersion) {
        return new AgentReflection(seasonId, memberId, ReflectionOutcome.ACCEPTED, bodyJson, null, filterVersion);
    }

    public static AgentReflection discarded(long seasonId, long memberId, String discardCodesJson,
            String filterVersion) {
        return new AgentReflection(seasonId, memberId, ReflectionOutcome.DISCARDED, null, discardCodesJson,
                filterVersion);
    }

    @Override
    public Long getId() {
        return seasonId;
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
