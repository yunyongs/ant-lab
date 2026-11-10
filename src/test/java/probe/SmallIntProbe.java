package probe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Hibernate 동작 확인용 표본 엔티티. {@code antlab} 밖 패키지에 두어 앱의 엔티티 스캔에 들어가지 않게 한다.
 * SMALLINT 열을 {@code @JdbcTypeCode} 없이 {@code int} 로 매핑하면 {@code validate} 가 어떻게 되는지 보여 준다.
 */
@Entity
@Table(name = "spike_smallint")
public class SmallIntProbe {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "n", nullable = false)
    private int n;
}
