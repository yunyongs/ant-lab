package antlab.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 종목 — 읽기 전용 매핑(asset). 적재 SQL 이 만든 행을 읽기만 한다.
 * setter·공개 생성자가 없고 {@code @Immutable} 이라 Hibernate 가 변경을 DB 로 보내지 않는다.
 * 연관은 만들지 않고 다른 표는 {@code Long} 번호로만 가리킨다(SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "asset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Asset {

    @Id
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "market", length = 2, nullable = false)
    private Market market;

    @Column(name = "ticker", length = 12, nullable = false)
    private String ticker;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", length = 5, nullable = false)
    private AssetType assetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3, nullable = false)
    private Currency currency;

    @Column(name = "asset_group_id", nullable = false)
    private Long assetGroupId;

    // No getter on [복기 전용] values: they leave the DB only through the review projections (SPRING_JPA §4.6).
    @Getter(AccessLevel.NONE)
    @Column(name = "listed_on", nullable = false)
    private LocalDate listedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_basis", length = 5, nullable = false)
    private ReturnBasis returnBasis;

    @Column(name = "proxy_index_name", length = 100, nullable = true)
    private String proxyIndexName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Asset other && id != null && id.equals(other.id));
    }

    @Override
    public int hashCode() {
        return Asset.class.hashCode();
    }
}
