package antlab.collect.series;

import java.util.List;

/** Rules C7-C12 of erd 10.5 for series_observation: each query returns no row on consistent data. */
public final class MacroRules {

    private static final String NEXT_VINTAGE = """
            FROM series_observation a JOIN series_observation b
              ON b.series_id = a.series_id AND b.ref_date = a.ref_date AND b.vintage_no = a.vintage_no + 1
            JOIN series s ON s.id = a.series_id
            """;

    /** C7: vintage_no values of one (series, date) are 1..n without gaps. */
    public static final String C7 = """
            SELECT series_id, ref_date FROM series_observation
            GROUP BY series_id, ref_date
            HAVING min(vintage_no) <> 1 OR max(vintage_no) <> count(*)
            """;

    /** C8: observed_at never decreases with vintage_no, and strictly increases for a series that is not read with its vintage history. */
    public static final String C8 = "SELECT a.id " + NEXT_VINTAGE
            + " WHERE b.observed_at < a.observed_at OR (b.observed_at = a.observed_at AND NOT (s.source = 'FRED' AND s.revisable))";

    /** C9: consecutive vintages differ in value. */
    public static final String C9 = "SELECT a.id " + NEXT_VINTAGE + " WHERE b.value = a.value";

    /**
     * C10: the date columns follow the source. A FRED row has no available_on, and only a revisable FRED series has
     * realtime_start: it increases strictly with vintage_no and is NULL at most at vintage 1 (the pre-vintage rule). An ECOS
     * row has no realtime_start.
     */
    public static final String C10 = """
            SELECT o.id FROM series_observation o JOIN series s ON s.id = o.series_id
            WHERE (s.source = 'FRED' AND o.available_on IS NOT NULL)
               OR (s.source = 'FRED' AND NOT s.revisable AND o.realtime_start IS NOT NULL)
               OR (s.source = 'FRED' AND s.revisable AND o.realtime_start IS NULL AND o.vintage_no > 1)
               OR (s.source = 'ECOS' AND o.realtime_start IS NOT NULL)
            UNION ALL
            SELECT a.id""" + " " + NEXT_VINTAGE + " WHERE s.source = 'FRED' AND s.revisable AND b.realtime_start <= a.realtime_start";

    /** C11: observed_at is the started_at of the run that fetched the row. */
    public static final String C11 = """
            SELECT o.id FROM series_observation o JOIN job_run r ON r.id = o.job_run_id
            WHERE o.observed_at <> r.started_at
            """;

    /** C12: a row of a FRED series belongs to a FRED_SERIES run and a row of an ECOS series to an ECOS_SERIES run. */
    public static final String C12 = """
            SELECT o.id FROM series_observation o JOIN series s ON s.id = o.series_id JOIN job_run r ON r.id = o.job_run_id
            WHERE r.job_name <> s.source || '_SERIES'
            """;

    public static final List<String> ALL = List.of(C7, C8, C9, C10, C11, C12);

    private MacroRules() {
    }
}
