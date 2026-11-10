package antlab.asset;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 종목 기준일 판정 — 읽기 전용 매핑(asset_position). 적재 SQL 이 만든 행을 읽기만 한다.
 * setter·공개 생성자가 없고 {@code @Immutable} 이라 Hibernate 가 변경을 DB 로 보내지 않는다.
 * 연관은 만들지 않고 다른 표는 {@code Long} 번호로만 가리킨다(SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "asset_position")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetPosition {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "base_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal basePrice;

    @Column(name = "base_fx_rate", precision = 19, scale = 4, nullable = false)
    private BigDecimal baseFxRate;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "market_cap_rank", nullable = true)
    private Integer marketCapRank;

    @Column(name = "window_start", nullable = true)
    private LocalDate windowStart;

    @Column(name = "window_end", nullable = true)
    private LocalDate windowEnd;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "months_used", nullable = true)
    private Integer monthsUsed;

    @Column(name = "up_participation", precision = 12, scale = 8, nullable = true)
    private BigDecimal upParticipation;

    @Column(name = "down_participation", precision = 12, scale = 8, nullable = true)
    private BigDecimal downParticipation;

    @Column(name = "volatility", precision = 12, scale = 8, nullable = true)
    private BigDecimal volatility;

    @Column(name = "max_drawdown", precision = 12, scale = 8, nullable = true)
    private BigDecimal maxDrawdown;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_position", length = 17, nullable = true)
    private Position systemPosition;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof AssetPosition other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return AssetPosition.class.hashCode();
    }
}
