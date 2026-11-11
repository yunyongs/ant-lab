package antlab.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 만든 시각만 가진 엔티티의 부모 — 추가 전용(불변) 엔티티가 쓴다. 값은 감사 기능이 채운다. */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class CreatedEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 서비스가 확정한 시각을 직접 넣는 하위 클래스({@link ExplicitCreatedEntity})만 부른다. */
    protected final void assignCreatedAt(Instant at) {
        this.createdAt = at;
    }
}
