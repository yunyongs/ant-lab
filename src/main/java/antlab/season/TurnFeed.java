package antlab.season;

import antlab.common.persistence.CreatedEntity;
import antlab.history.MoveDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 턴 피드: 사용자가 본 그대로의 소음 피드 문장(PLAN §4.13). 추가 전용.
 * 턴 전환이 {@code antlab.feed} 의 생성기 출력으로 넣고(MVP-11), {@link TurnFeedRepository} 가 읽는다.
 */
@Entity
@Immutable
@Table(name = "turn_feed")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TurnFeed extends CreatedEntity {

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

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "seq", nullable = false)
    private int seq;

    /** [복기 전용]·[내부] columns have no getter, so a response cannot read them by accident (SPRING_JPA §4.6). */
    @Getter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 6, nullable = false)
    private FeedKind kind;

    @Column(name = "asset_id", nullable = true)
    private Long assetId;

    @Column(name = "asset_group_id", nullable = true)
    private Long assetGroupId;

    /** [복기 전용]: no getter, see {@link #kind}. */
    @Getter(AccessLevel.NONE)
    @Column(name = "month_event_id", nullable = true)
    private Long monthEventId;

    @Getter(AccessLevel.NONE)
    @Column(name = "category_hidden", nullable = false)
    private boolean categoryHidden;

    @Getter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 4, nullable = true)
    private MoveDirection direction;

    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "strength", nullable = true)
    private Integer strength;

    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "repeat_count", nullable = false)
    private int repeatCount;

    @Getter(AccessLevel.NONE)
    @Column(name = "template_id", length = 40, nullable = false)
    private String templateId;

    /** JSON 객체 문자열 그대로(jsonb 열). */
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_vars", nullable = false)
    private String templateVars;

    @Column(name = "body", length = 300, nullable = false)
    private String body;

    /** The only thing a response derives from {@code kind}: a nudge alert goes to {@code alerts}, every other row to {@code feed}. */
    public boolean isAlert() {
        return kind == FeedKind.NUDGE;
    }

    public TurnFeed(Season season, int turnNo, int seq, FeedKind kind, Long assetId, Long assetGroupId,
            Long monthEventId, boolean categoryHidden, MoveDirection direction, Integer strength, int repeatCount,
            String templateId, String templateVars, String body) {
        this.season = season;
        this.turnNo = turnNo;
        this.seq = seq;
        this.kind = kind;
        this.assetId = assetId;
        this.assetGroupId = assetGroupId;
        this.monthEventId = monthEventId;
        this.categoryHidden = categoryHidden;
        this.direction = direction;
        this.strength = strength;
        this.repeatCount = repeatCount;
        this.templateId = templateId;
        this.templateVars = templateVars;
        this.body = body;
    }
}
