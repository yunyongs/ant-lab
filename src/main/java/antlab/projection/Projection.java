package antlab.projection;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only: one projection of a season at START or END, written once. {@code request} is the portfolio the engine was
 * asked about and {@code result} the engine's portfolios as ratios; neither holds a seed, a month or a won amount other
 * than {@code totalValue}, which turns the ratios into won when a row is read. Neither JSON is sent to a client as is.
 */
@Entity
@Immutable
@Table(name = "projection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Projection extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", length = 5, nullable = false)
    private ProjectionPhase phase;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    @Column(name = "total_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalValue;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "horizon_turns", nullable = false)
    private int horizonTurns;

    @Column(name = "path_count", nullable = false)
    private int pathCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request", nullable = false)
    private String request;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", nullable = false)
    private String result;

    @Column(name = "projection_version", length = 20, nullable = false)
    private String projectionVersion;

    @Column(name = "engine_version", length = 20, nullable = false)
    private String engineVersion;

    @Column(name = "history_digest", length = 64, nullable = false)
    private String historyDigest;

    public Projection(long seasonId, ProjectionPhase phase, int turnNo, BigDecimal totalValue, int horizonTurns,
            int pathCount, String request, String result, String projectionVersion, String engineVersion,
            String historyDigest) {
        this.seasonId = seasonId;
        this.phase = phase;
        this.turnNo = turnNo;
        this.totalValue = totalValue;
        this.horizonTurns = horizonTurns;
        this.pathCount = pathCount;
        this.request = request;
        this.result = result;
        this.projectionVersion = projectionVersion;
        this.engineVersion = engineVersion;
        this.historyDigest = historyDigest;
    }
}
