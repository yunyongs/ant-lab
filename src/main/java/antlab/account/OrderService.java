package antlab.account;

import antlab.asset.AssetRepository;
import antlab.asset.Position;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.member.CurrentMember;
import antlab.season.FormationChange;
import antlab.season.FormationChangeRepository;
import antlab.season.Lineup;
import antlab.season.LineupRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import antlab.season.TurnPrice;
import antlab.season.TurnPriceRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 — 한 요청 = 한 트랜잭션(SPRING_JPA §5.2). 지금은 시장가 매수 한 건이며 0턴 팀 구성과 1턴 이후 매수가 같은 경로다.
 *
 * <p>순서: 시즌 잠금(회원 조건 포함, 첫 시즌 조회) → 계좌 잠금 → 원장 확인 → 그 턴 가격 → 잔액 → 보유·구간 정합 → 선언 →
 * 전술 변경 묶기 → orders → execution → holding → cash_ledger(BUY → FEE) → cash_account → lineup.
 * 검증은 모두 첫 INSERT 앞에 있어, 거절된 요청은 번호표(identity)도 쓰지 않는다.
 * 락 순서는 season → cash_account. 회원·보유·구간은 잠그지 않는다(시즌 락 아래에서만 바뀐다).
 * 던지는 예외는 모두 unchecked 라 어느 단계에서든 전부 되돌아간다.
 */
@Service
public class OrderService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final CashAccountRepository accounts;
    private final CashLedgerRepository ledgers;
    private final TurnPriceRepository turnPrices;
    private final AssetRepository assets;
    private final HoldingRepository holdings;
    private final LineupRepository lineups;
    private final FormationChangeRepository formationChanges;
    private final OrderRepository orders;
    private final ExecutionRepository executions;

    public OrderService(CurrentMember currentMember, SeasonRepository seasons, CashAccountRepository accounts,
            CashLedgerRepository ledgers, TurnPriceRepository turnPrices, AssetRepository assets,
            HoldingRepository holdings, LineupRepository lineups, FormationChangeRepository formationChanges,
            OrderRepository orders, ExecutionRepository executions) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.accounts = accounts;
        this.ledgers = ledgers;
        this.turnPrices = turnPrices;
        this.assets = assets;
        this.holdings = holdings;
        this.lineups = lineups;
        this.formationChanges = formationChanges;
        this.orders = orders;
        this.executions = executions;
    }

    @Transactional
    public OrderResultDto place(long seasonId, PlaceOrderRequest request) {
        if (request.side() != OrderSide.BUY) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "매도는 아직 받지 않는다");
        }
        int turnNo = request.expectedTurnNo();
        int quantity = request.quantity();
        Long assetId = request.assetId();

        // 1. 시즌 — 소유 확인과 잠금을 한 조회로(첫 시즌 조회가 곧 잠금 조회). 회원은 만들지도 잠그지도 않는다.
        long memberId = currentMember.existingId().orElseThrow(OrderService::seasonNotFound);
        Season season = seasons.findByIdAndMemberIdForUpdate(seasonId, memberId)
                .orElseThrow(OrderService::seasonNotFound);
        if (season.getStatus() != SeasonStatus.IN_PROGRESS) {
            throw new DomainException(ErrorCode.SEASON_NOT_ACTIVE, "진행 중인 시즌이 아니다");
        }
        if (season.getCurrentTurnNo() != turnNo) {
            throw new DomainException(ErrorCode.TURN_MISMATCH, "시즌의 현재 턴과 주문이 본 턴이 다르다");
        }

        // 2~3. 계좌 잠금과 원장 확인 — 최신 원장의 거래 후 잔액이 계좌 잔액과 같아야 한다.
        CashAccount account = accounts.findBySeasonIdForUpdate(seasonId)
                .orElseThrow(() -> new DomainException(ErrorCode.LEDGER_MISMATCH, "현금 계좌를 확인할 수 없다"));
        CashLedger latest = ledgers.findLatestBySeasonId(seasonId)
                .orElseThrow(() -> new DomainException(ErrorCode.LEDGER_MISMATCH, "원장과 계좌 잔액이 맞지 않는다"));
        if (latest.getBalanceAfter().compareTo(account.getBalance()) != 0) {
            throw new DomainException(ErrorCode.LEDGER_MISMATCH, "원장과 계좌 잔액이 맞지 않는다");
        }

        // 4. 그 턴 가격(정본) — 다시 계산하지 않는다. 요율은 시즌 행의 값이다.
        TurnPrice turnPrice = turnPrices.findBySeasonIdAndTurnNoAndAssetId(seasonId, turnNo, assetId)
                .orElseThrow(() -> new DomainException(ErrorCode.ASSET_NOT_IN_POOL, "이 턴의 풀에 없는 종목이다"));
        BigDecimal price = turnPrice.getPrice();
        String market = assets.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("가격이 있는 종목이 종목 표에 없다")).getMarket().name();
        BigDecimal rate = MoneyMath.feeRate(market, season.getFeeRateKr(), season.getFeeRateUs(),
                season.getFxSpreadRate());

        // 5. 잔액 — 대금이 주문 가능 금액을 넘으면 수수료를 계산하기 전에 거절한다(큰 수량이 범위 예외로 새지 않게).
        BigDecimal available = account.getBalance().subtract(account.getReserved());
        if (price.multiply(BigDecimal.valueOf(quantity)).compareTo(available) > 0) {
            throw insufficientCash();
        }
        BigDecimal notional = MoneyMath.valuation(price, quantity);
        BigDecimal fee = MoneyMath.fee(price, quantity, rate);
        if (!MoneyMath.affordable(available, notional, fee)) {
            throw insufficientCash();
        }

        // 6. 보유와 열린 구간의 정합, 선언 포지션
        Optional<Holding> existing = holdings.findBySeasonIdAndAssetId(seasonId, assetId);
        Holding holding = existing.orElseGet(() -> new Holding(seasonId, assetId));
        Optional<Lineup> open = lineups.findOpenBySeasonIdAndAssetId(seasonId, assetId);
        if ((holding.getQuantity() > 0) != open.isPresent()) {
            throw new DomainException(ErrorCode.LINEUP_MISMATCH, "보유와 라인업 구간이 맞지 않는다");
        }
        Position declared = request.declaredPosition();
        if (open.isEmpty() && declared == null) {
            throw new DomainException(ErrorCode.POSITION_REQUIRED, "새로 사는 종목은 선언 포지션이 필요하다");
        }
        if (open.isPresent() && declared != null && declared != open.get().getDeclaredPosition()) {
            throw new DomainException(ErrorCode.POSITION_CONFLICT, "열린 구간의 선언 포지션과 다르다");
        }

        // 7. 같은 턴의 마지막 전술 변경에 묶는다 — 주문은 불변이라 INSERT 때 넣는다.
        Long changeId = formationChanges.findLastBySeasonIdAndTurnNo(seasonId, turnNo)
                .map(FormationChange::getId).orElse(null);

        // 8~13. 쓰기. 검증은 모두 위에서 끝났다.
        Order order = orders.save(new Order(seasonId, assetId, turnNo, OrderSide.BUY, OrderType.MARKET, quantity,
                null, OrderStatus.FILLED, OrderSource.DIRECT, changeId, null));
        Execution execution = executions.save(new Execution(order.getId(), price, quantity, fee, BigDecimal.ZERO, null));

        holding.applyBuy(quantity, MoneyMath.avgPrice(holding.getQuantity(), holding.getAvgPrice(), quantity, price));
        holdings.save(holding);

        ledgers.save(new CashLedger(account, LedgerType.BUY, notional.negate(),
                account.applyLedgerAmount(notional.negate()), order.getId()));
        if (fee.signum() > 0) {
            ledgers.save(new CashLedger(account, LedgerType.FEE, fee.negate(),
                    account.applyLedgerAmount(fee.negate()), order.getId()));
        }
        // 계좌 UPDATE 는 커밋 때 flush 되며 버전이 하나 오른다.

        Position position = open.map(Lineup::getDeclaredPosition).orElse(declared);
        if (open.isEmpty()) {
            lineups.save(new Lineup(season, assetId, declared, turnNo));
        }
        return OrderResultDto.of(order, execution, notional, account.getBalance(), holding, position);
    }

    private static DomainException seasonNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다");
    }

    private static DomainException insufficientCash() {
        return new DomainException(ErrorCode.INSUFFICIENT_CASH, "현금이 모자란다");
    }
}
