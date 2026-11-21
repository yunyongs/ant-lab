package antlab.league;

import antlab.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mutable entity: a manual same-seed group. Every column but {@code status} and {@code closedAt} is fixed at creation
 * ({@code updatable = false}); {@link #close(Instant)} is the only state change. The seed stays on the server: it is
 * never put in a response, and {@link #toString()} leaves it out.
 */
@Entity
@Table(name = "league")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class League extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "owner_member_id", nullable = false, updatable = false)
    private Long ownerMemberId;

    @Column(name = "owner_kind", length = 5, nullable = false, insertable = false, updatable = false)
    private String ownerKind;

    @Column(name = "name", length = 50, nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 6, nullable = false)
    private LeagueStatus status;

    @Column(name = "seed", nullable = false, updatable = false)
    private long seed;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "path_length", nullable = false, updatable = false)
    private int pathLength;

    @Column(name = "base_date", nullable = false, updatable = false)
    private LocalDate baseDate;

    @Column(name = "engine_version", length = 20, nullable = false, updatable = false)
    private String engineVersion;

    @Column(name = "data_snapshot_date", nullable = false, updatable = false)
    private LocalDate dataSnapshotDate;

    @Column(name = "initial_cash", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal initialCash;

    @Column(name = "fee_rate_kr", precision = 12, scale = 8, nullable = false, updatable = false)
    private BigDecimal feeRateKr;

    @Column(name = "fee_rate_us", precision = 12, scale = 8, nullable = false, updatable = false)
    private BigDecimal feeRateUs;

    @Column(name = "fx_spread_rate", precision = 12, scale = 8, nullable = false, updatable = false)
    private BigDecimal fxSpreadRate;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_entries", nullable = false, updatable = false)
    private int maxEntries;

    /**
     * Set only on a sibling created with the same seed as an earlier league, and then always the group root's id.
     * Internal: it names no seed, but a group is a reason to hold a season's review (AG-05).
     */
    @Column(name = "seed_league_id", nullable = true, updatable = false)
    private Long seedLeagueId;

    /** Set exactly while the status is CLOSED (ck_league_closed_at). */
    @Column(name = "closed_at", nullable = true)
    private Instant closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public League(long ownerMemberId, String name, long seed, int pathLength, LocalDate baseDate, String engineVersion,
            LocalDate dataSnapshotDate, BigDecimal initialCash, BigDecimal feeRateKr, BigDecimal feeRateUs,
            BigDecimal fxSpreadRate, int maxEntries) {
        if (pathLength <= 0) {
            throw new IllegalArgumentException("path length must be positive: " + pathLength);
        }
        this.ownerMemberId = ownerMemberId;
        this.name = name;
        this.status = LeagueStatus.OPEN;
        this.seed = seed;
        this.pathLength = pathLength;
        this.baseDate = baseDate;
        this.engineVersion = engineVersion;
        this.dataSnapshotDate = dataSnapshotDate;
        this.initialCash = initialCash;
        this.feeRateKr = feeRateKr;
        this.feeRateUs = feeRateUs;
        this.fxSpreadRate = fxSpreadRate;
        this.maxEntries = maxEntries;
    }

    /** Stops new entries. Closing a closed league is the caller's decision, not a state change. */
    public void close(Instant now) {
        if (status == LeagueStatus.CLOSED) {
            throw new IllegalStateException("already closed: " + id);
        }
        this.status = LeagueStatus.CLOSED;
        this.closedAt = now;
    }

    @Override
    public String toString() {
        return "League[id=" + id + ", status=" + status + "]";
    }
}
