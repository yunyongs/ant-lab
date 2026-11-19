package antlab.collect.kis;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.observability.CollectMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

/** A KIS client wired to a mock server and a fake clock: no network, no real waiting. Only synthetic FAKE values. */
final class KisHarness {

    static final String FAKE_KEY = "FAKE-APP-KEY-0001";
    static final String FAKE_SECRET = "FAKE-APP-SECRET-0001";
    static final String FAKE_TOKEN = "FAKE-ACCESS-TOKEN-0001";
    static final String BASE = KisEndpoint.BASE_URI.toString();
    static final Instant START = Instant.parse("2026-01-12T00:00:00Z");

    /** A clock that moves only when told to; the sleeper below tells it. */
    static final class AdvancingClock extends Clock {
        private Instant now;

        AdvancingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class RecordingSleeper implements Sleeper {
        private final AdvancingClock clock;
        final List<Duration> sleeps = new ArrayList<>();

        RecordingSleeper(AdvancingClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
            clock.advance(duration);
        }

        Duration total() {
            return sleeps.stream().reduce(Duration.ZERO, Duration::plus);
        }
    }

    final AdvancingClock clock = new AdvancingClock(START);
    final RecordingSleeper sleeper = new RecordingSleeper(clock);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CollectMetrics metrics = new CollectMetrics(registry);
    final MockRestServiceServer server;
    final KisQuoteClientFactory factory;
    final KisProperties properties;

    KisHarness() {
        this(properties("", 2, 60));
    }

    KisHarness(RestClient.Builder builder) {
        this(properties("", 2, 3), builder);
    }

    KisHarness(KisProperties properties) {
        this(properties, RestClient.builder());
    }

    KisHarness(KisProperties properties, RestClient.Builder builder) {
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.properties = properties;
        this.factory = new KisQuoteClientFactory(builder, KisEndpoint.BASE_URI, properties, clock, sleeper, metrics);
    }

    static KisProperties properties(String tokenDir, int rps, int maxRequests) {
        return new KisProperties(true, FAKE_KEY, FAKE_SECRET, tokenDir, rps, maxRequests);
    }

    static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/kis", name + ".json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ResponseActions expectToken() {
        return server.expect(once(), requestTo(BASE + KisEndpoint.TOKEN.path())).andExpect(method(HttpMethod.POST));
    }

    void expectTokenOk() {
        expectToken().andRespond(withSuccess(fixture("auth_ok"), MediaType.APPLICATION_JSON));
    }

    ResponseActions expectGet(KisEndpoint endpoint) {
        return server.expect(once(), requestTo(startsWith(BASE + endpoint.path() + "?")))
                .andExpect(method(HttpMethod.GET)).andExpect(header("tr_id", endpoint.trId()));
    }

    void respondDomestic(String fixtureName) {
        expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture(fixtureName), MediaType.APPLICATION_JSON));
    }

    void respondOverseas(String fixtureName) {
        expectGet(KisEndpoint.OVERSEAS_DAILY).andRespond(withSuccess(fixture(fixtureName), MediaType.APPLICATION_JSON));
    }
}
