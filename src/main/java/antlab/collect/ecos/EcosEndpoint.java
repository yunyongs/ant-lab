package antlab.collect.ecos;

import java.net.URI;

/**
 * The whole request surface of the ECOS collector: one GET of statistic values. Nothing else can be built from this type,
 * and {@link EcosAllowlistInterceptor} refuses anything that does not match it exactly. The key is a path segment (ECOS
 * has no other way to send it), so no URL is ever logged or put into a message.
 */
public final class EcosEndpoint {

    /** The one host; it is not configurable. */
    public static final URI BASE_URI = URI.create("https://ecos.bok.or.kr");
    /** Path segments in order; the key is the third. {@code json} and {@code kr} are the answer format and language. */
    static final String SEGMENT_API = "api";
    static final String SEGMENT_SEARCH = "StatisticSearch";
    static final String FORMAT = "json";
    static final String LANGUAGE = "kr";

    private EcosEndpoint() {
    }
}
