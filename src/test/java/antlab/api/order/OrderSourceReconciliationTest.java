package antlab.api.order;

import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A season with FEED and ALERT orders over real turn advances: every advance reconciles green, and the S6 verification query
 * (a copy of the SOURCE_FEED check of the SQL reference) finds nothing. Then single stored values are flipped inside a
 * transaction that is rolled back, to show the query bites. Synthetic fixtures only.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class OrderSourceReconciliationTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private static String s6() throws IOException {
        return new ClassPathResource("order-source/s6.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    private long violations(long season) throws IOException {
        return jdbc.queryForObject(s6(), Long.class, season);
    }

    private void order(long season, int turn, String ticker, String source) throws Exception {
        String base = body(turn, "BUY", assetId(jdbc, ticker), 1, null);
        String request = base.substring(0, base.length() - 1) + ", \"source\": " + source + "}";
        MockHttpServletResponse r = place(mvc, season, request);
        assertThat(r.getStatus()).as(request + " -> " + r.getContentAsString()).isEqualTo(201);
    }

    @Test
    void T11_orders_with_a_feed_origin_reconcile_green_and_pass_S6_and_S6_bites() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);

        // turn 1: a FEED order from a stored item of the generator, and an ALERT order from a planted alert after the last seq
        Map<String, Object> shown = jdbc.queryForMap("SELECT seq, asset_id FROM turn_feed WHERE season_id = ? AND turn_no = 1"
                + " ORDER BY seq LIMIT 1", season);
        Long chip = (Long) shown.get("asset_id");
        String ticker = chip == null ? "069500" : jdbc.queryForObject("SELECT ticker FROM asset WHERE id = ?", String.class, chip);
        order(season, 1, ticker, "{\"kind\":\"FEED\",\"feedTurnNo\":1,\"feedSeq\":" + shown.get("seq") + "}");
        int alertSeq = jdbc.queryForObject("SELECT max(seq) + 1 FROM turn_feed WHERE season_id = ? AND turn_no = 1", Integer.class, season);
        jdbc.update("INSERT INTO turn_feed (season_id, turn_no, seq, kind, template_id, body) VALUES (?, 1, ?, 'NUDGE', 'TEST_ROW', 'test row')",
                season, alertSeq);
        order(season, 1, "069500", "{\"kind\":\"ALERT\",\"feedTurnNo\":1,\"feedSeq\":" + alertSeq + "}");

        // turn 2: the turn-1 FEED item again (an earlier turn, allowed)
        assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        order(season, 2, ticker, "{\"kind\":\"FEED\",\"feedTurnNo\":1,\"feedSeq\":" + shown.get("seq") + "}");

        for (int turn = 2; turn < 12; turn++) {
            MockHttpServletResponse r = advance(mvc, season, turn);
            assertThat(r.getStatus()).as("advance from " + turn).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season)).isEqualTo("FINISHED");
        List<Map<String, Object>> sources = jdbc.queryForList(
                "SELECT source, turn_no FROM orders WHERE season_id = ? AND source <> 'DIRECT' ORDER BY id", season);
        assertThat(sources).extracting(row -> row.get("source")).containsExactly("FEED", "ALERT", "FEED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND status <> 'MATCH'",
                Long.class, season)).as("every turn reconciled green").isZero();
        assertThat(violations(season)).as("S6 finds nothing in what Java wrote").isZero();

        // the query bites: flip one stored value at a time and roll back
        flipsOne(season, "UPDATE orders SET source = 'ALERT' WHERE id = (SELECT min(id) FROM orders WHERE season_id = %d AND source = 'FEED')");
        flipsOne(season, "UPDATE orders SET source = 'FEED' WHERE id = (SELECT min(id) FROM orders WHERE season_id = %d AND source = 'ALERT')");
        flipsOne(season, "UPDATE turn_feed SET turn_no = 99 WHERE id = (SELECT turn_feed_id FROM orders WHERE season_id = %d"
                + " AND source = 'ALERT' LIMIT 1)");
        assertThat(violations(season)).as("the flips were rolled back").isZero();
    }

    private void flipsOne(long season, String template) {
        tx.executeWithoutResult(status -> {
            int changed = jdbc.update(template.formatted(season));
            assertThat(changed).isEqualTo(1);
            try {
                assertThat(violations(season)).as(template).isEqualTo(1);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            status.setRollbackOnly();
        });
    }
}
