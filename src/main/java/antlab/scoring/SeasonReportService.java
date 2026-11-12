package antlab.scoring;

import antlab.asset.Asset;
import antlab.asset.AssetRepository;
import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.member.CurrentMember;
import antlab.scoring.SeasonReportSources.AssetKey;
import antlab.scoring.SeasonReportSources.ChangeFact;
import antlab.scoring.SeasonReportSources.LineupSpan;
import antlab.scoring.SeasonReportSources.SeasonFacts;
import antlab.scoring.SeasonReportSources.TurnFact;
import antlab.season.Formation;
import antlab.season.FormationChange;
import antlab.season.FormationChangeRepository;
import antlab.season.FormationCostQuery;
import antlab.season.Lineup;
import antlab.season.LineupRepository;
import antlab.season.Season;
import antlab.season.SeasonPathRepository;
import antlab.season.SeasonPathReturns;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import antlab.season.Turn;
import antlab.season.TurnRepository;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시즌 성적표 — 저장하지 않고 끝난 시즌의 원천을 읽어 계산한다(P0-38). 읽기 전용이며 잠금이 없다.
 * 원천 문장이 여럿이라 한 스냅샷이 필요하다 → {@code REPEATABLE_READ}(끝난 시즌에도 개발 수동 대사가 로그를 더할 수 있다).
 * 검사 순서: 회원 → 시즌(남의 시즌 포함, 404) → 상태(409) → 원천 조회·정합 검사(500) → 계산. 다른 회원의 시즌은 없는 것과 같게 404 다.
 * 문장 수는 보유 종목 수·턴 수와 무관하다(고정).
 */
@Service
public class SeasonReportService {

    private final CurrentMember currentMember;
    private final SeasonRepository seasons;
    private final TurnRepository turns;
    private final FormationChangeRepository changes;
    private final LineupRepository lineups;
    private final SeasonPathRepository paths;
    private final AssetRepository assets;
    private final SeasonReportQuery query;
    private final FormationCostQuery costs;

    public SeasonReportService(CurrentMember currentMember, SeasonRepository seasons, TurnRepository turns,
            FormationChangeRepository changes, LineupRepository lineups, SeasonPathRepository paths,
            AssetRepository assets, SeasonReportQuery query, FormationCostQuery costs) {
        this.currentMember = currentMember;
        this.seasons = seasons;
        this.turns = turns;
        this.changes = changes;
        this.lineups = lineups;
        this.paths = paths;
        this.assets = assets;
        this.query = query;
        this.costs = costs;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SeasonReportDto get(Long seasonId) {
        Long memberId = currentMember.existingId()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        Season season = seasons.findByIdAndMemberId(seasonId, memberId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "시즌을 찾을 수 없다"));
        if (season.getStatus() != SeasonStatus.FINISHED) {
            throw new DomainException(ErrorCode.SEASON_NOT_FINISHED, "끝난 시즌만 성적표를 볼 수 있다");
        }
        return SeasonReportCalculator.calculate(sources(season));
    }

    private SeasonReportSources sources(Season season) {
        long id = season.getId();
        int t = season.getCurrentTurnNo();
        // 보고 턴이 범위를 벗어난 시즌은 계산기의 정합 검사가 멈춘다. 그 전에 조회 입력이 음수가 되지 않게 한다.
        if (t < 1) {
            throw new IllegalStateException("성적표 원천이 모순이다: 보고 턴이 범위를 벗어났다");
        }
        Formation start = season.getStartFormation();
        SeasonFacts facts = new SeasonFacts(season.getStatus(), season.getPathLength(), t, season.getInitialCash(),
                season.getRiskLimit(), season.getFeeRateKr(), season.getFeeRateUs(), season.getFxSpreadRate(),
                start.goalkeeper(), start.defender(), start.midfielder(), start.forward());
        List<TurnFact> turnFacts = turns.findBySeasonIdAndTurnNoBetween(id, 1, t).stream()
                .map(r -> new TurnFact(r.getTurnNo(), r.getTotalValue(), r.getDrawdown(), r.getGoalkeeperValue(),
                        r.getDefenderValue(), r.getMidfielderValue(), r.getForwardValue()))
                .toList();
        List<ChangeFact> changeFacts = changes.findAllBySeasonId(id).stream().map(SeasonReportService::toFact).toList();
        List<Lineup> lineupRows = lineups.findAllBySeasonId(id);
        query.requireConsistent(id, t);
        Map<Long, List<BigDecimal>> returnsByAsset = SeasonPathReturns.byAsset(paths.findTurnFactors(id, t), t);

        Set<Long> assetIds = new HashSet<>(returnsByAsset.keySet());
        lineupRows.forEach(l -> assetIds.add(l.getAssetId()));
        Map<Long, AssetKey> keys = new LinkedHashMap<>();
        for (Asset a : assets.findAllById(assetIds)) {
            keys.put(a.getId(), new AssetKey(a.getMarket().name(), a.getTicker()));
        }
        Map<AssetKey, List<BigDecimal>> poolReturns = new LinkedHashMap<>();
        returnsByAsset.forEach((assetId, list) -> poolReturns.put(key(keys, assetId), list));
        List<LineupSpan> lineup = lineupRows.stream().map(l -> new LineupSpan(key(keys, l.getAssetId()),
                l.getDeclaredPosition(), l.getStartTurn(), l.getEndTurn())).toList();

        return new SeasonReportSources(facts, turnFacts, changeFacts, query.orders(id, t), query.holdings(id, t), lineup,
                poolReturns, query.benchmarkPrices(id, t), costs.bySeason(id), query.recon(id, season.getPathLength()));
    }

    private static AssetKey key(Map<Long, AssetKey> keys, Long assetId) {
        AssetKey key = keys.get(assetId);
        if (key == null) {
            throw new IllegalStateException("성적표 원천이 모순이다: 종목을 찾을 수 없다");
        }
        return key;
    }

    private static ChangeFact toFact(FormationChange c) {
        Formation after = c.getAfter();
        return new ChangeFact(c.getId(), c.getTurnNo(), after.goalkeeper(), after.defender(), after.midfielder(), after.forward());
    }
}
