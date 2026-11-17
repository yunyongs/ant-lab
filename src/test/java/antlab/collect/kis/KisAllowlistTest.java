package antlab.collect.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KisAllowlistTest {

    private static final String HOST = "https://openapi.koreainvestment.com:9443";
    private static final String DOMESTIC = KisEndpoint.DOMESTIC_DAILY.path();
    private static final String OVERSEAS = KisEndpoint.OVERSEAS_DAILY.path();
    private static final String DOMESTIC_QUERY = "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=900001&FID_INPUT_DATE_1=20260105"
            + "&FID_INPUT_DATE_2=20260109&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=1";
    private static final String OVERSEAS_QUERY = "?AUTH=&EXCD=NAS&SYMB=FAKEA&GUBN=0&BYMD=20260109&MODP=1";
    private final KisAllowlistInterceptor allowlist = new KisAllowlistInterceptor(KisEndpoint.BASE_URI);

    @Test
    void 세_가지_요청만_통과한다() {
        assertThat(allowlist.check("POST", URI.create(HOST + "/oauth2/tokenP"), null)).isEqualTo(KisEndpoint.TOKEN);
        assertThat(allowlist.check("GET", URI.create(HOST + DOMESTIC + DOMESTIC_QUERY), "FHKST03010100"))
                .isEqualTo(KisEndpoint.DOMESTIC_DAILY);
        assertThat(allowlist.check("GET", URI.create(HOST + OVERSEAS + OVERSEAS_QUERY), "HHDFS76240000"))
                .isEqualTo(KisEndpoint.OVERSEAS_DAILY);
        assertThat(KisEndpoint.values()).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "POST|/uapi/domestic-stock/v1/trading/order-cash|TTTC0802U",
            "POST|/uapi/domestic-stock/v1/trading/order-cash|null",
            "GET|/uapi/domestic-stock/v1/trading/inquire-balance|TTTC8434R",
            "POST|/uapi/hashkey|null",
            "GET|/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice|TTTC0802U",
            "GET|/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice|null",
            "GET|/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice|HHDFS76240000",
            "GET|/uapi/overseas-price/v1/quotations/dailyprice|FHKST03010100",
            "GET|/uapi/overseas-price/v1/quotations/dailyprice/x|HHDFS76240000",
            "GET|/uapi/overseas-price/v1/quotations/dailyprice/|HHDFS76240000",
            "GET|/uapi/overseas-price/v1/quotations/dailyprice-x|HHDFS76240000",
            "GET|/uapi/overseas-price/v1/quotations/../trading/order|HHDFS76240000",
            "GET|/uapi/overseas-price/v1/quotations/./dailyprice|HHDFS76240000",
            "GET|/uapi%2Foverseas-price/v1/quotations/dailyprice|HHDFS76240000",
            "GET|/uapi/overseas-price/v1/quotations/dailyprice%00|HHDFS76240000",
            "GET|/oauth2/tokenP|null",
            "POST|/uapi/overseas-price/v1/quotations/dailyprice|HHDFS76240000",
            "POST|/oauth2/tokenP/|null",
            "POST|/oauth2/revokeP|null",
            "DELETE|/oauth2/tokenP|null",
            "PUT|/oauth2/tokenP|null",
            "POST|/oauth2/tokenP|HHDFS76240000"})
    void 허용_목록_밖의_메서드_경로_TR는_거절한다(String method, String path, String trId) {
        assertThatThrownBy(() -> allowlist.check(method, URI.create(HOST + path), trId))
                .isInstanceOf(KisForbiddenRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example:9443/oauth2/tokenP",
            "https://openapi.koreainvestment.com.evil.example:9443/oauth2/tokenP",
            "http://openapi.koreainvestment.com:9443/oauth2/tokenP",
            "https://openapi.koreainvestment.com/oauth2/tokenP",
            "https://openapi.koreainvestment.com:29443/oauth2/tokenP",
            "https://user@openapi.koreainvestment.com:9443/oauth2/tokenP",
            "https://openapi.koreainvestment.com:9443/oauth2/tokenP#frag",
            "https://openapivts.koreainvestment.com:29443/oauth2/tokenP"})
    void 다른_호스트_스킴_포트_사용자_정보는_거절한다(String uri) {
        assertThatThrownBy(() -> allowlist.check("POST", URI.create(uri), null)).isInstanceOf(KisForbiddenRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?FID_INPUT_ISCD=900001",
            "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=900001&FID_INPUT_DATE_1=20260105&FID_INPUT_DATE_2=20260109&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=1&EXTRA=1",
            "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=900001&FID_INPUT_DATE_1=20260105&FID_INPUT_DATE_2=20260109&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=1&FID_ORG_ADJ_PRC=0",
            "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=900001&FID_INPUT_DATE_1=20260105&FID_INPUT_DATE_2=20260109&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=1&x",
            "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=900%2F01&FID_INPUT_DATE_1=20260105&FID_INPUT_DATE_2=20260109&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=1",
            "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=900001&FID_INPUT_DATE_1=20260105&FID_INPUT_DATE_2=20260109&FID_PERIOD_DIV_CODE=D&fid_org_adj_prc=1",
            ""})
    void 허용_목록_밖의_질의_파라미터는_거절한다(String query) {
        assertThatThrownBy(() -> allowlist.check("GET", URI.create(HOST + DOMESTIC + query), "FHKST03010100"))
                .isInstanceOf(KisForbiddenRequestException.class).hasMessageNotContaining("900");
    }

    @Test
    void 토큰_요청은_질의를_가질_수_없다() {
        assertThatThrownBy(() -> allowlist.check("POST", URI.create(HOST + "/oauth2/tokenP?appkey=FAKE"), null))
                .isInstanceOf(KisForbiddenRequestException.class).hasMessageNotContaining("FAKE");
    }

    @Test
    void 거절_메시지는_질의문자열과_이상한_경로를_담지_않는다() {
        assertThatThrownBy(() -> allowlist.check("GET", URI.create(HOST + "/uapi%2Fx?appkey=FAKE-APP-KEY-0001"), "x"))
                .hasMessageNotContaining("FAKE-APP-KEY-0001").hasMessageNotContaining("%2F");
    }

    @Test
    void 허용_목록_밖_요청은_보내지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.requestInterceptor(allowlist).build();
        List<Runnable> attempts = List.of(
                () -> client.post().uri(URI.create(HOST + "/uapi/domestic-stock/v1/trading/order-cash")).header("tr_id", "TTTC0802U")
                        .retrieve().toBodilessEntity(),
                () -> client.get().uri(URI.create(HOST + "/uapi/domestic-stock/v1/trading/inquire-balance")).retrieve().toBodilessEntity(),
                () -> client.get().uri(URI.create(HOST + DOMESTIC)).header("tr_id", "WRONG").retrieve().toBodilessEntity(),
                () -> client.get().uri(URI.create(HOST + OVERSEAS + "/x")).header("tr_id", "HHDFS76240000").retrieve().toBodilessEntity(),
                () -> client.method(HttpMethod.DELETE).uri(URI.create(HOST + "/oauth2/tokenP")).retrieve().toBodilessEntity(),
                () -> client.get().uri(URI.create("https://evil.example/oauth2/tokenP")).retrieve().toBodilessEntity());

        for (Runnable attempt : attempts) {
            assertThatThrownBy(attempt::run).isInstanceOf(KisForbiddenRequestException.class);
        }
        server.verify();
    }
}
