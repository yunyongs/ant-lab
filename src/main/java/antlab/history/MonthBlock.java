package antlab.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 월 블록 — 읽기 전용 매핑(month_block). 적재 SQL 이 만든 행을 읽기만 한다.
 * setter·공개 생성자가 없고 {@code @Immutable} 이라 Hibernate 가 변경을 DB 로 보내지 않는다.
 * 연관은 만들지 않고 다른 표는 {@code Long} 번호로만 가리킨다(SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "month_block")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthBlock {

    @Id
    @Column(name = "id")
    private Long id;

    // No getter on [복기 전용] values: they leave the DB only through the review projections (SPRING_JPA §4.6).
    @Getter(AccessLevel.NONE)
    @Column(name = "month_start", nullable = false)
    private LocalDate monthStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_dir", length = 7, nullable = true)
    private AxisDirection rateDir;

    @Enumerated(EnumType.STRING)
    @Column(name = "growth_dir", length = 7, nullable = true)
    private AxisDirection growthDir;

    @Enumerated(EnumType.STRING)
    @Column(name = "fx_dir", length = 7, nullable = true)
    private AxisDirection fxDir;

    @Enumerated(EnumType.STRING)
    @Column(name = "sentiment_dir", length = 7, nullable = true)
    private AxisDirection sentimentDir;

    @Column(name = "rate_strength", precision = 12, scale = 8, nullable = true)
    private BigDecimal rateStrength;

    @Column(name = "growth_strength", precision = 12, scale = 8, nullable = true)
    private BigDecimal growthStrength;

    @Column(name = "fx_strength", precision = 12, scale = 8, nullable = true)
    private BigDecimal fxStrength;

    @Column(name = "sentiment_strength", precision = 12, scale = 8, nullable = true)
    private BigDecimal sentimentStrength;

    @Column(name = "prob_p1", precision = 5, scale = 4, nullable = true)
    private BigDecimal probP1;

    @Column(name = "prob_p2", precision = 5, scale = 4, nullable = true)
    private BigDecimal probP2;

    @Column(name = "prob_p3", precision = 5, scale = 4, nullable = true)
    private BigDecimal probP3;

    @Column(name = "prob_p4", precision = 5, scale = 4, nullable = true)
    private BigDecimal probP4;

    @Getter(AccessLevel.NONE)
    @Column(name = "event_summary", length = 200, nullable = true)
    private String eventSummary;

    @Column(name = "events_reviewed", nullable = false)
    private boolean eventsReviewed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof MonthBlock other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return MonthBlock.class.hashCode();
    }
}
