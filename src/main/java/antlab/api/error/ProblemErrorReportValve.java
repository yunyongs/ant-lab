package antlab.api.error;

import antlab.common.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

/**
 * Answers a 400 that Tomcat raised before Spring saw the request (bad percent-encoding, encoded slash, oversized
 * header) with the same problem+json shape as {@link ApiProblem}. It runs outside the DispatcherServlet, so no Spring
 * advice and no Jackson here: the body is one fixed text and carries no request value at all ({@code instance} is
 * always {@code /}). This covers the response only: Tomcat itself may still log the rejected request line (see
 * README). Other statuses and any response that already has a body keep Tomcat's own report, so Spring-written errors
 * are never rewritten. Only the content type and length are set; headers set by earlier layers stay.
 */
public class ProblemErrorReportValve extends ErrorReportValve {

    static final String DETAIL = "요청의 주소나 헤더를 해석할 수 없다";

    /** Fixed on purpose: a rejected request is never echoed, not even its path. */
    static final String BODY = "{\"title\":\"Bad Request\",\"status\":400,\"detail\":\"" + DETAIL
            + "\",\"instance\":\"/\",\"code\":\"" + ErrorCode.MALFORMED_REQUEST.name() + "\"}";

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        if (response.getStatus() != 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            super.report(request, response, throwable);
            return;
        }
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        try {
            // the guard above means nothing is buffered, so there is nothing to reset and the other headers stay
            response.setContentType("application/problem+json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.finishResponse();
        } catch (IOException | IllegalStateException e) {
            // the connection is gone or the response was already taken over; nothing more can be sent
        }
    }
}
