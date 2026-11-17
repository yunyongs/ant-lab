package antlab.collect;

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
 * One vintage of one daily close — read-only mapping (asset_daily_price). The collector appends rows with
 * JdbcTemplate and never updates or deletes one (erd D41). No setters, no public constructor.
 * Other tables are referenced by {@code Long} id only (SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "asset_daily_price")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetDailyPrice {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    // No getter on [복기 전용] values: they leave the DB only through the review projections (SPRING_JPA §4.6).
    @Getter(AccessLevel.NONE)
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_basis", length = 8, nullable = false)
    private PriceBasis priceBasis;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 3, nullable = false)
    private PriceSource source;

    @Column(name = "exchange_code", length = 3)
    private String exchangeCode;

    @Getter(AccessLevel.NONE)
    @Column(name = "close_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal closePrice;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "vintage_no", nullable = false)
    private Integer vintageNo;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "job_run_id", nullable = false)
    private Long jobRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
