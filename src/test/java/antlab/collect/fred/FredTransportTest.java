package antlab.collect.fred;

import static antlab.collect.fred.FredHarness.FAKE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.kis.KisClientConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The real JDK transport against a local fake server: redirects are not followed, nothing else is reachable. */
@ExtendWith(OutputCaptureExtension.class)
class FredTransportTest {

    private HttpServer server;
    private URI base;
    private volatile boolean redirect;
    private final AtomicInteger elsewhereHits = new AtomicInteger();
    private final List<String> queries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(FredEndpoint.OBSERVATIONS_PATH, exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            if (redirect) {
                exchange.getResponseHeaders().add("Location", base + "/elsewhere");
                reply(exchange, 302, "");
            } else {
                reply(exchange, 200, FredHarness.fixture("real_dgs2_2025_11"));
            }
        });
        server.createContext("/elsewhere", exchange -> {
            elsewhereHits.incrementAndGet();
            reply(exchange, 200, "{}");
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private FredSeriesClient client() {
        return new FredClientFactory(KisClientConfig.transportBuilder(), base, FredHarness.properties(1, 30, 100000),
                Clock.systemUTC(), duration -> {
                }).newClient();
    }

    @Test
    void 실제_JDK_전송으로_관측값을_받고_키는_쿼리로만_간다() {
        var rows = client().observations("DGS2", LocalDate.of(2025, 11, 3), false);

        assertThat(rows).hasSize(12);
        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst()).contains("api_key=" + FAKE_KEY).contains("series_id=DGS2").contains("file_type=json")
                .contains("observation_start=2025-11-03").doesNotContain("realtime");
    }

    @Test
    void 리다이렉트는_따라가지_않아_다른_경로가_불리지_않는다() {
        redirect = true;

        assertThatThrownBy(() -> client().observations("DGS2", LocalDate.of(2025, 11, 3), false))
                .isInstanceOfSatisfying(FredException.class, e -> {
                    assertThat(e.code()).isEqualTo("FRED_HTTP");
                    assertThat(e.getMessage()).contains("HTTP 302").doesNotContain("elsewhere").doesNotContain(FAKE_KEY);
                });
        assertThat(elsewhereHits.get()).isZero();
    }

    @Test
    void 허용_목록은_진짜_전송_앞에서도_다른_경로를_막는다() {
        var factory = new FredClientFactory(KisClientConfig.transportBuilder(), base, FredHarness.properties(1, 30, 100000),
                Clock.systemUTC(), duration -> {
                });
        var chain = factory.restClientWith(new FredRequestGate(new antlab.collect.kis.MinIntervalRateLimiter(Clock.systemUTC(), duration -> {
        }, 1), 5));

        assertThatThrownBy(() -> chain.get().uri("/elsewhere").retrieve().toBodilessEntity()).isInstanceOf(FredForbiddenRequestException.class);
        assertThat(elsewhereHits.get()).isZero();
    }

    @Test
    void 웹과_HTTP_로거를_DEBUG로_올려도_키가_출력에_없다(CapturedOutput output) {
        var loggers = List.of("org.springframework.web", "org.springframework.http", "org.springframework.web.client",
                "jdk.internal.net.http", "jdk.httpclient.HttpClient");
        var previous = new java.util.HashMap<String, ch.qos.logback.classic.Level>();
        for (String name : loggers) {
            var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(name);
            previous.put(name, logger.getLevel());
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        }
        try {
            client().observations("DGS2", LocalDate.of(2025, 11, 3), false);
        } finally {
            previous.forEach((name, level) -> ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(name)).setLevel(level));
        }

        assertThat(output.getAll()).doesNotContain(FAKE_KEY);
    }

    @Test
    void 운영_클라이언트는_관측_등록기_없는_전송_빌더로만_만든다() throws IOException {
        String config = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/antlab/collect/fred/FredJobConfig.java"));

        assertThat(config).contains("KisClientConfig.transportBuilder()").doesNotContain("RestClient.builder()")
                .doesNotContain("observationRegistry");
    }
}
