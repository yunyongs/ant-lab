package antlab.collect.fred;

import static antlab.collect.fred.FredHarness.fixture;
import static antlab.collect.fred.FredHarness.page;
import static antlab.collect.fred.FredHarness.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class FredClientTest {

    private static final LocalDate START = LocalDate.of(2022, 12, 1);

    private static void assertFails(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FredException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void 빈티지_응답을_읽고_요청은_빈티지_창과_필수_파라미터를_싣는다() {
        var h = new FredHarness();
        h.expectObservations()
                .andExpect(queryParam("series_id", "CPILFESL")).andExpect(queryParam("observation_start", "2022-12-01"))
                .andExpect(queryParam("realtime_start", "1776-07-04")).andExpect(queryParam("realtime_end", "9999-12-31"))
                .andExpect(queryParam("file_type", "json")).andExpect(queryParam("limit", "100000"))
                .andExpect(queryParam("offset", "0")).andExpect(queryParam("api_key", FredHarness.FAKE_KEY))
                .andRespond(withSuccess(fixture("real_cpilfesl_2022_vintages"), MediaType.APPLICATION_JSON));
        var client = h.factory.newClient();

        List<FredObservation> rows = client.observations("CPILFESL", START, true);

        h.server.verify();
        assertThat(rows).hasSize(17);
        assertThat(rows.getFirst()).isEqualTo(new FredObservation(LocalDate.of(2022, 12, 1), new BigDecimal("300.974"),
                LocalDate.of(2023, 1, 12), LocalDate.of(2023, 2, 9)));
        assertThat(rows.get(4).realtimeEnd()).as("the current vintage ends at the far date").isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(client.requestsMade()).isEqualTo(1);
    }

    @Test
    void 빈티지가_아니면_실시간_창_파라미터를_보내지_않는다() {
        var h = new FredHarness();
        h.expectObservations().andExpect(queryParam("series_id", "DGS2")).andExpect(queryParam("observation_start", "2025-11-03"))
                .andExpect(request -> assertThat(request.getURI().getRawQuery()).doesNotContain("realtime"))
                .andRespond(withSuccess(fixture("real_dgs2_2025_11"), MediaType.APPLICATION_JSON));

        List<FredObservation> rows = h.factory.newClient().observations("DGS2", LocalDate.of(2025, 11, 3), false);

        h.server.verify();
        assertThat(rows).hasSize(12);
    }

    @Test
    void 점은_값_없음이고_그_밖의_값은_정확한_소수다() {
        var h = new FredHarness();
        h.respondFixture("real_dgs2_2025_11");

        List<FredObservation> rows = h.factory.newClient().observations("DGS2", LocalDate.of(2025, 11, 3), false);

        assertThat(rows.get(6).refDate()).isEqualTo(LocalDate.of(2025, 11, 11));
        assertThat(rows.get(6).value()).as("'.' is no value").isNull();
        assertThat(rows.get(1).value()).isEqualByComparingTo("3.58");
        assertThat(rows.get(0).value()).isEqualByComparingTo("3.6");
    }

    @Test
    void 큰_정수와_끝자리_0은_값을_바꾸지_않는다() {
        assertThat(DefaultFredSeriesClient.parseValue("355000")).isEqualByComparingTo("355000").extracting(BigDecimal::scale).isEqualTo(0);
        assertThat(DefaultFredSeriesClient.parseValue("301.460")).isEqualByComparingTo("301.46");
        assertThat(DefaultFredSeriesClient.parseValue("-0.15")).isEqualByComparingTo("-0.15");
        assertThat(DefaultFredSeriesClient.parseValue("0.00000001")).isEqualByComparingTo("0.00000001");
    }

    @Test
    void 쪽이_여러_개면_offset을_늘려_모두_읽는다() {
        var h = new FredHarness(FredHarness.properties(1, 30, 2));
        h.expectObservations().andExpect(queryParam("limit", "2")).andExpect(queryParam("offset", "0")).andRespond(withSuccess(
                page(5, 0, row("2025-01-01", "1", "2025-01-02", "9999-12-31"), row("2025-01-02", "2", "2025-01-03", "9999-12-31")),
                MediaType.APPLICATION_JSON));
        h.expectObservations().andExpect(queryParam("offset", "2")).andRespond(withSuccess(
                page(5, 2, row("2025-01-03", "3", "2025-01-04", "9999-12-31"), row("2025-01-04", "4", "2025-01-05", "9999-12-31")),
                MediaType.APPLICATION_JSON));
        h.expectObservations().andExpect(queryParam("offset", "4")).andRespond(withSuccess(
                page(5, 4, row("2025-01-05", "5", "2025-01-06", "9999-12-31")), MediaType.APPLICATION_JSON));
        var client = h.factory.newClient();

        List<FredObservation> rows = client.observations("DGS2", LocalDate.of(2025, 1, 1), false);

        h.server.verify();
        assertThat(rows).extracting(FredObservation::value).extracting(BigDecimal::intValue).containsExactly(1, 2, 3, 4, 5);
        assertThat(client.requestsMade()).isEqualTo(3);
    }

    @Test
    void 쪽_상한을_넘기면_멈춘다() {
        var h = new FredHarness(FredHarness.properties(1, 100, 1));
        for (int i = 0; i < DefaultFredSeriesClient.PAGE_CAP; i++) {
            h.respond(page(1000, i, row("2025-01-01", "1", "2025-01-02", "9999-12-31")));
        }

        assertFails(() -> h.factory.newClient().observations("DGS2", START, false), "PAGE_CAP");
    }

    @Test
    void 빈_쪽이_오면_총수가_남아도_멈춘다() {
        var h = new FredHarness();
        h.respond(page(10, 0));

        assertThat(h.factory.newClient().observations("DGS2", START, false)).isEmpty();
    }

    @Test
    void 요청_수_상한을_넘으면_더_보내지_않는다() {
        var h = new FredHarness(FredHarness.properties(1, 1, 100000));
        h.respond(page(2, 0, row("2025-01-01", "1", "2025-01-02", "9999-12-31")));

        assertFails(() -> h.factory.newClient().observations("DGS2", START, false), "REQUEST_CAP");
        h.server.verify();
    }

    @Test
    void 요청은_초당_한_번_이하로_간격을_둔다() {
        var h = new FredHarness();
        h.respond(page(1, 0, row("2025-01-01", "1", "2025-01-02", "9999-12-31")));
        h.respond(page(1, 0, row("2025-01-01", "1", "2025-01-02", "9999-12-31")));
        var client = h.factory.newClient();

        client.observations("DGS2", START, false);
        client.observations("DGS2", START, false);

        assertThat(h.sleeper.sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void 속도_제한_응답은_기다렸다_다시_보내고_계속되면_코드로_실패한다() {
        var h = new FredHarness();
        h.expectObservations().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error_code\":429,\"error_message\":\"x\"}")
                .contentType(MediaType.APPLICATION_JSON));
        h.respond(page(1, 0, row("2025-01-01", "1", "2025-01-02", "9999-12-31")));

        assertThat(h.factory.newClient().observations("DGS2", START, false)).hasSize(1);
        assertThat(h.sleeper.sleeps).contains(DefaultFredSeriesClient.RATE_RETRY_WAITS.getFirst());

        var again = new FredHarness();
        for (int i = 0; i <= DefaultFredSeriesClient.RATE_RETRY_WAITS.size(); i++) {
            again.expectObservations().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error_code\":429}")
                    .contentType(MediaType.APPLICATION_JSON));
        }
        assertFails(() -> again.factory.newClient().observations("DGS2", START, false), "FRED_RATE_LIMIT");
    }

    @Test
    void 오류_응답은_상태와_오류_코드만_말한다() {
        var h = new FredHarness();
        h.expectObservations().andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"error_code\":400,\"error_message\":\"Bad Request. The series does not exist.\"}")
                .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.factory.newClient().observations("NOSUCH", START, false))
                .isInstanceOfSatisfying(FredException.class, e -> {
                    assertThat(e.code()).isEqualTo("FRED_HTTP");
                    assertThat(e.getMessage()).contains("HTTP 400").contains("error_code=400").doesNotContain("does not exist");
                });
    }

    @Test
    void 성공_상태의_오류_본문과_서버_오류와_리다이렉트와_본문_아님은_코드로_구분된다() {
        var rejected = new FredHarness();
        rejected.respond("{\"error_code\":400,\"error_message\":\"x\"}");
        assertFails(() -> rejected.factory.newClient().observations("DGS2", START, false), "FRED_REJECTED");

        var server = new FredHarness();
        server.expectObservations().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("oops"));
        assertFails(() -> server.factory.newClient().observations("DGS2", START, false), "FRED_HTTP");

        var redirect = new FredHarness();
        redirect.expectObservations().andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.example/"));
        assertThatThrownBy(() -> redirect.factory.newClient().observations("DGS2", START, false))
                .isInstanceOfSatisfying(FredException.class, e -> {
                    assertThat(e.code()).isEqualTo("FRED_HTTP");
                    assertThat(e.getMessage()).contains("HTTP 302").doesNotContain("other.example");
                });

        var text = new FredHarness();
        text.respond("<html>not json</html>");
        assertFails(() -> text.factory.newClient().observations("DGS2", START, false), "FRED_PARSE");
    }

    @Test
    void 읽을_수_없는_행은_필드_이름만_말하고_값은_말하지_않는다() {
        for (String bad : new String[] {
                row("2025-01-01", "1e5", "2025-01-02", "9999-12-31"),
                row("2025-01-01", "0.123456789", "2025-01-02", "9999-12-31"),
                row("2025-01-01", "NaN", "2025-01-02", "9999-12-31"),
                row("2025-13-01", "1", "2025-01-02", "9999-12-31"),
                row("2025-01-01", "1", "tomorrow", "9999-12-31"),
                "{\"date\":\"2025-01-01\",\"value\":1,\"realtime_start\":\"2025-01-02\",\"realtime_end\":\"9999-12-31\"}"}) {
            var h = new FredHarness();
            h.respond(page(1, 0, bad));
            assertThatThrownBy(() -> h.factory.newClient().observations("DGS2", START, false))
                    .isInstanceOfSatisfying(FredException.class, e -> {
                        assertThat(e.code()).isEqualTo("FRED_PARSE");
                        assertThat(e.getMessage()).doesNotContain("1e5").doesNotContain("0.123456789").doesNotContain("tomorrow");
                    });
        }
        var noList = new FredHarness();
        noList.respond("{\"count\":1}");
        assertFails(() -> noList.factory.newClient().observations("DGS2", START, false), "FRED_PARSE");
        var noCount = new FredHarness();
        noCount.respond("{\"observations\":[]}");
        assertFails(() -> noCount.factory.newClient().observations("DGS2", START, false), "FRED_PARSE");
    }

    @Test
    void 시계열_번호는_대문자_숫자_밑줄만_받는다() {
        var client = new FredHarness().factory.newClient();

        assertThatThrownBy(() -> client.observations("dgs2", START, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.observations("DGS2&x=1", START, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.observations(null, START, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.requestsMade()).isZero();
    }

    @Test
    void 속도_제한_재시도도_요청_수_상한에_세어져_상한에서_멈춘다() {
        var h = new FredHarness(FredHarness.properties(1, 2, 100000));
        for (int i = 0; i < 2; i++) {
            h.expectObservations().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error_code\":429}")
                    .contentType(MediaType.APPLICATION_JSON));
        }
        var client = h.factory.newClient();

        assertFails(() -> client.observations("DGS2", START, false), "REQUEST_CAP");

        assertThat(client.requestsMade()).as("both retries were counted").isEqualTo(2);
        h.server.verify();
    }
}
