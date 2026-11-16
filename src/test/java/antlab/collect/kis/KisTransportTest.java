package antlab.collect.kis;

import static antlab.collect.kis.KisHarness.FAKE_KEY;
import static antlab.collect.kis.KisHarness.FAKE_SECRET;
import static antlab.collect.kis.KisHarness.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.PriceBasis;
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

/** The real JDK transport against a local fake server: redirects are not followed, nothing else is reachable. */
class KisTransportTest {

    private HttpServer server;
    private URI base;
    private volatile boolean redirect;
    private final AtomicInteger elsewhereHits = new AtomicInteger();
    private final List<String> quoteRequests = new CopyOnWriteArrayList<>();
    private final List<String> quoteTrIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/oauth2/tokenP", exchange -> reply(exchange, 200, fixture("token_ok")));
        server.createContext(KisEndpoint.DOMESTIC_DAILY.path(), exchange -> {
            quoteRequests.add(exchange.getRequestURI().getRawQuery());
            quoteTrIds.add(exchange.getRequestHeaders().getFirst("tr_id"));
            if (redirect) {
                exchange.getResponseHeaders().add("Location", base + "/elsewhere");
                reply(exchange, 302, "");
            } else {
                reply(exchange, 200, fixture("kr_daily_p2"));
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

    private KisQuoteClient client() {
        var properties = new KisProperties(true, FAKE_KEY, FAKE_SECRET, "", 2, 60);
        return new KisQuoteClientFactory(KisClientConfig.transportBuilder(), base, properties, Clock.systemUTC(), duration -> {
        }).newClient();
    }

    @Test
    void 실제_JDK_전송으로_토큰과_일봉을_받는다() {
        var rows = client().domesticDaily("900001", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9), PriceBasis.RAW);

        assertThat(rows).hasSize(2);
        assertThat(quoteTrIds).containsExactly("FHKST03010100");
        assertThat(quoteRequests.getFirst()).contains("FID_ORG_ADJ_PRC=1").contains("FID_INPUT_ISCD=900001");
    }

    @Test
    void 리다이렉트는_따라가지_않아_다른_경로가_불리지_않는다() {
        redirect = true;

        assertThatThrownBy(() -> client().domesticDaily("900001", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9), PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> {
                    assertThat(e.code()).isEqualTo("KIS_HTTP");
                    assertThat(e.getMessage()).contains("HTTP 302").doesNotContain("elsewhere");
                });
        assertThat(elsewhereHits.get()).isZero();
    }

    @Test
    void 운영_공장의_주소는_실전_도메인_하나로_고정이다() {
        assertThat(KisEndpoint.BASE_URI).hasToString("https://openapi.koreainvestment.com:9443");
        assertThat(KisEndpoint.values()).extracting(KisEndpoint::path)
                .containsExactly("/oauth2/tokenP", "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
                        "/uapi/overseas-price/v1/quotations/dailyprice");
        assertThat(KisEndpoint.values()).extracting(KisEndpoint::trId).containsExactly(null, "FHKST03010100", "HHDFS76240000");
    }
}
