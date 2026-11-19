package antlab.collect.kis;

import static antlab.collect.kis.KisHarness.fixture;
import static antlab.support.MetricCounts.count;
import static antlab.support.MetricCounts.total;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.PriceBasis;
import antlab.observability.CollectMetrics;
import io.micrometer.core.instrument.Meter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** antlab.collect.request for KIS: a token request and a quote request are each one attempt with one outcome. */
class KisRequestMetricsTest {

    private static final LocalDate D05 = LocalDate.of(2026, 1, 5);
    private static final LocalDate D09 = LocalDate.of(2026, 1, 9);
    private static final String NAME = "antlab.collect.request";

    private static double outcome(KisHarness h, String outcome) {
        return count(h.registry, NAME, "source", "KIS", "outcome", outcome);
    }

    private static void failsWith(KisHarness h, String code) {
        assertThatThrownBy(() -> h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void 토큰_요청과_시세_요청은_각각_OK_하나다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.respondDomestic("kr_daily_p2");

        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThat(outcome(h, "OK")).isEqualTo(2);
        assertThat(total(h.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 이미_받은_토큰을_다시_쓰면_토큰_요청을_더_세지_않는다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.respondDomestic("kr_daily_p2");
        h.respondDomestic("kr_daily_p2");
        var client = h.factory.newClient();

        client.domesticDaily("900001", D05, D09, PriceBasis.RAW);
        client.domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThat(outcome(h, "OK")).isEqualTo(3);
        assertThat(total(h.registry, NAME)).isEqualTo(3);
    }

    @Test
    void 호출_한도_코드_뒤_재시도는_RATE_RETRY와_마지막_OK로_센다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rate_limit"), MediaType.APPLICATION_JSON));
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rate_limit"), MediaType.APPLICATION_JSON));
        h.respondDomestic("kr_daily_p2");

        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThat(outcome(h, "RATE_RETRY")).isEqualTo(2);
        assertThat(outcome(h, "OK")).isEqualTo(2);
        assertThat(total(h.registry, NAME)).isEqualTo(4);
    }

    @Test
    void 호출_한도가_계속되면_재시도_셋과_KIS_RATE_LIMIT_하나다() {
        var h = new KisHarness();
        h.expectTokenOk();
        for (int i = 0; i < 4; i++) {
            h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rate_limit"), MediaType.APPLICATION_JSON));
        }

        failsWith(h, "KIS_RATE_LIMIT");

        assertThat(outcome(h, "RATE_RETRY")).isEqualTo(3);
        assertThat(outcome(h, "KIS_RATE_LIMIT")).isEqualTo(1);
        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(5);
    }

    @Test
    void 만료된_토큰은_TOKEN_RETRY_하나와_새_토큰_OK로_센다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("auth_expired"), MediaType.APPLICATION_JSON));
        h.expectTokenOk();
        h.respondDomestic("kr_daily_p2");

        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThat(outcome(h, "TOKEN_RETRY")).isEqualTo(1);
        assertThat(outcome(h, "OK")).isEqualTo(3);
        assertThat(total(h.registry, NAME)).isEqualTo(4);
    }

    @Test
    void 토큰이_또_거절되면_TOKEN_RETRY_하나_뒤_KIS_REJECTED_하나다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("auth_expired"), MediaType.APPLICATION_JSON));
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("auth_expired"), MediaType.APPLICATION_JSON));

        failsWith(h, "KIS_REJECTED");

        assertThat(outcome(h, "TOKEN_RETRY")).isEqualTo(1);
        assertThat(outcome(h, "KIS_REJECTED")).isEqualTo(1);
        assertThat(outcome(h, "OK")).isEqualTo(2);
        assertThat(total(h.registry, NAME)).isEqualTo(4);
    }

    @Test
    void 토큰_캐시를_지우다_실패해도_그_요청은_한_번만_센다(@TempDir Path dir) throws IOException {
        // a read-only directory does not stop root or a non-POSIX file system
        assumeTrue(!"root".equals(System.getProperty("user.name")) && dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        long expiresAt = KisHarness.START.getEpochSecond() + 86_400;
        Files.writeString(dir.resolve(FileKisTokenStore.FILE_NAME),
                "{\"access_token\":\"" + KisHarness.FAKE_TOKEN + "\",\"expires_at\":" + expiresAt + "}");
        var h = new KisHarness(KisHarness.properties(dir.toString(), 2, 60));
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("auth_expired"), MediaType.APPLICATION_JSON));
        var client = h.factory.newClient();
        // the token comes from the cache file; a read-only directory makes its removal fail with KIS_HTTP
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThatThrownBy(() -> client.domesticDaily("900001", D05, D09, PriceBasis.RAW))
                    .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_HTTP"));
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertThat(outcome(h, "TOKEN_RETRY")).isEqualTo(1);
        assertThat(outcome(h, "KIS_HTTP")).as("the failed cache removal is not a request outcome").isZero();
        assertThat(outcome(h, "OK")).isZero();
        assertThat(total(h.registry, NAME)).isEqualTo(1);
    }

    @Test
    void 토큰_발급이_두_번_실패하면_토큰_요청마다_KIS_REJECTED를_센다() {
        var h = new KisHarness();
        for (int i = 0; i < 2; i++) {
            h.expectToken().andRespond(withStatus(HttpStatus.FORBIDDEN).body(fixture("auth_fail")).contentType(MediaType.APPLICATION_JSON));
        }

        failsWith(h, "KIS_REJECTED");

        assertThat(outcome(h, "KIS_REJECTED")).isEqualTo(2);
        assertThat(total(h.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 실패한_시세_요청은_그_코드_하나만_센다() {
        var rejected = new KisHarness();
        rejected.expectTokenOk();
        rejected.respondDomestic("rejected");
        failsWith(rejected, "KIS_REJECTED");
        assertThat(outcome(rejected, "KIS_REJECTED")).isEqualTo(1);
        assertThat(total(rejected.registry, NAME)).isEqualTo(2);

        var server = new KisHarness();
        server.expectTokenOk();
        server.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withServerError().body("<html>FAKE gateway page</html>"));
        failsWith(server, "KIS_HTTP");
        assertThat(outcome(server, "KIS_HTTP")).isEqualTo(1);
        assertThat(total(server.registry, NAME)).isEqualTo(2);

        var notJson = new KisHarness();
        notJson.expectTokenOk();
        notJson.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess("not json FAKE text", MediaType.TEXT_PLAIN));
        failsWith(notJson, "KIS_PARSE");
        assertThat(outcome(notJson, "KIS_PARSE")).isEqualTo(1);
        assertThat(total(notJson.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 받았지만_읽지_못한_쪽은_OK_없이_KIS_PARSE_하나다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("bad_close"), MediaType.APPLICATION_JSON));

        failsWith(h, "KIS_PARSE");

        assertThat(outcome(h, "KIS_PARSE")).isEqualTo(1);
        assertThat(outcome(h, "OK")).isEqualTo(1);
        assertThat(total(h.registry, NAME)).isEqualTo(2);
    }

    @Test
    void 요청_상한에_막힌_시도는_REQUEST_CAP으로_센다() {
        var h = new KisHarness(KisHarness.properties("", 2, 2));
        h.expectTokenOk();
        h.respondDomestic("kr_daily_p2");
        var client = h.factory.newClient();
        client.domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThatThrownBy(() -> client.domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("REQUEST_CAP"));
        assertThat(outcome(h, "REQUEST_CAP")).isEqualTo(1);
        assertThat(outcome(h, "OK")).isEqualTo(2);
        assertThat(total(h.registry, NAME)).isEqualTo(3);
    }

    @Test
    void 태그_값은_고정_목록_안에서만_나온다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.respondDomestic("rejected");
        failsWith(h, "KIS_REJECTED");

        for (Meter meter : h.registry.find(NAME).meters()) {
            assertThat(meter.getId().getTags()).extracting(t -> t.getKey()).containsExactlyInAnyOrder("source", "outcome");
            assertThat(meter.getId().getTag("outcome")).isIn(CollectMetrics.outcomes(
                    CollectMetrics.Source.valueOf(meter.getId().getTag("source"))));
        }
    }
}
