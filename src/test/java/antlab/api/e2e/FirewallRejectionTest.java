package antlab.api.e2e;

import static antlab.api.e2e.TomcatParseStageTest.assertFixedProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import antlab.api.error.ErrorHttpMapping;
import antlab.common.error.ErrorCode;
import antlab.support.ContractErrorCodes;
import antlab.support.RawHttp;
import antlab.support.RawHttp.Answer;
import antlab.support.RealPortTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a client sees when the Spring Security firewall rejects a request ({@code //}, {@code ;}, {@code %25}, an unknown
 * method), over a real port and raw bytes. The firewall answers inside the filter proxy, so it is a different layer from
 * the Tomcat parse stage in {@link TomcatParseStageTest}, but the answer is the same fixed contract problem. The expected
 * body is typed in {@link TomcatParseStageTest}, not read from the production constant.
 */
class FirewallRejectionTest extends RealPortTest {

    private static final String CLOSE = "Connection: close\r\n\r\n";
    private static final String MARK = "SECRET123";

    private static Map<String, String> probes() {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("double slash", "GET /api/v1/" + MARK + "//pool HTTP/1.1\r\nHost: x\r\n" + CLOSE);
        probes.put("semicolon", "GET /api/v1/pool;jsessionid=" + MARK + " HTTP/1.1\r\nHost: x\r\n" + CLOSE);
        probes.put("encoded percent", "GET /api/v1/a%25" + MARK + " HTTP/1.1\r\nHost: x\r\n" + CLOSE);
        probes.put("unknown method", MARK + " /api/v1/pool HTTP/1.1\r\nHost: x\r\n" + CLOSE);
        return probes;
    }

    @TestFactory
    Stream<DynamicTest> 방화벽이_거절한_요청은_계약_코드의_고정_문제_본문이다() throws IOException {
        JsonNode contract = ApiErrorSweepTest.contract();
        return probes().entrySet().stream().map(probe -> dynamicTest(probe.getKey(), () -> {
            Answer answer = RawHttp.send(port, probe.getValue());
            assertFixedProblem(answer);
            JsonNode json = JsonMapper.shared().readTree(answer.body());
            ErrorCode code = ErrorCode.valueOf(json.get("code").asString());
            assertThat(code).isEqualTo(ErrorCode.MALFORMED_REQUEST);
            assertThat(ContractErrorCodes.framework(contract).get(400)).contains(code.name());
            assertThat(ErrorHttpMapping.status(code).value()).isEqualTo(400);
            // Boot's default error body ({"timestamp","status","error","path"}) must not come back
            assertThat(json.propertyNames()).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
            assertThat(answer.text()).as(answer.toString()).doesNotContain(MARK, "api", "pool", "timestamp");
        }));
    }

    @Test
    void 러너_경로의_방화벽_거절도_같은_본문이다() {
        Answer answer = RawHttp.send(port,
                "GET /internal/agent-runner/v1//agents/" + MARK + " HTTP/1.1\r\nHost: x\r\n" + CLOSE);
        assertFixedProblem(answer);
        assertThat(answer.text()).doesNotContain(MARK, "agent");
    }

    @Test
    void 관리_포트의_방화벽_거절도_같은_본문이다() {
        // the management port has its own Tomcat but the same filter proxy bean; every probe kind is tried there too
        for (Map.Entry<String, String> probe : probes().entrySet()) {
            Answer answer = RawHttp.send(managementPort, probe.getValue().replace("/api/v1/", "/actuator/"));
            assertFixedProblem(answer);
            assertThat(answer.text()).as(probe.getKey() + " " + answer).doesNotContain(MARK, "actuator");
        }
    }

    @Test
    void 정상_요청은_방화벽_뒤에서_그대로_처리된다() {
        // control: the handler answers rejections only. A clean path still reaches the chain (401, no token).
        Answer answer = RawHttp.send(port, "GET /api/v1/seasons HTTP/1.1\r\nHost: x\r\n" + CLOSE);
        assertThat(answer.status()).as(answer.toString()).isEqualTo(401);
        assertThat(answer.text()).as(answer.toString()).contains("UNAUTHENTICATED");
    }

    @Test
    void 거절된_요청의_값은_어떤_로그에도_남지_않는다() throws InterruptedException {
        // DEBUG on the security packages: Spring's own rejection handler logs the exception message (which names the
        // value) at that level, so INFO alone would not notice a handler that logs.
        Logger security = (Logger) LoggerFactory.getLogger("org.springframework.security");
        Level before = security.getLevel();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        security.setLevel(Level.DEBUG);
        try {
            for (String probe : probes().values()) {
                assertThat(RawHttp.send(port, probe.replace(MARK, "LOGMARK77")).status()).isEqualTo(400);
            }
            Thread.sleep(200);
            List<ILoggingEvent> events = List.copyOf(appender.list);
            for (ILoggingEvent event : events) {
                assertThat(everything(event)).as(event.getLoggerName()).doesNotContain("LOGMARK77");
            }
        } finally {
            security.setLevel(before);
            root.detachAppender(appender);
        }
    }

    private static String everything(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append(' ').append(t.getMessage());
        }
        return text.toString();
    }
}
