package antlab.collect.fred;

import static antlab.collect.fred.FredHarness.FAKE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import antlab.collect.kis.MinIntervalRateLimiter;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** The request surface is one GET of observations on one host; everything else is refused before it is sent. */
class FredAllowlistTest {

    private static final String OK_QUERY = "series_id=DGS2&api_key=" + FAKE_KEY + "&file_type=json&limit=100000&offset=0";
    private final FredAllowlistInterceptor allow = new FredAllowlistInterceptor(FredEndpoint.BASE_URI);

    private static URI url(String suffix) {
        return URI.create("https://api.stlouisfed.org" + suffix);
    }

    private void refuse(String method, URI uri) {
        assertThatThrownBy(() -> allow.check(method, uri)).isInstanceOf(FredForbiddenRequestException.class)
                .hasMessageNotContaining(FAKE_KEY)
                .satisfies(e -> assertThat(((FredException) e).code()).isEqualTo("FRED_FORBIDDEN_REQUEST"));
    }

    @Test
    void 필수_파라미터만으로도_선택_파라미터를_더해도_통과한다() {
        assertThatCode(() -> allow.check("GET", url("/fred/series/observations?" + OK_QUERY))).doesNotThrowAnyException();
        assertThatCode(() -> allow.check("GET", url("/fred/series/observations?" + OK_QUERY
                + "&observation_start=1990-01-01&realtime_start=1776-07-04&realtime_end=9999-12-31"))).doesNotThrowAnyException();
    }

    @Test
    void 다른_메서드는_거절된다() {
        for (String method : new String[] {"POST", "PUT", "DELETE", "PATCH", "HEAD"}) {
            refuse(method, url("/fred/series/observations?" + OK_QUERY));
        }
    }

    @Test
    void 다른_경로는_거절된다() {
        for (String path : new String[] {"/fred/series", "/fred/series/updates", "/fred/series/vintagedates", "/fred/series/search",
                "/fred/category", "/fred/series/observations/", "/fred/series/observations/x", "/fred/series/observations/../series",
                "/fred/series/observations%2Fx", "/FRED/series/observations", "/", "", "/geofred/series/data",
                "/fred/series/observations;x=1"}) {
            refuse("GET", url(path + "?" + OK_QUERY));
        }
    }

    @Test
    void 다른_호스트_스킴_포트_사용자정보_조각은_거절된다() {
        refuse("GET", URI.create("http://api.stlouisfed.org/fred/series/observations?" + OK_QUERY));
        refuse("GET", URI.create("https://api.stlouisfed.org:8443/fred/series/observations?" + OK_QUERY));
        refuse("GET", URI.create("https://api.stlouisfed.org.evil.example/fred/series/observations?" + OK_QUERY));
        refuse("GET", URI.create("https://evil.example/fred/series/observations?" + OK_QUERY));
        refuse("GET", URI.create("https://user@api.stlouisfed.org/fred/series/observations?" + OK_QUERY));
        refuse("GET", URI.create("https://api.stlouisfed.org/fred/series/observations?" + OK_QUERY + "#frag"));
    }

    @Test
    void 필수_파라미터가_빠지거나_모르는_이름이_있거나_겹치면_거절된다() {
        for (String query : new String[] {
                "series_id=DGS2&file_type=json&limit=1&offset=0",
                "series_id=DGS2&api_key=" + FAKE_KEY + "&file_type=json&offset=0",
                "series_id=DGS2&api_key=" + FAKE_KEY + "&file_type=json&limit=1",
                OK_QUERY + "&order_by=observation_date",
                OK_QUERY + "&sort_order=desc",
                OK_QUERY + "&units=pch",
                OK_QUERY + "&series_id=ICSA",
                OK_QUERY + "&api_key=" + FAKE_KEY,
                OK_QUERY + "&observation_start",
                OK_QUERY + "&=1",
                ""}) {
            refuse("GET", url("/fred/series/observations" + (query.isEmpty() ? "" : "?" + query)));
        }
    }

    @Test
    void 값이_모양에_맞지_않으면_거절된다() {
        for (String query : new String[] {
                "series_id=dgs2&api_key=" + FAKE_KEY + "&file_type=json&limit=1&offset=0",
                "series_id=DGS2%26x&api_key=" + FAKE_KEY + "&file_type=json&limit=1&offset=0",
                "series_id=DGS2&api_key=short&file_type=json&limit=1&offset=0",
                "series_id=DGS2&api_key=" + FAKE_KEY + "%26x&file_type=json&limit=1&offset=0",
                "series_id=DGS2&api_key=" + FAKE_KEY + "&file_type=xml&limit=1&offset=0",
                "series_id=DGS2&api_key=" + FAKE_KEY + "&file_type=json&limit=abc&offset=0",
                "series_id=DGS2&api_key=" + FAKE_KEY + "&file_type=json&limit=1&offset=-1",
                OK_QUERY + "&observation_start=yesterday",
                OK_QUERY + "&realtime_end=9999-12-31T00:00",
                OK_QUERY + "&realtime_start="}) {
            refuse("GET", url("/fred/series/observations?" + query));
        }
    }

    @Test
    void 거절_메시지에는_쿼리와_키가_없고_이상한_경로는_보이지_않는다() {
        assertThatThrownBy(() -> allow.check("GET", url("/fred/series/updates?" + OK_QUERY)))
                .hasMessageContaining("/fred/series/updates").hasMessageNotContaining("api_key").hasMessageNotContaining(FAKE_KEY);
        assertThatThrownBy(() -> allow.check("GET", url("/fred/series/observations%2Fx?" + OK_QUERY)))
                .hasMessageContaining("path not shown").hasMessageNotContaining(FAKE_KEY);
        assertThatThrownBy(() -> allow.check("GET", url("/fred/series/observations?" + OK_QUERY + "&units=pch")))
                .hasMessageContaining("allow-list").hasMessageNotContaining("pch").hasMessageNotContaining(FAKE_KEY);
    }

    @Test
    void 운영_주소는_한_호스트_하나의_경로로_고정이다() {
        assertThat(FredEndpoint.BASE_URI).hasToString("https://api.stlouisfed.org");
        assertThat(FredEndpoint.OBSERVATIONS_PATH).isEqualTo("/fred/series/observations");
        assertThat(FredEndpoint.REQUIRED_QUERY).containsExactlyInAnyOrder("series_id", "api_key", "file_type", "limit", "offset");
        assertThat(FredEndpoint.OPTIONAL_QUERY).containsExactlyInAnyOrder("observation_start", "realtime_start", "realtime_end");
    }

    @Test
    void 허용_목록은_클라이언트_사슬_맨_앞이라_거절된_요청은_서버에_가지도_세어지지도_않는다() {
        var h = new FredHarness();
        var chain = h.factory.restClientWith(new FredRequestGate(new MinIntervalRateLimiter(h.clock, h.sleeper, 1), 5));

        assertThatThrownBy(() -> chain.get().uri("/fred/series/updates?" + OK_QUERY).retrieve().toBodilessEntity())
                .isInstanceOf(FredForbiddenRequestException.class);
        assertThatThrownBy(() -> chain.post().uri("/fred/series/observations?" + OK_QUERY).retrieve().toBodilessEntity())
                .isInstanceOf(FredForbiddenRequestException.class);

        h.server.verify();
        assertThat(h.sleeper.sleeps).as("a refused request is not rate limited").isEmpty();
    }
}
