package antlab.collect.ecos;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.kis.Sleeper;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

/**
 * An ECOS client wired to a mock server and a fake clock: no network, no real waiting. The fake key is obviously fake.
 * Files named real_* are answers of the 2026-11-19 shape probe, stored as ECOS sent them (the key is never in an answer).
 */
final class EcosHarness {

    static final String FAKE_KEY = "FAKEECOSKEY0000001";
    static final String BASE = EcosEndpoint.BASE_URI.toString();
    static final Instant START = Instant.parse("2026-11-19T10:00:00Z");

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
    }

    final AdvancingClock clock = new AdvancingClock(START);
    final RecordingSleeper sleeper = new RecordingSleeper(clock);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CollectMetrics metrics = new CollectMetrics(registry);
    final MockRestServiceServer server;
    final EcosClientFactory factory;
    final EcosProperties properties;

    EcosHarness() {
        this(properties(1, 20, 100000));
    }

    EcosHarness(EcosProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.properties = properties;
        this.factory = new EcosClientFactory(builder, EcosEndpoint.BASE_URI, properties, clock, sleeper, metrics);
    }

    static EcosProperties properties(int rps, int maxRequests, int pageLimit) {
        return new EcosProperties(true, FAKE_KEY, rps, maxRequests, pageLimit, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2));
    }

    static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/ecos", name + ".json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One ECOS answer as ECOS shapes it: {@code rows} are complete row objects. */
    static String answer(int total, String... rows) {
        return "{\"StatisticSearch\":{\"list_total_count\":" + total + ",\"row\":[" + String.join(",", rows) + "]}}";
    }

    static String row(String stat, String item, String time, String value) {
        return "{\"STAT_CODE\":\"" + stat + "\",\"STAT_NAME\":\"x\",\"ITEM_CODE1\":\"" + item + "\",\"ITEM_NAME1\":\"y\","
                + "\"ITEM_CODE2\":null,\"ITEM_NAME2\":null,\"ITEM_CODE3\":null,\"ITEM_NAME3\":null,\"ITEM_CODE4\":null,"
                + "\"ITEM_NAME4\":null,\"UNIT_NAME\":\"z\",\"WGT\":null,\"TIME\":\"" + time + "\",\"DATA_VALUE\":"
                + (value == null ? "null" : "\"" + value + "\"") + "}";
    }

    static String result(String code) {
        return "{\"RESULT\":{\"CODE\":\"" + code + "\",\"MESSAGE\":\"text that must never be copied, key " + FAKE_KEY + "\"}}";
    }

    /** The exact path the client must ask for. */
    static String path(int from, int to, String stat, String cycle, String start, String end, String item) {
        return "/api/StatisticSearch/" + FAKE_KEY + "/json/kr/" + from + "/" + to + "/" + stat + "/" + cycle + "/" + start + "/" + end
                + "/" + item;
    }

    ResponseActions expectSearch() {
        return server.expect(once(), requestTo(startsWith(BASE + "/api/StatisticSearch/"))).andExpect(method(HttpMethod.GET));
    }

    ResponseActions expectPath(String path) {
        return server.expect(once(), requestTo(BASE + path)).andExpect(method(HttpMethod.GET));
    }

    void respond(String body) {
        expectSearch().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    void respondFixture(String fixtureName) {
        respond(fixture(fixtureName));
    }
}
