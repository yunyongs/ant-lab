package antlab.scoring;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.common.money.MoneyMath;
import antlab.member.CurrentMember;
import antlab.scoring.BaselineSimulator.FeeRates;
import antlab.scoring.DisciplineFacts.ChangeFact;
import antlab.scoring.DisciplineFacts.TurnValue;
import antlab.scoring.RepresentativeTable.Entry;
import antlab.scoring.RepresentativeTable.Resolution;
import antlab.scoring.SeasonAnalysisDto.BaselineDto;
import antlab.scoring.SeasonAnalysisDto.DecompositionDto;
import antlab.scoring.SeasonAnalysisDto.DisciplineDto;
import antlab.scoring.SeasonAnalysisDto.LegDto;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.season.Formation;
import antlab.season.FormationChange;
import antlab.season.FormationChangeRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import antlab.season.Turn;
import antlab.season.TurnRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Season analysis (MVP-17): baselines, three-way split and trading discipline of a finished season, computed on read and never stored.
 * Read-only and lock-free; several source statements need one snapshot, so {@code REPEATABLE_READ} (a finished season can still get a
 * manual reconciliation log). Check order is that of {@link SeasonReportService}: member, season (another member's season is a 404),
 * status (409), source consistency (500), then the pure calculators. The number of statements does not depend on holdings or turns.
 */
@Service
public class SeasonAnalysisService {

    /** Version of the analysis rules; it changes when a rule of the baselines or of the discipline item changes. */
    public static final String ANALYSIS_VERSION = "mvp17-v1";

    private static final String INCONSISTENT = "분석 원천이 모순이다: ";

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final TurnRepository turns;
    private final FormationChangeRepository changes;
    private final SeasonReportQuery consistency;
    private final SeasonAnalysisQuery prices;
    private final DisciplineQuery disciplineOrders;
    private final RepresentativeTable table;

    public SeasonAnalysisService(CurrentMember currentMember, SeasonRepository seasons, TurnRepository turns,
            FormationChangeRepository changes, SeasonReportQuery consistency, SeasonAnalysisQuery prices,
            DisciplineQuery disciplineOrders, RepresentativeTable table) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.turns = turns;
        this.changes = changes;
        this.consistency = consistency;
        this.prices = prices;
        this.disciplineOrders = disciplineOrders;
        this.table = table;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SeasonAnalysisDto get(Long seasonId) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        if (season.getStatus() != SeasonStatus.FINISHED) {
            throw new DomainException(ErrorCode.SEASON_NOT_FINISHED, "끝난 시즌만 분석을 볼 수 있다");
        }
        long id = season.getId();
        int reportTurn = season.getCurrentTurnNo();
        // A finished season stops at the last turn of its path, as the report requires (SeasonReportCalculator.validate)
        if (reportTurn < 1 || reportTurn != season.getPathLength()) {
            throw new IllegalStateException(INCONSISTENT + "보고 턴이 범위를 벗어났다");
        }
        BigDecimal initialCash = season.getInitialCash();
        List<Turn> turnRows = turns.findBySeasonIdAndTurnNoBetween(id, 1, reportTurn);
        if (turnRows.size() != reportTurn || turnRows.get(reportTurn - 1).getTurnNo() != reportTurn) {
            throw new IllegalStateException(INCONSISTENT + "턴 스냅샷이 첫 턴부터 보고 턴까지 이어지지 않는다");
        }
        List<FormationChange> changeRows = changes.findAllBySeasonId(id);
        if (changeRows.stream().anyMatch(c -> c.getTurnNo() >= reportTurn)) {
            throw new IllegalStateException(INCONSISTENT + "끝난 시즌에 마지막 턴의 주문이나 전술 변경이 남아 있다");
        }
        requireChain(season.getStartFormation(), changeRows);
        consistency.requireConsistent(id, reportTurn);
        if (prices.ordersFromReportTurn(id, reportTurn) != 0) {
            throw new IllegalStateException(INCONSISTENT + "끝난 시즌에 마지막 턴의 주문이나 전술 변경이 남아 있다");
        }

        BigDecimal userFinal = turnRows.get(reportTurn - 1).getTotalValue();
        Map<AssetKey, List<BigDecimal>> series = prices.prices(id, reportTurn, candidates());
        Resolution resolution = table.resolve(series.keySet());
        Formation start = season.getStartFormation();
        FeeRates fees = new FeeRates(season.getFeeRateKr(), season.getFeeRateUs(), season.getFxSpreadRate());

        List<BaselineResult> results = new ArrayList<>();
        List<BaselineDto> baselines = new ArrayList<>();
        for (Entry entry : resolution.entries()) {
            BaselineResult result = entry.available()
                    ? BaselineSimulator.simulate(entry.spec(), schedule(entry.key(), start, changeRows, reportTurn), series,
                            initialCash, fees)
                    : null;
            results.add(result);
            baselines.add(baselineDto(entry, result, userFinal, initialCash));
        }
        BaselineResult market = results.get(0);
        BaselineResult follow = results.get(2);
        BaselineResult fixed = results.get(3);
        DecompositionDto decomposition = follow == null || fixed == null ? null
                : decompositionDto(Decomposition.of(userFinal, follow.finalValue(), fixed.finalValue(), market.finalValue(),
                        initialCash));

        List<TurnValue> teamValues = turnRows.stream().map(t -> new TurnValue(t.getTurnNo(), t.getTotalValue())).toList();
        List<ChangeFact> facts = changeRows.stream()
                .map(c -> new ChangeFact(c.getId(), c.getTurnNo(), c.getBefore().forward(), c.getAfter().forward())).toList();
        DisciplineCalculator.Result discipline = DisciplineCalculator.calculate(new DisciplineFacts(reportTurn, initialCash,
                teamValues, facts, disciplineOrders.orders(id, reportTurn)));
        return new SeasonAnalysisDto(ANALYSIS_VERSION, resolution.version(), reportTurn, baselines, decomposition,
                disciplineDto(discipline));
    }

    /** The following baseline starts from the start formation, so each change must start from the one before it (by id). */
    private static void requireChain(Formation start, List<FormationChange> changeRows) {
        Formation previous = start;
        for (FormationChange change : changeRows.stream().sorted(java.util.Comparator.comparingLong(FormationChange::getId)).toList()) {
            if (!change.getBefore().equals(previous)) {
                throw new IllegalStateException(INCONSISTENT + "전술 변경이 시작 포메이션부터 이어지지 않는다");
            }
            previous = change.getAfter();
        }
    }

    private List<AssetKey> candidates() {
        List<AssetKey> out = new ArrayList<>();
        out.add(table.domesticEquity());
        out.add(table.usEquity());
        out.addAll(table.bondCandidates());
        out.add(table.gold());
        out.add(table.growth());
        return out;
    }

    /** MARKET and 60/40 have fixed targets; the fixed formation baseline keeps the start formation; the following one switches with each change. */
    private static TargetSchedule schedule(BaselineKey key, Formation start, List<FormationChange> changeRows, int reportTurn) {
        return switch (key) {
            case MARKET -> new TargetSchedule(BaselineRules.MARKET_TARGET, List.of());
            case SIXTY_FORTY -> new TargetSchedule(BaselineRules.SIXTY_FORTY_TARGET, List.of());
            case FORMATION_FIXED -> new TargetSchedule(target(start), List.of());
            case FORMATION_FOLLOW -> TargetSchedule.fromChanges(target(start), changeRows.stream()
                    .filter(c -> c.getTurnNo() >= 1 && c.getTurnNo() < reportTurn)
                    .map(c -> new TargetSchedule.Change(c.getTurnNo(), c.getId(), target(c.getAfter()))).toList());
        };
    }

    private static List<Integer> target(Formation formation) {
        return List.of(formation.goalkeeper(), formation.defender(), formation.midfielder(), formation.forward());
    }

    private static BaselineDto baselineDto(Entry entry, BaselineResult result, BigDecimal userFinal, BigDecimal initialCash) {
        if (result == null) {
            return new BaselineDto(entry.key().name(), false, entry.reason(), List.of(), null, null, null, null, null, null,
                    null, null, null, false, null);
        }
        BigDecimal finalValue = MoneyMath.exactWon(result.finalValue());
        BigDecimal excess = MoneyMath.exactWon(userFinal.subtract(finalValue));
        List<LegDto> legs = new ArrayList<>();
        for (BaselineSpec.Slot slot : entry.spec().slots()) {
            for (BaselineSpec.Leg leg : slot.legs()) {
                legs.add(new LegDto(leg.asset().market() + ":" + leg.asset().ticker(), slot.name(), leg.sharePercent()));
            }
        }
        return new BaselineDto(entry.key().name(), true, null, legs, finalValue,
                MoneyMath.ratio(finalValue.subtract(initialCash), initialCash), excess, MoneyMath.ratio(excess, initialCash),
                MoneyMath.exactWon(result.totalFee()), result.rebalanceCount(), result.bandNoFillCount(),
                result.forcedChangeCount(), MoneyMath.exactWon(result.forcedChangeFee()), entry.bondLegSubstituted(),
                result.turnValues().stream().map(MoneyMath::exactWon).toList());
    }

    private static DecompositionDto decompositionDto(Decomposition d) {
        return new DecompositionDto(d.selectionWon(), d.tacticWon(), d.structureWon(), d.totalWon(), d.selectionReturn(),
                d.tacticReturn(), d.structureReturn(), d.totalReturn());
    }

    private static DisciplineDto disciplineDto(DisciplineCalculator.Result r) {
        BigDecimal hundred = BigDecimal.valueOf(100);
        return new DisciplineDto(MoneyMath.ratio(BigDecimal.valueOf(-DisciplineRules.DROP_PERCENT), hundred),
                MoneyMath.ratio(BigDecimal.valueOf(DisciplineRules.RISE_PERCENT), hundred), r.sellNotional(),
                r.panicSellNotional(), r.panicSellShare(), r.buyNotional(), r.chaseBuyNotional(), r.chaseBuyShare(),
                r.tradeNotional(), r.undisciplinedShare(), r.formationChanges(), r.panicFormationChanges());
    }
}
