package antlab.scoring;

import antlab.asset.Position;
import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * The eight judgement columns of {@code asset_position} for one asset on one base date, plus the reason and the up and down month counts.
 * The eight columns are all null exactly when {@code reason} is set ({@code ck_asset_position_judgment_all_or_none}).
 * The months are written as the first day of the month at the writer's edge.
 */
public record BaseDateJudgement(PositionMeter.Reason reason, YearMonth windowStart, YearMonth windowEnd, Integer monthsUsed,
        BigDecimal upParticipation, BigDecimal downParticipation, BigDecimal volatility, BigDecimal maxDrawdown,
        Position systemPosition, int nUp, int nDown) {

    public BaseDateJudgement {
        boolean allNull = windowStart == null && windowEnd == null && monthsUsed == null && upParticipation == null
                && downParticipation == null && volatility == null && maxDrawdown == null && systemPosition == null;
        boolean allSet = windowStart != null && windowEnd != null && monthsUsed != null && upParticipation != null
                && downParticipation != null && volatility != null && maxDrawdown != null && systemPosition != null;
        if (reason == null ? !allSet : !allNull) {
            throw new IllegalArgumentException("the eight judgement columns are all set or all null, matching the reason");
        }
    }

    public boolean judgeable() {
        return reason == null;
    }

    static BaseDateJudgement unjudged(PositionMeter.Reason reason, int nUp, int nDown) {
        return new BaseDateJudgement(reason, null, null, null, null, null, null, null, null, nUp, nDown);
    }
}
