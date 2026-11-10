package antlab.scoring;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대사 시험이 API 로 만든 합성 시즌을 쓰는 도구. 합성 고정본의 풀: 000660(국내), 069500(국내), SPY(미국), BRK.B(미국).
 * 고정 회원의 새 시즌(경로 12턴, 0턴)을 시작하고 주문 몇 건을 넣어 대사 규칙이 모두 일치하는 기준 상태를 만든다.
 */
final class ReconciliationApiSupport {

    static final List<String> FIXTURES = List.of("/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
            "/fixtures/replay_months.sql");

    private static final String START_BODY = """
            {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}}""";

    private ReconciliationApiSupport() {
    }

    static long startSeason(MockMvcTester mvc) throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content(START_BODY).exchange().getResponse();
        if (r.getStatus() != 201) {
            throw new IllegalStateException("시즌 시작 실패: " + r.getStatus());
        }
        return JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong();
    }

    static long assetId(JdbcTemplate jdbc, String ticker) {
        return jdbc.queryForObject("SELECT id FROM asset WHERE ticker = ?", Long.class, ticker);
    }

    static MockHttpServletResponse buy(MockMvcTester mvc, JdbcTemplate jdbc, long seasonId, String ticker, int quantity,
            String position) {
        String body = "{\"expectedTurnNo\": 0, \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, ticker)
                + ", \"quantity\": " + quantity + ", \"declaredPosition\": \"" + position + "\"}";
        return mvc.post().uri("/api/v1/seasons/" + seasonId + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange().getResponse();
    }

    /** 국내 한 종목과 미국 한 종목을 사서 원장·체결·보유·라인업이 모두 있는 0턴 시즌을 만든다. */
    static long seasonWithOrders(MockMvcTester mvc, JdbcTemplate jdbc) throws Exception {
        long season = startSeason(mvc);
        if (buy(mvc, jdbc, season, "000660", 10, "FORWARD_BREAKOUT").getStatus() != 201
                || buy(mvc, jdbc, season, "SPY", 2, "DEFENDER").getStatus() != 201) {
            throw new IllegalStateException("기준 주문 실패");
        }
        return season;
    }
}
