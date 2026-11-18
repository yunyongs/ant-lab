package antlab.collect.ecos;

import java.time.LocalDate;
import java.util.List;

/**
 * Statistic lookup only: the implementation can send exactly one kind of request. One instance is one run: it owns the
 * request counter and the per-run cap.
 */
public interface EcosSeriesClient {

    /**
     * All pages of one item of one statistic table from {@code from} to {@code to} (both inclusive, at the cycle's
     * resolution). Rows come back in the order ECOS sent them. An answer with no data is an empty list.
     */
    List<EcosObservation> observations(String statCode, String itemCode, EcosCycle cycle, LocalDate from, LocalDate to);

    /** Requests sent by this instance. */
    int requestsMade();
}
