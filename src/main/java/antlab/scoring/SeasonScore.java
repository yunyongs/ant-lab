package antlab.scoring;

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
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Append-only: the score of one finished season, written once. The key is the season id (assigned), so the entity
 * implements {@link Persistable}. The composite foreign key (season_id, member_id) ties the row to a season of the
 * same member. A formula change is a new {@code scoreVersion} for new rows; stored rows are never rewritten.
 * {@code inputs} holds the JSON object of the values the score was computed from; it is never sent to a client.
 */
@Entity
@Immutable
@Table(name = "season_score")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonScore extends CreatedEntity implements Persistable<Long> {

    @Id
    @Column(name = "season_id")
    private Long seasonId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "score_version", length = 20, nullable = false)
    private String scoreVersion;

    @Column(name = "score", precision = 5, scale = 2, nullable = true)
    private BigDecimal score;

    @Column(name = "result_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal resultScore;

    @Column(name = "process_score", precision = 5, scale = 2, nullable = false)
    private BigDecimal processScore;

    @Column(name = "sharpe", precision = 14, scale = 8, nullable = false)
    private BigDecimal sharpe;

    @Column(name = "bench_sharpe", precision = 14, scale = 8, nullable = false)
    private BigDecimal benchSharpe;

    @Column(name = "sharpe_diff", precision = 14, scale = 8, nullable = false)
    private BigDecimal sharpeDiff;

    @Column(name = "sharpe_diff_percentile", precision = 9, scale = 8, nullable = true)
    private BigDecimal sharpeDiffPercentile;

    @Column(name = "luck_percentile", precision = 9, scale = 8, nullable = true)
    private BigDecimal luckPercentile;

    @Column(name = "risk_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal riskScore;

    @Column(name = "discipline_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal disciplineScore;

    @Column(name = "diversification_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal diversificationScore;

    @Column(name = "turnover_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal turnoverScore;

    @Column(name = "consistency_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal consistencyScore;

    @Column(name = "formation_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal formationScore;

    @Column(name = "role_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal roleScore;

    @Column(name = "noise_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal noiseScore;

    @Column(name = "nudge_score", precision = 5, scale = 2, nullable = true)
    private BigDecimal nudgeScore;

    @Column(name = "process_items", nullable = false)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int processItems;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating_exclusion", length = 11, nullable = true)
    private RatingExclusion ratingExclusion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs", nullable = false)
    private String inputs;

    @Transient
    private boolean fresh = true;

    public SeasonScore(long seasonId, long memberId, SeasonScoreResult result, RatingExclusion ratingExclusion,
            String inputs) {
        this.seasonId = seasonId;
        this.memberId = memberId;
        this.scoreVersion = result.scoreVersion();
        this.score = result.score();
        this.resultScore = result.resultScore();
        this.processScore = result.processScore();
        this.sharpe = result.sharpe();
        this.benchSharpe = result.benchSharpe();
        this.sharpeDiff = result.sharpeDiff();
        this.sharpeDiffPercentile = result.sharpeDiffPercentile();
        this.luckPercentile = result.luckPercentile();
        this.riskScore = result.riskScore();
        this.disciplineScore = result.disciplineScore();
        this.diversificationScore = result.diversificationScore();
        this.turnoverScore = result.turnoverScore();
        this.consistencyScore = result.consistencyScore();
        this.formationScore = result.formationScore();
        this.roleScore = result.roleScore();
        this.noiseScore = result.noiseScore();
        this.nudgeScore = result.nudgeScore();
        this.processItems = result.processItems();
        this.ratingExclusion = ratingExclusion;
        this.inputs = inputs;
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
