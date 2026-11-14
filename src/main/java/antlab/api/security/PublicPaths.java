package antlab.api.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** The one list of paths reached without an access token; the allow rules and the bearer resolver both read it. */
final class PublicPaths {

    /** Contract operations that need no token. Exact paths. */
    static final List<String> CONTRACT_PUBLIC = List.of("/api/v1/pool", "/api/v1/auth/signup", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout");

    static final List<String> OPERATIONS = List.of("/livez", "/readyz", "/actuator/**");

    static final List<String> API_DOCS = List.of("/v3/api-docs", "/v3/api-docs/**");

    static final List<String> SWAGGER_UI = List.of("/swagger-ui.html", "/swagger-ui/**");

    private PublicPaths() {
    }

    /** Paths on which an {@code Authorization} header is never read, so a stale token cannot block them. */
    static RequestMatcher matcher(boolean apiDocs, boolean swaggerUi) {
        List<String> patterns = new ArrayList<>(CONTRACT_PUBLIC);
        patterns.addAll(OPERATIONS);
        if (apiDocs) {
            patterns.addAll(API_DOCS);
        }
        if (swaggerUi) {
            patterns.addAll(SWAGGER_UI);
        }
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(patterns.stream().map(paths::matcher).toArray(RequestMatcher[]::new));
    }
}
