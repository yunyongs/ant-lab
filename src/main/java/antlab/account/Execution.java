package antlab.account;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** 체결 — 추가 전용. 체결가·수수료·세금은 서비스가 계산해 넘기고 여기는 담기만 한다. 주문은 {@code Long} 번호로 가리킨다. */
@Entity
@Immutable
@Table(name = "execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "fill_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal fillPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "fee", precision = 19, scale = 4, nullable = false)
    private BigDecimal fee;

    @Column(name = "tax", precision = 19, scale = 4, nullable = false)
    private BigDecimal tax;

    @Column(name = "realized_pnl", precision = 19, scale = 4, nullable = true)
    private BigDecimal realizedPnl;

    public Execution(Long orderId, BigDecimal fillPrice, int quantity, BigDecimal fee, BigDecimal tax,
            BigDecimal realizedPnl) {
        this.orderId = orderId;
        this.fillPrice = fillPrice;
        this.quantity = quantity;
        this.fee = fee;
        this.tax = tax;
        this.realizedPnl = realizedPnl;
    }
}
