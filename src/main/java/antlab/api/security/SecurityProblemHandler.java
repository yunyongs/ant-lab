package antlab.api.security;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Filters run before the DispatcherServlet, so the advice never sees their exceptions; hand them to the MVC resolver so the body is the same Problem. */
@Component
class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    SecurityProblemHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        resolve(request, response, ex, HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        resolve(request, response, ex, HttpServletResponse.SC_FORBIDDEN);
    }

    /** Fail closed: if the resolver writes nothing, still send the denial status instead of an empty 200. */
    private void resolve(HttpServletRequest request, HttpServletResponse response, Exception ex, int status)
            throws IOException {
        if (resolver.resolveException(request, response, null, ex) == null && !response.isCommitted()) {
            response.sendError(status);
        }
    }
}
