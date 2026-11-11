package antlab.scoring;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수동 검사 — 한 요청 = 한 트랜잭션: 시즌 잠금(첫 시즌 조회, 소유 확인 포함) → 현재 턴 읽기 → 규칙 15개 평가 → 기록.
 * 잠금이 있으므로 읽기 전용으로 둘 수 없다(PostgreSQL 은 읽기 전용 트랜잭션의 행 잠금을 거부한다).
 * 시즌 상태는 보지 않는다 — 끝난 시즌도 검사할 수 있다. 불일치여도 결과를 커밋하고 돌려준다(되돌릴 턴 전환이 없다).
 */
@Service
public class ManualReconciliationService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final ReconciliationService reconciliation;
    private final ReconciliationLogWriter writer;

    public ManualReconciliationService(CurrentMember currentMember, SeasonRepository seasons,
            ReconciliationService reconciliation, ReconciliationLogWriter writer) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.reconciliation = reconciliation;
        this.writer = writer;
    }

    @Transactional
    public ManualReconciliationDto run(long seasonId) {
        long memberId = currentMember.existingId().orElseThrow(ManualReconciliationService::seasonNotFound);
        Season season = seasons.findByIdAndMemberIdForUpdate(seasonId, memberId)
                .orElseThrow(ManualReconciliationService::seasonNotFound);
        // 현재 턴은 잠근 뒤에 읽는다 — 잠금 대기 중에 턴이 넘어갔을 수 있다.
        int turnNo = season.getCurrentTurnNo();
        List<RuleResult> results = reconciliation.evaluateManual(seasonId, turnNo);
        int attemptNo = writer.record(seasonId, turnNo, results);
        List<RuleSummaryDto> rules = results.stream().map(r -> new RuleSummaryDto(r.ruleNo(), r.checkCode().name(),
                r.status().name(), r.mismatchCount())).toList();
        int mismatchRules = (int) results.stream().filter(r -> r.status() == ReconciliationStatus.MISMATCH).count();
        return new ManualReconciliationDto(seasonId, turnNo, attemptNo, mismatchRules == 0 ? "MATCH" : "MISMATCH",
                mismatchRules, rules);
    }

    private static DomainException seasonNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다");
    }
}
