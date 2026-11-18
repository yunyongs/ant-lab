package antlab.collect.fred;

import static antlab.collect.fred.FredHarness.FAKE_KEY;
import static antlab.collect.fred.FredHarness.page;
import static antlab.collect.fred.FredHarness.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.kis.SecretMasker;
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

/** FRED takes the key in the query string, so a URL anywhere would leak it: no failure path may carry one. */
@ExtendWith(OutputCaptureExtension.class)
class FredSecretLeakTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final String ECHO = "{\"error_code\":400,\"error_message\":\"echo api_key=" + FAKE_KEY + " key " + FAKE_KEY + "\"}";

    private final List<String> surfaces = new ArrayList<>();

    private void record(Throwable error) {
        surfaces.add(String.valueOf(error));
        surfaces.add(String.valueOf(error.getMessage()));
        assertThat(error.getCause()).as("no cause chain that could carry a URI").isNull();
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

    private void run(Consumer<FredHarness> setup) {
        var h = new FredHarness();
        setup.accept(h);
        attempt(() -> h.factory.newClient().observations("DGS2", START, false));
    }

    @Test
    void 실패_경로_어디에도_키가_로그와_예외와_문자열에_없다(CapturedOutput output) {
        run(h -> h.expectObservations().andRespond(withStatus(HttpStatus.BAD_REQUEST).body(ECHO).contentType(MediaType.APPLICATION_JSON)));
        run(h -> h.expectObservations().andRespond(withServerError().body(ECHO)));
        run(h -> h.expectObservations().andRespond(withSuccess(ECHO, MediaType.TEXT_PLAIN)));
        run(h -> h.respond(ECHO));
        run(h -> h.respond(page(1, 0, row("2025-01-01", FAKE_KEY, "2025-01-02", "9999-12-31"))));
        run(h -> {
            for (int i = 0; i <= DefaultFredSeriesClient.RATE_RETRY_WAITS.size(); i++) {
                h.expectObservations().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(ECHO).contentType(MediaType.APPLICATION_JSON));
            }
        });
        run(h -> h.expectObservations().andRespond(withException(new IOException("connect failed for " + FAKE_KEY))));
        run(h -> h.expectObservations().andRespond(withException(new IOException("GET https://api.stlouisfed.org/x?api_key=" + FAKE_KEY))));
        run(h -> h.expectObservations().andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.example/?api_key=" + FAKE_KEY)));
        var capped = new FredHarness(FredHarness.properties(1, 1, 1));
        capped.respond(page(3, 0, row("2025-01-01", "1", "2025-01-02", "9999-12-31")));
        attempt(() -> capped.factory.newClient().observations("DGS2", START, false));
        var forbidden = new FredAllowlistInterceptor(FredEndpoint.BASE_URI);
        attempt(() -> forbidden.check("GET", URI.create("https://api.stlouisfed.org/fred/series/updates?api_key=" + FAKE_KEY)));
        attempt(() -> forbidden.check("GET", URI.create("https://api.stlouisfed.org/fred/series/observations?api_key=" + FAKE_KEY + "&x=1")));

        assertThat(surfaces).isNotEmpty().anyMatch(s -> s.contains("FRED_") || s.contains("HTTP") || s.contains("refused"));
        assertThat(output.getAll()).as("the capture works: the rate-limit retries are logged").contains("rate limit hit");
        assertThat(String.join("\n", surfaces) + "\n" + output.getAll()).doesNotContain(FAKE_KEY);
    }

    @Test
    void 성공한_호출의_로그에도_키가_없다(CapturedOutput output) {
        var h = new FredHarness();
        h.expectObservations().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error_code\":429}")
                .contentType(MediaType.APPLICATION_JSON));
        h.respond(page(1, 0, row("2025-01-01", "1", "2025-01-02", "9999-12-31")));

        h.factory.newClient().observations("DGS2", START, false);

        assertThat(output.getAll()).contains("rate limit hit").doesNotContain(FAKE_KEY);
    }

    @Test
    void 설정_객체와_공장과_클라이언트의_문자열에는_키가_없다() {
        var h = new FredHarness();
        h.respond(page(1, 0, row("2025-01-01", "1", "2025-01-02", "9999-12-31")));
        var client = h.factory.newClient();
        client.observations("DGS2", START, false);

        String text = h.properties + " " + h.factory + " " + client;

        assertThat(text).doesNotContain(FAKE_KEY).contains("****");
    }

    @Test
    void 마스커는_알려준_키와_쿼리_모양의_키를_가린다() {
        var masker = new SecretMasker();
        masker.register(FAKE_KEY);

        String masked = masker.mask("url ?series_id=DGS2&api_key=zzzzzzzz&limit=1 and " + FAKE_KEY + " json {\"api_key\":\"unregistered\"}");

        assertThat(masked).doesNotContain(FAKE_KEY).doesNotContain("zzzzzzzz").doesNotContain("unregistered")
                .contains("series_id=DGS2").contains("limit=1").contains("****");
    }

    @Test
    void 시험_자료의_가짜_키는_눈에_띄는_가짜_표시를_가진다() {
        assertThat(FAKE_KEY).startsWith("FAKE-");
        for (String name : List.of("real_cpilfesl_2022_vintages", "real_cpilfesl_1990_first_vintage", "real_icsa_1990_first_vintage",
                "real_dgs2_2025_11", "real_icsa_series_meta", "real_cpilfesl_vintagedates_head")) {
            assertThat(FredHarness.fixture(name)).as(name).doesNotContain("api_key").doesNotContain(FAKE_KEY);
        }
    }
}
