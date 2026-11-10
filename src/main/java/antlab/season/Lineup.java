package antlab.season;

import antlab.asset.Position;
import antlab.common.persistence.AuditedEntity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 라인업: 한 종목을 어느 포지션으로 선언했는지의 구간(시작 턴 ~ 끝 턴). 끝 턴이 비어 있으면 열린 구간이다.
 * 가변 엔티티 — 구간을 닫는 {@link #close(int)} 만 상태를 바꾼다.
 */
@Entity
@Table(name = "lineup")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lineup extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "declared_position", length = 17, nullable = false)
    private Position declaredPosition;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "start_turn", nullable = false)
    private int startTurn;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "end_turn", nullable = true)
    private Integer endTurn;

    /** 열린 구간으로 시작한다. */
    public Lineup(Season season, Long assetId, Position declaredPosition, int startTurn) {
        if (startTurn < 0) {
            throw new IllegalArgumentException("시작 턴은 0 이상이어야 한다: " + startTurn);
        }
        this.season = season;
        this.assetId = assetId;
        this.declaredPosition = declaredPosition;
        this.startTurn = startTurn;
    }

    /** 구간을 닫는다. 열린 구간만, 끝 턴은 시작 턴 이상이어야 한다(ck_lineup_turns). */
    public void close(int endTurn) {
        if (this.endTurn != null) {
            throw new IllegalStateException("이미 닫힌 구간이다: 끝 턴 " + this.endTurn);
        }
        if (endTurn < startTurn) {
            throw new IllegalStateException("끝 턴(" + endTurn + ")이 시작 턴(" + startTurn + ")보다 앞설 수 없다");
        }
        this.endTurn = endTurn;
    }
}
