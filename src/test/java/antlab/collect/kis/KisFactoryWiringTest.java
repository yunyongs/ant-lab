package antlab.collect.kis;

import static antlab.collect.kis.KisHarness.BASE;
import static antlab.collect.kis.KisHarness.FAKE_SECRET;
import static antlab.collect.kis.KisHarness.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.PriceBasis;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/** The client chain the factory really builds (production path): the allow-list sits in front of everything. */
class KisFactoryWiringTest {

    @Test
    void 공장이_만든_클라이언트는_허용_목록_밖_요청을_보내기_전에_거절한다() {
        var harness = new KisHarness();
        RestClient client = harness.factory.newRestClientForTest();
        String goodQuery = "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=900001&FID_INPUT_DATE_1=20260105&FID_INPUT_DATE_2=20260109"
                + "&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=1";
        List<Runnable> attempts = List.of(
                () -> client.get().uri(URI.create(BASE + "/uapi/domestic-stock/v1/trading/inquire-balance")).retrieve().toBodilessEntity(),
                () -> client.post().uri(URI.create(BASE + KisEndpoint.DOMESTIC_DAILY.path() + goodQuery))
                        .header("tr_id", "FHKST03010100").retrieve().toBodilessEntity(),
                () -> client.method(HttpMethod.DELETE).uri(URI.create(BASE + "/oauth2/tokenP")).retrieve().toBodilessEntity(),
                () -> client.get().uri(URI.create("https://evil.example/oauth2/tokenP")).retrieve().toBodilessEntity(),
                () -> client.get().uri(URI.create(BASE + KisEndpoint.DOMESTIC_DAILY.path() + goodQuery + "&EXTRA=1"))
                        .header("tr_id", "FHKST03010100").retrieve().toBodilessEntity());

        for (Runnable attempt : attempts) {
            assertThatThrownBy(attempt::run).isInstanceOf(KisForbiddenRequestException.class);
        }
        harness.server.verify();
    }

    @Test
    void 허용된_요청은_공장이_만든_클라이언트로_나간다() {
        var harness = new KisHarness();
        harness.expectTokenOk();
        harness.respondDomestic("kr_daily_p2");

        var rows = harness.factory.newClient().domesticDaily("900001", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9), PriceBasis.RAW);

        assertThat(rows).isNotEmpty();
        harness.server.verify();
    }

    @Test
    void 응답_본문이_상한을_넘으면_파싱_전에_실패한다() {
        var harness = new KisHarness();
        harness.expectTokenOk();
        String huge = "{\"pad\":\"" + "x".repeat(DefaultKisQuoteClient.MAX_BODY_BYTES) + "\"}";
        harness.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(MockRestResponseCreators.withSuccess(huge, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.factory.newClient().domesticDaily("900001", LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 9), PriceBasis.RAW))
                .isInstanceOfSatisfying(KisException.class, e -> {
                    assertThat(e.code()).isEqualTo("KIS_PARSE");
                    assertThat(e.getMessage()).contains("too large").doesNotContain("xxxx");
                });
    }

    @Test
    void 상한_이하의_큰_본문은_받는다() {
        var harness = new KisHarness();
        harness.expectTokenOk();
        String padded = fixture("kr_daily_p2").replaceFirst("\\}\\s*$", ",\"pad\":\"" + "x".repeat(1_000_000) + "\"}");
        harness.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(MockRestResponseCreators.withSuccess(padded, MediaType.APPLICATION_JSON));

        var rows = harness.factory.newClient().domesticDaily("900001", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9), PriceBasis.RAW);

        assertThat(rows).isNotEmpty();
    }

    @Test
    void 응답의_메시지_코드가_등록된_비밀값과_같아도_오류_문구에_남지_않는다() {
        var echoKey = "KEYabc12345";
        var harness = new KisHarness(new KisProperties(true, echoKey, FAKE_SECRET, "", 2, 60));
        harness.expectTokenOk();
        harness.expectGet(KisEndpoint.DOMESTIC_DAILY).andRespond(MockRestResponseCreators.withSuccess(
                "{\"rt_cd\":\"1\",\"msg_cd\":\"" + echoKey + "\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.factory.newClient().domesticDaily("900001", LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 9), PriceBasis.RAW))
                .isInstanceOf(KisException.class).hasMessageNotContaining(echoKey).hasMessageContaining(SecretMasker.MASK);
    }
}
