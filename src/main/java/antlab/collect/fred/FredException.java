package antlab.collect.fred;

/**
 * A failed FRED call. The message is built from the endpoint name, HTTP status and error code only, so it never carries a
 * URL, query, body or key, and there is no cause chain (causes carry URIs).
 */
public class FredException extends RuntimeException {

    private final String code;

    public FredException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    /** Stable machine code: FRED_RATE_LIMIT, FRED_HTTP, FRED_REJECTED, FRED_FORBIDDEN_REQUEST, FRED_PARSE, REQUEST_CAP, PAGE_CAP, FRED_INTERRUPTED. */
    public String code() {
        return code;
    }
}
