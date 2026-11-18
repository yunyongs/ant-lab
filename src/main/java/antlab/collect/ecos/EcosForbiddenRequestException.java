package antlab.collect.ecos;

/** A request outside the one-entry allow-list; it is refused before anything is sent. */
public class EcosForbiddenRequestException extends EcosException {

    public EcosForbiddenRequestException(String message) {
        super("ECOS_FORBIDDEN_REQUEST", message);
    }
}
