package antlab.account;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;

/**
 * Why a single order failed its checks. A human request turns it into the API error of {@link #humanCode}; an agent
 * decision records the name as the unit's reject code and goes on with the other units. Names are the reject codes
 * of the decision contract.
 */
public enum OrderRejection {
    /** BENCH is reserved: no bench screen or API exists yet, so every request that names it is refused. */
    SOURCE_NOT_ALLOWED(ErrorCode.INVALID_ARGUMENT, "이 출발점은 쓸 수 없다"),
    /** One reason for every "not shown" case (future turn, unknown, other season, kind or asset mismatch, not on the board). */
    SOURCE_NOT_VISIBLE(ErrorCode.SOURCE_NOT_VISIBLE, "화면에 보인 항목에서 시작한 주문이 아니다"),
    ASSET_NOT_IN_POOL(ErrorCode.ASSET_NOT_IN_POOL, "이 턴의 풀에 없는 종목이다"),
    QUANTITY_INVALID(ErrorCode.INVALID_ARGUMENT, "수량은 1 이상이어야 한다"),
    POSITION_ON_SELL(ErrorCode.INVALID_ARGUMENT, "매도에는 선언 포지션을 보내지 않는다"),
    INSUFFICIENT_HOLDING(ErrorCode.INSUFFICIENT_HOLDING, "보유 수량이 모자란다"),
    INSUFFICIENT_CASH(ErrorCode.INSUFFICIENT_CASH, "현금이 모자란다"),
    POSITION_REQUIRED(ErrorCode.POSITION_REQUIRED, "새로 사는 종목은 선언 포지션이 필요하다"),
    POSITION_CONFLICT(ErrorCode.POSITION_CONFLICT, "열린 구간의 선언 포지션과 다르다"),
    SINGLE_ASSET_LIMIT(ErrorCode.INVALID_ARGUMENT, "한 종목이 총 평가액에서 차지할 수 있는 비중을 넘는다");

    private final ErrorCode humanCode;
    private final String message;

    OrderRejection(ErrorCode humanCode, String message) {
        this.humanCode = humanCode;
        this.message = message;
    }

    public ErrorCode humanCode() {
        return humanCode;
    }

    DomainException toException() {
        return new DomainException(humanCode, message);
    }
}
