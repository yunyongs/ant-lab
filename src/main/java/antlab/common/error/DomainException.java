package antlab.common.error;

import java.util.Objects;

/**
 * 도메인 규칙 위반. unchecked 이므로 트랜잭션 안에서 던지면 롤백된다(SPRING_JPA §5.1).
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode getCode() {
        return code;
    }
}
