package antlab.scoring;

import java.math.BigDecimal;
import java.util.List;

/**
 * The benchmark the season score compares against: the benchmark portfolio value in won at turns 0..T. The score
 * calculator depends on this port only, so a better market series can replace the Phase 0 proxy without touching it.
 */
public interface MarketBaselineSeries {

    /** Values at turns 0..T (T + 1 entries); entry 0 is the initial cash after the benchmark purchase fees. */
    List<BigDecimal> values(SeasonReportSources src);
}
