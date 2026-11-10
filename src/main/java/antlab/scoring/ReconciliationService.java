package antlab.scoring;

import antlab.season.SeasonRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대사 평가 — 수동 검사 규칙 15개를 규칙 번호 순으로 돌려 {@link RuleResult} 15개를 낸다(SPRING_JPA §6).
 *
 * <p>읽기 일관성: 쿼리는 15문장이라 READ COMMITTED 에서 문장마다 스냅샷이 다르다. 같은 결과를 얻는 근거는
 * "시즌에 딸린 행을 쓰는 모든 경로가 먼저 {@code season} 행을 잠근다"는 불변식이다. 그래서 이 메서드는 호출자가 이미 시즌을 잠근
 * 트랜잭션 안에서만 부른다({@code MANDATORY}). 잠금은 이 메서드가 잡지 않는다.
 */
@Service
public class ReconciliationService {

    private final ReconciliationLogRepository repository;
    private final EntityManager entityManager;
    private final SeasonRepository seasons;

    public ReconciliationService(ReconciliationLogRepository repository, EntityManager entityManager,
            SeasonRepository seasons) {
        this.repository = repository;
        this.seasons = seasons;
        this.entityManager = entityManager;
    }

    /**
     * 수동 검사 15개 규칙 평가. 읽기만 한다.
     *
     * @param turnNo 검사 턴 — 수동 검사는 시즌의 현재 턴, 턴 전환은 넘어가려던 턴. 음수는 호출자 버그다.
     * @throws IllegalArgumentException 검사 턴이 음수이거나 시즌이 없음
     * @throws IllegalStateException 격리 수준이 READ COMMITTED 가 아님(REPEATABLE READ 는 잠금 대기 뒤 40001 이 날 수 있다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RuleResult> evaluateManual(long seasonId, int turnNo) {
        prepare(seasonId, turnNo);
        // 호출자가 이미 잠갔으면 바로 지나간다(멱등 재획득). 첫 규칙 쿼리 전에 잡아야 15문장이 한 상태를 본다.
        seasons.findByIdForUpdate(seasonId).orElseThrow(() -> new IllegalArgumentException("시즌이 없다"));
        return List.copyOf(manualRules(seasonId, turnNo));
    }

    /**
     * 턴 전환 대사 17개(수동 15개 + 규칙 11·19)를 규칙 번호 순으로 평가한다. 읽기만 한다.
     *
     * <p><b>전제:</b> 호출자가 같은 트랜잭션에서 시즌을 이미 잠그고 존재를 확인했다. 이 메서드는 시즌 존재를 따로 확인하지 않으므로
     * 없는 시즌으로 부르면 계좌 없음 류의 불일치가 나온다.
     *
     * @param turnNo 넘어가려던 턴(= 현재 턴 + 1) — 방금 넣은 {@code turn}·{@code turn_price} 행을 본다
     * @throws IllegalArgumentException 검사 턴이 음수
     * @throws IllegalStateException 격리 수준이 READ COMMITTED 가 아님
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RuleResult> evaluateTurn(long seasonId, int turnNo) {
        prepare(seasonId, turnNo);
        List<RuleResult> results = manualRules(seasonId, turnNo);
        results.add(RuleResult.of(11, repository.findRule11Valuation(seasonId, turnNo)));
        results.add(RuleResult.of(19, repository.findRule19PositionValue(seasonId, turnNo)));
        results.sort(Comparator.comparingInt(RuleResult::ruleNo));
        return List.copyOf(results);
    }

    private void prepare(long seasonId, int turnNo) {
        if (turnNo < 0) {
            throw new IllegalArgumentException("검사 턴이 음수다");
        }
        // 대기 중인 변경(같은 트랜잭션에서 방금 넣은 행)이 네이티브 쿼리에 보이도록 먼저 내보낸다.
        entityManager.flush();
        String isolation = repository.findTransactionIsolation();
        if (!"read committed".equals(isolation)) {
            throw new IllegalStateException("대사는 READ COMMITTED 에서만 돌린다");
        }
    }

    private List<RuleResult> manualRules(long seasonId, int turnNo) {
        List<RuleResult> results = new ArrayList<>(17);
        results.add(RuleResult.of(1, repository.findRule01AccountOpening(seasonId, turnNo)));
        results.add(RuleResult.of(2, repository.findRule02CashSum(seasonId, turnNo)));
        results.add(RuleResult.of(3, repository.findRule03LedgerChain(seasonId, turnNo)));
        results.add(RuleResult.of(4, repository.findRule04OrderLedger(seasonId, turnNo)));
        results.add(RuleResult.of(6, repository.findRule06HoldingQty(seasonId, turnNo)));
        results.add(RuleResult.of(7, repository.findRule07OrderFill(seasonId, turnNo)));
        results.add(RuleResult.of(8, repository.findRule08ExecValues(seasonId, turnNo)));
        results.add(RuleResult.of(9, repository.findRule09ReservedZero(seasonId, turnNo)));
        results.add(RuleResult.of(12, repository.findRule12TurnPrice(seasonId, turnNo)));
        results.add(RuleResult.of(13, repository.findRule13FillPrice(seasonId, turnNo)));
        results.add(RuleResult.of(14, repository.findRule14FeeRule(seasonId, turnNo)));
        results.add(RuleResult.of(15, repository.findRule15Formation(seasonId, turnNo)));
        results.add(RuleResult.of(16, repository.findRule16FormationBind(seasonId, turnNo)));
        results.add(RuleResult.of(17, repository.findRule17LineupHolding(seasonId, turnNo)));
        results.add(RuleResult.of(18, repository.findRule18LineupOverlap(seasonId, turnNo)));
        return results;
    }
}
