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
    INTERNAL_ERROR,
    /** 기준일 풀 행이 없다 — 풀 조회(P0-27)와 시즌 시작(P0-30)이 함께 쓴다. */
    POOL_NOT_LOADED,
    /** 기준일 풀에 판정이 비어 있는 종목이 있다. */
    POOL_NOT_JUDGED,
    /** 요청한 길이의 경로를 만들 수 없다 — 60턴 초과이거나 재생 구간의 달이 모자란다(P0-29). */
    PATH_TOO_LONG,
    /** 진행 중인 시즌이 3개라 새로 시작할 수 없다(P0-30). */
    SEASON_LIMIT,
    /** 아직 진행하지 않은 턴이다(P0-30 가격 조회, P0-35). */
    TURN_NOT_REACHED,
    /** 현재 회원을 알 수 없다 — 운영에서 고정 회원이 꺼져 있고 인증(SEC)이 아직 없을 때. */
    MEMBER_UNAVAILABLE
}
