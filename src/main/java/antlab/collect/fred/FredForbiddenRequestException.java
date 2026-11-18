package antlab.collect.fred;

/** A request outside the one-entry allow-list; it is refused before anything is sent. */
public class FredForbiddenRequestException extends FredException {

    public FredForbiddenRequestException(String message) {
        super("FRED_FORBIDDEN_REQUEST", message);
    }
}
