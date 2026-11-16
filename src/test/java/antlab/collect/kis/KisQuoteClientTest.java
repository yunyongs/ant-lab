package antlab.collect.kis;

import static antlab.collect.kis.KisHarness.BASE;
import static antlab.collect.kis.KisHarness.FAKE_KEY;
import static antlab.collect.kis.KisHarness.FAKE_SECRET;
import static antlab.collect.kis.KisHarness.FAKE_TOKEN;
import static antlab.collect.kis.KisHarness.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import antlab.collect.PriceBasis;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class KisQuoteClientTest {

    private static final LocalDate D05 = LocalDate.of(2026, 1, 5);
    private static final LocalDate D07 = LocalDate.of(2026, 1, 7);
    private static final LocalDate D09 = LocalDate.of(2026, 1, 9);

    private static BigDecimal price(String text) {
        return new BigDecimal(text);
    }

    private static String krPage(LocalDate newest, int rows) {
        StringBuilder out = new StringBuilder("{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"output2\":[");
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"stck_bsop_date\":\"").append(KisResponseParser.COMPACT_DATE.format(newest.minusDays(i)))
                    .append("\",\"stck_clpr\":\"").append(1000 + i).append("\"}");
        }
        return out.append("]}").toString();
    }

    @Test
    void 국내_일봉은_날짜순으로_정렬되고_금액은_정확한_BigDecimal이다() {
        var h = new KisHarness();
        h.expectToken().andExpect(jsonPath("$.grant_type").value("client_credentials"))
                .andExpect(jsonPath("$.appkey").value(FAKE_KEY)).andExpect(jsonPath("$.appsecret").value(FAKE_SECRET))
                .andExpect(header("content-type", containsString("application/json")))
                .andRespond(withSuccess(fixture("auth_ok"), MediaType.APPLICATION_JSON));
        h.respondDomestic("kr_daily_p1");

        List<DailyClose> rows = h.factory.newClient().domesticDaily("900001", D07, D09, PriceBasis.ADJUSTED);

        assertThat(rows).extracting(DailyClose::tradeDate).containsExactly(D07, LocalDate.of(2026, 1, 8), D09);
        assertThat(rows).extracting(DailyClose::close)
                .containsExactly(price("10700"), price("10850.5"), price("10900"));
        h.server.verify();
    }

    @Test
    void 해외_일봉은_소수_넷까지_그대로_읽는다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.respondOverseas("us_daily_p1");

        List<DailyClose> rows = h.factory.newClient().overseasDaily("FAKEA", KisExchange.NAS, D07, D09);

        assertThat(rows).extracting(DailyClose::close)
                .containsExactly(price("99.1234"), price("100.5"), price("101.25"));
        assertThat(rows).allSatisfy(r -> assertThat(r.close().scale()).isLessThanOrEqualTo(4));
        h.server.verify();
    }

    @Test
    void 국내_쪽매김은_가장_이른_날_전날을_다음_끝_날짜로_쓰고_중복_없이_합친다() {
        var h = new KisHarness();
        h.expectTokenOk();
        LocalDate to = LocalDate.of(2026, 4, 10);
        LocalDate from = LocalDate.of(2025, 12, 1);
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andExpect(queryParam("FID_INPUT_DATE_2", "20260410"))
                .andRespond(withSuccess(krPage(to, 100), MediaType.APPLICATION_JSON));
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andExpect(queryParam("FID_INPUT_DATE_2", "20251231"))
                .andExpect(queryParam("FID_INPUT_DATE_1", "20251201"))
                .andRespond(withSuccess(krPage(LocalDate.of(2025, 12, 31), 31), MediaType.APPLICATION_JSON));

        var client = h.factory.newClient();
        List<DailyClose> rows = client.domesticDaily("900001", from, to, PriceBasis.ADJUSTED);

        assertThat(rows).hasSize(131).extracting(DailyClose::tradeDate).doesNotHaveDuplicates().isSorted();
        assertThat(rows.getFirst().tradeDate()).isEqualTo(from);
        assertThat(client.requestsMade()).isEqualTo(3);
        h.server.verify();
    }

    @Test
    void 해외_쪽매김은_BYMD를_과거로_옮기며_시작일에서_멈춘다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.OVERSEAS_DAILY).andExpect(queryParam("BYMD", "20260109"))
                .andRespond(withSuccess(fixture("us_daily_p1"), MediaType.APPLICATION_JSON));
        h.expectGet(KisEndpoint.OVERSEAS_DAILY).andExpect(queryParam("BYMD", "20260106"))
                .andRespond(withSuccess(fixture("us_daily_p2"), MediaType.APPLICATION_JSON));

        List<DailyClose> rows = h.factory.newClient().overseasDaily("FAKEA", KisExchange.NAS, D05, D09);

        assertThat(rows).extracting(DailyClose::tradeDate).hasSize(5).isSorted().first().isEqualTo(D05);
        h.server.verify();
    }

    @Test
    void 쪽매김이_스무_쪽을_넘으면_조용히_자르지_않고_실패한다() {
        var h = new KisHarness(KisHarness.properties("", 2, 100));
        h.expectTokenOk();
        LocalDate newest = LocalDate.of(2026, 12, 31);
        for (int page = 0; page < DefaultKisQuoteClient.PAGE_CAP; page++) {
            h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(
                    krPage(newest.minusDays(100L * page), 100), MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> h.factory.newClient().domesticDaily("900001", LocalDate.of(2000, 1, 1), newest, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("PAGE_CAP"));
        h.server.verify();
    }

    @Test
    void 같은_날짜에_다른_종가가_오면_파싱_오류다() {
        var h = new KisHarness();
        h.expectTokenOk();
        String body = "{\"rt_cd\":\"0\",\"output2\":[{\"stck_bsop_date\":\"20260109\",\"stck_clpr\":\"1\"},"
                + "{\"stck_bsop_date\":\"20260109\",\"stck_clpr\":\"2\"}]}";
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.factory.newClient().domesticDaily("900001", D09, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_PARSE"));
    }

    @Test
    void 요청_인수는_가격_기준에_따라_정확히_실린다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY)
                .andExpect(queryParam("FID_COND_MRKT_DIV_CODE", "J")).andExpect(queryParam("FID_INPUT_ISCD", "900001"))
                .andExpect(queryParam("FID_INPUT_DATE_1", "20260105")).andExpect(queryParam("FID_INPUT_DATE_2", "20260109"))
                .andExpect(queryParam("FID_PERIOD_DIV_CODE", "D")).andExpect(queryParam("FID_ORG_ADJ_PRC", "0"))
                .andExpect(header("authorization", "Bearer " + FAKE_TOKEN)).andExpect(header("appkey", FAKE_KEY))
                .andExpect(header("appsecret", FAKE_SECRET)).andExpect(header("custtype", "P"))
                .andRespond(withSuccess(fixture("kr_daily_p2"), MediaType.APPLICATION_JSON));
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andExpect(queryParam("FID_ORG_ADJ_PRC", "1"))
                .andRespond(withSuccess(fixture("kr_daily_p2"), MediaType.APPLICATION_JSON));
        h.expectGet(KisEndpoint.OVERSEAS_DAILY)
                .andExpect(queryParam("AUTH", "")).andExpect(queryParam("EXCD", "NYS")).andExpect(queryParam("SYMB", "FAKEB"))
                .andExpect(queryParam("GUBN", "0")).andExpect(queryParam("BYMD", "20260109")).andExpect(queryParam("MODP", "1"))
                .andRespond(withSuccess(fixture("us_daily_p2"), MediaType.APPLICATION_JSON));

        var client = h.factory.newClient();
        client.domesticDaily("900001", D05, D09, PriceBasis.ADJUSTED);
        client.domesticDaily("900001", D05, D09, PriceBasis.RAW);
        client.overseasDaily("FAKEB", KisExchange.NYS, D05, D09);

        h.server.verify();
    }

    @Test
    void 토큰은_여러_호출과_여러_클라이언트에서_한_번만_발급된다() {
        var h = new KisHarness();
        h.expectTokenOk();
        for (int i = 0; i < 5; i++) {
            h.respondDomestic("kr_daily_p2");
        }

        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);
        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);
        var client = h.factory.newClient();
        for (int i = 0; i < 3; i++) {
            client.domesticDaily("900001", D05, D09, PriceBasis.RAW);
        }

        assertThat(client.requestsMade()).isEqualTo(3);
        h.server.verify();
    }

    @Test
    void 호출_한도_코드가_두_번_오면_1초_2초_기다린_뒤_성공한다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rate_limit"), MediaType.APPLICATION_JSON));
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rate_limit"), MediaType.APPLICATION_JSON));
        h.respondDomestic("kr_daily_p2");

        List<DailyClose> rows = h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThat(rows).hasSize(2);
        assertThat(h.sleeper.sleeps).contains(Duration.ofSeconds(1), Duration.ofSeconds(2)).doesNotContain(Duration.ofSeconds(4));
        h.server.verify();
    }

    @Test
    void 호출_한도_코드가_계속되면_세_번_기다린_뒤_KIS_RATE_LIMIT으로_끝난다() {
        var h = new KisHarness();
        h.expectTokenOk();
        for (int i = 0; i < 4; i++) {
            h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("rate_limit"), MediaType.APPLICATION_JSON));
        }
        var client = h.factory.newClient();

        assertThatThrownBy(() -> client.domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_RATE_LIMIT"));
        assertThat(h.sleeper.sleeps).contains(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(client.requestsMade()).isEqualTo(5);
        h.server.verify();
    }

    @Test
    void 실행당_요청_상한을_넘기면_보내기_전에_멈춘다() {
        var h = new KisHarness(KisHarness.properties("", 2, 3));
        h.expectTokenOk();
        h.respondDomestic("kr_daily_p2");
        h.respondDomestic("kr_daily_p2");
        var client = h.factory.newClient();
        client.domesticDaily("900001", D05, D09, PriceBasis.RAW);
        client.domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThatThrownBy(() -> client.domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("REQUEST_CAP"));
        assertThat(client.requestsMade()).isEqualTo(3);
        h.server.verify();
    }

    @Test
    void 요청_상한은_클라이언트마다_따로_센다() {
        var h = new KisHarness(KisHarness.properties("", 2, 2));
        h.expectTokenOk();
        h.respondDomestic("kr_daily_p2");
        h.respondDomestic("kr_daily_p2");
        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        h.server.verify();
    }

    @Test
    void 만료로_거절된_토큰은_한_번만_다시_발급받고_65초_간격을_지킨다() {
        var h = new KisHarness();
        List<Instant> issuedAt = new ArrayList<>();
        h.expectToken().andExpect(r -> issuedAt.add(h.clock.instant()))
                .andRespond(withSuccess(fixture("auth_ok"), MediaType.APPLICATION_JSON));
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("auth_expired"), MediaType.APPLICATION_JSON));
        h.expectToken().andExpect(r -> issuedAt.add(h.clock.instant()))
                .andRespond(withSuccess(fixture("auth_ok"), MediaType.APPLICATION_JSON));
        h.respondDomestic("kr_daily_p2");

        List<DailyClose> rows = h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThat(rows).hasSize(2);
        assertThat(issuedAt).hasSize(2);
        assertThat(Duration.between(issuedAt.get(0), issuedAt.get(1))).isGreaterThanOrEqualTo(KisTokenService.ISSUANCE_SPACING);
        h.server.verify();
    }

    @Test
    void 또_거절되면_다시_발급하지_않고_실패한다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("auth_expired"), MediaType.APPLICATION_JSON));
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess(fixture("auth_expired"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_REJECTED"));
        h.server.verify();
    }

    @Test
    void 토큰_발급이_한_번_실패하면_65초_뒤_한_번_더_시도한다() {
        var h = new KisHarness();
        List<Instant> issuedAt = new ArrayList<>();
        h.expectToken().andExpect(r -> issuedAt.add(h.clock.instant()))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body(fixture("auth_fail")).contentType(MediaType.APPLICATION_JSON));
        h.expectToken().andExpect(r -> issuedAt.add(h.clock.instant()))
                .andRespond(withSuccess(fixture("auth_ok"), MediaType.APPLICATION_JSON));
        h.respondDomestic("kr_daily_p2");

        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        assertThat(Duration.between(issuedAt.get(0), issuedAt.get(1))).isGreaterThanOrEqualTo(KisTokenService.ISSUANCE_SPACING);
        h.server.verify();
    }

    @Test
    void 토큰_발급이_두_번_실패하면_거절로_끝난다() {
        var h = new KisHarness();
        for (int i = 0; i < 2; i++) {
            h.expectToken().andRespond(withStatus(HttpStatus.FORBIDDEN).body(fixture("auth_fail")).contentType(MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_REJECTED"));
        h.server.verify();
    }

    @Test
    void 토큰_발급이_실패하면_같은_실행_안에서는_다시_보내지_않고_실패를_유지한다() {
        var h = new KisHarness();
        for (int i = 0; i < 2; i++) {
            h.expectToken().andRespond(withStatus(HttpStatus.FORBIDDEN).body(fixture("auth_fail")).contentType(MediaType.APPLICATION_JSON));
        }
        KisQuoteClient client = h.factory.newClient();
        assertThatThrownBy(() -> client.domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_REJECTED"));
        Duration sleptSoFar = h.sleeper.total();

        assertThatThrownBy(() -> client.domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_TOKEN_UNAVAILABLE"));
        assertThatThrownBy(() -> client.overseasDaily("SPY", KisExchange.AMS, D05, D09))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_TOKEN_UNAVAILABLE"));
        assertThatThrownBy(() -> h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_TOKEN_UNAVAILABLE"));

        assertThat(h.sleeper.total()).isEqualTo(sleptSoFar);
        h.server.verify();
    }

    @Test
    void 재발급_뒤에도_만료_코드면_HTTP_상태와_무관하게_거절로_끝난다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(fixture("auth_expired")).contentType(MediaType.APPLICATION_JSON));
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(fixture("auth_expired")).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> assertThat(e.code()).isEqualTo("KIS_REJECTED"));
        h.server.verify();
    }

    @Test
    void 거절_응답과_서버_오류와_JSON이_아닌_본문은_상태와_코드만_담은_오류가_된다() {
        var rejected = new KisHarness();
        rejected.expectTokenOk();
        rejected.respondDomestic("rejected");
        assertThatThrownBy(() -> rejected.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> {
                    assertThat(e.code()).isEqualTo("KIS_REJECTED");
                    assertThat(e.getMessage()).contains("HTTP 200", "msg_cd=OPSQ0002").doesNotContain("FAKE rejected message");
                });

        var serverError = new KisHarness();
        serverError.expectTokenOk();
        serverError.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withServerError().body("<html>FAKE gateway page</html>"));
        assertThatThrownBy(() -> serverError.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> {
                    assertThat(e.code()).isEqualTo("KIS_HTTP");
                    assertThat(e.getMessage()).contains("HTTP 500").doesNotContain("FAKE gateway page");
                });

        var notJson = new KisHarness();
        notJson.expectTokenOk();
        notJson.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withSuccess("not json FAKE text", MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> notJson.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> {
                    assertThat(e.code()).isEqualTo("KIS_PARSE");
                    assertThat(e.getMessage()).doesNotContain("FAKE text");
                });
    }

    @Test
    void 리다이렉트_응답은_따라가지_않고_위치_머리글을_읽지_않는다() {
        var h = new KisHarness();
        h.expectTokenOk();
        h.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.example/elsewhere"));

        assertThatThrownBy(() -> h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> {
                    assertThat(e.code()).isEqualTo("KIS_HTTP");
                    assertThat(e.getMessage()).contains("HTTP 302").doesNotContain("other.example");
                });
        h.server.verify();
    }

    @Test
    void 잘못된_인수는_요청_전에_거절한다() {
        var h = new KisHarness();
        var client = h.factory.newClient();

        assertThatThrownBy(() -> client.domesticDaily("900001?x=1", D05, D09, PriceBasis.RAW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.domesticDaily("900001", D09, D05, PriceBasis.RAW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.requestsMade()).isZero();
        assertThat(BASE).startsWith("https://openapi.koreainvestment.com:9443");
        h.server.verify();
    }

    @Test
    void 토큰_요청_본문은_JSON_본문이고_키를_담는다() {
        var h = new KisHarness();
        h.expectToken().andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(fixture("auth_ok"), MediaType.APPLICATION_JSON));
        h.respondDomestic("kr_daily_p2");

        h.factory.newClient().domesticDaily("900001", D05, D09, PriceBasis.RAW);

        h.server.verify();
    }
}
