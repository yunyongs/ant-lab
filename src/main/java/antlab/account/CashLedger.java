package antlab.account;

import antlab.common.persistence.CreatedEntity;
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
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 현금 원장 — 추가 전용. 정정은 반대 부호의 ADJUSTMENT 행을 더하는 것뿐이다.
 * 원장은 계좌의 원장이라 {@code season_id} 가 계좌를 가리킨다(같은 패키지의 가변 부모라 연관). 주문은 번호로만 가리킨다.
 */
@Entity
@Immutable
@Table(name = "cash_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashLedger extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private CashAccount cashAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 12, nullable = false)
    private LedgerType type;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", precision = 19, scale = 4, nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "ref_order_id", nullable = true)
    private Long refOrderId;

    public CashLedger(CashAccount cashAccount, LedgerType type, BigDecimal amount, BigDecimal balanceAfter,
            Long refOrderId) {
        this.cashAccount = cashAccount;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.refOrderId = refOrderId;
    }
}
