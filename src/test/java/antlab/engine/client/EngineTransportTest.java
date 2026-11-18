package antlab.engine.client;

import static antlab.engine.client.EngineHarness.FAKE_TOKEN;
import static antlab.support.FakeEngineServer.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.common.error.DomainException;
import antlab.common.error.ErrorCode;
import antlab.support.FakeEngineServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import io.micrometer.observation.ObservationRegistry;

/** The real JDK transport against a local fake engine: timeouts, redirects, proxies and the token header on the wire. */
@ExtendWith(OutputCaptureExtension.class)
class EngineTransportTest {

    private FakeEngineServer engine;

    @BeforeEach
    void start() {
        engine = FakeEngineServer.start();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private EngineClient client(String url, String token, Duration connect, Duration paths, Duration projections, Duration regime) {
        var properties = new EngineProperties(url, token, connect, paths, projections, regime, EngineProperties.PathSourceMode.JAVA,
                "m2-sb6-v1", 2000);
        return EngineClientConfig.newClient(properties, ObservationRegistry.NOOP);
    }

    private EngineClient client(String url, String token, Duration connect, Duration paths, Duration projections) {
        return client(url, token, connect, paths, projections, Duration.ofSeconds(60));
    }

    private EngineClient client() {
        return client(engine.url(), FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(60));
    }

    @Test
    void 실제_전송으로_세_연산을_부르고_토큰_헤더는_하나만_나간다() {
        engine.respondFixture("/v1/paths", 200, "paths-response-m2.json")
                .respondFixture("/v1/projections", 200, "projection-response.json")
                .respondFixture("/v1/info", 200, "info-response.json");
        EngineClient client = client();

        assertThat(client.createPath(EngineHarness.pathRequest()).months()).hasSize(12);
        assertThat(client.createProjection(EngineHarness.projectionRequest()).portfolios()).hasSize(2);
        assertThat(client.info().service()).isEqualTo("antlab-engine");

        assertThat(engine.requests()).hasSize(3).allSatisfy(r -> {
            assertThat(r.header(EngineClient.TOKEN_HEADER)).containsExactly(FAKE_TOKEN);
            assertThat(r.header("Accept")).isNotEmpty();
        });
        assertThat(engine.requests().get(0).method()).isEqualTo("POST");
        assertThat(engine.requests().get(0).header("Content-Type")).containsExactly("application/json");
        assertThat(engine.requests().get(2).method()).isEqualTo("GET");
        assertThat(engine.requests().get(2).body()).isEmpty();
    }

    @Test
    void 읽기_시간_제한을_넘기면_불가용이고_제한_시간_안에_돌아온다() {
        engine.respondFixture("/v1/paths", 200, "paths-response-m2.json").delay(Duration.ofMillis(1500));
        EngineClient client = client(engine.url(), FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofSeconds(15));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(EngineUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1400));
    }

    @Test
    void 헤더_뒤에_본문이_멈추어도_경로_시간_제한_안에_불가용으로_돌아온다() {
        engine.respondFixture("/v1/paths", 200, "paths-response-m2.json").stallBody(Duration.ofSeconds(4));
        EngineClient client = client(engine.url(), FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(15));
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.createPath(EngineHarness.pathRequest())).isInstanceOf(EngineUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2000));
    }

    @Test
    void 예상_분포_호출은_경로_호출보다_긴_자기_시간_제한을_쓴다() {
        engine.respondFixture("/v1/paths", 200, "paths-response-m2.json")
                .respondFixture("/v1/projections", 200, "projection-response.json").delay(Duration.ofMillis(700));
        EngineClient client = client(engine.url(), FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(EngineUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.TIMEOUT));
        assertThat(client.createProjection(EngineHarness.projectionRequest()).portfolios()).hasSize(2);
    }

    @Test
    void 국면_호출은_실제_전송으로_나가고_자기_시간_제한을_쓴다() {
        engine.respondFixture("/v1/paths", 200, "paths-response-m2.json")
                .respondFixture("/v1/regime/evaluate", 200, "regime-response.json").delay(Duration.ofMillis(700));
        EngineClient client = client(engine.url(), FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofSeconds(15),
                Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(EngineUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.TIMEOUT));
        assertThat(client.evaluateRegime(EngineHarness.regimeRequest()).months()).hasSize(2);
        assertThat(engine.requests().getLast().header(EngineClient.TOKEN_HEADER)).containsExactly(FAKE_TOKEN);
    }

    @Test
    void 국면_호출이_자기_시간_제한을_넘기면_불가용이고_다른_제한은_따르지_않는다() {
        engine.respondFixture("/v1/regime/evaluate", 200, "regime-response.json").delay(Duration.ofMillis(1500));
        EngineClient client = client(engine.url(), FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMillis(300));

        assertThatThrownBy(() -> client.evaluateRegime(EngineHarness.regimeRequest()))
                .isInstanceOfSatisfying(EngineUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.TIMEOUT));
    }

    @Test
    void 연결이_거부되면_불가용이다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        EngineClient client = client("http://127.0.0.1:" + closedPort, FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofSeconds(15));

        assertThatThrownBy(() -> client.createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(EngineUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.UNREACHABLE));
    }

    @Test
    void URL_이나_토큰이_비면_요청을_보내지_않고_불가용이다() {
        EngineClient noUrl = client("", FAKE_TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15));
        EngineClient noToken = client(engine.url(), "", Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(15));

        for (EngineClient blank : new EngineClient[] { noUrl, noToken }) {
            assertThatThrownBy(() -> blank.createProjection(EngineHarness.projectionRequest()))
                    .isInstanceOfSatisfying(EngineUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(EngineUnavailableException.Reason.NOT_CONFIGURED));
            assertThatThrownBy(blank::info).isInstanceOf(EngineUnavailableException.class);
        }
        assertThat(engine.requests()).isEmpty();
    }

    @Test
    void 리다이렉트는_따라가지_않아_다른_경로가_불리지_않는다() {
        engine.redirect("/v1/paths", engine.url() + "/elsewhere").respond("/elsewhere", 200, "application/json", "{}");

        assertThatThrownBy(() -> client().createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
        assertThat(engine.hits("/elsewhere")).isZero();
        assertThat(engine.hits("/v1/paths")).isEqualTo(1);
    }

    @Test
    void 시스템_프록시_설정이_있어도_프록시를_거치지_않는다() {
        String[] keys = { "http.proxyHost", "http.proxyPort", "http.nonProxyHosts" };
        String[] before = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            before[i] = System.getProperty(keys[i]);
        }
        try (FakeEngineServer proxy = FakeEngineServer.start()) {
            engine.respondFixture("/v1/info", 200, "info-response.json");
            System.setProperty("http.proxyHost", "127.0.0.1");
            System.setProperty("http.proxyPort", proxy.url().substring(proxy.url().lastIndexOf(':') + 1));
            // the default exclusion list holds loopback addresses; replace it so a proxy-using client would go through
            System.setProperty("http.nonProxyHosts", "nothing.invalid");

            assertThat(client().info().service()).isEqualTo("antlab-engine");

            assertThat(engine.hits("/v1/info")).isEqualTo(1);
            assertThat(proxy.requests()).isEmpty();
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (before[i] == null) {
                    System.clearProperty(keys[i]);
                } else {
                    System.setProperty(keys[i], before[i]);
                }
            }
        }
    }

    @Test
    void 한도를_넘는_본문은_계약_밖이라_내부_오류다() {
        String huge = "x".repeat(EngineClient.MAX_BODY_BYTES + 10);
        engine.respond("/v1/paths", 200, "application/json", huge);

        assertThatThrownBy(() -> client().createPath(EngineHarness.pathRequest()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    void 토큰은_로그와_예외에_남지_않는다(CapturedOutput output) {
        engine.respondFixture("/v1/paths", 401, "problem-unauthenticated.json")
                .respondFixture("/v1/projections", 503, "problem-engine-busy.json");
        EngineClient client = client();

        assertThatThrownBy(() -> client.createPath(EngineHarness.pathRequest())).satisfies(e -> {
            assertThat(String.valueOf(e)).doesNotContain(FAKE_TOKEN);
            assertThat(e.getMessage()).doesNotContain(FAKE_TOKEN);
        });
        assertThatThrownBy(() -> client.createProjection(EngineHarness.projectionRequest())).satisfies(e -> {
            assertThat(String.valueOf(e)).doesNotContain(FAKE_TOKEN);
            assertThat(e.getMessage()).doesNotContain(FAKE_TOKEN);
        });
        assertThat(output.getAll()).doesNotContain(FAKE_TOKEN);
        assertThat(fixture("problem-engine-busy.json")).isNotEmpty();
    }
}
