package antlab.collect.ecos;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Refuses, before anything is sent, every request that is not a GET of one ECOS statistic search: same scheme, host and
 * port as the base, no user info, query or fragment, and a path of exactly the shape
 * {@code /api/StatisticSearch/<key>/json/kr/<from>/<to>/<stat>/<cycle>/<start>/<end>/<item>} with a daily (8 digit) or a
 * monthly (6 digit) pair of bounds that matches the cycle. Exact equality only. The path holds the key, so a refusal never
 * shows any part of it.
 */
final class EcosAllowlistInterceptor implements ClientHttpRequestInterceptor {

    private static final Pattern PATH = Pattern.compile(
            "/api/StatisticSearch/[A-Za-z0-9]{8,64}/json/kr/\\d{1,9}/\\d{1,9}/[0-9A-Z]{1,12}/([DM])/(\\d{6}|\\d{8})/(\\d{6}|\\d{8})/[0-9A-Za-z]{1,20}");

    private final URI base;

    EcosAllowlistInterceptor(URI base) {
        this.base = Objects.requireNonNull(base);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        check(request.getMethod().name(), request.getURI());
        return execution.execute(request, body);
    }

    void check(String method, URI uri) {
        String rawPath = uri.getRawPath();
        if (!"GET".equals(method) || !sameOrigin(uri) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawQuery() != null || rawPath == null) {
            throw refused(method);
        }
        Matcher m = PATH.matcher(rawPath);
        if (!m.matches()) {
            throw refused(method);
        }
        int width = "D".equals(m.group(1)) ? 8 : 6;
        if (m.group(2).length() != width || m.group(3).length() != width) {
            throw refused(method);
        }
    }

    private boolean sameOrigin(URI uri) {
        return base.getScheme().equalsIgnoreCase(uri.getScheme()) && base.getHost().equalsIgnoreCase(uri.getHost())
                && base.getPort() == uri.getPort();
    }

    private static EcosForbiddenRequestException refused(String method) {
        return new EcosForbiddenRequestException("ECOS request refused, not on the allow-list: " + method + " (path not shown)");
    }
}
