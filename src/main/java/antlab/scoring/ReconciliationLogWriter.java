package antlab.scoring;

import antlab.season.SeasonRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대사 결과를 새 시도 번호로 {@code reconciliation_log} 에 남긴다(일치해도 기록). {@code db/22} 의 {@code recon_log} 의 순서 사본이다:
 * 시즌 잠금 → 코드 집합 검사(수동 15개 또는 전체 17개) → 그 (시즌, 턴)의 {@code max + 1} → 행마다 INSERT.
 * 수동 검사·턴 전환·SQL 이 한 번호 공간을 쓴다. 트랜잭션은 합류(REQUIRED)한다 — 바깥 트랜잭션이 없으면(롤백 뒤 기록) 새로 연다.
 *
 * <p>23505(유일 위반) 재시도는 옮기지 않는다: PostgreSQL 에서 유일 위반은 트랜잭션을 망가뜨려 같은 트랜잭션에서 다시 읽을 수 없고,
 * 모든 기록 경로가 같은 {@code season} 잠금 아래라 겹치지 않는다. 그래도 나면 예외가 그대로 올라간다. 롤백 뒤 기록 경로는 바깥 트랜잭션이 없어 `TurnFacade` 가 새 트랜잭션으로 최대 3번 다시 부른다.
 */
@Service
public class ReconciliationLogWriter {

    private final ReconciliationLogRepository logs;
    private final SeasonRepository seasons;

    public ReconciliationLogWriter(ReconciliationLogRepository logs, SeasonRepository seasons) {
        this.logs = logs;
        this.seasons = seasons;
    }

    /**
     * @return 이번에 쓴 시도 번호
     * @throws IllegalArgumentException 검사 턴이 음수, 묶음이 비었거나 코드 집합이 수동 15개도 전체 17개도 아님(빠짐·중복),
     *         시즌이 없음 — 모두 쓰기 전이라 번호를 쓰지 않는다
     */
    @Transactional
    public int record(long seasonId, int turnNo, List<RuleResult> rows) {
        if (turnNo < 0) {
            throw new IllegalArgumentException("검사 턴이 음수다");
        }
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("결과 행 묶음이 비어 있다");
        }
        // 1. 시즌 잠금 — 락 순서의 맨 앞. 호출자가 이미 잠갔으면 같은 트랜잭션이라 즉시 지나간다.
        seasons.findByIdForUpdate(seasonId).orElseThrow(() -> new IllegalArgumentException("시즌이 없다"));
        // 2. 코드 집합 — 잠금 뒤·INSERT 전이라 거절해도 번호표(identity)를 쓰지 않는다.
        Set<CheckCode> codes = new HashSet<>();
        for (RuleResult row : rows) {
            if (!codes.add(row.checkCode())) {
                throw new IllegalArgumentException("검사 코드가 겹친다");
            }
        }
        if (!codes.equals(Set.copyOf(RuleCatalog.manualCodes())) && !codes.equals(Set.copyOf(RuleCatalog.allCodes()))) {
            throw new IllegalArgumentException("결과 행의 코드 집합이 수동 15개도 전체 17개도 아니다");
        }
        // 3. 새 시도 번호, 4. 행마다 INSERT(IDENTITY 라 묶음 저장 불가)
        int attemptNo = logs.findMaxAttemptNo(seasonId, turnNo) + 1;
        for (RuleResult row : rows) {
            logs.save(new ReconciliationLog(seasonId, turnNo, attemptNo, row.checkCode(), row.status(),
                    row.expectedValue(), row.actualValue(), row.diffValue(), row.mismatchCount(), row.classification(),
                    row.detail()));
        }
        return attemptNo;
    }
}
