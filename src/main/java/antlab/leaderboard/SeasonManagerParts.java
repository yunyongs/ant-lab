package antlab.leaderboard;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One {@code season_manager_parts} row as written: either the engine's parts ({@code ranked} set, {@code exclusion} null)
 * or the reason there are none ({@code exclusion} set, {@code ranked} null). The process part, its score version and the
 * final multiple come from Java tables and are always present.
 *
 * @param finalMultiple final value / initial cash, 8 places (display only, never ranks)
 */
public record SeasonManagerParts(long seasonId, long memberId, String referenceVersion, ManagerPartsExclusion exclusion,
        Ranked ranked, BigDecimal partProcess, String processScoreVersion, BigDecimal finalMultiple) {

    /** What the engine answered, stored as sent (components at 12 places, percentiles and parts at 8). */
    public record Ranked(String referenceKey, int referenceSize, String botVersion, String engineVersion,
            BigDecimal excessCagr, BigDecimal sharpeDiff, BigDecimal sortinoDiff, BigDecimal calmarDiff, BigDecimal mdd,
            BigDecimal benchMdd, BigDecimal pctExcessCagr, BigDecimal pctSharpeDiff, BigDecimal pctSortinoDiff,
            BigDecimal pctCalmarDiff, BigDecimal pctRiskItem, BigDecimal pctMdd, BigDecimal partReturn,
            BigDecimal partRiskAdjusted, BigDecimal partRisk) {
    }

    public SeasonManagerParts {
        Objects.requireNonNull(referenceVersion, "referenceVersion");
        Objects.requireNonNull(partProcess, "partProcess");
        Objects.requireNonNull(processScoreVersion, "processScoreVersion");
        Objects.requireNonNull(finalMultiple, "finalMultiple");
        if ((exclusion == null) == (ranked == null)) {
            throw new IllegalArgumentException("a parts row is either ranked or excluded");
        }
    }
}
