package antlab.season;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 시즌 경로: 턴 번호마다 어느 월 블록을 쓰는지. 추가 전용 — 넣은 뒤 바뀌지 않는다.
 * 월 블록 번호는 시즌이 끝나기 전에 사용자에게 보이면 안 되는 값이다(SPRING_JPA §4.6).
 */
@Entity
@Immutable
@Table(name = "season_path")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonPath extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    // No getter: the block id is [복기 전용] and is used only as a join key inside the review projections (SPRING_JPA §4.6).
    @Getter(AccessLevel.NONE)
    @Column(name = "month_block_id", nullable = false)
    private Long monthBlockId;

    public SeasonPath(Season season, int turnNo, Long monthBlockId) {
        this.season = season;
        this.turnNo = turnNo;
        this.monthBlockId = monthBlockId;
    }
}
