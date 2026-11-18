package antlab.scoring;

import antlab.projection.ProjectionInput;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything the score of one finished season is computed from, read in one snapshot: the report sources, the discipline
 * ratios (null when nothing measures them), the season-start team as shares of its total value and the start team's value
 * at the last turn if it had been held. No path, month or trade of the season is a field here; the engine gets seed and
 * base date only.
 *
 * @param seasonId       the finished season
 * @param memberId       its owner
 * @param seed           the season seed (sent to the engine as a decimal string; never in a response)
 * @param baseDate       the season's base date
 * @param pathLength     the season length in turns
 * @param notStarted     a human season abandoned before turn 3 (left out of the rating)
 * @param sources        the report sources, as the season report reads them
 * @param discipline     panic-sell and chase-buy ratios, null when absent
 * @param noiseShare     share of traded value that followed the noise, null when the season never traded or it could not be read
 * @param startTeam      the team after the turn-0 team building, as shares of its total value
 * @param startEndValue  cash plus the start quantities at the last turn's prices, in won
 */
public record ScoreBasis(long seasonId, long memberId, long seed, LocalDate baseDate, int pathLength, boolean notStarted,
        SeasonReportSources sources, DisciplineRatios discipline, BigDecimal noiseShare, ProjectionInput startTeam, BigDecimal startEndValue) {
}
