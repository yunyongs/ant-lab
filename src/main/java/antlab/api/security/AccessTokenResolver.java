package antlab.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Reads the bearer token from the {@code Authorization} header only. Public paths never read it, so a stale token cannot
 * block login, refresh or logout. Two headers are refused rather than picking one.
 */
final class AccessTokenResolver implements BearerTokenResolver {

    private final RequestMatcher open;
    private final BearerTokenResolver header = new DefaultBearerTokenResolver();

    AccessTokenResolver(RequestMatcher open) {
        this.open = open;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        if (open.matches(request)) {
            return null;
        }
        if (Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() > 1) {
            throw new InvalidBearerTokenException("more than one Authorization header");
        }
        return header.resolve(request);
    }
}
