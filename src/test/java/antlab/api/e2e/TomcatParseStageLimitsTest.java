package antlab.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.RawHttp;
import antlab.support.RawHttp.Answer;
import antlab.support.RealPortTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The size and timeout limits of the parse stage, reproduced with small limits so the probes stay small (a 16 KB write
 * to a server that closes early can end in a reset on some systems). Its own context: 2 KB for the request line and
 * headers together, 1 s connection timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.max-http-request-header-size=2KB", "server.tomcat.connection-timeout=1s"})
class TomcatParseStageLimitsTest extends RealPortTest {

    private static final String CLOSE = "Connection: close\r\n\r\n";

    @Test
    void 한도_안의_헤더는_정상_처리되고_한도를_넘으면_고정_본문의_400이다() {
        Answer within = RawHttp.send(port, "GET /livez HTTP/1.1\r\nHost: x\r\nX-Pad: " + "a".repeat(1000) + "\r\n" + CLOSE);
        assertThat(within.status()).as(within.toString()).isEqualTo(200);

        Answer over = RawHttp.send(port, "GET /livez HTTP/1.1\r\nHost: x\r\nX-Pad: " + "a".repeat(3000) + "\r\n" + CLOSE);
        TomcatParseStageTest.assertFixedProblem(over);
    }

    @Test
    void 한도를_넘는_요청_줄도_고정_본문의_400이고_414로_나가지_않는다() {
        Answer answer = RawHttp.send(port, "GET /livez?" + "a".repeat(3000) + " HTTP/1.1\r\nHost: x\r\n" + CLOSE);
        TomcatParseStageTest.assertFixedProblem(answer);
    }

    @Test
    void 끝나지_않은_헤더는_연결_시간_제한에_본문_없이_닫히고_408을_보내지_않는다() {
        Answer answer = RawHttp.send(port, "GET /livez HTTP/1.1\r\nHost: x\r\n");
        assertThat(answer.text()).as(answer.toString()).isEmpty();
        assertThat(answer.closed()).as(answer.toString()).isTrue();
        assertThat(answer.problem()).as(answer.toString()).isNull();
    }
}
