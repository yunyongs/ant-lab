package antlab.common.error;

/**
 * API 오류 코드. 이름은 응답의 최상위 {@code code} 값이며, SQL HINT 코드와 글자 단위로 같게 쓴다.
 * 도메인 코드는 쓰는 기능이 여기에 더한다. HTTP 상태는 이 enum 이 알지 못한다
 * (예외 → HTTP 매핑은 {@code antlab.api.error.ErrorHttpMapping}).
 */
public enum ErrorCode {
    INVALID_ARGUMENT,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    NOT_ACCEPTABLE,
    UNSUPPORTED_MEDIA_TYPE,
    INTERNAL_ERROR
}
