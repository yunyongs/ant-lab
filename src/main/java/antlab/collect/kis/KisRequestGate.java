package antlab.collect.kis;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Sits behind the allow-list: only requests that will really be sent are rate limited and counted. The per-run cap is
 * checked before the limiter, so a capped run never waits.
 */
final class KisRequestGate implements ClientHttpRequestInterceptor {

    private final MinIntervalRateLimiter limiter;
    private final int maxRequests;
    private final AtomicInteger sent = new AtomicInteger();

    KisRequestGate(MinIntervalRateLimiter limiter, int maxRequests) {
        this.limiter = limiter;
        this.maxRequests = maxRequests;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (sent.get() >= maxRequests) {
            throw new KisException("REQUEST_CAP", "KIS request cap of " + maxRequests + " per run reached; nothing more is sent");
        }
        limiter.acquire();
        sent.incrementAndGet();
        return execution.execute(request, body);
    }

    int sent() {
        return sent.get();
    }
}
