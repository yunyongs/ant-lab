package antlab.api.error;

import antlab.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * {@link ErrorCode} ↔ HTTP 상태. switch 식에 default 를 두지 않아,
 * 코드를 더하면 매핑을 빠뜨릴 수 없다(컴파일 오류).
 */
public final class ErrorHttpMapping {

    private ErrorHttpMapping() {
    }

    public static HttpStatus status(ErrorCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> HttpStatus.NOT_ACCEPTABLE;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case POOL_NOT_LOADED -> HttpStatus.CONFLICT;
            case POOL_NOT_JUDGED -> HttpStatus.CONFLICT;
        };
    }

    /** 상태 → 코드. 목록에 없는 4xx 는 INVALID_ARGUMENT, 5xx 는 INTERNAL_ERROR 로 접는다. */
    public static ErrorCode code(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_ARGUMENT;
        };
    }
}
