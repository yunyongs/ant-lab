package antlab.collect.kis;

import antlab.observability.CollectMetrics;
import java.net.URI;
import java.time.Clock;
import org.springframework.web.client.RestClient;

/**
 * Creates one {@link KisQuoteClient} per run. What the clients share: the token (memory and the optional shared file) and
 * the rate limiter, so concurrent runs together stay within the per-second limit. What each client owns: its request
 * counter and cap.
 */
public final class KisQuoteClientFactory {

    private final RestClient.Builder base;
    private final URI baseUri;
    private final KisProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;
    private final SecretMasker masker = new SecretMasker();
    private final KisTokenService tokens;
    private final MinIntervalRateLimiter limiter;
    private final CollectMetrics metrics;

    KisQuoteClientFactory(RestClient.Builder base, URI baseUri, KisProperties properties, Clock clock, Sleeper sleeper) {
        this(base, baseUri, properties, clock, sleeper, CollectMetrics.none());
    }

    KisQuoteClientFactory(RestClient.Builder base, URI baseUri, KisProperties properties, Clock clock, Sleeper sleeper,
            CollectMetrics metrics) {
        properties.requireKeys();
        this.base = base;
        this.baseUri = baseUri;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
        this.metrics = metrics;
        masker.register(properties.appKey());
        masker.register(properties.appSecret());
        KisTokenStore store = properties.tokenCachePath() == null ? null : new FileKisTokenStore(properties.tokenCachePath());
        this.tokens = new KisTokenService(clock, sleeper, store, masker);
        this.limiter = new MinIntervalRateLimiter(clock, sleeper, properties.rps());
    }

    public KisQuoteClient newClient() {
        KisRequestGate gate = new KisRequestGate(limiter, properties.maxRequestsPerRun());
        return new DefaultKisQuoteClient(restClientWith(gate), properties, tokens, masker, clock, sleeper, gate::sent, metrics);
    }

    /** The production client chain (allow-list first, then the gate), exposed to tests so the wiring itself can be checked. */
    RestClient restClientWith(KisRequestGate gate) {
        return base.clone()
                .baseUrl(baseUri.toString())
                .requestInterceptor(new KisAllowlistInterceptor(baseUri))
                .requestInterceptor(gate)
                .build();
    }

    RestClient newRestClientForTest() {
        return restClientWith(new KisRequestGate(limiter, properties.maxRequestsPerRun()));
    }
}
