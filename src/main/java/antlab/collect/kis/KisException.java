package antlab.collect.kis;

/**
 * A failed KIS call. The message is built by the client from the method, endpoint name, HTTP status and message code only,
 * so it never carries a header, URL query, body, key, secret or token, and there is no cause chain (causes carry URIs).
 */
public class KisException extends RuntimeException {

    private final String code;

    public KisException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    /** Stable machine code: KIS_RATE_LIMIT, KIS_HTTP, KIS_REJECTED, KIS_FORBIDDEN_REQUEST, KIS_PARSE, REQUEST_CAP, PAGE_CAP. */
    public String code() {
        return code;
    }
}
