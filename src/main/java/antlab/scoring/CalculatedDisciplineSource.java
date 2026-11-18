package antlab.scoring;

import antlab.scoring.DisciplineFacts.ChangeFact;
import antlab.scoring.DisciplineFacts.TurnValue;
import antlab.season.FormationChangeRepository;
import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.TurnRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The discipline ratios of a finished season, from the same facts and the same calculator as the season analysis: the
 * panic-sell share and the chase-buy share of the traded value. A share is null when its denominator is zero (no sells or
 * no buys), and the item then uses the other share alone. Read-only; call it inside the score reader's snapshot.
 */
@Component
public class CalculatedDisciplineSource implements DisciplineSource {

    private final SeasonRepository seasons;
    private final TurnRepository turns;
    private final FormationChangeRepository changes;
    private final DisciplineQuery orders;

    public CalculatedDisciplineSource(SeasonRepository seasons, TurnRepository turns, FormationChangeRepository changes,
            DisciplineQuery orders) {
        this.seasons = seasons;
        this.turns = turns;
        this.changes = changes;
        this.orders = orders;
    }

    @Override
    public Optional<DisciplineRatios> of(long seasonId) {
        Season season = seasons.findById(seasonId).orElseThrow(() -> new IllegalStateException("score.season_missing"));
        int reportTurn = season.getCurrentTurnNo();
        List<TurnValue> values = turns.findBySeasonIdAndTurnNoBetween(seasonId, 1, reportTurn).stream()
                .map(t -> new TurnValue(t.getTurnNo(), t.getTotalValue())).toList();
        List<ChangeFact> facts = changes.findAllBySeasonId(seasonId).stream()
                .map(c -> new ChangeFact(c.getId(), c.getTurnNo(), c.getBefore().forward(), c.getAfter().forward())).toList();
        DisciplineCalculator.Result result = DisciplineCalculator.calculate(new DisciplineFacts(reportTurn,
                season.getInitialCash(), values, facts, orders.orders(seasonId, reportTurn)));
        if (result.panicSellShare() == null && result.chaseBuyShare() == null) {
            return Optional.empty();
        }
        return Optional.of(new DisciplineRatios(result.panicSellShare(), result.chaseBuyShare()));
    }
}
