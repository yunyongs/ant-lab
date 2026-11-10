package antlab.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 달 사건 태그 — 읽기 전용 매핑(month_event). 적재 SQL 이 만든 행을 읽기만 한다.
 * setter·공개 생성자가 없고 {@code @Immutable} 이라 Hibernate 가 변경을 DB 로 보내지 않는다.
 * 연관은 만들지 않고 다른 표는 {@code Long} 번호로만 가리킨다(SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "month_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthEvent {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "event_key", length = 40, nullable = false)
    private String eventKey;

    @Column(name = "month_block_id", nullable = false)
    private Long monthBlockId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 12, nullable = false)
    private EventCategory category;

    @Column(name = "event_type", length = 30, nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", length = 7, nullable = false)
    private EventPhase phase;

    @Column(name = "outcome", length = 20, nullable = true)
    private String outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 4, nullable = true)
    private MoveDirection direction;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "strength", nullable = false)
    private Integer strength;

    @Column(name = "reaction_change", precision = 12, scale = 8, nullable = true)
    private BigDecimal reactionChange;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    @Column(name = "source", length = 500, nullable = false)
    private String source;

    @Column(name = "hindsight", length = 300, nullable = true)
    private String hindsight;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof MonthEvent other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return MonthEvent.class.hashCode();
    }
}
