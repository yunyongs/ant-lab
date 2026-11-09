package antlab.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 종목 달 수익률 — 읽기 전용 매핑(asset_month_return). 적재 SQL 이 만든 행을 읽기만 한다.
 * setter·공개 생성자가 없고 {@code @Immutable} 이라 Hibernate 가 변경을 DB 로 보내지 않는다.
 * 연관은 만들지 않고 다른 표는 {@code Long} 번호로만 가리킨다(SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "asset_month_return")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetMonthReturn {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "month_block_id", nullable = false)
    private Long monthBlockId;

    @Column(name = "local_return", precision = 12, scale = 8, nullable = false)
    private BigDecimal localReturn;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_kind", length = 11, nullable = false)
    private ReturnKind returnKind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof AssetMonthReturn other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return AssetMonthReturn.class.hashCode();
    }
}
