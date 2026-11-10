package probe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@link SmallIntProbe} 와 같은 표를 {@code @JdbcTypeCode(SMALLINT)} 로 매핑한 판. */
@Entity
@Table(name = "spike_smallint")
public class SmallIntAnnotatedProbe {

    @Id
    @Column(name = "id")
    private Long id;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "n", nullable = false)
    private int n;
}
