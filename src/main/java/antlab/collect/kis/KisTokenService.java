package antlab.collect.kis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One token for many calls: memory first, then the shared file, and only then a new issuance. A token with at most an hour
 * left is replaced. KIS allows one issuance per minute, so issuances (also a retry after a failure) are at least 65 s apart.
 */
final class KisTokenService {

    static final long REUSE_SECONDS = 3600;
    static final Duration ISSUANCE_SPACING = Duration.ofSeconds(65);
    private static final int ISSUANCE_ATTEMPTS = 2;

    private final Clock clock;
    private final Sleeper sleeper;
    private final KisTokenStore store;
    private final SecretMasker masker;
    private StoredToken current;
    private Instant lastIssuanceAt;

    /** {@code store} is null when no cache directory is configured: the token then lives in memory only. */
    KisTokenService(Clock clock, Sleeper sleeper, KisTokenStore store, SecretMasker masker) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.store = store;
        this.masker = masker;
    }

    synchronized String accessToken(Supplier<StoredToken> issuer) {
        if (usable(current)) {
            return current.accessToken();
        }
        Optional<StoredToken> shared = store == null ? Optional.empty() : store.load().filter(this::usable);
        if (shared.isPresent()) {
            current = shared.get();
            masker.register(current.accessToken());
            return current.accessToken();
        }
        current = issue(issuer);
        return current.accessToken();
    }

    /** Called when KIS says the token is expired or invalid; the next {@link #accessToken} issues a new one. */
    synchronized void reject(String accessToken) {
        if (current != null && current.accessToken().equals(accessToken)) {
            current = null;
        }
        if (store != null) {
            store.discard(accessToken);
        }
    }

    private boolean usable(StoredToken token) {
        return token != null && token.expiresAtEpochSecond() - clock.instant().getEpochSecond() > REUSE_SECONDS;
    }

    private StoredToken issue(Supplier<StoredToken> issuer) {
        KisException last = null;
        for (int attempt = 0; attempt < ISSUANCE_ATTEMPTS; attempt++) {
            waitForSpacing();
            lastIssuanceAt = clock.instant();
            try {
                StoredToken issued = issuer.get();
                masker.register(issued.accessToken());
                if (store != null) {
                    store.save(issued);
                }
                return issued;
            } catch (KisException e) {
                last = e;
            }
        }
        throw last;
    }

    private void waitForSpacing() {
        if (lastIssuanceAt == null) {
            return;
        }
        Instant earliest = lastIssuanceAt.plus(ISSUANCE_SPACING);
        Instant now = clock.instant();
        if (now.isBefore(earliest)) {
            sleeper.sleep(Duration.between(now, earliest));
        }
    }
}
