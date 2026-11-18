package antlab.collect.ecos;

/**
 * A failed ECOS call. The message is built from the endpoint name, HTTP status and the ECOS result code only, so it never
 * carries a URL (the key is part of the URL path), body or key, and there is no cause chain (causes carry URIs).
 */
public class EcosException extends RuntimeException {

    private final String code;

    public EcosException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    /**
     * Stable machine code: ECOS_RATE_LIMIT, ECOS_AUTH, ECOS_HTTP, ECOS_REJECTED, ECOS_FORBIDDEN_REQUEST, ECOS_PARSE,
     * ECOS_EMPTY, REQUEST_CAP, PAGE_CAP, ECOS_INTERRUPTED.
     */
    public String code() {
        return code;
    }
}
