package antlab.api.error;

import antlab.common.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

/**
 * Answers a 400 that Tomcat raised before Spring saw the request (bad percent-encoding, encoded slash, oversized
 * header) with the same problem+json shape as {@link ApiProblem}. It runs outside the DispatcherServlet, so no Spring
 * advice and no Jackson here: the body is fixed text. Other statuses and any response that already has a body keep
 * Tomcat's own report, so Spring-written errors are never rewritten.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

    static final String DETAIL = "요청의 주소나 헤더를 해석할 수 없다";

    /** Plain path characters only: no '%' and no query, so a rejected input is never echoed back. */
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9/_.~-]{0,200}$");

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        if (response.getStatus() != 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            super.report(request, response, throwable);
            return;
        }
        byte[] body = body(request.getRequestURI()).getBytes(StandardCharsets.UTF_8);
        try {
            response.reset();
            response.setStatus(400);
            response.setContentType("application/problem+json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.finishResponse();
        } catch (IOException | IllegalStateException e) {
            // the connection is gone or the response was already taken over; nothing more can be sent
        }
    }

    static String body(String requestUri) {
        String instance = requestUri != null && SAFE_PATH.matcher(requestUri).matches() ? requestUri : "/";
        return "{\"title\":\"Bad Request\",\"status\":400,\"detail\":\"" + DETAIL + "\",\"instance\":\"" + instance
                + "\",\"code\":\"" + ErrorCode.MALFORMED_REQUEST.name() + "\"}";
    }
}
