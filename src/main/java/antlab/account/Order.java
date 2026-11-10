package antlab.account;

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
 * 주문 — 추가 전용. 접수와 체결이 한 번에 일어나므로(SPRING_JPA §5.2) 넣은 뒤 바꾸지 않는다.
 * 지정가가 들어오면(EXT) 다시 본다. 엔티티 이름은 {@code Orders} 다 — JPQL 에서 {@code ORDER} 는 예약어다.
 * 시즌·종목·전술 변경·피드는 {@code Long} 번호 그대로이고, 복합 FK 의 일관성은 DB 가 지킨다(erd D29).
 */
@Entity(name = "Orders")
@Immutable
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 4, nullable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 6, nullable = false)
    private OrderType type;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "limit_price", precision = 19, scale = 4, nullable = true)
    private BigDecimal limitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 7, nullable = false)
    private OrderSource source;

    @Column(name = "formation_change_id", nullable = true)
    private Long formationChangeId;

    @Column(name = "turn_feed_id", nullable = true)
    private Long turnFeedId;

    public Order(Long seasonId, Long assetId, int turnNo, OrderSide side, OrderType type, int quantity,
            BigDecimal limitPrice, OrderStatus status, OrderSource source, Long formationChangeId, Long turnFeedId) {
        this.seasonId = seasonId;
        this.assetId = assetId;
        this.turnNo = turnNo;
        this.side = side;
        this.type = type;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.status = status;
        this.source = source;
        this.formationChangeId = formationChangeId;
        this.turnFeedId = turnFeedId;
    }
}
