package antlab.api.turn;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 턴 전환 API 시험 공용 도구. 합성 고정본(실제 시세 아님)의 풀: 000660(국내), 069500(국내), SPY(미국), BRK.B(미국).
 * 시즌은 고정 회원의 12턴 경로, 팀은 4종목을 한 번씩 사서 만든다.
 */
public final class TurnApiSupport {

    static final String[] SCRIPTS = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql",
            "/fixtures/replay_months.sql", "/fixtures/turn_returns.sql"};

    private TurnApiSupport() {
    }

    static MockHttpServletResponse advanceRaw(MockMvcTester mvc, long seasonId, String body) {
        return mvc.post().uri("/api/v1/seasons/" + seasonId + "/turns").contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange().getResponse();
    }

    public static MockHttpServletResponse advance(MockMvcTester mvc, long seasonId, int expectedTurnNo) {
        return advanceRaw(mvc, seasonId, "{\"expectedTurnNo\": " + expectedTurnNo + "}");
    }

    public static long assetId(JdbcTemplate jdbc, String ticker) {
        return jdbc.queryForObject("SELECT id FROM asset WHERE ticker = ?", Long.class, ticker);
    }

    public static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    public static String code(MockHttpServletResponse r) throws Exception {
        return json(r).get("code").asString();
    }

    public static void buy(MockMvcTester mvc, JdbcTemplate jdbc, long season, int turn, String ticker, int qty,
            String position) {
        String body = "{\"expectedTurnNo\": " + turn + ", \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, ticker)
                + ", \"quantity\": " + qty + (position == null ? "" : ", \"declaredPosition\": \"" + position + "\"") + "}";
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/orders")
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
        if (r.getStatus() != 201) {
            throw new IllegalStateException("주문 실패: " + r.getStatus());
        }
    }

    /** 0턴 팀: 국내 공격·미드필더, 미국 수비·골키퍼 각 한 종목. */
    public static void buyTeam(MockMvcTester mvc, JdbcTemplate jdbc, long season) {
        buy(mvc, jdbc, season, 0, "000660", 10, "FORWARD_BREAKOUT");
        buy(mvc, jdbc, season, 0, "069500", 100, "MIDFIELDER");
        buy(mvc, jdbc, season, 0, "SPY", 5, "DEFENDER");
        buy(mvc, jdbc, season, 0, "BRK.B", 5, "GOALKEEPER");
    }

    public static void buy(MockMvcTester mvc, JdbcTemplate jdbc, long season, int turn, String ticker, int qty) {
        buy(mvc, jdbc, season, turn, ticker, qty, null);
    }

    /** 12턴 경로 시즌을 연다(고정 회원). */
    public static long startSeason(MockMvcTester mvc) throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse();
        if (r.getStatus() != 201) {
            throw new IllegalStateException("시즌 시작 실패: " + r.getStatus());
        }
        return JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong();
    }

    /** 0턴 팀을 산 뒤 보유 종목(069500)의 라인업 구간을 닫아 대사가 어긋나게 한다(드릴 3단계와 같은 문장). */
    public static long brokenSeason(MockMvcTester mvc, JdbcTemplate jdbc) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        jdbc.update("UPDATE lineup SET end_turn = 0 WHERE season_id = ? AND asset_id = ?", season, assetId(jdbc, "069500"));
        return season;
    }

    /** 시즌에 딸린 표 행 수와 시즌 행 값 요약. 거절된 전환은 아무것도 바꾸지 않는다. */
    static Map<String, Object> state(JdbcTemplate jdbc, long seasonId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String table : new String[] {"turn", "turn_price", "reconciliation_log", "holding", "cash_ledger", "lineup",
                "orders"}) {
            out.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id = ?", Long.class,
                    seasonId));
        }
        out.put("season", jdbc.queryForObject(
                "SELECT status || ':' || current_turn_no || ':' || version FROM season WHERE id = ?", String.class, seasonId));
        out.put("account", jdbc.queryForObject("SELECT balance::text || '/' || version FROM cash_account WHERE season_id = ?",
                String.class, seasonId));
        return out;
    }
}
