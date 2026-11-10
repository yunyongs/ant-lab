package antlab.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

/** 만든 시각과 고친 시각을 가진 엔티티의 부모 — 가변 엔티티가 쓴다. */
@MappedSuperclass
@Getter
public abstract class AuditedEntity extends CreatedEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
