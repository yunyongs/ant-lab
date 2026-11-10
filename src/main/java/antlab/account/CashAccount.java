package antlab.account;

import antlab.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 현금 계좌 — 시즌 하나에 하나(식별 1:1, erd D6). 키는 {@code season_id} 이고 DB 가 번호를 주지 않으므로
 * 만드는 쪽이 정한다(할당 키). 버전이 래퍼 {@code Long} 이라 저장 전에는 null 이고, 그래서
 * {@code save()} 가 새 엔티티로 보고 INSERT 한다(원시 {@code long} 이면 키가 있다는 이유로 merge 로 간다).
 * 금액 변경은 원장 금액을 반영하는 {@link #applyLedgerAmount} 하나뿐이다.
 */
@Entity
@Table(name = "cash_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashAccount extends AuditedEntity {

    @Id
    @Column(name = "season_id")
    private Long seasonId;

    @Column(name = "balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal balance;

    @Column(name = "reserved", precision = 19, scale = 4, nullable = false)
    private BigDecimal reserved;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 잔액 0, 예약금 0 의 새 계좌. 첫 입금은 원장 DEPOSIT 행과 {@link #applyLedgerAmount} 로 한다. */
    public CashAccount(Long seasonId) {
        this.seasonId = seasonId;
        this.balance = BigDecimal.ZERO;
        this.reserved = BigDecimal.ZERO;
    }

    /**
     * 원장 금액(부호 있음)을 잔액에 더하고 새 잔액을 돌려준다(원장의 balance_after 로 쓴다).
     * 결과는 0 이상이어야 하고 금액은 원 단위여야 한다(ck_cash_account_balance_nonneg, ck_cash_account_balance_won).
     * 반올림은 하지 않는다 — 원 단위가 아니면 거부한다.
     */
    public BigDecimal applyLedgerAmount(BigDecimal signedAmount) {
        if (signedAmount.stripTrailingZeros().scale() > 0) {
            throw new IllegalStateException("원 단위가 아닌 금액: " + signedAmount.toPlainString());
        }
        BigDecimal next = balance.add(signedAmount);
        if (next.signum() < 0) {
            throw new IllegalStateException("잔액이 음수가 될 수 없다: " + next.toPlainString());
        }
        this.balance = next;
        return next;
    }
}
