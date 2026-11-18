package antlab.scoring;

import antlab.season.Season;
import antlab.season.SeasonRepository;
import antlab.season.SeasonStatus;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The real {@link NoiseReactionSource}: the same calculation as the season analysis, for the score. Only a finished season has a
 * value; it is empty for another season, an unknown id or a season that never traded. It reads inside the caller's transaction
 * and opens none of its own.
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
    public Optional<BigDecimal> noiseShare(long seasonId) {
        Optional<Season> found = seasons.findById(seasonId);
        if (found.isEmpty() || found.get().getStatus() != SeasonStatus.FINISHED) {
            return Optional.empty();
        }
        int reportTurn = found.get().getCurrentTurnNo();
        return Optional.ofNullable(NoiseReactionCalculator.ofSeason(reportTurn, signals.read(seasonId, reportTurn),
                behaviour.orders(seasonId, reportTurn)).noiseShare());
    }
}
