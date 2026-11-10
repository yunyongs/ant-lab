package antlab.account;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.member.CurrentMember;
import antlab.season.FormationDto;
import antlab.season.PositionRatios;
import antlab.season.PositionValuation;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 팀 조회 — 읽기 전용, 잠금 없음. 계좌·보유·라인업을 문장 여러 개로 읽어도 한 스냅샷이도록 REPEATABLE_READ 로 읽는다
 * (PostgreSQL 에서 트랜잭션 전체가 한 시점을 본다 — 읽는 도중 끼어든 매수 커밋이 섞이지 않는다).
 * 값은 현재 턴 가격 × 지금의 보유라 이번 턴의 매매가 바로 반영된다(턴 전환 전용인 규칙 11·19 와 같은 정의, 라인업은 열린 구간 전부).
 * 목표 포메이션은 시즌의 현재 포메이션이다. 문장 수는 현재 회원 1 + 시즌 1 + 계좌 1 + 보유 조인 1 = 4 이고 보유 종목 수와 무관하다.
 */
@Service
public class HoldingsQueryService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final CashAccountRepository accounts;
    private final HoldingRepository holdings;

    public HoldingsQueryService(CurrentMember currentMember, SeasonRepository seasons, CashAccountRepository accounts,
            HoldingRepository holdings) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.accounts = accounts;
        this.holdings = holdings;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HoldingsDto get(Long seasonId) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        CashAccount account = accounts.findById(season.getId())
                .orElseThrow(() -> new IllegalStateException("시즌에 현금 계좌가 없다"));
        List<HoldingViewRow> rows = holdings.findViewRows(season.getId(), season.getCurrentTurnNo());

        List<PositionValuation.Line> lines = new ArrayList<>();
        for (HoldingViewRow row : rows) {
            if (row.price() == null || row.declaredPosition() == null) {
                // 수량이 있는데 그 턴 가격이나 열린 라인업이 없다 — 주문 서비스가 막는 상태라 데이터 손상이다.
                throw new IllegalStateException("보유 종목의 가격 또는 선언 포지션이 없다");
            }
            lines.add(new PositionValuation.Line(row.assetId(), row.quantity(), row.price(), row.declaredPosition()));
        }
        PositionValuation.PositionValues values = PositionValuation.of(account.getBalance(), lines);
        PositionRatios ratios = PositionRatios.of(values.goalkeeper(), values.defender(), values.midfielder(),
                values.forward(), values.total(), season.getCurrentFormation());

        List<HoldingDto> items = new ArrayList<>();
        for (HoldingViewRow row : rows) {
            BigDecimal value = MoneyMath.valuation(row.price(), row.quantity());
            items.add(new HoldingDto(row.assetId(), row.quantity(), MoneyMath.exactAvgPrice(row.avgPrice()),
                    row.declaredPosition(), MoneyMath.exactWon(row.price()), MoneyMath.exactWon(value),
                    MoneyMath.ratio(value, values.total())));
        }
        return new HoldingsDto(season.getId(), season.getCurrentTurnNo(), MoneyMath.exactWon(account.getBalance()),
                MoneyMath.exactWon(values.holdingsValue()), MoneyMath.exactWon(values.total()),
                MoneyMath.exactWon(values.goalkeeper()), MoneyMath.exactWon(values.defender()),
                MoneyMath.exactWon(values.midfielder()), MoneyMath.exactWon(values.forward()),
                ratios.goalkeeperWeight(), ratios.defenderWeight(), ratios.midfielderWeight(), ratios.forwardWeight(),
                FormationDto.from(season.getCurrentFormation()),
                ratios.goalkeeperGap(), ratios.defenderGap(), ratios.midfielderGap(), ratios.forwardGap(),
                ratios.formationDeviation(), List.copyOf(items));
    }
}
