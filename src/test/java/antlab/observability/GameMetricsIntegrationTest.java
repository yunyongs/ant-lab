package antlab.observability;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Counter deltas through the real controllers, facade and transactions. The context is shared, so only deltas are asserted. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class GameMetricsIntegrationTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry registry;

    private double count(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private double seasonCommitted() {
        return count("antlab.season.start", "outcome", "committed", "code", "none");
    }

    private double turnCommitted() {
        return count("antlab.turn.advance", "outcome", "committed", "code", "none");
    }

    private double turnRejected(String code) {
        return count("antlab.turn.advance", "outcome", "rejected", "code", code);
    }

    private double mismatchRules() {
        return count("antlab.reconciliation.mismatch.rules");
    }

    private double orderRejected(String code) {
        return count("antlab.order.rejected", "code", code);
    }

    @Test
    void 시즌_시작과_전환_성공은_committed가_1_늘고_기각은_늘지_않는다() throws Exception {
        double seasonBefore = seasonCommitted();
        long season = startSeason(mvc);
        assertThat(seasonCommitted()).isEqualTo(seasonBefore + 1);

        buyTeam(mvc, jdbc, season);
        double turnBefore = turnCommitted();
        double rejectedBefore = turnRejected("RECONCILIATION_FAILED");
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(turnCommitted()).isEqualTo(turnBefore + 1);
        assertThat(turnRejected("RECONCILIATION_FAILED")).isEqualTo(rejectedBefore);
    }

    @Test
    void 롤백된_전환은_rejected와_불일치_규칙_2개이고_committed는_그대로다() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        double committedBefore = turnCommitted();
        double rejectedBefore = turnRejected("RECONCILIATION_FAILED");
        double rulesBefore = mismatchRules();
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(409);
        assertThat(turnCommitted()).isEqualTo(committedBefore);
        assertThat(turnRejected("RECONCILIATION_FAILED")).isEqualTo(rejectedBefore + 1);
        assertThat(mismatchRules()).isEqualTo(rulesBefore + 2);
    }

    @Test
    void 거절된_주문은_코드별로_세고_주문_행은_남지_않는다() throws Exception {
        long season = startSeason(mvc);
        long orders = jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, season);
        double before = orderRejected("TURN_MISMATCH");
        String body = "{\"expectedTurnNo\": 5, \"side\": \"BUY\", \"assetId\": " + assetId(jdbc, "069500") + ", \"quantity\": 1,"
                + " \"declaredPosition\": \"MIDFIELDER\"}";
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons/" + season + "/orders")
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(orderRejected("TURN_MISMATCH")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE season_id = ?", Long.class, season)).isEqualTo(orders);
    }
}
