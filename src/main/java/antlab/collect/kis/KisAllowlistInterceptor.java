package antlab.collect.kis;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Refuses, before anything is sent, every request that is not exactly one of the three {@link KisEndpoint} entries:
 * same scheme, host and port as the base, no user info or fragment, a path of plain characters equal to the entry's path,
 * for quote requests the entry's TR id, and a query holding exactly the entry's parameter names once each with plain
 * values. Exact equality only — no prefixes, no word lists.
 */
final class KisAllowlistInterceptor implements ClientHttpRequestInterceptor {

    private static final Pattern PATH_CHARS = Pattern.compile("[A-Za-z0-9/_-]+");
    private static final Pattern QUERY_VALUE = Pattern.compile("[A-Za-z0-9.]*");

    private final URI base;

    KisAllowlistInterceptor(URI base) {
        this.base = Objects.requireNonNull(base);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        check(request.getMethod().name(), request.getURI(), request.getHeaders().getFirst("tr_id"));
        return execution.execute(request, body);
    }

    KisEndpoint check(String method, URI uri, String trId) {
        String rawPath = uri.getRawPath();
        if (!sameOrigin(uri) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || rawPath == null || !PATH_CHARS.matcher(rawPath).matches()) {
            throw refused(method, rawPath);
        }
        KisEndpoint endpoint = KisEndpoint.find(method, rawPath).orElseThrow(() -> refused(method, rawPath));
        if (!Objects.equals(endpoint.trId(), trId)) {
            throw new KisForbiddenRequestException("KIS request refused: " + endpoint + " with a transaction id that is not its own");
        }
        checkQuery(endpoint, uri.getRawQuery());
        return endpoint;
    }

    private static void checkQuery(KisEndpoint endpoint, String rawQuery) {
        Set<String> seen = new HashSet<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&", -1)) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? null : pair.substring(eq + 1);
                if (value == null || !QUERY_VALUE.matcher(value).matches() || !seen.add(name)) {
                    throw badQuery(endpoint);
                }
            }
        }
        if (!seen.equals(endpoint.queryNames())) {
            throw badQuery(endpoint);
        }
    }

    private static KisForbiddenRequestException badQuery(KisEndpoint endpoint) {
        return new KisForbiddenRequestException("KIS request refused: " + endpoint + " with a query outside its parameter allow-list");
    }

    private boolean sameOrigin(URI uri) {
        return base.getScheme().equalsIgnoreCase(uri.getScheme()) && base.getHost().equalsIgnoreCase(uri.getHost())
                && base.getPort() == uri.getPort();
    }

    private static KisForbiddenRequestException refused(String method, String rawPath) {
        String shown = rawPath != null && PATH_CHARS.matcher(rawPath).matches() ? rawPath : "(path not shown)";
        return new KisForbiddenRequestException("KIS request refused, not on the allow-list: " + method + " " + shown);
    }
}
