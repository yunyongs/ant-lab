package antlab.collect.kis;

/** A request outside the three-entry allow-list; it is refused before anything is sent. */
public class KisForbiddenRequestException extends KisException {

    public KisForbiddenRequestException(String message) {
        super("KIS_FORBIDDEN_REQUEST", message);
    }
}
