package antlab.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.RawHttp;
import antlab.support.RawHttp.Answer;
import antlab.support.RealPortTest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.catalina.Host;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/**
 * What a client sees when Tomcat rejects a request while parsing it, over a real port and raw bytes (default Tomcat
 * limits; the size and timeout limits are in {@link TomcatParseStageLimitsTest}). The expected body is a literal typed
 * here, not the production constant.
 */
class TomcatParseStageTest extends RealPortTest {

    @Autowired
    private ServletWebServerApplicationContext context;

    private static final String PROBLEM_BODY = "{\"title\":\"Bad Request\",\"status\":400,"
            + "\"detail\":\"요청의 주소나 헤더를 해석할 수 없다\",\"instance\":\"/\",\"code\":\"MALFORMED_REQUEST\"}";
    private static final String GOOD = "GET /livez HTTP/1.1\r\nHost: x\r\n";
    private static final String CLOSE = "Connection: close\r\n\r\n";

    /** A 400 problem+json answer that is complete and the last on its connection. */
    static void assertFixedProblem(Answer answer) {
        assertThat(answer.status()).as(answer.toString()).isEqualTo(400);
        assertThat(answer.headerValues("Content-Type")).as(answer.toString()).containsExactly("application/problem+json");
        assertThat(answer.body()).as(answer.toString()).isEqualTo(PROBLEM_BODY);
        // exactly one length, counted in UTF-8 bytes (the text is Korean), and no chunking
        assertThat(answer.headerValues("Content-Length")).as(answer.toString())
                .containsExactly(String.valueOf(PROBLEM_BODY.getBytes(StandardCharsets.UTF_8).length));
        assertThat(answer.headerValues("Transfer-Encoding")).as(answer.toString()).isEmpty();
        assertThat(answer.headerValues("Connection")).as(answer.toString()).containsExactly("close");
        assertThat(answer.answers()).as(answer.toString()).isEqualTo(1);
        assertThat(answer.closed()).as(answer.toString()).isTrue();
    }

    @Test
    void Host에는_우리_밸브_하나만_있고_보고서_설정은_코드로_고정돼_있다() {
        TomcatWebServer webServer = (TomcatWebServer) context.getWebServer();
        Host host = (Host) webServer.getTomcat().getHost();
        List<Valve> reportValves = Arrays.stream(host.getPipeline().getValves())
                .filter(valve -> valve instanceof ErrorReportValve).toList();
        assertThat(reportValves).hasSize(1);
        ErrorReportValve valve = (ErrorReportValve) reportValves.get(0);
        assertThat(valve.getClass().getName()).isEqualTo("antlab.api.error.ProblemErrorReportValve");
        // fixed by TomcatProblemConfig on purpose: the replaced valve's own settings are not inherited
        assertThat(valve.isShowReport()).isFalse();
        assertThat(valve.isShowServerInfo()).isFalse();
        assertThat(((StandardHost) host).getErrorReportValveClass()).isEqualTo("antlab.api.error.ProblemErrorReportValve");
    }

    @Test
    void 평문_경로와_질의와_헤더_값은_본문에_실리지_않는다() {
        // a plain path is no proof of harmlessness: these carry values a caller might consider secret
        String[] probes = {
            "GET /reset/SECRET123 HTTP/1.1\r\nHost: x\r\nBad Header: SECRET123\r\n" + CLOSE,
            "GET /reset/SECRET123%ZZ?token=SECRET123 HTTP/1.1\r\nHost: x\r\n" + CLOSE,
            "GET /reset/SECRET123?token=SECRET123 HTTP/1.1\r\n" + CLOSE,
            "GET /reset/SECRET123/a%2Fb HTTP/1.1\r\nHost: x\r\n" + CLOSE};
        for (String probe : probes) {
            Answer answer = RawHttp.send(port, probe);
            assertFixedProblem(answer);
            assertThat(answer.text()).as(answer.toString()).doesNotContain("SECRET123").doesNotContain("reset");
        }
    }

    @Test
    void 요청_값은_antlab_로그에_남지_않는다() throws InterruptedException {
        // Tomcat itself logs the rejected header line (INFO, once per process) and the request target (DEBUG): a
        // known residual, written down in the README. What this pins is that nothing of ours logs a request value.
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            RawHttp.send(port, "GET /LOGMARK77/x?token=LOGMARK77 HTTP/1.1\r\nHost: x\r\nBad Header: LOGMARK77\r\n" + CLOSE);
            RawHttp.send(port, "GET /LOGMARK77/x%ZZ?token=LOGMARK77 HTTP/1.1\r\nHost: x\r\n" + CLOSE);
            Thread.sleep(200);
            List<ILoggingEvent> events = List.copyOf(appender.list);
            for (ILoggingEvent event : events) {
                String text = event.getFormattedMessage() + " " + (event.getThrowableProxy() == null ? ""
                        : event.getThrowableProxy().getMessage());
                if (text.contains("LOGMARK77")) {
                    assertThat(event.getLoggerName()).as(text).startsWith("org.apache.coyote.");
                }
            }
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void Connection_close를_요청하지_않아도_잘못된_요청_뒤의_같은_연결_요청은_처리되지_않는다() {
        // control: two good requests on one connection are both answered, so the probe can tell the difference
        Answer control = RawHttp.send(port, GOOD + "\r\n" + GOOD + CLOSE);
        assertThat(control.answers()).as(control.toString()).isEqualTo(2);

        String[] rejected = {
            "GET /api/v1/pool%ZZ HTTP/1.1\r\nHost: x\r\n\r\n",
            "GET /api/v1/a%2Fb HTTP/1.1\r\nHost: x\r\n\r\n",
            "GET /api/v1/pool HTTP/1.1\r\nHost: x\r\nBad Header: v\r\n\r\n"};
        for (String bad : rejected) {
            Answer answer = RawHttp.send(port, bad + GOOD + "\r\n");
            assertFixedProblem(answer);
        }
    }

    @Test
    void 상충하는_Content_Length는_고정_본문으로_거절된다() {
        Answer answer = RawHttp.send(port,
                "POST /api/v1/auth/login HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}" + GOOD + "\r\n");
        assertFixedProblem(answer);
    }

    @Test
    void 모르는_Transfer_Encoding은_Tomcat의_501로_두고_후속_요청은_처리되지_않는다() {
        Answer answer = RawHttp.send(port,
                "POST /api/v1/auth/login HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: bogus\r\n\r\n" + GOOD + "\r\n");
        assertThat(answer.status()).as(answer.toString()).isEqualTo(501);
        assertThat(answer.text()).as(answer.toString()).doesNotContain("MALFORMED_REQUEST").doesNotContain("problem+json");
        assertThat(answer.answers()).as(answer.toString()).isEqualTo(1);
        assertThat(answer.closed()).as(answer.toString()).isTrue();
    }

    @Test
    void 모르는_HTTP_버전의_505는_Tomcat_본문이고_요청_값과_서버_정보를_싣지_않는다() {
        Answer answer = RawHttp.send(port, "GET /reset/SECRET123 HTTP/9.9\r\nHost: x\r\n" + CLOSE);
        assertThat(answer.status()).as(answer.toString()).isEqualTo(505);
        assertThat(answer.text()).as(answer.toString()).doesNotContain("MALFORMED_REQUEST", "9.9", "SECRET123", "reset")
                .doesNotContainIgnoringCase("apache tomcat").doesNotContain("11.0");
    }
}
