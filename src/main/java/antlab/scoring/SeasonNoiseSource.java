package antlab.scoring;

import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real {@link NoiseReactionSource}: the same calculation as the season analysis, for the score. Only a finished season has a
 * value; it is empty for another season, an unknown id or a season that never traded. It joins the caller's transaction.
 */
@Component
public class SeasonNoiseSource implements NoiseReactionSource {

    private final SeasonRepository seasons;
    private final BehaviourQuery behaviour;
    private final SeasonSignalReader signals;

    public SeasonNoiseSource(SeasonRepository seasons, BehaviourQuery behaviour, SeasonSignalReader signals) {
        this.seasons = seasons;
        this.behaviour = behaviour;
        this.signals = signals;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> noiseShare(long seasonId) {
        Optional<Season> found = seasons.findById(seasonId);
        if (found.isEmpty() || found.get().getStatus() != SeasonStatus.FINISHED) {
            return Optional.empty();
        }
        int reportTurn = found.get().getCurrentTurnNo();
        List<BehaviourOrder> judged = behaviour.orders(seasonId, reportTurn).stream().filter(o -> o.turnNo() >= 1).toList();
        return Optional.ofNullable(NoiseReactionCalculator.calculate(reportTurn, signals.read(seasonId, reportTurn), judged)
                .noiseShare());
    }
}
