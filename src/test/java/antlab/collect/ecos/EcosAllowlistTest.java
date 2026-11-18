package antlab.collect.ecos;

import static antlab.collect.ecos.EcosHarness.FAKE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.kis.MinIntervalRateLimiter;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** The request surface is one GET of a statistic search on one host; everything else is refused before it is sent. */
class EcosAllowlistTest {

    private static final String OK = "/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/100000/817Y002/D/20251103/20251118/010200000";
    private final EcosAllowlistInterceptor allow = new EcosAllowlistInterceptor(EcosEndpoint.BASE_URI);

    private static URI url(String suffix) {
        return URI.create("https://ecos.bok.or.kr" + suffix);
    }

    private void refuse(String method, URI uri) {
        assertThatThrownBy(() -> allow.check(method, uri)).isInstanceOf(EcosForbiddenRequestException.class)
                .hasMessageNotContaining(FAKE_KEY).hasMessageNotContaining("StatisticSearch")
                .satisfies(e -> assertThat(((EcosException) e).code()).isEqualTo("ECOS_FORBIDDEN_REQUEST"));
    }

    @Test
    void 일별과_월별의_정확한_모양은_통과한다() {
        assertThatCode(() -> allow.check("GET", url(OK))).doesNotThrowAnyException();
        assertThatCode(() -> allow.check("GET", url("/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/100000/901Y067/M/199001/202610/I16E")))
                .doesNotThrowAnyException();
    }

    @Test
    void 다른_메서드는_거절된다() {
        for (String method : new String[] {"POST", "PUT", "DELETE", "PATCH", "HEAD"}) {
            refuse(method, url(OK));
        }
    }

    @Test
    void 다른_경로와_쪽매김_밖의_변형은_거절된다() {
        for (String path : new String[] {
                "/api/StatisticItemList/" + FAKE_KEY + "/json/kr/1/10/722Y001",
                "/api/StatisticTableList/" + FAKE_KEY + "/json/kr/1/10/722Y001",
                "/api/KeyStatisticList/" + FAKE_KEY + "/json/kr/1/10",
                "/api/statisticsearch/" + FAKE_KEY + "/json/kr/1/10/817Y002/D/20251103/20251118/010200000",
                "/API/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10/817Y002/D/20251103/20251118/010200000",
                "/api/StatisticSearch/" + FAKE_KEY + "/xml/kr/1/10/817Y002/D/20251103/20251118/010200000",
                "/api/StatisticSearch/" + FAKE_KEY + "/json/en/1/10/817Y002/D/20251103/20251118/010200000",
                "/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10/817Y002/D/20251103/20251118/010200000/",
                "/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10/817Y002/D/20251103/20251118/010200000/extra",
                "/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10/817Y002/D/20251103/20251118",
                "/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10/817Y002/D/20251103/20251118/010200000;x=1",
                "/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10/817Y002/D/20251103/20251118/010%2F0000",
                "/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10/817Y002/D/20251103/20251118/../010200000",
                "/api/StatisticSearch/short/json/kr/1/10/817Y002/D/20251103/20251118/010200000",
                "/api/StatisticSearch/" + FAKE_KEY + "%2Fx/json/kr/1/10/817Y002/D/20251103/20251118/010200000",
                "/", ""}) {
            refuse("GET", url(path));
        }
    }

    @Test
    void 주기_문자와_날짜_자릿수가_맞지_않거나_알_수_없는_주기는_거절된다() {
        for (String tail : new String[] {
                "817Y002/D/202511/202511/010200000",
                "817Y002/M/20251103/20251118/010200000",
                "817Y002/D/20251103/202511/010200000",
                "817Y002/A/2025/2026/010200000",
                "817Y002/Q/2025Q1/2025Q4/010200000",
                "817Y002/d/20251103/20251118/010200000",
                "817y002/D/20251103/20251118/010200000",
                "817Y002/D/2025-11-03/2025-11-18/010200000",
                "817Y002/D/20251103/20251118/"}) {
            refuse("GET", url("/api/StatisticSearch/" + FAKE_KEY + "/json/kr/1/10/" + tail));
        }
    }

    @Test
    void 쿼리와_다른_호스트_스킴_포트_사용자정보_조각은_거절된다() {
        refuse("GET", url(OK + "?x=1"));
        refuse("GET", url(OK + "?"));
        refuse("GET", URI.create("http://ecos.bok.or.kr" + OK));
        refuse("GET", URI.create("https://ecos.bok.or.kr:8443" + OK));
        refuse("GET", URI.create("https://ecos.bok.or.kr.evil.example" + OK));
        refuse("GET", URI.create("https://evil.example" + OK));
        refuse("GET", URI.create("https://user@ecos.bok.or.kr" + OK));
        refuse("GET", URI.create("https://ecos.bok.or.kr" + OK + "#frag"));
    }

    @Test
    void 거절_메시지에는_경로도_키도_없다() {
        assertThatThrownBy(() -> allow.check("GET", url("/api/StatisticItemList/" + FAKE_KEY + "/json/kr/1/10/722Y001")))
                .hasMessageContaining("path not shown").hasMessageContaining("GET").hasMessageNotContaining("/api")
                .hasMessageNotContaining(FAKE_KEY);
    }

    @Test
    void 운영_주소는_한_호스트로_고정이다() {
        assertThat(EcosEndpoint.BASE_URI).hasToString("https://ecos.bok.or.kr");
    }

    @Test
    void 허용_목록은_클라이언트_사슬_맨_앞이라_거절된_요청은_서버에_가지도_세어지지도_않는다() {
        var h = new EcosHarness();
        var chain = h.factory.restClientWith(new EcosRequestGate(new MinIntervalRateLimiter(h.clock, h.sleeper, 1), 5));

        assertThatThrownBy(() -> chain.get().uri("/api/StatisticItemList/" + FAKE_KEY + "/json/kr/1/10/722Y001").retrieve().toBodilessEntity())
                .isInstanceOf(EcosForbiddenRequestException.class);
        assertThatThrownBy(() -> chain.post().uri(OK).retrieve().toBodilessEntity()).isInstanceOf(EcosForbiddenRequestException.class);

        h.server.verify();
        assertThat(h.sleeper.sleeps).as("a refused request is not rate limited").isEmpty();
    }
}
