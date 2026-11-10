package antlab.season;

import antlab.account.CashAccount;
import antlab.account.CashAccountRepository;
import antlab.account.CashLedger;
import antlab.account.CashLedgerRepository;
import antlab.account.LedgerType;
import antlab.asset.AssetPosition;
import antlab.asset.AssetPositionRepository;
import antlab.asset.PoolProperties;
import antlab.asset.PoolReadiness;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.engine.GeneratedPath;
import antlab.engine.PathRequest;
import antlab.engine.PathSource;
import antlab.member.CurrentMember;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시즌 시작 — 한 요청 = 한 트랜잭션(SPRING_JPA §5.1). 순서: 회원(잠금) → 진행 중 상한 → 풀 준비 → 시드·경로 →
 * season → season_path → cash_account + DEPOSIT 원장 → 0턴 turn_price(풀 전체). 어느 단계든 실패하면 전부 되돌아간다.
 * 락 순서는 member → season → cash_account: 새 시즌 행은 커밋 전 다른 세션에 보이지 않아 따로 잠그지 않고,
 * 같은 회원의 동시 시작만 member 행 잠금으로 직렬화한다(둘 다 "2개"를 세고 4개가 되는 경합 방지).
 */
@Service
public class SeasonStartService {

    /** 진행 중(FINISHED 가 아닌) 시즌 상한. 규칙이라 설정이 아니다 — 설정이면 같은 판에서 규칙이 바뀐다. */
    static final int MAX_ACTIVE_SEASONS = 3;

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final CashAccountRepository accounts;
    private final CashLedgerRepository ledgers;
    private final SeasonPathRepository paths;
    private final TurnPriceRepository turnPrices;
    private final AssetPositionRepository positions;
    private final PathSource pathSource;
    private final SeedGenerator seedGenerator;
    private final SeasonProperties properties;
    private final PoolProperties poolProperties;

    public SeasonStartService(CurrentMember currentMember, SeasonRepository seasons, CashAccountRepository accounts,
            CashLedgerRepository ledgers, SeasonPathRepository paths, TurnPriceRepository turnPrices,
            AssetPositionRepository positions, PathSource pathSource, SeedGenerator seedGenerator,
            SeasonProperties properties, PoolProperties poolProperties) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.accounts = accounts;
        this.ledgers = ledgers;
        this.paths = paths;
        this.turnPrices = turnPrices;
        this.positions = positions;
        this.pathSource = pathSource;
        this.seedGenerator = seedGenerator;
        this.properties = properties;
        this.poolProperties = poolProperties;
    }

    @Transactional
    public SeasonDto start(StartSeasonRequest request) {
        long memberId = currentMember.ensureId();
        if (seasons.countByMemberIdAndStatusNot(memberId, SeasonStatus.FINISHED) >= MAX_ACTIVE_SEASONS) {
            throw new DomainException(ErrorCode.SEASON_LIMIT, "진행 중인 시즌은 최대 3개다. 끝난 뒤 새로 시작한다");
        }

        LocalDate baseDate = poolProperties.baseDate();
        List<AssetPosition> pool = positions.findByBaseDate(baseDate);
        PoolReadiness.require(pool, p -> p.getSystemPosition() == null);

        // 경로는 season INSERT 전에 만든다 — 길이 정책 위반이면 어떤 행도 만들지 않는다.
        int length = request.pathLengthOrDefault();
        long seed = seedGenerator.nextSeed();
        GeneratedPath path = pathSource.generate(new PathRequest(baseDate, length, seed));

        BigDecimal initialCash = MoneyMath.exactWon(properties.initialCash());
        Season season = seasons.save(new Season(memberId, length, seed, path.engineVersion(),
                properties.dataSnapshotDate(), baseDate, initialCash, properties.feeRateKr(),
                properties.feeRateUs(), properties.fxSpreadRate(), MoneyMath.exactRate(request.riskLimit()),
                request.formation().toFormation()));

        List<Long> blockIds = path.monthBlockIds();
        for (int k = 1; k <= blockIds.size(); k++) {
            paths.save(new SeasonPath(season, k, blockIds.get(k - 1)));
        }

        // 계좌는 한 번만 저장한다(INSERT 한 번, UPDATE 없음): 입금을 반영한 뒤 save.
        CashAccount account = new CashAccount(season.getId());
        BigDecimal balanceAfter = account.applyLedgerAmount(initialCash);
        accounts.save(account);
        ledgers.save(new CashLedger(account, LedgerType.DEPOSIT, initialCash, balanceAfter, null));

        // 0턴 가격: 풀 전체, 종목 번호 순. 결과가 0원이 되는 등 데이터 오류는 예외 → 전부 롤백(500).
        for (AssetPosition p : pool) {
            turnPrices.save(new TurnPrice(season, 0, p.getAssetId(),
                    MoneyMath.initialPrice(p.getBasePrice(), p.getBaseFxRate())));
        }
        return SeasonDto.from(season, account.getBalance());
    }
}
