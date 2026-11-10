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
 * 이번 기능에서는 매핑만 있고 저장소는 없다 — P0 API 는 피드를 만들지도 읽지도 않는다(MVP 에서 더한다).
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

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 6, nullable = false)
    private FeedKind kind;

    @Column(name = "asset_id", nullable = true)
    private Long assetId;

    @Column(name = "asset_group_id", nullable = true)
    private Long assetGroupId;

    @Column(name = "month_event_id", nullable = true)
    private Long monthEventId;

    @Column(name = "category_hidden", nullable = false)
    private boolean categoryHidden;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 4, nullable = true)
    private MoveDirection direction;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "strength", nullable = true)
    private Integer strength;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "repeat_count", nullable = false)
    private int repeatCount;

    @Column(name = "template_id", length = 40, nullable = false)
    private String templateId;

    /** JSON 객체 문자열 그대로(jsonb 열). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_vars", nullable = false)
    private String templateVars;

    @Column(name = "body", length = 300, nullable = false)
    private String body;

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
