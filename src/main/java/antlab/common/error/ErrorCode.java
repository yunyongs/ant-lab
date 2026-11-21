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
    /** Tomcat rejected the request line or headers before Spring saw it (bad percent-encoding, encoded slash, oversized header). */
    MALFORMED_REQUEST,
    INTERNAL_ERROR,
    /** 기준일 풀 행이 없다 — 풀 조회(P0-27)와 시즌 시작(P0-30)이 함께 쓴다. */
    POOL_NOT_LOADED,
    /** 기준일 풀에 판정이 비어 있는 종목이 있다. */
    POOL_NOT_JUDGED,
    /** 요청한 길이의 경로를 만들 수 없다 — 60턴 초과이거나 재생 구간의 달이 모자란다(P0-29). */
    PATH_TOO_LONG,
    /** The Python engine cannot answer right now (not reachable, too slow, busy, history unavailable); try again later (M2-06). */
    ENGINE_UNAVAILABLE,
    /** 진행 중인 시즌이 3개라 새로 시작할 수 없다(P0-30). */
    SEASON_LIMIT,
    /** 아직 진행하지 않은 턴이다(P0-30 가격 조회, P0-35). */
    TURN_NOT_REACHED,
    /** 끝나지 않은 시즌의 성적표는 볼 수 없다(P0-38). */
    SEASON_NOT_FINISHED,
    /** 진행 중이 아닌 시즌에는 주문할 수 없다(P0-31). */
    SEASON_NOT_ACTIVE,
    /** 주문이 본 턴과 시즌의 현재 턴이 다르다(P0-31). */
    TURN_MISMATCH,
    /** 최신 원장의 거래 후 잔액과 계좌 잔액이 다르다 — 원장 사슬이 깨졌다(P0-31). */
    LEDGER_MISMATCH,
    /** 보유 수량과 열린 라인업 구간이 서로 맞지 않는다(P0-31). */
    LINEUP_MISMATCH,
    /** 현재 턴 가격 목록(풀)에 없는 종목이다(P0-31). */
    ASSET_NOT_IN_POOL,
    /** The order names an origin (feed item, alert or ranking) that was not shown to this season at or before the current turn (MVP-13). */
    SOURCE_NOT_VISIBLE,
    /** 대금과 수수료를 낼 현금이 모자란다(P0-31). */
    INSUFFICIENT_CASH,
    /** 보유 수량보다 많이 팔려 하거나 보유가 없다(P0-36). */
    INSUFFICIENT_HOLDING,
    /** 열린 구간의 선언 포지션과 요청의 선언이 다르다(P0-31). */
    POSITION_CONFLICT,
    /** 새로 여는 보유인데 선언 포지션이 없다(P0-31). */
    POSITION_REQUIRED,
    /** 저장된 현재 포메이션이 마지막 전술 변경의 이후 값(없으면 시작 값)과 다르다 — 사슬이 깨졌다(P0-37). */
    INVALID_FORMATION,
    /** 같은 시즌·턴의 앞선 주문·전술 변경과 시각이 같거나 앞선다(P0-37). */
    EVENT_TIME_CONFLICT,
    /** 턴 전환 대사가 하나라도 어긋나 아무것도 바꾸지 않았다(P0-33). */
    RECONCILIATION_FAILED,
    /** The season was stopped after repeated reconciliation failures; only an administrator resumes it (MVP-15). */
    SEASON_HALTED,
    /** 개발용 엔드포인트를 고정 헤더 없이 불렀다(로컬 개발 전용 관문). */
    FORBIDDEN,
    /** The email is already registered, in any letter case (signup). */
    EMAIL_TAKEN,
    /** The request has no valid credentials; answered by the security layer. */
    UNAUTHENTICATED,
    /** Login: the email or password does not match; the same answer whichever is wrong. */
    INVALID_CREDENTIALS,
    /** The agent is retired and cannot start a season (agent runner). */
    AGENT_RETIRED,
    /** A different decision was already recorded for this turn (agent runner). */
    DECISION_CONFLICT,
    /** The agent season's current turn has no final decision yet, so it cannot advance (agent runner). */
    DECISION_MISSING,
    /** The persona version is already recorded with another file hash (agent runner). */
    PERSONA_VERSION_CONFLICT,
    /** A newer persona version is already recorded, so this one cannot start a season (agent runner). */
    PERSONA_VERSION_STALE,
    /** A league cannot be closed while a seat is still playing (agent runner). */
    LEAGUE_NOT_SETTLED,
    /** The league is closed, so no season can enter it. */
    LEAGUE_CLOSED,
    /** The member already holds a seat in this league (one seat per member). */
    LEAGUE_SEAT_TAKEN,
    /** The league has all its seats taken. */
    LEAGUE_FULL,
    /** The season settings or the generated path no longer match the league's, so an entry would be unfair. */
    LEAGUE_STALE,
    /** The review of a league season is held until the league is closed. */
    LEAGUE_OPEN
}
