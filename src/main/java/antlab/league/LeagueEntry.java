package antlab.league;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.springframework.data.domain.Persistable;

/**
 * Append-only: one season in a league. The key is assigned, so the entity implements {@link Persistable}. The unique
 * keys of the table (one seat per member, one league per season) are the rules; a repeated insert fails there.
 */
@Entity
@Immutable
@IdClass(LeagueEntryId.class)
@Table(name = "league_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeagueEntry extends CreatedEntity implements Persistable<LeagueEntryId> {

    @Id
    @Column(name = "league_id", nullable = false)
    private Long leagueId;

    @Id
    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Transient
    private boolean fresh = true;

    public LeagueEntry(long leagueId, long seasonId, long memberId) {
        this.leagueId = leagueId;
        this.seasonId = seasonId;
        this.memberId = memberId;
    }

    @Override
    public LeagueEntryId getId() {
        return new LeagueEntryId(leagueId, seasonId);
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
