package antlab.account;

import antlab.common.persistence.AuditedEntity;
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

/**
 * 보유 — 시즌·종목마다 한 행. 전량 매도 뒤에도 행을 남긴다(수량 0, 평균단가 0 — erd D7).
 * 평균단가 계산은 서비스가 하고(반올림은 한 곳), 여기는 계산된 값을 받아 DB CHECK 와 같은 불변식만 지킨다.
 */
@Entity
@Table(name = "holding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "avg_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal avgPrice;

    /** 수량 0, 평균단가 0 의 빈 보유. 첫 매수는 {@link #applyBuy} 로 한다. */
    public Holding(Long seasonId, Long assetId) {
        this.seasonId = seasonId;
        this.assetId = assetId;
        this.quantity = 0;
        this.avgPrice = BigDecimal.ZERO;
    }

    /** 매수 반영: 수량을 늘리고 호출자가 계산한 새 평균단가(0 초과)를 넣는다. */
    public void applyBuy(int qty, BigDecimal newAvgPrice) {
        if (qty <= 0) {
            throw new IllegalStateException("매수 수량은 1 이상이어야 한다: " + qty);
        }
        if (newAvgPrice.signum() <= 0) {
            throw new IllegalStateException("평균단가는 0 보다 커야 한다: " + newAvgPrice.toPlainString());
        }
        if (newAvgPrice.stripTrailingZeros().scale() > 4) {
            throw new IllegalStateException("평균단가는 소수 4자리까지다: " + newAvgPrice.toPlainString());
        }
        int next;
        try {
            next = Math.addExact(quantity, qty);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("보유 수량 한도를 넘는 매수: " + qty, e);
        }
        this.quantity = next;
        this.avgPrice = newAvgPrice;
    }

    /** 매도 반영: 수량을 줄인다. 보유보다 많이 팔 수 없고, 전량 매도면 평균단가를 0 으로 되돌린다. */
    public void applySell(int qty) {
        if (qty <= 0) {
            throw new IllegalStateException("매도 수량은 1 이상이어야 한다: " + qty);
        }
        if (qty > quantity) {
            throw new IllegalStateException("보유 수량(" + quantity + ")보다 많이 팔 수 없다: " + qty);
        }
        this.quantity -= qty;
        if (quantity == 0) {
            this.avgPrice = BigDecimal.ZERO;
        }
    }
}
