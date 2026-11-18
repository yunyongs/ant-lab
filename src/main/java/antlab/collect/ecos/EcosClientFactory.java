package antlab.collect.ecos;

import antlab.collect.kis.MinIntervalRateLimiter;
import antlab.collect.kis.SecretMasker;
import antlab.collect.kis.Sleeper;
import java.net.URI;
import java.time.Clock;
import org.springframework.web.client.RestClient;

/**
 * Creates one {@link EcosSeriesClient} per run. What the clients share: the rate limiter, so concurrent runs together
 * stay within the per-second limit. What each client owns: its request counter and cap.
 */
public final class EcosClientFactory {

    static final Sleeper SYSTEM_SLEEPER = duration -> {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EcosException("ECOS_INTERRUPTED", "interrupted while waiting for the ECOS rate limit");
        }
    };

    private final RestClient.Builder base;
    private final URI baseUri;
    private final EcosProperties properties;
    private final Sleeper sleeper;
    private final SecretMasker masker = new SecretMasker();
    private final MinIntervalRateLimiter limiter;

    EcosClientFactory(RestClient.Builder base, URI baseUri, EcosProperties properties, Clock clock, Sleeper sleeper) {
        properties.requireKey();
        this.base = base;
        this.baseUri = baseUri;
        this.properties = properties;
        this.sleeper = sleeper;
        masker.register(properties.apiKey());
        this.limiter = new MinIntervalRateLimiter(clock, sleeper, properties.rps());
    }

    public static EcosClientFactory create(RestClient.Builder transport, EcosProperties properties, Clock clock) {
        return new EcosClientFactory(transport, EcosEndpoint.BASE_URI, properties, clock, SYSTEM_SLEEPER);
    }

    public EcosSeriesClient newClient() {
        EcosRequestGate gate = new EcosRequestGate(limiter, properties.maxRequestsPerRun());
        return new DefaultEcosSeriesClient(restClientWith(gate), properties, masker, sleeper, gate::sent);
    }

    /** The production client chain (allow-list first, then the gate), exposed to tests so the wiring itself can be checked. */
    RestClient restClientWith(EcosRequestGate gate) {
        return base.clone()
                .baseUrl(baseUri.toString())
                .requestInterceptor(new EcosAllowlistInterceptor(baseUri))
                .requestInterceptor(gate)
                .build();
    }

    @Override
    public String toString() {
        return "EcosClientFactory[" + properties + "]";
    }
}
