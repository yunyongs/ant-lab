package antlab.collect.kis;

import static antlab.collect.kis.KisHarness.FAKE_KEY;
import static antlab.collect.kis.KisHarness.FAKE_SECRET;
import static antlab.collect.kis.KisHarness.FAKE_TOKEN;
import static antlab.collect.kis.KisHarness.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.PriceBasis;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@ExtendWith(OutputCaptureExtension.class)
class KisSecretLeakTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 5);
    private static final LocalDate TO = LocalDate.of(2026, 1, 9);
    private static final String ECHO = "{\"msg1\":\"echo appkey=" + FAKE_KEY + " appsecret=" + FAKE_SECRET + " Bearer " + FAKE_TOKEN
            + "\",\"access_token\":\"" + FAKE_TOKEN + "\"}";

    private final List<String> surfaces = new ArrayList<>();

    private void record(Throwable error) {
        surfaces.add(String.valueOf(error));
        surfaces.add(String.valueOf(error.getMessage()));
        assertThat(error.getCause()).as("no cause chain that could carry a URI or header").isNull();
        for (Throwable suppressed : error.getSuppressed()) {
            surfaces.add(String.valueOf(suppressed));
        }
    }

    private void attempt(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            record(e);
        }
    }

    private void assertClean(CapturedOutput output) {
        String everything = String.join("\n", surfaces) + "\n" + output.getAll();
        assertThat(everything).doesNotContain(FAKE_KEY).doesNotContain(FAKE_SECRET).doesNotContain(FAKE_TOKEN);
    }

    private void run(Consumer<KisHarness> setup) {
        var h = new KisHarness();
        setup.accept(h);
        attempt(() -> h.factory.newClient().domesticDaily("900001", FROM, TO, PriceBasis.ADJUSTED));
    }

    @Test
    void 실패_경로_어디에도_키_시크릿_토큰이_로그와_예외와_문자열에_없다(CapturedOutput output) {
        run(h -> {
            h.expectToken().andRespond(withStatus(HttpStatus.FORBIDDEN).body(ECHO).contentType(MediaType.APPLICATION_JSON));
            h.expectToken().andRespond(withStatus(HttpStatus.FORBIDDEN).body(ECHO).contentType(MediaType.APPLICATION_JSON));
        });
        run(h -> {
            h.expectTokenOk();
            h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withServerError().body(ECHO));
        });
        run(h -> {
            h.expectTokenOk();
            h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(ECHO, MediaType.TEXT_PLAIN));
        });
        run(h -> {
            h.expectTokenOk();
            h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(
                    "{\"rt_cd\":\"1\",\"msg_cd\":\"OPSQ0002\",\"msg1\":\"" + FAKE_TOKEN + "\"}", MediaType.APPLICATION_JSON));
        });
        run(h -> {
            h.expectTokenOk();
            for (int i = 0; i < 4; i++) {
                h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rate_limit"), MediaType.APPLICATION_JSON));
            }
        });
        run(h -> {
            h.expectToken().andRespond(withException(new IOException("connect failed for " + FAKE_KEY + " " + FAKE_SECRET)));
            h.expectToken().andRespond(withException(new IOException("connect failed for " + FAKE_KEY + " " + FAKE_SECRET)));
        });
        run(h -> {
            h.expectTokenOk();
            h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withException(new IOException("reset " + FAKE_TOKEN)));
        });
        run(h -> {
            h.expectTokenOk();
            h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.example/?k=" + FAKE_KEY));
        });
        var capped = new KisHarness(KisHarness.properties("", 2, 1));
        capped.expectTokenOk();
        attempt(() -> capped.factory.newClient().domesticDaily("900001", FROM, TO, PriceBasis.RAW));
        var forbidden = new KisAllowlistInterceptor(KisEndpoint.BASE_URI);
        attempt(() -> forbidden.check("POST", URI.create("https://openapi.koreainvestment.com:9443/uapi/trading/order?appkey=" + FAKE_KEY), FAKE_TOKEN));

        assertThat(surfaces).isNotEmpty().anyMatch(s -> s.contains("KIS_") || s.contains("HTTP") || s.contains("refused"));
        assertThat(output.getAll()).as("the capture works: the rate-limit retries are logged").contains("rate limit hit");
        assertClean(output);
    }

    @Test
    void 성공한_호출의_로그에도_비밀이_없다(CapturedOutput output) {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rate_limit"), MediaType.APPLICATION_JSON));
        h.respondDomestic("kr_daily_p2");

        h.factory.newClient().domesticDaily("900001", FROM, TO, PriceBasis.RAW);

        assertClean(output);
    }

    @Test
    void 설정_객체와_토큰_객체와_공장의_문자열에는_비밀이_없다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.respondDomestic("kr_daily_p2");
        var client = h.factory.newClient();
        client.domesticDaily("900001", FROM, TO, PriceBasis.RAW);

        String text = h.properties + " " + h.factory + " " + client + " " + new StoredToken(FAKE_TOKEN, 1L);

        assertThat(text).doesNotContain(FAKE_KEY).doesNotContain(FAKE_SECRET).doesNotContain(FAKE_TOKEN).contains("****");
    }

    @Test
    void 마스커는_알려준_값과_머리글과_JSON_값을_가린다() {
        var masker = new SecretMasker();
        masker.register(FAKE_KEY);
        masker.register(FAKE_TOKEN);

        String masked = masker.mask("key " + FAKE_KEY + " header Authorization: Bearer some-unknown.token_value "
                + "json {\"appsecret\":\"unregistered-secret\"} query access_token=zzz&x=1 and " + FAKE_TOKEN);

        assertThat(masked).doesNotContain(FAKE_KEY).doesNotContain(FAKE_TOKEN).doesNotContain("some-unknown")
                .doesNotContain("unregistered-secret").doesNotContain("zzz").contains("****").contains("x=1");
    }

    @Test
    void 마스커는_짧은_값을_등록하지_않아_평범한_글자를_지우지_않는다() {
        var masker = new SecretMasker();
        masker.register("ab");
        masker.register(null);

        assertThat(masker.mask("about")).isEqualTo("about");
    }

    @Test
    void 시험_자료의_가짜_비밀은_눈에_띄는_가짜_표시를_가진다() {
        assertThat(List.of(FAKE_KEY, FAKE_SECRET, FAKE_TOKEN)).allMatch(s -> s.startsWith("FAKE-"));
        assertThat(fixture("token_ok")).contains("FAKE").contains("\"_fixture\"");
    }
}
