package antlab.collect.ecos;

import static antlab.collect.ecos.EcosHarness.FAKE_KEY;
import static antlab.collect.ecos.EcosHarness.answer;
import static antlab.collect.ecos.EcosHarness.result;
import static antlab.collect.ecos.EcosHarness.row;
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

/** ECOS takes the key in the URL path, so a URL anywhere would leak it: no failure path may carry one. */
@ExtendWith(OutputCaptureExtension.class)
class EcosSecretLeakTest {

    private static final LocalDate D1 = LocalDate.of(2025, 11, 3);
    private static final LocalDate D2 = LocalDate.of(2025, 11, 18);
    private static final String ECHO = "{\"RESULT\":{\"CODE\":\"ERROR-100\",\"MESSAGE\":\"echo key " + FAKE_KEY + " /api/StatisticSearch/"
            + FAKE_KEY + "/json\"}}";

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

    private void run(Consumer<EcosHarness> setup) {
        var h = new EcosHarness();
        setup.accept(h);
        attempt(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2));
    }

    @Test
    void 실패_경로_어디에도_키가_로그와_예외와_문자열에_없다(CapturedOutput output) {
        run(h -> h.expectSearch().andRespond(withStatus(HttpStatus.BAD_REQUEST).body(ECHO).contentType(MediaType.APPLICATION_JSON)));
        run(h -> h.expectSearch().andRespond(withServerError().body(ECHO)));
        run(h -> h.expectSearch().andRespond(withSuccess(ECHO, MediaType.TEXT_PLAIN)));
        run(h -> h.respond(ECHO));
        run(h -> h.respond(result("INFO-100")));
        run(h -> h.respond(answer(1, row("817Y002", "010200000", "20251103", FAKE_KEY))));
        run(h -> h.respond(answer(1, row(FAKE_KEY, "010200000", "20251103", "1"))));
        run(h -> {
            for (int i = 0; i <= DefaultEcosSeriesClient.RATE_RETRY_WAITS.size(); i++) {
                h.expectSearch().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(ECHO).contentType(MediaType.APPLICATION_JSON));
            }
        });
        run(h -> h.expectSearch().andRespond(withException(new IOException("connect failed for " + FAKE_KEY))));
        run(h -> h.expectSearch().andRespond(withException(new IOException("GET https://ecos.bok.or.kr/api/StatisticSearch/" + FAKE_KEY))));
        run(h -> h.expectSearch().andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.example/" + FAKE_KEY)));
        var capped = new EcosHarness(EcosHarness.properties(1, 1, 1));
        capped.respond(answer(3, row("817Y002", "010200000", "20251103", "1")));
        attempt(() -> capped.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2));
        var forbidden = new EcosAllowlistInterceptor(EcosEndpoint.BASE_URI);
        attempt(() -> forbidden.check("GET", URI.create("https://ecos.bok.or.kr/api/StatisticItemList/" + FAKE_KEY + "/json/kr/1/10/722Y001")));
        attempt(() -> forbidden.check("POST", URI.create("https://ecos.bok.or.kr/api/StatisticSearch/" + FAKE_KEY
                + "/json/kr/1/10/817Y002/D/20251103/20251118/010200000")));

        assertThat(surfaces).isNotEmpty().anyMatch(s -> s.contains("ECOS_") || s.contains("HTTP") || s.contains("refused"));
        assertThat(output.getAll()).as("the capture works: the rate-limit retries are logged").contains("rate limit hit");
        assertThat(String.join("\n", surfaces) + "\n" + output.getAll()).doesNotContain(FAKE_KEY);
    }

    @Test
    void 프로젝트_로거를_DEBUG로_올려도_키가_출력에_없다(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("antlab");
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            run(h -> h.respond(ECHO));
            var ok = new EcosHarness();
            ok.respond(answer(1, row("817Y002", "010200000", "20251103", "1")));
            ok.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2);
        } finally {
            logger.setLevel(previous);
        }

        assertThat(output.getAll() + String.join("\n", surfaces)).doesNotContain(FAKE_KEY);
    }

    @Test
    void 성공한_호출의_로그에도_키가_없다(CapturedOutput output) {
        var h = new EcosHarness();
        h.expectSearch().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}").contentType(MediaType.APPLICATION_JSON));
        h.respond(answer(1, row("817Y002", "010200000", "20251103", "1")));

        h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2);

        assertThat(output.getAll()).contains("rate limit hit").doesNotContain(FAKE_KEY);
    }

    @Test
    void 설정_객체와_공장과_클라이언트의_문자열에는_키가_없다() {
        var h = new EcosHarness();
        h.respond(answer(1, row("817Y002", "010200000", "20251103", "1")));
        var client = h.factory.newClient();
        client.observations("817Y002", "010200000", EcosCycle.D, D1, D2);

        String text = h.properties + " " + h.factory + " " + client;

        assertThat(text).doesNotContain(FAKE_KEY).contains("****");
    }

    @Test
    void 마스커는_알려준_키를_경로_안에서도_가린다() {
        var masker = new SecretMasker();
        masker.register(FAKE_KEY);

        String masked = masker.mask("GET https://ecos.bok.or.kr/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10");

        assertThat(masked).doesNotContain(FAKE_KEY).contains("****").contains("/json/kr/1/10");
    }

    @Test
    void 시험_자료의_가짜_키는_눈에_띄는_가짜_표시를_가지고_진짜_응답_파일에는_키가_없다() {
        assertThat(FAKE_KEY).startsWith("FAKE");
        for (String name : List.of("real_itemlist_722y001", "real_cpi_901y009_m", "real_ktb3y_817y002_d", "real_cli_901y067_m",
                "real_exports_901y118_m", "real_usdkrw_731y001_d")) {
            assertThat(EcosHarness.fixture(name)).as(name).doesNotContain(FAKE_KEY).doesNotContain("api/StatisticSearch");
        }
    }

    @Test
    void 저장소의_추적_파일에는_ECOS_키_자리에_값이_없다() throws IOException {
        for (String file : List.of(".env.example", "src/main/resources/application.yml")) {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            assertThat(text).as(file).doesNotContainPattern("ECOS_API_KEY=[A-Za-z0-9]");
        }
    }
}
