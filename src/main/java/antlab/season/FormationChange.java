package antlab.season;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

/** 포메이션 변경 기록: 어느 턴에 어떤 비중에서 어떤 비중으로 바꿨는지. 추가 전용. 변경 비용은 저장하지 않는다(erd D31). */
@Entity
@Immutable
@Table(name = "formation_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FormationChange extends CreatedEntity {

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

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "goalkeeper", column = @Column(name = "before_goalkeeper", nullable = false)),
            @AttributeOverride(name = "defender", column = @Column(name = "before_defender", nullable = false)),
            @AttributeOverride(name = "midfielder", column = @Column(name = "before_midfielder", nullable = false)),
            @AttributeOverride(name = "forward", column = @Column(name = "before_forward", nullable = false))})
    private Formation before;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "goalkeeper", column = @Column(name = "after_goalkeeper", nullable = false)),
            @AttributeOverride(name = "defender", column = @Column(name = "after_defender", nullable = false)),
            @AttributeOverride(name = "midfielder", column = @Column(name = "after_midfielder", nullable = false)),
            @AttributeOverride(name = "forward", column = @Column(name = "after_forward", nullable = false))})
    private Formation after;

    @Column(name = "reason", length = 200, nullable = true)
    private String reason;

    public FormationChange(Season season, int turnNo, Formation before, Formation after, String reason) {
        if (before.equals(after)) {
            throw new IllegalArgumentException("이전과 이후 포메이션이 같을 수 없다");
        }
        this.season = season;
        this.turnNo = turnNo;
        this.before = before;
        this.after = after;
        this.reason = reason;
    }
}
