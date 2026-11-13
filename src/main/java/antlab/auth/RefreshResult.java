package antlab.auth;

/**
 * The outcome of a refresh, returned instead of thrown: a reuse revoke must commit, and a domain exception would roll
 * it back. The caller answers 401 after the transaction has committed.
 */
public sealed interface RefreshResult {

    record Issued(IssuedTokens tokens) implements RefreshResult {
    }

    record Rejected() implements RefreshResult {
    }

    static RefreshResult rejected() {
        return new Rejected();
    }
}
