package antlab.api.report;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * Report source query boundaries that need the real tables but no golden sample: a quantity that is exactly zero
 * (the holdings query drops it, the report must still be 200) and per-turn summing of the cumulative quantity
 * (order of fills inside one turn does not matter, a dip below zero at a turn boundary does).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReportQueryBoundaryTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private void sellAll(long season, int turn, String ticker, int qty) {
        String body = "{\"expectedTurnNo\": " + turn + ", \"side\": \"SELL\", \"assetId\": " + assetId(jdbc, ticker)
                + ", \"quantity\": " + qty + "}";
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/orders")
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
        assertThat(r.getStatus()).as("전량 매도").isEqualTo(201);
    }

    private JsonNode okReport(long season) throws Exception {
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, season);
        assertThat(r.getStatus()).isEqualTo(200);
        return json(r);
    }

    @Test
    void 전량_매도로_수량이_정확히_0이_된_시즌도_200이고_값이_저장값과_같다() throws Exception {
        long season = ReportApiSupport.season(mvc, jdbc, 1);
        // Turn 1: sell the whole BRK.B position (5), then play the remaining turns out
        sellAll(season, 1, "BRK.B", 5);
        for (int t = 1; t < 12; t++) {
            assertThat(advance(mvc, season, t).getStatus()).as("턴 전환 " + t).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season)).isEqualTo("FINISHED");
        JsonNode o = okReport(season);
        BigDecimal notional = jdbc.queryForObject("SELECT COALESCE(sum(e.fill_price * e.quantity), 0) FROM orders o "
                + "JOIN execution e ON e.order_id = o.id WHERE o.season_id = ? AND o.turn_no BETWEEN 1 AND 11",
                BigDecimal.class, season);
        BigDecimal fee = jdbc.queryForObject("SELECT COALESCE(sum(e.fee), 0) FROM orders o "
                + "JOIN execution e ON e.order_id = o.id WHERE o.season_id = ? AND o.turn_no BETWEEN 0 AND 11",
                BigDecimal.class, season);
        BigDecimal finalValue = jdbc.queryForObject("SELECT total_value FROM turn WHERE season_id = ? AND turn_no = 12",
                BigDecimal.class, season);
        assertThat(notional.signum()).as("매도 대금이 있다").isPositive();
        assertThat(new BigDecimal(o.get("tradeNotional").asString())).isEqualByComparingTo(notional);
        assertThat(new BigDecimal(o.get("totalFee").asString())).isEqualByComparingTo(fee);
        assertThat(new BigDecimal(o.get("finalValue").asString())).isEqualByComparingTo(finalValue);
    }

    @Test
    void 같은_턴의_순서_교환과_분할_체결은_같은_판정이다() throws Exception {
        long brk = assetId(jdbc, "BRK.B");
        // Holds 5 from turn 0. In turn 3 the net is zero (SELL 8, BUY 8): a per-order running check would see -3 first.
        long sellFirst = ReportApiSupport.finishedSeason(mvc, jdbc);
        ReportApiSupport.fill(jdbc, sellFirst, brk, 3, "SELL", 8);
        ReportApiSupport.fill(jdbc, sellFirst, brk, 3, "BUY", 8);
        okReport(sellFirst);
        long buyFirst = ReportApiSupport.finishedSeason(mvc, jdbc);
        ReportApiSupport.fill(jdbc, buyFirst, brk, 3, "BUY", 8);
        ReportApiSupport.fill(jdbc, buyFirst, brk, 3, "SELL", 8);
        okReport(buyFirst);
        long split = ReportApiSupport.finishedSeason(mvc, jdbc);
        ReportApiSupport.fill(jdbc, split, brk, 3, "SELL", 3, 5);
        ReportApiSupport.fill(jdbc, split, brk, 3, "BUY", 2, 6);
        okReport(split);
    }
}
