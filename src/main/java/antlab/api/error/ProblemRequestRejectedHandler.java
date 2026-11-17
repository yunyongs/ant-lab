package antlab.api.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

/**
 * Answers a request the Spring Security firewall rejects ({@code //}, {@code ;}, {@code %25}, an unknown HTTP method and
 * the like) with the same fixed {@code problem+json} body as {@link ProblemErrorReportValve}. The firewall runs inside
 * the filter proxy, before the DispatcherServlet, so no advice sees it; the default handler would call
 * {@code sendError(400)} and Boot's error page would answer with the request path. Nothing of the request or of the
 * exception message is written or logged here: the message names the rejected value.
 */
@Component
public class ProblemRequestRejectedHandler implements RequestRejectedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException rejected)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        byte[] body = ProblemErrorReportValve.BODY.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/problem+json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
