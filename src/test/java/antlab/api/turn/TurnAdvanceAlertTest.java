package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.feed.AlertGenerator;
import antlab.feed.AlertInput;
import antlab.feed.FeedGenerator;
import antlab.feed.FeedInput;
import antlab.feed.FeedInputReader;
import antlab.feed.GeneratedFeedRow;
import antlab.feed.PlayerFacts;
import antlab.feed.PlayerFactsReader;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The nudge alerts of the turn advance: stored after the feed rows with the following {@code seq}, more of them the longer the player
 * has not traded, answered in {@code alerts} (the feed list holds no alert), and equal to what the generator makes of the same stored
 * facts. Synthetic fixtures only.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceAlertTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FeedInputReader reader;
    @Autowired
    FeedGenerator feedGenerator;
    @Autowired
    AlertGenerator alertGenerator;
    @Autowired
    PlayerFactsReader playerFacts;

    private long seasonWithTeam() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        return season;
    }

    private JsonNode advanced(long season, int turn) throws Exception {
        MockHttpServletResponse r = advance(mvc, season, turn);
        assertThat(r.getStatus()).isEqualTo(201);
        return json(r);
    }

    private int stored(long season, int turn, boolean alerts) {
        return jdbc.queryForObject("SELECT count(*) FROM turn_feed WHERE season_id = ? AND turn_no = ? AND (kind = 'NUDGE') = ?",
                Integer.class, season, turn, alerts);
    }

    @Test
    void the_alert_count_grows_with_every_interval_without_a_trade_up_to_four() throws Exception {
        long season = seasonWithTeam();
        // interval 0 holds four trades, then nothing: idle intervals 0, 1, 2, 3, 4 -> 1, 2, 3, 4, 4 alerts
        int[] expected = {1, 2, 3, 4, 4};
        for (int turn = 0; turn < expected.length; turn++) {
            JsonNode result = advanced(season, turn);
            int shown = turn + 1;
            assertThat(result.get("alerts")).as("turn " + shown).hasSize(expected[turn]);
            assertThat(stored(season, shown, true)).isEqualTo(expected[turn]);
            assertThat(stored(season, shown, false)).as("the feed is not touched").isEqualTo(result.get("feed").size());

            // one numbering across both lists: the feed first, the alerts after it, no gap and no repeat
            List<Integer> seqs = jdbc.queryForList("SELECT seq FROM turn_feed WHERE season_id = ? AND turn_no = ? ORDER BY seq",
                    Integer.class, season, shown);
            for (int i = 0; i < seqs.size(); i++) {
                assertThat(seqs.get(i)).isEqualTo(i + 1);
            }
            int feedRows = result.get("feed").size();
            for (int i = 0; i < expected[turn]; i++) {
                assertThat(result.get("alerts").get(i).get("seq").asInt()).isEqualTo(feedRows + i + 1);
                assertThat(result.get("alerts").get(i).get("turnNo").asInt()).isEqualTo(shown);
                assertThat(result.get("alerts").get(i).propertyNames()).containsExactlyInAnyOrder("turnNo", "seq", "assetId", "body");
            }
            for (JsonNode item : result.get("feed")) {
                assertThat(item.get("seq").asInt()).as("a feed item is not an alert").isLessThanOrEqualTo(feedRows);
            }

            JsonNode looked = json(getTurn(mvc, season, shown));
            assertThat(looked.get("alerts")).as("the lookup answers the same alerts").isEqualTo(result.get("alerts"));
            assertThat(looked.get("feed")).isEqualTo(result.get("feed"));
        }
    }

    @Test
    void a_trade_in_the_last_interval_gives_one_alert_and_the_idle_count_restarts_from_it() throws Exception {
        long quiet = seasonWithTeam();
        long active = seasonWithTeam();
        for (long season : new long[] {quiet, active}) {
            assertThat(advanced(season, 0).get("alerts")).hasSize(1);
            assertThat(advanced(season, 1).get("alerts")).hasSize(2);
        }
        buy(mvc, jdbc, active, 2, "069500", 1);
        JsonNode quietTurn3 = advanced(quiet, 2);
        JsonNode activeTurn3 = advanced(active, 2);
        assertThat(quietTurn3.get("alerts")).as("two zero-trade intervals in a row").hasSize(3);
        assertThat(activeTurn3.get("alerts")).as("a trade in the last interval").hasSize(1);
        assertThat(quietTurn3.get("alerts").size()).isGreaterThan(activeTurn3.get("alerts").size());
        assertThat(advanced(active, 3).get("alerts")).as("one idle interval after the trade").hasSize(2);
        assertThat(advanced(active, 4).get("alerts")).hasSize(3);
    }

    @Test
    void the_player_facts_count_executions_of_the_interval_before_the_turn() throws Exception {
        long season = seasonWithTeam();
        for (int turn = 0; turn < 3; turn++) {
            advanced(season, turn);
        }
        buy(mvc, jdbc, season, 3, "069500", 1);
        advanced(season, 3);
        BigDecimal cash = new BigDecimal("28000000");
        BigDecimal total = new BigDecimal("30000000");
        // intervals: 0 = four trades, 1 and 2 = none, 3 = one trade
        assertFacts(playerFacts.read(season, 1, cash, total), 4, 0);
        assertFacts(playerFacts.read(season, 2, cash, total), 0, 1);
        assertFacts(playerFacts.read(season, 3, cash, total), 0, 2);
        assertFacts(playerFacts.read(season, 4, cash, total), 1, 0);
        assertFacts(playerFacts.read(season, 5, cash, total), 0, 1);
        assertFacts(playerFacts.read(season, 6, cash, total), 0, 2);
        PlayerFacts first = playerFacts.read(season, 1, cash, total);
        assertThat(first.cashWeight()).isEqualByComparingTo("0.93333333");
        assertThat(first.cashWeight().scale()).isEqualTo(8);
        assertThat(playerFacts.read(season, 1, cash, BigDecimal.ZERO).cashWeight()).as("no total, no share").isNull();
    }

    @Test
    void a_season_that_never_traded_is_idle_from_interval_zero() throws Exception {
        long season = startSeason(mvc);
        BigDecimal cash = new BigDecimal("30000000");
        // no order at all: turn 1 follows the idle interval 0, turn 2 follows 0 and 1
        assertFacts(playerFacts.read(season, 1, cash, cash), 0, 1);
        assertFacts(playerFacts.read(season, 2, cash, cash), 0, 2);
        assertFacts(playerFacts.read(season, 3, cash, cash), 0, 3);
    }

    @Test
    void an_order_that_never_filled_is_not_a_trade() throws Exception {
        long season = seasonWithTeam();
        advanced(season, 0);
        Long asset = jdbc.queryForObject("SELECT id FROM asset WHERE ticker = '069500'", Long.class);
        // interval 1 holds one pending order and one rejected order, neither with an execution row
        for (String status : new String[] {"PENDING", "REJECTED"}) {
            jdbc.update("INSERT INTO orders (season_id, asset_id, turn_no, side, quantity, status, source)"
                    + " VALUES (?, ?, 1, 'BUY', 1, ?, 'DIRECT')", season, asset, status);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders o JOIN execution e ON e.order_id = o.id"
                + " WHERE o.season_id = ? AND o.turn_no = 1", Integer.class, season)).isZero();
        BigDecimal cash = new BigDecimal("28000000");
        BigDecimal total = new BigDecimal("30000000");
        assertFacts(playerFacts.read(season, 2, cash, total), 0, 1);
        assertFacts(playerFacts.read(season, 3, cash, total), 0, 2);
    }

    private static void assertFacts(PlayerFacts facts, int tradeCount, int idleTurns) {
        assertThat(facts.tradeCount()).as("trade count at turn " + facts.asOfTurn()).isEqualTo(tradeCount);
        assertThat(facts.idleTurns()).as("idle intervals at turn " + facts.asOfTurn()).isEqualTo(idleTurns);
    }

    @Test
    void every_stored_alert_equals_what_the_generator_makes_of_the_same_stored_facts() throws Exception {
        long season = seasonWithTeam();
        for (int turn = 0; turn < 4; turn++) {
            advanced(season, turn);
        }
        long seed = jdbc.queryForObject("SELECT seed FROM season WHERE id = ?", Long.class, season);
        int compared = 0;
        for (int turn = 1; turn <= 4; turn++) {
            Map<Long, BigDecimal> prices = new LinkedHashMap<>();
            jdbc.query("SELECT asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = ?", rs -> {
                prices.put(rs.getLong("asset_id"), rs.getBigDecimal("price"));
            }, season, turn);
            FeedInput input = reader.read(season, seed, turn, prices);
            int feedRows = feedGenerator.generate(input).size();
            Map<String, Object> snapshot = jdbc.queryForMap("SELECT cash_balance, total_value FROM turn WHERE season_id = ?"
                    + " AND turn_no = ?", season, turn);
            PlayerFacts facts = playerFacts.read(season, turn, (BigDecimal) snapshot.get("cash_balance"),
                    (BigDecimal) snapshot.get("total_value"));
            List<GeneratedFeedRow> expected = alertGenerator.generate(new AlertInput(seed, turn, input.assets(), facts), feedRows + 1);
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT seq, kind, asset_id, asset_group_id, month_event_id,"
                    + " category_hidden, direction, strength, repeat_count, template_id, template_vars::text AS vars, body"
                    + " FROM turn_feed WHERE season_id = ? AND turn_no = ? AND kind = 'NUDGE' ORDER BY seq", season, turn);
            assertThat(rows).as("turn " + turn).hasSameSizeAs(expected);
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                GeneratedFeedRow want = expected.get(i);
                assertThat(((Number) row.get("seq")).intValue()).isEqualTo(want.seq());
                assertThat(row.get("kind")).isEqualTo("NUDGE");
                assertThat(row.get("asset_id") == null ? null : ((Number) row.get("asset_id")).longValue()).isEqualTo(want.assetId());
                assertThat(row.get("asset_group_id")).isNull();
                assertThat(row.get("month_event_id")).isNull();
                assertThat(row.get("category_hidden")).isEqualTo(false);
                assertThat(row.get("direction")).isNull();
                assertThat(row.get("strength") == null ? null : ((Number) row.get("strength")).intValue()).isEqualTo(want.strength());
                assertThat(((Number) row.get("repeat_count")).intValue()).isZero();
                assertThat(row.get("template_id")).isEqualTo(want.templateId());
                assertThat(row.get("vars")).isEqualTo(want.templateVarsJson());
                assertThat(row.get("body")).isEqualTo(want.body());
                assertThat(((String) row.get("body")).length()).isLessThanOrEqualTo(300);
                compared++;
            }
        }
        assertThat(compared).isPositive();
    }

    @Test
    void the_feed_rows_of_a_turn_are_the_same_with_and_without_alerts() throws Exception {
        long season = seasonWithTeam();
        advanced(season, 0);
        long seed = jdbc.queryForObject("SELECT seed FROM season WHERE id = ?", Long.class, season);
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        jdbc.query("SELECT asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = 1", rs -> {
            prices.put(rs.getLong("asset_id"), rs.getBigDecimal("price"));
        }, season);
        List<GeneratedFeedRow> feedOnly = feedGenerator.generate(reader.read(season, seed, 1, prices));
        List<String> storedFeed = jdbc.queryForList("SELECT template_id || '|' || body FROM turn_feed WHERE season_id = ?"
                + " AND turn_no = 1 AND kind <> 'NUDGE' ORDER BY seq", String.class, season);
        assertThat(storedFeed).isEqualTo(feedOnly.stream().map(r -> r.templateId() + "|" + r.body()).toList());
    }
}
