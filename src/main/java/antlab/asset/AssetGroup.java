package antlab.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 종목 그룹 — 읽기 전용 매핑(asset_group). 적재 SQL 이 만든 행을 읽기만 한다.
 * setter·공개 생성자가 없고 {@code @Immutable} 이라 Hibernate 가 변경을 DB 로 보내지 않는다.
 * 연관은 만들지 않고 다른 표는 {@code Long} 번호로만 가리킨다(SPRING_JPA §4.3).
 */
@Entity
@Immutable
@Table(name = "asset_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetGroup {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "code", length = 30, nullable = false)
    private String code;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 11, nullable = false)
    private AssetGroupKind kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
