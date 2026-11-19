package antlab.collect.ecos;

import static antlab.collect.ecos.EcosHarness.answer;
import static antlab.collect.ecos.EcosHarness.result;
import static antlab.collect.ecos.EcosHarness.row;
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

/** antlab.collect.request for ECOS: each request attempt moves exactly one series, at the point its fate is known. */
class EcosRequestMetricsTest {

    private static final LocalDate D1 = LocalDate.of(2025, 11, 3);
    private static final LocalDate D2 = LocalDate.of(2025, 11, 18);
    private static final String NAME = "antlab.collect.request";
    private static final String ROW = row("817Y002", "010200000", "20251103", "2.7");

    private static double outcome(EcosHarness h, String outcome) {
        return count(h.registry, NAME, "source", "ECOS", "outcome", outcome);
    }

    private static void failsWith(EcosHarness h, String code) {
        assertThatThrownBy(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2))
                .isInstanceOfSatisfying(EcosException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void 성공한_요청_하나는_OK를_한_번만_센다() {
        var h = new EcosHarness();
        h.respond(answer(1, ROW));

        h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2);

        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(1);
    }

    @Test
    void 자료_없음_응답도_이해한_답이라_OK_하나다() {
        var h = new EcosHarness();
        h.respond(result("INFO-200"));

        assertThat(h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2)).isEmpty();

        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(1);
    }

    @Test
    void 쪽이_둘이면_요청_둘을_센다() {
        var h = new EcosHarness();
        h.respond(answer(2, ROW));
        h.respond(answer(2, row("817Y002", "010200000", "20251104", "2.8")));

        h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2);

        assertThat(outcome(h, "OK")).isEqualTo(2);
        assertThat(total(h.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 속도_제한_뒤_재시도는_RATE_RETRY와_마지막_OK로_센다() {
        var h = new EcosHarness();
        for (int i = 0; i < 2; i++) {
            h.expectSearch().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}").contentType(MediaType.APPLICATION_JSON));
        }
        h.respond(answer(1, ROW));

        h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2);

        assertThat(outcome(h, "RATE_RETRY")).isEqualTo(2);
        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(3);
    }

    @Test
    void 속도_제한이_계속되면_재시도_셋과_ECOS_RATE_LIMIT_하나다() {
        var h = new EcosHarness();
        for (int i = 0; i <= DefaultEcosSeriesClient.RATE_RETRY_WAITS.size(); i++) {
            h.expectSearch().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}").contentType(MediaType.APPLICATION_JSON));
        }

        failsWith(h, "ECOS_RATE_LIMIT");

        assertThat(outcome(h, "RATE_RETRY")).isEqualTo(3);
        assertThat(outcome(h, "ECOS_RATE_LIMIT")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(4);
    }

    @Test
    void 실패한_요청은_그_코드_하나만_세고_OK는_세지_않는다() {
        var auth = new EcosHarness();
        auth.respond(result("INFO-100"));
        failsWith(auth, "ECOS_AUTH");
        assertThat(outcome(auth, "ECOS_AUTH")).isEqualTo(1);
        assertThat(total(auth.registry, NAME)).isEqualTo(1);

        var rejected = new EcosHarness();
        rejected.respond(result("ERROR-101"));
        failsWith(rejected, "ECOS_REJECTED");
        assertThat(outcome(rejected, "ECOS_REJECTED")).isEqualTo(1);
        assertThat(total(rejected.registry, NAME)).isEqualTo(1);

        var server = new EcosHarness();
        server.expectSearch().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("oops"));
        failsWith(server, "ECOS_HTTP");
        assertThat(outcome(server, "ECOS_HTTP")).isEqualTo(1);
        assertThat(total(server.registry, NAME)).isEqualTo(1);

        var notJson = new EcosHarness();
        notJson.respond("not json");
        failsWith(notJson, "ECOS_PARSE");
        assertThat(outcome(notJson, "ECOS_PARSE")).isEqualTo(1);
        assertThat(total(notJson.registry, NAME)).isEqualTo(1);
    }

    @Test
    void 받았지만_읽지_못한_쪽은_OK_없이_ECOS_PARSE_하나다() {
        var h = new EcosHarness();
        h.respond(answer(1, row("OTHER", "010200000", "20251103", "2.7")));

        failsWith(h, "ECOS_PARSE");

        assertThat(outcome(h, "ECOS_PARSE")).isEqualTo(1);
        assertThat(outcome(h, "OK")).isZero();
        assertThat(total(h.registry, NAME)).isEqualTo(1);
    }

    @Test
    void 요청_상한에_막힌_시도는_REQUEST_CAP으로_센다() {
        var h = new EcosHarness(EcosHarness.properties(1, 1, 100000));
        h.respond(answer(2, ROW));

        failsWith(h, "REQUEST_CAP");

        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(outcome(h, "REQUEST_CAP")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 태그_값은_고정_목록_안에서만_나온다() {
        var h = new EcosHarness();
        h.respond(result("weird text with " + EcosHarness.FAKE_KEY));
        failsWith(h, "ECOS_REJECTED");

        for (Meter meter : h.registry.find(NAME).meters()) {
            assertThat(meter.getId().getTags()).extracting(t -> t.getKey()).containsExactlyInAnyOrder("source", "outcome");
            assertThat(meter.getId().getTag("outcome")).isIn(CollectMetrics.outcomes(
                    CollectMetrics.Source.valueOf(meter.getId().getTag("source"))));
        }
    }
}
