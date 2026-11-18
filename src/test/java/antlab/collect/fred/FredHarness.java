package antlab.collect.fred;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.kis.Sleeper;
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
 * A FRED client wired to a mock server and a fake clock: no network, no real waiting. The fake key is obviously fake.
 * Files named real_* are answers of the 2026-11-18 shape probe, stored unchanged (FRED answers never echo the key).
 */
final class FredHarness {

    static final String FAKE_KEY = "FAKE-FRED-KEY-0001";
    static final String BASE = FredEndpoint.BASE_URI.toString();
    static final Instant START = Instant.parse("2026-11-18T10:00:00Z");

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
    }

    final AdvancingClock clock = new AdvancingClock(START);
    final RecordingSleeper sleeper = new RecordingSleeper(clock);
    final MockRestServiceServer server;
    final FredClientFactory factory;
    final FredProperties properties;

    FredHarness() {
        this(properties(1, 30, 100000));
    }

    FredHarness(FredProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.properties = properties;
        this.factory = new FredClientFactory(builder, FredEndpoint.BASE_URI, properties, clock, sleeper);
    }

    static FredProperties properties(int rps, int maxRequests, int pageLimit) {
        return new FredProperties(true, FAKE_KEY, rps, maxRequests, pageLimit, LocalDate.of(1990, 1, 1), 60, Duration.ofHours(2));
    }

    static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/fred", name + ".json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One FRED answer page as FRED shapes it: {@code rows} are complete observation objects. */
    static String page(int count, int offset, String... rows) {
        return "{\"realtime_start\":\"1776-07-04\",\"realtime_end\":\"9999-12-31\",\"count\":" + count + ",\"offset\":" + offset
                + ",\"limit\":100000,\"observations\":[" + String.join(",", rows) + "]}";
    }

    static String row(String date, String value, String realtimeStart, String realtimeEnd) {
        return "{\"realtime_start\":\"" + realtimeStart + "\",\"realtime_end\":\"" + realtimeEnd + "\",\"date\":\"" + date
                + "\",\"value\":\"" + value + "\"}";
    }

    ResponseActions expectObservations() {
        return server.expect(once(), requestTo(startsWith(BASE + FredEndpoint.OBSERVATIONS_PATH + "?")))
                .andExpect(method(HttpMethod.GET));
    }

    void respond(String body) {
        expectObservations().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    void respondFixture(String fixtureName) {
        respond(fixture(fixtureName));
    }
}
