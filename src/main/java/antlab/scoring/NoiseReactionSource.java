package antlab.scoring;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Where the noise-reaction share of a season comes from, as a ratio with 8 decimals. The default bean answers empty and the score's
 * noise item stays null; {@link SeasonNoiseSource} is the real bean. Empty also means the season is not finished or never traded.
 */
public interface NoiseReactionSource {

    Optional<BigDecimal> noiseShare(long seasonId);
}
