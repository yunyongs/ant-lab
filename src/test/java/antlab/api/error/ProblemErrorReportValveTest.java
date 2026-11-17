package antlab.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.common.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.CoyoteAdapter;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.coyote.ActionCode;
import org.apache.coyote.ActionHook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Tomcat-stage 400 body is one fixed text, written only onto an empty 400, and whatever an earlier layer already
 * set (headers, buffered bytes) is left as it was. The expected texts are literals typed here, not the production
 * constants, so changing the contract text fails this test.
 */
class ProblemErrorReportValveTest {

    private static final String EXPECTED_BODY = "{\"title\":\"Bad Request\",\"status\":400,"
            + "\"detail\":\"요청의 주소나 헤더를 해석할 수 없다\",\"instance\":\"/\",\"code\":\"MALFORMED_REQUEST\"}";

    /** A Tomcat request/response pair that collects what would reach the wire; headers are read from the response object. */
    private static final class Exchange {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final org.apache.coyote.Response coyote = new org.apache.coyote.Response();
        final Response response = new Response(coyote);

        Exchange() {
            org.apache.coyote.Request coyoteRequest = new org.apache.coyote.Request();
            Request request = new Request(new Connector(), coyoteRequest);
            coyoteRequest.setNote(CoyoteAdapter.ADAPTER_NOTES, request);
            coyote.setRequest(coyoteRequest);
            enableIo(coyote);
            coyote.setOutputBuffer(new org.apache.coyote.OutputBuffer() {
                @Override
                public int doWrite(ByteBuffer chunk) {
                    int count = chunk.remaining();
                    byte[] bytes = new byte[count];
                    chunk.get(bytes);
                    wire.write(bytes, 0, count);
                    return count;
                }

                @Override
                public long getBytesWritten() {
                    return wire.size();
                }
            });
            response.setRequest(request);
        }

        /** The hook is package-private in Tomcat; without it the stock report sees "no I/O allowed" and writes nothing. */
        private static void enableIo(org.apache.coyote.Response target) {
            try {
                Method setHook = org.apache.coyote.Response.class.getDeclaredMethod("setHook", ActionHook.class);
                setHook.setAccessible(true);
                setHook.invoke(target, (ActionHook) (code, param) -> {
                    if (code == ActionCode.IS_IO_ALLOWED) {
                        ((AtomicBoolean) param).set(true);
                    }
                });
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Tomcat response hook is not reachable", e);
            }
        }

        /** A 400 as Tomcat leaves it after a parse failure: status and error flag, nothing written. */
        Exchange parseFailure() {
            response.setStatus(400);
            response.setError();
            return this;
        }

        void report() throws IOException {
            ProblemErrorReportValve valve = new ProblemErrorReportValve();
            // the two settings TomcatProblemConfig fixes for the statuses this valve leaves to Tomcat
            valve.setShowReport(false);
            valve.setShowServerInfo(false);
            valve.report(response.getRequest(), response, null);
            response.finishResponse();
        }

        String wireText() {
            return wire.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void 본문은_독립된_리터럴_문장과_같다() {
        assertThat(ProblemErrorReportValve.DETAIL).isEqualTo("요청의 주소나 헤더를 해석할 수 없다");
        assertThat(ProblemErrorReportValve.BODY).isEqualTo(EXPECTED_BODY);
    }

    @Test
    void 본문은_문제_상세의_필수_필드와_계약_코드를_가진다() {
        JsonNode json = JsonMapper.shared().readTree(EXPECTED_BODY);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
        assertThat(json.get("code").asString()).isEqualTo(ErrorCode.MALFORMED_REQUEST.name());
        assertThat(JsonMapper.shared().readTree(ProblemErrorReportValve.BODY)).isEqualTo(json);
    }

    @Test
    void 빈_400은_고정_본문으로_답하고_길이는_UTF_8_바이트_수다() throws IOException {
        Exchange exchange = new Exchange().parseFailure();
        exchange.report();

        assertThat(exchange.wireText()).isEqualTo(EXPECTED_BODY);
        byte[] bytes = EXPECTED_BODY.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isGreaterThan(EXPECTED_BODY.length());
        assertThat(exchange.coyote.getContentLengthLong()).isEqualTo(bytes.length);
        assertThat(exchange.response.getStatus()).isEqualTo(400);
        assertThat(exchange.response.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void 앞선_계층이_정한_헤더와_오류_상태는_지우지_않는다() throws IOException {
        Exchange exchange = new Exchange().parseFailure();
        exchange.response.setHeader("X-Kept", "marker");
        exchange.response.setHeader("Cache-Control", "no-store");
        exchange.report();

        assertThat(exchange.response.getHeader("X-Kept")).isEqualTo("marker");
        assertThat(exchange.response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(exchange.response.isError()).isTrue();
        // the error was marked reported by the valve, so no second report is due
        assertThat(exchange.response.isErrorReportRequired()).isFalse();
    }

    @Test
    void 버퍼에만_있는_본문이_있는_400은_바이트를_그대로_둔다() throws IOException {
        Exchange exchange = new Exchange().parseFailure();
        exchange.response.setHeader("X-Kept", "marker");
        exchange.response.getOutputStream().write("kept body".getBytes(StandardCharsets.UTF_8));
        assertThat(exchange.response.isCommitted()).isFalse();
        assertThat(exchange.response.getContentWritten()).isPositive();
        exchange.report();

        assertThat(exchange.wireText()).isEqualTo("kept body");
        assertThat(exchange.response.getHeader("X-Kept")).isEqualTo("marker");
        assertThat(exchange.response.getContentType()).isNull();
    }

    @Test
    void 이미_보고된_오류는_다시_쓰지_않는다() throws IOException {
        Exchange exchange = new Exchange().parseFailure();
        assertThat(exchange.response.setErrorReported()).isTrue();
        exchange.report();

        assertThat(exchange.wireText()).isEmpty();
        assertThat(exchange.response.getContentType()).isNull();
    }

    @Test
    void 다른_상태는_Tomcat_보고서에_맡기고_고정_본문으로_바꾸지_않는다() throws IOException {
        // 408, 414, 431 and 505 are the statuses a parse stage could raise besides 400: all stay with Tomcat's own report
        for (int status : new int[] {408, 414, 431, 505}) {
            Exchange exchange = new Exchange();
            exchange.response.setStatus(status);
            exchange.response.setError();
            exchange.report();

            assertThat(exchange.response.getStatus()).as("status " + status).isEqualTo(status);
            assertThat(exchange.wireText()).as("body of " + status).startsWith("<!doctype html>")
                    .doesNotContain("MALFORMED_REQUEST", "problem+json").doesNotContainIgnoringCase("apache tomcat");
            assertThat(exchange.response.getContentType()).as("type of " + status).startsWith("text/html");
        }
    }
}
