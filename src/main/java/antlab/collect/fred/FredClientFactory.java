package antlab.collect.fred;

import antlab.collect.kis.MinIntervalRateLimiter;
import antlab.collect.kis.SecretMasker;
import antlab.collect.kis.Sleeper;
import java.net.URI;
import java.time.Clock;
import org.springframework.web.client.RestClient;

/**
 * Creates one {@link FredSeriesClient} per run. What the clients share: the rate limiter, so concurrent runs together
 * stay within the per-second limit. What each client owns: its request counter and cap.
 */
public final class FredClientFactory {

    static final Sleeper SYSTEM_SLEEPER = duration -> {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FredException("FRED_INTERRUPTED", "interrupted while waiting for the FRED rate limit");
        }
    };

    private final RestClient.Builder base;
    private final URI baseUri;
    private final FredProperties properties;
    private final Sleeper sleeper;
    private final SecretMasker masker = new SecretMasker();
    private final MinIntervalRateLimiter limiter;

    FredClientFactory(RestClient.Builder base, URI baseUri, FredProperties properties, Clock clock, Sleeper sleeper) {
        properties.requireKey();
        this.base = base;
        this.baseUri = baseUri;
        this.properties = properties;
        this.sleeper = sleeper;
        masker.register(properties.apiKey());
        this.limiter = new MinIntervalRateLimiter(clock, sleeper, properties.rps());
    }

    public static FredClientFactory create(RestClient.Builder transport, FredProperties properties, Clock clock) {
        return new FredClientFactory(transport, FredEndpoint.BASE_URI, properties, clock, SYSTEM_SLEEPER);
    }

    public FredSeriesClient newClient() {
        FredRequestGate gate = new FredRequestGate(limiter, properties.maxRequestsPerRun());
        return new DefaultFredSeriesClient(restClientWith(gate), properties, masker, sleeper, gate::sent);
    }

    /** The production client chain (allow-list first, then the gate), exposed to tests so the wiring itself can be checked. */
    RestClient restClientWith(FredRequestGate gate) {
        return base.clone()
                .baseUrl(baseUri.toString())
                .requestInterceptor(new FredAllowlistInterceptor(baseUri))
                .requestInterceptor(gate)
                .build();
    }

    @Override
    public String toString() {
        return "FredClientFactory[" + properties + "]";
    }
}
