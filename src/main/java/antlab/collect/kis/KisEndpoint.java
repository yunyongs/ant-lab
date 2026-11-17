package antlab.collect.kis;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * The whole request surface of the collector: one token POST and two quote GETs. Nothing else can be built from this
 * type, and {@link KisAllowlistInterceptor} refuses anything that does not match one entry exactly.
 */
public enum KisEndpoint {
    TOKEN("POST", "/oauth2/tokenP", null, Set.of()),
    DOMESTIC_DAILY("GET", "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice", "FHKST03010100",
            Set.of("FID_COND_MRKT_DIV_CODE", "FID_INPUT_ISCD", "FID_INPUT_DATE_1", "FID_INPUT_DATE_2", "FID_PERIOD_DIV_CODE",
                    "FID_ORG_ADJ_PRC")),
    OVERSEAS_DAILY("GET", "/uapi/overseas-price/v1/quotations/dailyprice", "HHDFS76240000",
            Set.of("AUTH", "EXCD", "SYMB", "GUBN", "BYMD", "MODP"));

    /** The real-money domain; there is no mock domain for quotes and the host is not configurable. */
    public static final URI BASE_URI = URI.create("https://openapi.koreainvestment.com:9443");

    private final String method;
    private final String path;
    private final String trId;
    private final Set<String> queryNames;

    KisEndpoint(String method, String path, String trId, Set<String> queryNames) {
        this.method = method;
        this.path = path;
        this.trId = trId;
        this.queryNames = queryNames;
    }

    /** The exact set of query parameter names the request carries; empty for the token request. */
    public Set<String> queryNames() {
        return queryNames;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    /** Transaction id header value, or null for the token request. */
    public String trId() {
        return trId;
    }

    static Optional<KisEndpoint> find(String method, String path) {
        for (KisEndpoint endpoint : values()) {
            if (endpoint.method.equals(method) && endpoint.path.equals(path)) {
                return Optional.of(endpoint);
            }
        }
        return Optional.empty();
    }
}
