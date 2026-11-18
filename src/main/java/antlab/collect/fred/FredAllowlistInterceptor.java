package antlab.collect.fred;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Refuses, before anything is sent, every request that is not a GET of {@link FredEndpoint#OBSERVATIONS_PATH}: same
 * scheme, host and port as the base, no user info or fragment, the exact path, and a query that holds every required name
 * once, only known optional names, and a plain value of the expected shape for each. Exact equality only.
 */
final class FredAllowlistInterceptor implements ClientHttpRequestInterceptor {

    private static final Pattern PATH_CHARS = Pattern.compile("[A-Za-z0-9/_-]+");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Map<String, Pattern> VALUES = Map.of(
            "series_id", Pattern.compile("[A-Z0-9_]{1,25}"),
            "api_key", Pattern.compile("[A-Za-z0-9_-]{8,64}"),
            "file_type", Pattern.compile("json"),
            "limit", Pattern.compile("\\d{1,6}"),
            "offset", Pattern.compile("\\d{1,9}"),
            "observation_start", DATE,
            "realtime_start", DATE,
            "realtime_end", DATE);

    private final URI base;

    FredAllowlistInterceptor(URI base) {
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
                || rawPath == null || !PATH_CHARS.matcher(rawPath).matches() || !FredEndpoint.OBSERVATIONS_PATH.equals(rawPath)) {
            throw refused(method, rawPath);
        }
        checkQuery(uri.getRawQuery());
    }

    private static void checkQuery(String rawQuery) {
        Set<String> seen = new HashSet<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&", -1)) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? null : pair.substring(eq + 1);
                Pattern shape = VALUES.get(name);
                if (value == null || shape == null || !shape.matcher(value).matches() || !seen.add(name)) {
                    throw badQuery();
                }
            }
        }
        if (!seen.containsAll(FredEndpoint.REQUIRED_QUERY)) {
            throw badQuery();
        }
        for (String name : seen) {
            if (!FredEndpoint.REQUIRED_QUERY.contains(name) && !FredEndpoint.OPTIONAL_QUERY.contains(name)) {
                throw badQuery();
            }
        }
    }

    private static FredForbiddenRequestException badQuery() {
        return new FredForbiddenRequestException("FRED request refused: the query is outside the parameter allow-list");
    }

    private boolean sameOrigin(URI uri) {
        return base.getScheme().equalsIgnoreCase(uri.getScheme()) && base.getHost().equalsIgnoreCase(uri.getHost())
                && base.getPort() == uri.getPort();
    }

    private static FredForbiddenRequestException refused(String method, String rawPath) {
        String shown = rawPath != null && PATH_CHARS.matcher(rawPath).matches() ? rawPath : "(path not shown)";
        return new FredForbiddenRequestException("FRED request refused, not on the allow-list: " + method + " " + shown);
    }
}
