package antlab.api.order;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 주문 API 시험 공용 도구. 합성 고정본(실제 표본 아님)의 풀: 000660(국내, 150,000원), 069500(국내, 35,000원),
 * SPY(미국, 648,000원), BRK.B(미국, 540,000원). 초기 자금 30,000,000원, 요율은 국내 0.00015·미국 0.0025+0.004.
 */
final class OrderApiSupport {

    /** 주문이 쓰는 6개 표(시즌 단위로 센다). */
    static final List<String> SIX_TABLES =
            List.of("orders", "execution", "holding", "cash_ledger", "cash_account", "lineup");

    private static final String VALID_BODY = """
            {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}}""";

    private OrderApiSupport() {
    }

    static MockHttpServletResponse startSeasonResponse(MockMvcTester mvc) {
        return mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                .exchange().getResponse();
    }

    /** 고정 회원의 새 시즌(경로 12턴, 0턴)을 시작하고 번호를 돌려준다. */
    static long startSeason(MockMvcTester mvc) throws Exception {
        MockHttpServletResponse r = startSeasonResponse(mvc);
        if (r.getStatus() != 201) {
            throw new IllegalStateException("시즌 시작 실패: " + r.getStatus());
        }
        return JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong();
    }

    static long assetId(JdbcTemplate jdbc, String ticker) {
        return jdbc.queryForObject("SELECT id FROM asset WHERE ticker = ?", Long.class, ticker);
    }

    /** 요청 본문. 수량은 문자열 그대로 넣는다(소수·지수 같은 틀린 입력을 시험하려고). 선언 포지션이 null 이면 필드를 뺀다. */
    static String body(Object turn, String side, Object assetId, Object quantity, String position) {
        StringBuilder sb = new StringBuilder("{\"expectedTurnNo\": ").append(turn).append(", \"side\": \"")
                .append(side).append("\", \"assetId\": ").append(assetId).append(", \"quantity\": ")
                .append(quantity);
        if (position != null) {
            sb.append(", \"declaredPosition\": \"").append(position).append("\"");
        }
        return sb.append("}").toString();
    }

    static MockHttpServletResponse place(MockMvcTester mvc, long seasonId, String body) {
        return mvc.post().uri("/api/v1/seasons/" + seasonId + "/orders").contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange().getResponse();
    }

    static JsonNode json(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString());
    }

    static String code(MockHttpServletResponse r) throws Exception {
        return json(r).get("code").asString();
    }

    /** 시즌의 6개 표 행 수, 계좌 잔액, 보유·라인업 값 요약, 계좌 예약금/버전, 세 identity(orders·execution·cash_ledger)의 마지막 값. 거절된 요청은 아무것도 바꾸지 않는다. */
    static Map<String, Object> state(JdbcTemplate jdbc, long seasonId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", count(jdbc, "SELECT count(*) FROM orders WHERE season_id = ?", seasonId));
        out.put("execution", count(jdbc,
                "SELECT count(*) FROM execution WHERE order_id IN (SELECT id FROM orders WHERE season_id = ?)", seasonId));
        out.put("holding", count(jdbc, "SELECT count(*) FROM holding WHERE season_id = ?", seasonId));
        out.put("cash_ledger", count(jdbc, "SELECT count(*) FROM cash_ledger WHERE season_id = ?", seasonId));
        out.put("cash_account", count(jdbc, "SELECT count(*) FROM cash_account WHERE season_id = ?", seasonId));
        out.put("lineup", count(jdbc, "SELECT count(*) FROM lineup WHERE season_id = ?", seasonId));
        out.put("balance", jdbc.queryForObject("SELECT balance::text FROM cash_account WHERE season_id = ?",
                String.class, seasonId));
        // 행 수만으로는 값이 바뀐 것(수량·평균단가·구간 종료·예약금·버전)을 못 본다 — 값 요약도 함께 비교한다
        out.put("holding_rows", jdbc.queryForObject("""
                SELECT coalesce(string_agg(asset_id || ':' || quantity || ':' || avg_price::text, ',' ORDER BY asset_id), '')
                FROM holding WHERE season_id = ?""", String.class, seasonId));
        out.put("lineup_rows", jdbc.queryForObject("""
                SELECT coalesce(string_agg(asset_id || ':' || declared_position || ':' || start_turn || ':'
                                           || coalesce(end_turn::text, '-'), ',' ORDER BY id), '')
                FROM lineup WHERE season_id = ?""", String.class, seasonId));
        out.put("account", jdbc.queryForObject("SELECT reserved::text || '/' || version FROM cash_account WHERE season_id = ?",
                String.class, seasonId));
        for (String table : List.of("orders", "execution", "cash_ledger")) {
            out.put("identity_" + table, jdbc.queryForObject(
                    "SELECT coalesce(pg_sequence_last_value(pg_get_serial_sequence('" + table + "', 'id')), 0)",
                    Long.class));
        }
        return out;
    }

    private static long count(JdbcTemplate jdbc, String sql, long seasonId) {
        return jdbc.queryForObject(sql, Long.class, seasonId);
    }

    /** 합성 풀에 가격이 정해진 종목을 더한다(수수료 경계·0원 시험용). 이름이 '가짜'로 시작해 정리 SQL 이 지운다. */
    static long addAsset(JdbcTemplate jdbc, long seasonId, String market, String ticker, String price) {
        Long group = jdbc.queryForObject("SELECT id FROM asset_group WHERE code = 'FAKE_SEMI'", Long.class);
        Long asset = jdbc.queryForObject("""
                INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
                VALUES (?, ?, '가짜 경계', 'STOCK', ?, ?, DATE '2000-01-04', 'PRICE') RETURNING id""",
                Long.class, market, ticker, market.equals("KR") ? "KRW" : "USD", group);
        jdbc.update("INSERT INTO turn_price (season_id, turn_no, asset_id, price) VALUES (?, 0, ?, ?::numeric)",
                seasonId, asset, price);
        return asset;
    }
}
