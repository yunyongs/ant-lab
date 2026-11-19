package antlab.collect.fred;

import static antlab.collect.fred.FredHarness.page;
import static antlab.collect.fred.FredHarness.row;
import static antlab.support.MetricCounts.count;
import static antlab.support.MetricCounts.total;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import antlab.observability.CollectMetrics;
import io.micrometer.core.instrument.Meter;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** antlab.collect.request for FRED: each request attempt moves exactly one series, at the point its fate is known. */
class FredRequestMetricsTest {

    private static final LocalDate START = LocalDate.of(2025, 11, 1);
    private static final String NAME = "antlab.collect.request";
    private static final String ROW = row("2025-11-17", "3.6", "2026-11-18", "2026-11-18");

    private static double outcome(FredHarness h, String outcome) {
        return count(h.registry, NAME, "source", "FRED", "outcome", outcome);
    }

    private static void failsWith(FredHarness h, String code) {
        assertThatThrownBy(() -> h.factory.newClient().observations("DGS2", START, false))
                .isInstanceOfSatisfying(FredException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void 성공한_요청_하나는_OK를_한_번만_센다() {
        var h = new FredHarness();
        h.respond(page(1, 0, ROW));

        h.factory.newClient().observations("DGS2", START, false);

        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(1);
    }

    @Test
    void 쪽이_둘이면_요청_둘을_센다() {
        var h = new FredHarness();
        h.respond(page(2, 0, ROW));
        h.respond(page(2, 1, row("2025-11-18", "3.7", "2026-11-18", "2026-11-18")));

        h.factory.newClient().observations("DGS2", START, false);

        assertThat(outcome(h, "OK")).isEqualTo(2);
        assertThat(total(h.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 속도_제한_뒤_재시도는_RATE_RETRY와_마지막_OK로_센다() {
        var h = new FredHarness();
        for (int i = 0; i < 2; i++) {
            h.expectObservations().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error_code\":429}")
                    .contentType(MediaType.APPLICATION_JSON));
        }
        h.respond(page(1, 0, ROW));

        h.factory.newClient().observations("DGS2", START, false);

        assertThat(outcome(h, "RATE_RETRY")).isEqualTo(2);
        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(3);
    }

    @Test
    void 속도_제한이_계속되면_재시도_셋과_FRED_RATE_LIMIT_하나다() {
        var h = new FredHarness();
        for (int i = 0; i <= DefaultFredSeriesClient.RATE_RETRY_WAITS.size(); i++) {
            h.expectObservations().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error_code\":429}")
                    .contentType(MediaType.APPLICATION_JSON));
        }

        failsWith(h, "FRED_RATE_LIMIT");

        assertThat(outcome(h, "RATE_RETRY")).isEqualTo(3);
        assertThat(outcome(h, "FRED_RATE_LIMIT")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(4);
    }

    @Test
    void 실패한_요청은_그_코드_하나만_세고_OK는_세지_않는다() {
        var server = new FredHarness();
        server.expectObservations().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("oops"));
        failsWith(server, "FRED_HTTP");
        assertThat(outcome(server, "FRED_HTTP")).isEqualTo(1);
        assertThat(total(server.registry, NAME)).isEqualTo(1);

        var rejected = new FredHarness();
        rejected.respond("{\"error_code\":400,\"error_message\":\"x\"}");
        failsWith(rejected, "FRED_REJECTED");
        assertThat(outcome(rejected, "FRED_REJECTED")).isEqualTo(1);
        assertThat(total(rejected.registry, NAME)).isEqualTo(1);

        var notJson = new FredHarness();
        notJson.respond("not json");
        failsWith(notJson, "FRED_PARSE");
        assertThat(outcome(notJson, "FRED_PARSE")).isEqualTo(1);
        assertThat(total(notJson.registry, NAME)).isEqualTo(1);
    }

    @Test
    void 받았지만_읽지_못한_쪽은_OK_없이_FRED_PARSE_하나다() {
        var h = new FredHarness();
        h.respond(page(1, 0, "{\"realtime_start\":\"2026-11-18\",\"realtime_end\":\"2026-11-18\",\"date\":\"2025-11-17\",\"value\":3.6}"));

        failsWith(h, "FRED_PARSE");

        assertThat(outcome(h, "FRED_PARSE")).isEqualTo(1);
        assertThat(outcome(h, "OK")).isZero();
        assertThat(total(h.registry, NAME)).isEqualTo(1);
    }

    @Test
    void 앞_쪽은_OK_뒤_쪽의_읽기_실패는_FRED_PARSE로_따로_센다() {
        var h = new FredHarness();
        h.respond(page(2, 0, ROW));
        h.respond(page(2, 1));

        failsWith(h, "FRED_PARSE");

        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(outcome(h, "FRED_PARSE")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 요청_상한에_막힌_시도는_REQUEST_CAP으로_센다() {
        var h = new FredHarness(FredHarness.properties(1, 1, 100000));
        h.respond(page(2, 0, ROW));

        failsWith(h, "REQUEST_CAP");

        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(outcome(h, "REQUEST_CAP")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 태그_값은_고정_목록_안에서만_나온다() {
        var h = new FredHarness();
        h.respond("{\"error_code\":400}");
        failsWith(h, "FRED_REJECTED");

        for (Meter meter : h.registry.find(NAME).meters()) {
            assertThat(meter.getId().getTags()).extracting(t -> t.getKey()).containsExactlyInAnyOrder("source", "outcome");
            assertThat(meter.getId().getTag("outcome")).isIn(CollectMetrics.outcomes(
                    CollectMetrics.Source.valueOf(meter.getId().getTag("source"))));
        }
    }
}
