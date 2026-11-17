package antlab.season;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;

/**
 * Why a formation change failed its checks. A human request turns it into the API error of {@link #humanCode}; an agent
 * decision records the name as the unit's reject code. Names are the reject codes of the decision contract.
 */
public enum FormationRejection {
    TURN_ZERO_FORMATION(ErrorCode.TURN_MISMATCH, "팀 구성 중인 0턴에는 전술을 바꿀 수 없다"),
    FORMATION_INVALID(ErrorCode.INVALID_ARGUMENT, "사유가 너무 길거나 쓸 수 없는 문자가 있다"),
    FORMATION_UNCHANGED(ErrorCode.INVALID_ARGUMENT, "현재 포메이션과 같아 바꿀 수 없다");

    private final ErrorCode humanCode;
    private final String message;

    FormationRejection(ErrorCode humanCode, String message) {
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
