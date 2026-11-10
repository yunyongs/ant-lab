package antlab.season;

import antlab.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 턴 스냅샷: 한 턴이 끝났을 때의 현금·평가액·포지션별 평가액·낙폭. 추가 전용. 계산은 서비스가 하고 여기는 담기만 한다. */
@Entity
@Immutable
@Table(name = "turn")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Turn extends CreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id", nullable = false)
    private Season season;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "turn_no", nullable = false)
    private int turnNo;

    @Column(name = "cash_balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal cashBalance;

    @Column(name = "holdings_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal holdingsValue;

    @Column(name = "total_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalValue;

    @Column(name = "goalkeeper_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal goalkeeperValue;

    @Column(name = "defender_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal defenderValue;

    @Column(name = "midfielder_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal midfielderValue;

    @Column(name = "forward_value", precision = 19, scale = 4, nullable = false)
    private BigDecimal forwardValue;

    @Column(name = "drawdown", precision = 12, scale = 8, nullable = false)
    private BigDecimal drawdown;

    public Turn(Season season, int turnNo, BigDecimal cashBalance, BigDecimal holdingsValue, BigDecimal totalValue,
            BigDecimal goalkeeperValue, BigDecimal defenderValue, BigDecimal midfielderValue,
            BigDecimal forwardValue, BigDecimal drawdown) {
        this.season = season;
        this.turnNo = turnNo;
        this.cashBalance = cashBalance;
        this.holdingsValue = holdingsValue;
        this.totalValue = totalValue;
        this.goalkeeperValue = goalkeeperValue;
        this.defenderValue = defenderValue;
        this.midfielderValue = midfielderValue;
        this.forwardValue = forwardValue;
        this.drawdown = drawdown;
    }
}
