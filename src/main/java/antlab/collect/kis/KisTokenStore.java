package antlab.collect.kis;

import java.util.Optional;

/** Where a token survives between runs. Failures to read count as "no token"; failures to write are real errors. */
interface KisTokenStore {

    Optional<StoredToken> load();

    void save(StoredToken token);

    /** Forgets the token if it is still the stored one (it was rejected by KIS). */
    void discard(String rejectedAccessToken);
}
