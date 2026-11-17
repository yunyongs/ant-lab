package antlab.member;

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
 * Append-only: the manager rating after one counted season. {@code seqNo} counts the member's counted seasons in finish
 * order; the rating is stored from the first one and shown from the third. {@code seasonsUsed} is how many seasons
 * the weighted mean covered (at most 5). The rating row's member must be the score row's member; the service checks it.
 */
@Entity
@Immutable
@Table(name = "rating_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RatingHistory extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pillar", length = 10, nullable = false)
    private RatingPillar pillar;

    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "rating", precision = 5, scale = 2, nullable = false)
    private BigDecimal rating;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "seasons_used", nullable = false)
    private int seasonsUsed;

    public RatingHistory(long memberId, RatingPillar pillar, int seqNo, long seasonId, BigDecimal rating,
            int seasonsUsed) {
        this.memberId = memberId;
        this.pillar = pillar;
        this.seqNo = seqNo;
        this.seasonId = seasonId;
        this.rating = rating;
        this.seasonsUsed = seasonsUsed;
    }
}
