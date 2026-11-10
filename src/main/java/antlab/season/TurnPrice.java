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

/** 턴 가격: 시즌·턴·종목별 원화 정본 가격(원 단위). 추가 전용 — 체결가와 평가액의 유일한 출처다(PLAN §5.2). */
@Entity
@Immutable
@Table(name = "turn_price")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TurnPrice extends CreatedEntity {

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

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "price", precision = 19, scale = 4, nullable = false)
    private BigDecimal price;

    public TurnPrice(Season season, int turnNo, Long assetId, BigDecimal price) {
        this.season = season;
        this.turnNo = turnNo;
        this.assetId = assetId;
        this.price = price;
    }
}
