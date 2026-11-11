package antlab.common.persistence;

import jakarta.persistence.ExcludeSuperclassListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.Objects;

/**
 * 만든 시각을 서비스가 정해서 넣는 추가 전용 엔티티의 부모 — 주문과 전술 변경이 쓴다.
 * 감사 기능은 저장하는 순간 시계를 다시 읽으므로, 같은 턴의 앞선 이벤트와 비교해 확정한 시각과 실제로 저장되는 시각이
 * 어긋날 수 있다(erd 7.5 규칙 16). 그래서 이 부모는 감사 리스너를 끄고 생성자로 받은 시각을 그대로 저장한다.
 * 시각을 바꾸는 공개 메서드는 없다. 다른 엔티티의 감사 시각은 그대로 {@link CreatedEntity} 가 채운다.
 */
@MappedSuperclass
@ExcludeSuperclassListeners
public abstract class ExplicitCreatedEntity extends CreatedEntity {

    protected ExplicitCreatedEntity() {
    }

    protected ExplicitCreatedEntity(Instant createdAt) {
        assignCreatedAt(Objects.requireNonNull(createdAt, "createdAt"));
    }
}
