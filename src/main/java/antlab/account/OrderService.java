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
 * 주문 — 한 요청 = 한 트랜잭션(SPRING_JPA §5.2). 시장가 매수와 매도 두 분기이며 0턴 팀 구성과 1턴 이후가 같은 경로다.
 *
 * <p>공통 앞부분({@link #open}): 시즌 잠금(회원 조건 포함, 첫 시즌 조회) → 계좌 잠금 → 원장 확인 → 그 턴 가격 →
 * 요율 → 그 턴의 마지막 전술 변경. 락 순서는 season → cash_account. 회원·보유·구간은 잠그지 않는다 — 모든 쓰기 경로가
 * 시즌을 먼저 잠그므로 시즌 락 아래에서만 바뀐다(SQL {@code trade_sell} 은 함수 단위라 보유 행도 잠그지만 여기서는 생략).
 *
 * <p>매수: 잔액 → 보유·구간 정합 → 선언 → orders → execution → holding → cash_ledger(BUY → FEE) → cash_account → lineup.
 * <p>매도(S1–S10): 보유 ≥ 수량(아니면 INSUFFICIENT_HOLDING) → 열린 구간 → 금액·실현 손익(체결 직전 평균단가, 보유를
 * 바꾸기 전에 계산) → orders → execution → holding → cash_ledger(SELL → FEE) → cash_account → 전량이면 구간 닫기.
 * 매도에는 선언 포지션을 받지 않고(잠금 전 400), 0턴 매도도 막지 않는다(SQL 의 0턴 거부는 파일 구조 때문이다).
 *
 * <p>검증은 모두 첫 INSERT 앞에 있어, 거절된 요청은 번호표(identity)도 쓰지 않는다.
 * 던지는 예외는 모두 unchecked 라 어느 단계에서든 전부 되돌아간다. 이 서비스는 턴 전환 퍼사드를 부르지 않는다.
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
        // S0 — 잠금 전 입력 검사. 구간 선언 변경은 라인업 변경의 일이라 매도에 선언이 오면 거절한다.
        if (request.side() == OrderSide.SELL && request.declaredPosition() != null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT, "매도에는 선언 포지션을 보내지 않는다");
        }
        OrderContext ctx = open(seasonId, request);
        return request.side() == OrderSide.SELL ? sell(ctx, request) : buy(ctx, request);
    }

    /** 매수·매도 공통 앞부분 — 시즌 잠금부터 전술 변경 번호까지. 쓰기는 하지 않는다. */
    private OrderContext open(long seasonId, PlaceOrderRequest request) {
        int turnNo = request.expectedTurnNo();
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

        // 4. 그 턴 가격(정본) — 다시 계산하지 않는다. 요율은 시즌 행의 값이다(매도도 같은 요율).
        TurnPrice turnPrice = turnPrices.findBySeasonIdAndTurnNoAndAssetId(seasonId, turnNo, assetId)
                .orElseThrow(() -> new DomainException(ErrorCode.ASSET_NOT_IN_POOL, "이 턴의 풀에 없는 종목이다"));
        BigDecimal price = turnPrice.getPrice();
        String market = assets.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("가격이 있는 종목이 종목 표에 없다")).getMarket().name();
        BigDecimal rate = MoneyMath.feeRate(market, season.getFeeRateKr(), season.getFeeRateUs(),
                season.getFxSpreadRate());

        // 같은 턴의 마지막 전술 변경에 묶는다 — 주문은 불변이라 INSERT 때 넣는다.
        Long changeId = formationChanges.findLastBySeasonIdAndTurnNo(seasonId, turnNo)
                .map(FormationChange::getId).orElse(null);
        return new OrderContext(season, account, turnNo, price, rate, changeId);
    }

    private OrderResultDto buy(OrderContext ctx, PlaceOrderRequest request) {
        Season season = ctx.season();
        CashAccount account = ctx.account();
        long seasonId = season.getId();
        int turnNo = ctx.turnNo();
        int quantity = request.quantity();
        Long assetId = request.assetId();
        BigDecimal price = ctx.price();

        // 5. 잔액 — 대금이 주문 가능 금액을 넘으면 수수료를 계산하기 전에 거절한다(큰 수량이 범위 예외로 새지 않게).
        BigDecimal available = account.getBalance().subtract(account.getReserved());
        if (price.multiply(BigDecimal.valueOf(quantity)).compareTo(available) > 0) {
            throw insufficientCash();
        }
        BigDecimal notional = MoneyMath.valuation(price, quantity);
        BigDecimal fee = MoneyMath.fee(price, quantity, ctx.rate());
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

        // 8~13. 쓰기. 검증은 모두 위에서 끝났다.
        Order order = orders.save(new Order(seasonId, assetId, turnNo, OrderSide.BUY, OrderType.MARKET, quantity,
                null, OrderStatus.FILLED, OrderSource.DIRECT, ctx.changeId(), null));
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

    /** 매도 — SQL trade_sell 과 같은 순서(S1–S10). 매도 쪽 잔액 검사는 없다(SQL 과 같다). 합산 요율 < 1 이면 대금 ≥ 수수료이고, 미국 합산 요율(미국 + 환전)이 1 이상인 설정이면 계좌가 음수 잔액을 거부해 전부 되돌아간다. */
    private OrderResultDto sell(OrderContext ctx, PlaceOrderRequest request) {
        CashAccount account = ctx.account();
        long seasonId = ctx.season().getId();
        int turnNo = ctx.turnNo();
        int quantity = request.quantity();
        Long assetId = request.assetId();
        BigDecimal price = ctx.price();

        // S1. 보유 — 없거나 모자라면 거절. 보유 0 인데 구간이 남은 손상 상태도 여기서 먼저 걸린다(SQL 과 같은 우선순위).
        Holding holding = holdings.findBySeasonIdAndAssetId(seasonId, assetId)
                .filter(h -> h.getQuantity() >= quantity)
                .orElseThrow(() -> new DomainException(ErrorCode.INSUFFICIENT_HOLDING, "보유 수량이 모자란다"));
        // S2. 열린 구간
        Lineup open = lineups.findOpenBySeasonIdAndAssetId(seasonId, assetId)
                .orElseThrow(() -> new DomainException(ErrorCode.LINEUP_MISMATCH, "보유와 라인업 구간이 맞지 않는다"));

        // S3. 금액 — 보유 검사 뒤(큰 수량이 범위 예외로 새지 않게). 실현 손익은 보유를 바꾸기 전 평균단가로 계산한다.
        BigDecimal notional = MoneyMath.valuation(price, quantity);
        BigDecimal fee = MoneyMath.fee(price, quantity, ctx.rate());
        BigDecimal pnl = MoneyMath.realizedPnl(price, holding.getAvgPrice(), quantity);

        // S4~S9. 쓰기. 검증은 모두 위에서 끝났다.
        Order order = orders.save(new Order(seasonId, assetId, turnNo, OrderSide.SELL, OrderType.MARKET, quantity,
                null, OrderStatus.FILLED, OrderSource.DIRECT, ctx.changeId(), null));
        Execution execution = executions.save(new Execution(order.getId(), price, quantity, fee, BigDecimal.ZERO, pnl));

        holding.applySell(quantity);
        holdings.save(holding);

        ledgers.save(new CashLedger(account, LedgerType.SELL, notional, account.applyLedgerAmount(notional),
                order.getId()));
        if (fee.signum() > 0) {
            ledgers.save(new CashLedger(account, LedgerType.FEE, fee.negate(),
                    account.applyLedgerAmount(fee.negate()), order.getId()));
        }
        // 계좌 UPDATE 는 커밋 때 flush 되며 버전이 하나 오른다.

        if (holding.getQuantity() == 0) {
            open.close(turnNo);
        }
        return OrderResultDto.of(order, execution, notional, account.getBalance(), holding,
                open.getDeclaredPosition());
    }

    /** 공통 앞부분의 결과 — 매수·매도 분기가 함께 읽는다. */
    private record OrderContext(Season season, CashAccount account, int turnNo, BigDecimal price, BigDecimal rate,
            Long changeId) {
    }

    private static DomainException seasonNotFound() {
        return new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다");
    }

    private static DomainException insufficientCash() {
        return new DomainException(ErrorCode.INSUFFICIENT_CASH, "현금이 모자란다");
    }
}
