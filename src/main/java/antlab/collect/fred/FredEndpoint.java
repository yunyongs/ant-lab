package antlab.collect.fred;

import java.net.URI;
import java.util.Set;

/**
 * The whole request surface of the FRED collector: one GET of observations. Nothing else can be built from this type,
 * and {@link FredAllowlistInterceptor} refuses anything that does not match it exactly. The key travels in the query
 * (FRED has no header option), so no URL is ever logged or put into a message.
 */
public final class FredEndpoint {

    /** The one host; it is not configurable. */
    public static final URI BASE_URI = URI.create("https://api.stlouisfed.org");
    public static final String OBSERVATIONS_PATH = "/fred/series/observations";
    /** The vintage window that returns every vintage of a value (ALFRED). */
    public static final String ALL_VINTAGES_START = "1776-07-04";
    public static final String ALL_VINTAGES_END = "9999-12-31";

    static final Set<String> REQUIRED_QUERY = Set.of("series_id", "api_key", "file_type", "limit", "offset");
    static final Set<String> OPTIONAL_QUERY = Set.of("observation_start", "realtime_start", "realtime_end");

    private FredEndpoint() {
    }
}
