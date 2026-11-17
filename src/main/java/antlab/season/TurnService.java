package antlab.season;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.feed.AlertGenerator;
import antlab.feed.AlertInput;
import antlab.feed.FeedGenerationException;
import antlab.feed.FeedGenerator;
import antlab.feed.FeedInput;
import antlab.feed.FeedInputReader;
import antlab.feed.GeneratedFeedRow;
import antlab.feed.PlayerFactsReader;
import antlab.feed.TurnFeedWriter;
import antlab.member.CurrentMember;
import antlab.scoring.ReconciliationFailedException;
import antlab.scoring.ReconciliationLogWriter;
import antlab.scoring.ReconciliationService;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 턴 전환 — 한 요청 = 한 트랜잭션(SPRING_JPA §5.1·§5.3). {@code db/23} 의 {@code ta_apply} + {@code turn_advance}(성공 경로)의 Java 짝이다.
 *
 * <p>순서: 시즌 잠금(회원 조건 포함, 첫 시즌 조회) → 상태·턴 확인 → 계좌 잠금 → 다음 블록 확인 → 풀 전체 턴 가격 INSERT →
 * 턴 스냅샷 INSERT → 턴 피드와 독려 알림 생성·저장(MVP-11·MVP-12, 스냅샷 뒤·대사 앞) → 대사 17개 → 하나라도 불일치면 예외로 전부 롤백 → 전부 일치면 로그 17행 → 마지막에 현재 턴 +1(마지막 턴이면 FINISHED).
 * <b>현재 턴 갱신은 대사·기록 뒤다</b>: 대사가 시작에 flush 를 하므로 앞에서 부르면 {@code current_turn_no = t+1} 이 대사 전에 DB 에 나가
 * 규칙 18(현재 턴 상한)의 판단이 SQL 과 갈린다. 가격 INSERT 와 턴 갱신이 같은 트랜잭션·시즌 락 아래라 "현재 턴인데 가격 행이 없다"는 상태는 생기지 않는다.
 * 계좌는 읽기만 한다(커밋 때 계좌 UPDATE 0건). 잠금은 season → cash_account 두 개뿐이다.
 * 응답은 {@link TurnResultAssembler} 가 만든다(턴 조회와 같은 조립 코드).
 * 대사 불일치를 롤백 <em>뒤</em> 새 트랜잭션에 기록하는 일은 {@code TurnFacade} 가 한다 — 여기서는 17행을 실은 예외를 던지고 끝난다.
 */
@Service
public class TurnService {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

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
    private final FeedInputReader feedReader;
    private final FeedGenerator feedGenerator;
    private final AlertGenerator alertGenerator;
    private final PlayerFactsReader playerFacts;
    private final TurnFeedWriter feedWriter;

    public TurnService(CurrentMember currentMember, SeasonRepository seasons, CashAccountRepository accounts,
            SeasonPathRepository seasonPaths, TurnPriceRepository turnPrices, TurnRepository turns,
            TurnPriceCalculator priceCalculator, TurnSnapshotCalculator snapshotCalculator,
            ReconciliationService reconciliation, ReconciliationLogWriter logWriter, FeedInputReader feedReader,
            FeedGenerator feedGenerator, AlertGenerator alertGenerator, PlayerFactsReader playerFacts,
            TurnFeedWriter feedWriter) {
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
        this.feedReader = feedReader;
        this.feedGenerator = feedGenerator;
        this.alertGenerator = alertGenerator;
        this.playerFacts = playerFacts;
        this.feedWriter = feedWriter;
    }

    /** The member's own turn request: the season must be in progress. */
    @Transactional
    public TurnResultDto advance(long seasonId, int expectedTurnNo) {
        return advanceBody(seasonId, expectedTurnNo, SeasonStatus.IN_PROGRESS);
    }

    /** One auto turn of an abandoning season (MVP-14): the same body, the season must be abandoning. */
    @Transactional
    public TurnResultDto advanceAbandoned(long seasonId, int expectedTurnNo) {
        return advanceBody(seasonId, expectedTurnNo, SeasonStatus.ABANDONING);
    }

    private TurnResultDto advanceBody(long seasonId, int expectedTurnNo, SeasonStatus required) {
        // 1~2. 시즌 — 소유 확인과 잠금을 한 조회로. 상태 검사가 턴 검사보다 앞이다(마지막 턴의 둘째 요청은 SEASON_NOT_ACTIVE).
        long memberId = currentMember.existingId().orElseThrow(TurnService::seasonNotFound);
        Season season = seasons.findByIdAndMemberIdForUpdate(seasonId, memberId)
                .orElseThrow(TurnService::seasonNotFound);
        if (season.getStatus() == SeasonStatus.HALTED) {
            throw new DomainException(ErrorCode.SEASON_HALTED, "대사가 거듭 맞지 않아 시즌을 멈췄다 — 관리자 확인이 필요하다");
        }
        if (season.getStatus() != required) {
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

        // 7b. 턴 피드와 독려 알림 — 이 턴까지의 데이터만 읽는다. 실패(FeedGenerationException)는 500 이고 같은 트랜잭션이라 턴 전체가 롤백된다.
        List<TurnFeed> feed = storeFeed(season, next, priceByAsset, snapshot);

        // 8~9. 대사 17개 — 하나라도 어긋나면 unchecked 예외로 전체 롤백
        List<RuleResult> results = reconciliation.evaluateTurn(seasonId, next);
        if (results.stream().anyMatch(r -> r.status() != ReconciliationStatus.MATCH)) {
            throw new ReconciliationFailedException(seasonId, next, results);
        }

        // 10. 전부 일치 — 같은 트랜잭션에 합류해 17행 기록
        logWriter.record(seasonId, next, results);

        // 11. 응답 입력 — 직전 총액은 한 번 읽는다(1문장). 현재 턴 갱신 전에 읽어 시즌 UPDATE 가 앞당겨 나가지 않게 한다.
        BigDecimal previousTotal = next == 1 ? season.getInitialCash()
                : turns.findTotalValueBySeasonIdAndTurnNo(seasonId, expectedTurnNo)
                        .orElseThrow(() -> new IllegalStateException("직전 턴의 스냅샷이 없다"));

        // 12. 현재 턴 +1, 마지막 턴이면 FINISHED (대사·기록 뒤)
        season.advanceTurn();

        // 13. 응답 — 조회와 같은 조립기. 목표 포메이션은 시즌의 현재 값이다: 전환 시점에는 모든 변경의 turn_no ≤ next-1 < next 라
        //     마지막 변경의 after = 현재 포메이션이다(대사 규칙 15 가 같은 트랜잭션에서 확인).
        return TurnResultAssembler.assemble(season, turn, previousTotal, season.getCurrentFormation(), feed);
    }

    /** The turn's feed rows, then its nudge alerts numbered after them, written together; returns them in display order. */
    private List<TurnFeed> storeFeed(Season season, int turnNo, Map<Long, BigDecimal> priceByAsset, TurnSnapshot snapshot) {
        try {
            FeedInput input = feedReader.read(season.getId(), season.getSeed(), turnNo, priceByAsset);
            List<GeneratedFeedRow> rows = new ArrayList<>(feedGenerator.generate(input));
            AlertInput alertInput = new AlertInput(season.getSeed(), turnNo, input.assets(),
                    playerFacts.read(season.getId(), turnNo, snapshot.cashBalance(), snapshot.totalValue()));
            rows.addAll(alertGenerator.generate(alertInput, rows.size() + 1));
            return feedWriter.write(season, turnNo, rows);
        } catch (FeedGenerationException failed) {
            log.error("피드 생성 실패: seasonId={} turnNo={} failure={}", season.getId(), turnNo, failed.failure());
            throw failed;
        } catch (DataAccessException stored) {
            // A stored row broke a constraint (key, CHECK, length): log the code only, never a value.
            log.error("피드 저장 실패: seasonId={} turnNo={} failure=FEED_STORE", season.getId(), turnNo);
            throw stored;
        }
    }

    private static DomainException seasonNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다");
    }
}
