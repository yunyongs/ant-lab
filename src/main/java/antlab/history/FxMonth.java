package antlab.history;

import antlab.asset.Currency;
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
 * 달 환율 변화 — 읽기 전용 매핑(fx_month). 적재 SQL 이 만든 행을 읽기만 한다.
 * setter·공개 생성자가 없고 {@code @Immutable} 이라 Hibernate 가 변경을 DB 로 보내지 않는다.
 * 연관은 만들지 않고 다른 표는 {@code Long} 번호로만 가리킨다(SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "fx_month")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FxMonth {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "month_block_id", nullable = false)
    private Long monthBlockId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3, nullable = false)
    private Currency currency;

    @Column(name = "fx_change", precision = 12, scale = 8, nullable = false)
    private BigDecimal fxChange;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
