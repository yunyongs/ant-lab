package antlab.season;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.scoring.ReconciliationFailedException;
import antlab.scoring.ReconciliationLogWriter;
import antlab.scoring.ReconciliationService;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleResult;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 턴 전환 — 한 요청 = 한 트랜잭션(SPRING_JPA §5.1·§5.3). {@code db/23} 의 {@code ta_apply} + {@code turn_advance}(성공 경로)의 Java 짝이다.
 *
 * <p>순서: 시즌 잠금(회원 조건 포함, 첫 시즌 조회) → 상태·턴 확인 → 계좌 잠금 → 다음 블록 확인 → 풀 전체 턴 가격 INSERT →
 * 턴 스냅샷 INSERT → 대사 17개 → 하나라도 불일치면 예외로 전부 롤백 → 전부 일치면 로그 17행 → 마지막에 현재 턴 +1(마지막 턴이면 FINISHED).
 * <b>현재 턴 갱신은 대사·기록 뒤다</b>: 대사가 시작에 flush 를 하므로 앞에서 부르면 {@code current_turn_no = t+1} 이 대사 전에 DB 에 나가
 * 규칙 18(현재 턴 상한)의 판단이 SQL 과 갈린다. 가격 INSERT 와 턴 갱신이 같은 트랜잭션·시즌 락 아래라 "현재 턴인데 가격 행이 없다"는 상태는 생기지 않는다.
 * 계좌는 읽기만 한다(커밋 때 계좌 UPDATE 0건). 잠금은 season → cash_account 두 개뿐이다.
 * 대사 불일치를 롤백 <em>뒤</em> 새 트랜잭션에 기록하는 일은 {@code TurnFacade} 가 한다 — 여기서는 17행을 실은 예외를 던지고 끝난다.
 */
@Service
public class TurnService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final CashAccountRepository accounts;
    private final SeasonPathRepository seasonPaths;
    private final TurnPriceRepository turnPrices;
    private final TurnRepository turns;
    private final TurnPriceCalculator priceCalculator;
    private final TurnSnapshotCalculator snapshotCalculator;
    private final ReconciliationService reconciliation;
    private final ReconciliationLogWriter logWriter;

    public TurnService(CurrentMember currentMember, SeasonRepository seasons, CashAccountRepository accounts,
            SeasonPathRepository seasonPaths, TurnPriceRepository turnPrices, TurnRepository turns,
            TurnPriceCalculator priceCalculator, TurnSnapshotCalculator snapshotCalculator,
            ReconciliationService reconciliation, ReconciliationLogWriter logWriter) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.accounts = accounts;
        this.seasonPaths = seasonPaths;
        this.turnPrices = turnPrices;
        this.turns = turns;
        this.priceCalculator = priceCalculator;
        this.snapshotCalculator = snapshotCalculator;
        this.reconciliation = reconciliation;
        this.logWriter = logWriter;
    }

    @Transactional
    public TurnResultDto advance(long seasonId, int expectedTurnNo) {
        // 1~2. 시즌 — 소유 확인과 잠금을 한 조회로. 상태 검사가 턴 검사보다 앞이다(마지막 턴의 둘째 요청은 SEASON_NOT_ACTIVE).
        long memberId = currentMember.existingId().orElseThrow(TurnService::seasonNotFound);
        Season season = seasons.findByIdAndMemberIdForUpdate(seasonId, memberId)
                .orElseThrow(TurnService::seasonNotFound);
        if (season.getStatus() != SeasonStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "진행 중인 시즌이 아니다");
        }
        if (season.getCurrentTurnNo() != expectedTurnNo) {
            throw new DomainException(ErrorCode.TURN_MISMATCH, "시즌의 현재 턴과 전환이 본 턴이 다르다");
        }

        // 3. 계좌 잠금 — 락 순서 season → cash_account. 읽기만 한다. 없으면 데이터 손상이라 500.
        CashAccount account = accounts.findBySeasonIdForUpdate(seasonId)
                .orElseThrow(() -> new IllegalStateException("시즌의 현금 계좌가 없다"));

        // 4. 다음 턴 경로
        int next = expectedTurnNo + 1;
        if (!seasonPaths.existsBySeasonIdAndTurnNo(seasonId, next)) {
            throw new IllegalStateException("다음 턴의 경로가 없다");
        }

        // 5~6. 풀 전체 다음 턴 가격(쓰기 전에 전부 계산·검증한다)
        List<TurnPriceCalculator.AssetPrice> prices = priceCalculator.compute(season, next);
        Map<Long, BigDecimal> priceByAsset = new HashMap<>();
        for (TurnPriceCalculator.AssetPrice price : prices) {
            priceByAsset.put(price.assetId(), price.price());
        }
        for (TurnPriceCalculator.AssetPrice price : prices) {
            turnPrices.save(new TurnPrice(season, next, price.assetId(), price.price()));
        }

        // 7. 턴 스냅샷
        TurnSnapshot snapshot = snapshotCalculator.snapshot(season, account, next, priceByAsset);
        Turn turn = turns.save(new Turn(season, next, snapshot.cashBalance(), snapshot.holdingsValue(),
                snapshot.totalValue(), snapshot.goalkeeperValue(), snapshot.defenderValue(),
                snapshot.midfielderValue(), snapshot.forwardValue(), snapshot.drawdown()));

        // 8~9. 대사 17개 — 하나라도 어긋나면 unchecked 예외로 전체 롤백
        List<RuleResult> results = reconciliation.evaluateTurn(seasonId, next);
        if (results.stream().anyMatch(r -> r.status() != ReconciliationStatus.MATCH)) {
            throw new ReconciliationFailedException(seasonId, next, results);
        }

        // 10. 전부 일치 — 같은 트랜잭션에 합류해 17행 기록
        logWriter.record(seasonId, next, results);

        // 11. 현재 턴 +1, 마지막 턴이면 FINISHED (대사·기록 뒤)
        season.advanceTurn();
        return TurnResultDto.of(season, turn);
    }

    private static DomainException seasonNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다");
    }
}
