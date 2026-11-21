package antlab.common.error;

import java.util.Map;
import java.util.Objects;

/**
 * 도메인 규칙 위반. unchecked 이므로 트랜잭션 안에서 던지면 롤백된다(SPRING_JPA §5.1).
 * An answer may carry extra problem members (for example pointers and fixed codes); the thrower guarantees they never
 * hold a rejected value.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    public DomainException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public DomainException(ErrorCode code, String message, Map<String, Object> properties) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode getCode() {
        return code;
    }

    /** Extra members of the problem answer; empty for most errors. */
    public Map<String, Object> getProperties() {
        return properties;
    }
}
