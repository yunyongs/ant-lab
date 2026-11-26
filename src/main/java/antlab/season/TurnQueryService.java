package antlab.season;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.common.money.MoneyMath;
import antlab.scoring.MarketBenchmarkLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 턴 결과 조회 — 읽기 전용, 잠금 없음. 불변 행({@code turn}, 그 턴 이전의 {@code formation_change})만 읽으므로 기본 격리로 충분하다.
 * 응답은 턴 전환과 같은 {@link TurnResultAssembler} 가 만든다. 다른 회원의 시즌은 없는 것과 같게 404 로 답한다.
 * 문장 수는 현재 회원 1 + 시즌 1 + 턴 행 1 + 전술 변경 1 + 그 턴의 피드 1 + 그 턴의 거시 환경 1 = 6 이고 보유 종목 수와 무관하다.
 */
@Service
public class TurnQueryService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final TurnRepository turns;
    private final FormationChangeRepository formationChanges;
    private final TurnFeedRepository feeds;
    private final SeasonPathRepository seasonPaths;
    private final MarketBenchmarkLine benchmarkLine;

    public TurnQueryService(CurrentMember currentMember, SeasonRepository seasons, TurnRepository turns,
            FormationChangeRepository formationChanges, TurnFeedRepository feeds,
            SeasonPathRepository seasonPaths, MarketBenchmarkLine benchmarkLine) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.turns = turns;
        this.formationChanges = formationChanges;
        this.feeds = feeds;
        this.seasonPaths = seasonPaths;
        this.benchmarkLine = benchmarkLine;
    }

    /** 아직 진행하지 않은 턴(현재 턴 초과)은 409. 번호가 1 미만이면 호출 전에 400 이다. */
    @Transactional(readOnly = true)
    public TurnResultDto get(Long seasonId, int turnNo) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        if (turnNo > season.getCurrentTurnNo()) {
            throw new DomainException(ErrorCode.TURN_NOT_REACHED, "아직 진행하지 않은 턴이다");
        }
        // 성공 여부는 turn 행으로 판단한다(대사 시도 번호로 판단하지 않는다). 현재 턴 이하인데 행이 없으면 데이터 손상이다.
        List<Turn> rows = turns.findBySeasonIdAndTurnNoBetween(season.getId(), turnNo - 1, turnNo);
        Turn turn = rows.stream().filter(t -> t.getTurnNo() == turnNo).findFirst()
                .orElseThrow(() -> new IllegalStateException("턴 스냅샷이 없다"));
        BigDecimal previousTotal = turnNo == 1 ? season.getInitialCash()
                : rows.stream().filter(t -> t.getTurnNo() == turnNo - 1).findFirst().map(Turn::getTotalValue)
                        .orElseThrow(() -> new IllegalStateException("직전 턴의 스냅샷이 없다"));
        // 그 턴 시작 시점의 목표 = 그 턴 이전의 마지막 변경의 after, 없으면 시작 포메이션(db/26 sr_turn_base 와 같다).
        Formation target = formationChanges.findLastBefore(season.getId(), turnNo)
                .map(FormationChange::getAfter).orElse(season.getStartFormation());
        // Look-ahead guard: exactly the path row of this turn, reached only after the turnNo <= current check above.
        TurnMacroRow macro = seasonPaths.findTurnMacro(season.getId(), turnNo)
                .orElseThrow(() -> new IllegalStateException("턴의 경로 행이 없다"));
        return TurnResultAssembler.assemble(season, turn, previousTotal, target,
                feeds.findBySeasonIdAndTurnNoOrderBySeq(season.getId(), turnNo), macro);
    }

    /**
     * Value line for turns 0..current: one season read, one turn-range read and one bounded benchmark-price read when
     * current is positive. Turn 0 has no stored row, so it is the initial capital with drawdown and return 0. Nothing
     * above the current turn is ever read.
     */
    @Transactional(readOnly = true)
    public TurnSummaryListDto summaries(Long seasonId) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        int current = season.getCurrentTurnNo();
        BigDecimal zero = MoneyMath.exactRate(BigDecimal.ZERO);
        List<BigDecimal> benchmarkValues = benchmarkLine.wonValues(season.getId(), current, season.getInitialCash(),
                season.getFeeRateKr(), season.getFeeRateUs(), season.getFxSpreadRate()).orElse(null);
        List<TurnSummaryDto> items = new ArrayList<>();
        items.add(new TurnSummaryDto(0, MoneyMath.exactWon(season.getInitialCash()), zero, zero,
                benchmarkValues == null ? null : benchmarkValues.get(0)));
        BigDecimal previous = season.getInitialCash();
        if (current >= 1) {
            for (Turn turn : turns.findBySeasonIdAndTurnNoBetween(season.getId(), 1, current)) {
                BigDecimal total = turn.getTotalValue();
                items.add(new TurnSummaryDto(turn.getTurnNo(), MoneyMath.exactWon(total),
                        MoneyMath.exactRate(turn.getDrawdown()),
                        MoneyMath.ratio(total.subtract(previous), previous),
                        benchmarkValues == null ? null : benchmarkValues.get(turn.getTurnNo())));
                previous = total;
            }
        }
        if (items.size() != current + 1) {
            throw new IllegalStateException("턴 스냅샷이 모자란다");
        }
        return new TurnSummaryListDto(List.copyOf(items));
    }
}
