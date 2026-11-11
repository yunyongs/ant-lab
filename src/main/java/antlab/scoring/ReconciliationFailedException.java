package antlab.scoring;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import java.util.List;
import java.util.Objects;

/**
 * 턴 전환 대사 불일치 — 던지면 바깥 트랜잭션 전체가 롤백된다. 응답에는 고정 문장만 나가고(규칙 코드·금액 없음),
 * 17행 결과는 롤백 너머로 운반하기 위해 불변 값으로 싣는다(롤백 뒤 기록은 `TurnFacade` 가 한다).
 */
public class ReconciliationFailedException extends DomainException {

    private final long seasonId;
    private final int turnNo;
    private final List<RuleResult> results;

    public ReconciliationFailedException(long seasonId, int turnNo, List<RuleResult> results) {
        super(ErrorCode.RECONCILIATION_FAILED, "대사가 맞지 않아 턴을 넘기지 않았다");
        this.seasonId = seasonId;
        this.turnNo = turnNo;
        this.results = List.copyOf(Objects.requireNonNull(results, "results"));
    }

    public long getSeasonId() {
        return seasonId;
    }

    /** 넘어가려던 턴(= 현재 턴 + 1). */
    public int getTurnNo() {
        return turnNo;
    }

    /** 규칙 번호 순 17행. */
    public List<RuleResult> getResults() {
        return results;
    }
}
