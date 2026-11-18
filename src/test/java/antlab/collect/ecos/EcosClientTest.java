package antlab.collect.ecos;

import static antlab.collect.ecos.EcosHarness.FAKE_KEY;
import static antlab.collect.ecos.EcosHarness.answer;
import static antlab.collect.ecos.EcosHarness.path;
import static antlab.collect.ecos.EcosHarness.result;
import static antlab.collect.ecos.EcosHarness.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** The client against the real shape-probe answers and hand-made bad ones: paths, paging, parsing, error codes, retries. */
class EcosClientTest {

    private static final LocalDate D1 = LocalDate.of(2025, 11, 3);
    private static final LocalDate D2 = LocalDate.of(2025, 11, 18);

    private static void fails(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(EcosException.class, e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.getMessage()).doesNotContain(FAKE_KEY);
            assertThat(e.getCause()).isNull();
        });
    }

    @Test
    void 일별_진짜_응답은_열두_영업일_값이_되고_요청_경로는_정확히_이_모양이다() {
        var h = new EcosHarness();
        h.expectPath(path(1, 100000, "817Y002", "D", "20251103", "20251118", "010200000"))
                .andRespond(withSuccess(EcosHarness.fixture("real_ktb3y_817y002_d"), MediaType.APPLICATION_JSON));

        List<EcosObservation> rows = h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2);

        assertThat(rows).hasSize(12);
        assertThat(rows.getFirst()).isEqualTo(new EcosObservation(D1, new BigDecimal("2.741")));
        assertThat(rows.getLast().refDate()).isEqualTo(D2);
        h.server.verify();
    }

    @Test
    void 월별_진짜_응답은_그_달_첫날로_날짜를_잡고_요청의_시작과_끝은_여섯_자리다() {
        var h = new EcosHarness();
        h.expectPath(path(1, 100000, "901Y009", "M", "202509", "202510", "0"))
                .andRespond(withSuccess(EcosHarness.fixture("real_cpi_901y009_m"), MediaType.APPLICATION_JSON));

        List<EcosObservation> rows = h.factory.newClient().observations("901Y009", "0", EcosCycle.M, LocalDate.of(2025, 9, 15),
                LocalDate.of(2025, 10, 31));

        assertThat(rows).containsExactly(new EcosObservation(LocalDate.of(2025, 9, 1), new BigDecimal("117.06")),
                new EcosObservation(LocalDate.of(2025, 10, 1), new BigDecimal("117.42")));
        h.server.verify();
    }

    @Test
    void 나머지_진짜_응답도_읽힌다_큰_수와_소수_한_자리() {
        var h = new EcosHarness();
        h.respondFixture("real_cli_901y067_m");
        h.respondFixture("real_exports_901y118_m");
        h.respondFixture("real_usdkrw_731y001_d");
        var client = h.factory.newClient();

        assertThat(client.observations("901Y067", "I16E", EcosCycle.M, D1, D2)).extracting(EcosObservation::value)
                .containsExactly(new BigDecimal("99.7"), new BigDecimal("99.6"));
        assertThat(client.observations("901Y118", "T002", EcosCycle.M, D1, D2)).extracting(EcosObservation::value)
                .containsExactly(new BigDecimal("65904118"), new BigDecimal("59511578"));
        assertThat(client.observations("731Y001", "0000001", EcosCycle.D, D1, D2)).hasSize(12).extracting(EcosObservation::value)
                .contains(new BigDecimal("1427.4"), new BigDecimal("1429"));
        assertThat(client.requestsMade()).isEqualTo(3);
    }

    @Test
    void 쪽을_나눠_받을_때_다음_쪽의_범위가_이어진다() {
        var h = new EcosHarness(EcosHarness.properties(1, 20, 2));
        h.expectPath(path(1, 2, "817Y002", "D", "20251103", "20251118", "010200000")).andRespond(withSuccess(
                answer(3, row("817Y002", "010200000", "20251103", "1"), row("817Y002", "010200000", "20251104", "2")),
                MediaType.APPLICATION_JSON));
        h.expectPath(path(3, 4, "817Y002", "D", "20251103", "20251118", "010200000")).andRespond(withSuccess(
                answer(3, row("817Y002", "010200000", "20251105", "3")), MediaType.APPLICATION_JSON));

        List<EcosObservation> rows = h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2);

        assertThat(rows).extracting(r -> r.value().intValue()).containsExactly(1, 2, 3);
        h.server.verify();
    }

    @Test
    void 쪽_수_상한을_넘기면_멈춘다() {
        var h = new EcosHarness(EcosHarness.properties(1, 100, 1));
        for (int i = 0; i < DefaultEcosSeriesClient.PAGE_CAP; i++) {
            h.respond(answer(1000, row("817Y002", "010200000", "20251103", "1")));
        }

        fails(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2), "PAGE_CAP");
    }

    @Test
    void 알린_개수에_못_미치는_빈_쪽은_파싱_실패다() {
        var h = new EcosHarness();
        h.respond(answer(5));

        fails(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_PARSE");
    }

    @Test
    void 데이터_없음_INFO_200은_빈_목록이고_성공이다() {
        var h = new EcosHarness();
        h.respond(result("INFO-200"));

        assertThat(h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2)).isEmpty();
    }

    @Test
    void 첫_쪽이_아닌_곳의_INFO_200은_파싱_실패다() {
        var h = new EcosHarness(EcosHarness.properties(1, 20, 1));
        h.respond(answer(2, row("817Y002", "010200000", "20251103", "1")));
        h.respond(result("INFO-200"));

        fails(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_PARSE");
    }

    @Test
    void 키_오류_INFO_100은_AUTH_호출_초과_ERROR_602는_RATE_LIMIT_나머지는_REJECTED다() {
        var h = new EcosHarness();
        h.respond(result("INFO-100"));
        h.respond(result("ERROR-602"));
        h.respond(result("ERROR-101"));
        h.respond(result("weird text with " + FAKE_KEY));
        var client = h.factory.newClient();

        fails(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_AUTH");
        fails(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_RATE_LIMIT");
        fails(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_REJECTED");
        fails(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_REJECTED");
    }

    @Test
    void 오류_메시지는_결과_코드만_담고_ECOS가_보낸_문장은_담지_않는다() {
        var h = new EcosHarness();
        h.respond(result("ERROR-101"));

        assertThatThrownBy(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2))
                .hasMessageContaining("result=ERROR-101").hasMessageNotContaining("never be copied");
    }

    @Test
    void 값이_null이거나_비어_있는_행은_건너뛰고_나머지는_읽는다() {
        var h = new EcosHarness();
        h.respond(answer(3, row("817Y002", "010200000", "20251103", "2.7"), row("817Y002", "010200000", "20251104", null),
                row("817Y002", "010200000", "20251105", "  ")));

        assertThat(h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2)).extracting(EcosObservation::refDate)
                .containsExactly(LocalDate.of(2025, 11, 3));
    }

    @Test
    void 값은_반올림하지_않고_여덟_자리를_넘거나_평범한_수가_아니면_거절한다() {
        assertThat(DefaultEcosSeriesClient.parseValue("1.23456780")).isEqualByComparingTo("1.2345678");
        assertThat(DefaultEcosSeriesClient.parseValue("-0.5")).isEqualByComparingTo("-0.5");
        for (String bad : new String[] {"1.123456789", "1e5", "NaN", "1,000", "+1", ".", "abc", "12345678901234567"}) {
            assertThatThrownBy(() -> DefaultEcosSeriesClient.parseValue(bad)).isInstanceOfSatisfying(EcosException.class,
                    e -> assertThat(e.code()).isEqualTo("ECOS_PARSE"));
        }
    }

    @Test
    void 다른_통계나_항목의_행_주기와_안_맞는_시간_없는_달력_날짜는_파싱_실패다() {
        for (String body : new String[] {
                answer(1, row("OTHER", "010200000", "20251103", "1")),
                answer(1, row("817Y002", "OTHER", "20251103", "1")),
                answer(1, row("817Y002", "010200000", "202511", "1")),
                answer(1, row("817Y002", "010200000", "20251332", "1")),
                answer(1, row("817Y002", "010200000", "2025-11-03", "1")),
                answer(1, "{\"STAT_CODE\":\"817Y002\",\"ITEM_CODE1\":\"010200000\",\"TIME\":\"20251103\"}"),
                answer(1, "{\"STAT_CODE\":\"817Y002\",\"ITEM_CODE1\":\"010200000\",\"TIME\":\"20251103\",\"DATA_VALUE\":5}"),
                "{\"StatisticSearch\":{\"row\":[]}}",
                "{\"StatisticSearch\":{\"list_total_count\":0}}",
                "{\"other\":1}",
                "not json",
                "[1,2]"}) {
            var h = new EcosHarness();
            h.respond(body);
            fails(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_PARSE");
        }
    }

    @Test
    void 월별_시간은_여섯_자리_달력_달이어야_한다() {
        var h = new EcosHarness();
        h.respond(answer(1, row("901Y009", "0", "202513", "1")));

        fails(() -> h.factory.newClient().observations("901Y009", "0", EcosCycle.M, D1, D2), "ECOS_PARSE");
    }

    @Test
    void HTTP_오류와_리다이렉트는_상태만_말하고_본문이나_주소를_말하지_않는다() {
        var h = new EcosHarness();
        h.expectSearch().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom " + FAKE_KEY));
        h.expectSearch().andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.example/" + FAKE_KEY));
        h.expectSearch().andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));
        var client = h.factory.newClient();

        fails(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_HTTP");
        fails(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_HTTP");
        fails(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_PARSE");
    }

    @Test
    void 전송_예외는_틀에_박힌_메시지로_바뀌고_원인을_달지_않는다() {
        var h = new EcosHarness();
        h.expectSearch().andRespond(withException(new IOException("connect failed GET https://ecos.bok.or.kr/api/x/" + FAKE_KEY)));

        assertThatThrownBy(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2))
                .isInstanceOfSatisfying(EcosException.class, e -> {
                    assertThat(e.code()).isEqualTo("ECOS_HTTP");
                    assertThat(e.getMessage()).doesNotContain(FAKE_KEY).doesNotContain("ecos.bok.or.kr");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void 속도_제한_429는_2_5_10초_기다려_다시_보내고_계속되면_RATE_LIMIT이다() {
        var h = new EcosHarness();
        h.expectSearch().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}").contentType(MediaType.APPLICATION_JSON));
        h.expectSearch().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}").contentType(MediaType.APPLICATION_JSON));
        h.respond(answer(1, row("817Y002", "010200000", "20251103", "1")));
        var client = h.factory.newClient();

        assertThat(client.observations("817Y002", "010200000", EcosCycle.D, D1, D2)).hasSize(1);
        assertThat(DefaultEcosSeriesClient.RATE_RETRY_WAITS).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(h.sleeper.sleeps).contains(Duration.ofSeconds(2), Duration.ofSeconds(5));
        assertThat(client.requestsMade()).as("retries are requests").isEqualTo(3);

        var again = new EcosHarness();
        for (int i = 0; i <= DefaultEcosSeriesClient.RATE_RETRY_WAITS.size(); i++) {
            again.expectSearch().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}").contentType(MediaType.APPLICATION_JSON));
        }
        fails(() -> again.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_RATE_LIMIT");
        assertThat(again.sleeper.sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    void 재시도도_요청_수_상한에_세어져_상한에서_멈춘다() {
        var h = new EcosHarness(EcosHarness.properties(1, 2, 100000));
        for (int i = 0; i < 2; i++) {
            h.expectSearch().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{}").contentType(MediaType.APPLICATION_JSON));
        }
        var client = h.factory.newClient();

        fails(() -> client.observations("817Y002", "010200000", EcosCycle.D, D1, D2), "REQUEST_CAP");
        assertThat(client.requestsMade()).isEqualTo(2);
    }

    @Test
    void 잘못된_인자는_요청_전에_거절한다() {
        var h = new EcosHarness();
        var client = h.factory.newClient();

        for (String[] bad : new String[][] {{"817y002", "0"}, {"817Y002/1", "0"}, {"", "0"}, {"817Y002", ""}, {"817Y002", "0/1"},
                {"817Y002", "0?x=1"}, {null, "0"}, {"817Y002", null}}) {
            assertThatThrownBy(() -> client.observations(bad[0], bad[1], EcosCycle.D, D1, D2)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> client.observations("817Y002", "0", EcosCycle.D, D2, D1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.requestsMade()).isZero();
    }

    @Test
    void 한_쪽이_너무_크면_파싱_실패로_멈춘다() {
        var h = new EcosHarness();
        h.respond("{\"StatisticSearch\":{\"list_total_count\":1,\"row\":[]},\"pad\":\""
                + "x".repeat(DefaultEcosSeriesClient.MAX_BODY_BYTES) + "\"}");

        fails(() -> h.factory.newClient().observations("817Y002", "010200000", EcosCycle.D, D1, D2), "ECOS_PARSE");
    }
}
