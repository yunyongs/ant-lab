package antlab.scoring;

import antlab.common.money.MoneyMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The benchmark the season score compares against: the benchmark portfolio value in won at turns 0..T. The score
 * calculator depends on this port only, so a better market series can replace the Phase 0 proxy without touching it.
 */
public interface MarketBaselineSeries {

    /** Values at turns 0..T (T + 1 entries); entry 0 is the initial cash after the benchmark purchase fees. */
    List<BigDecimal> values(SeasonReportSources src);

    /**
     * Monthly returns for turns 1..T on the same base as the team's: the first return is measured against the initial
     * cash, not against {@code values[0]}, so the benchmark's purchase fees count as they do for the team. Later returns
     * are value(t) / value(t - 1) - 1. Unrounded (working scale).
     */
    default List<BigDecimal> returns(SeasonReportSources src) {
        List<BigDecimal> values = values(src);
        List<BigDecimal> out = new ArrayList<>(values.size() - 1);
        BigDecimal previous = src.season().initialCash();
        for (int t = 1; t < values.size(); t++) {
            out.add(MoneyMath.scoreDivide(values.get(t).subtract(previous), previous));
            previous = values.get(t);
        }
        return out;
    }
}
