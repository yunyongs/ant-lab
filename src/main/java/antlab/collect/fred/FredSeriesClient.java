package antlab.collect.fred;

import java.time.LocalDate;
import java.util.List;

/**
 * Observation lookup only: the implementation can send exactly one kind of request. One instance is one run: it owns the
 * request counter and the per-run cap.
 */
public interface FredSeriesClient {

    /**
     * All pages of one series from {@code observationStart}. With {@code allVintages} every vintage of every value is
     * returned (ALFRED); otherwise only the values as they stand today. Rows come back in the order FRED sent them.
     */
    List<FredObservation> observations(String sourceSeriesId, LocalDate observationStart, boolean allVintages);

    /** Requests sent by this instance. */
    int requestsMade();
}
