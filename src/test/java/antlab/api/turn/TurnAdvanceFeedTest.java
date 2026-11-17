package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The feed inside the turn advance: stored with the turn after the snapshot and before reconciliation, answered as the stored rows,
 * and rolled back with everything else when it cannot be made (an unreviewed block, an event without a reaction) or when
 * reconciliation fails. Synthetic fixtures only (fake months are marked as reviewed).
 */
@ExtendWith(OutputCaptureExtension.class)
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceFeedTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    antlab.feed.FeedInputReader reader;
    @Autowired
    antlab.feed.FeedGenerator generator;

    private long seasonWithTeam() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        return season;
    }

    private List<Map<String, Object>> stored(long season, int turn) {
        return jdbc.queryForList("SELECT turn_no, seq, asset_id, body FROM turn_feed WHERE season_id = ? AND turn_no = ?"
                + " ORDER BY seq", season, turn);
    }

    /** The response items as the same {turn_no, seq, asset_id, body} maps the table gives. */
    private static List<Map<String, Object>> items(JsonNode feed) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode item : feed) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("turn_no", item.get("turnNo").asInt());
            row.put("seq", item.get("seq").asInt());
            row.put("asset_id", item.get("assetId").isNull() ? null : item.get("assetId").asLong());
            row.put("body", item.get("body").asString());
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> normalised(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            copy.put("turn_no", ((Number) row.get("turn_no")).intValue());
            copy.put("seq", ((Number) row.get("seq")).intValue());
            copy.put("asset_id", row.get("asset_id") == null ? null : ((Number) row.get("asset_id")).longValue());
            copy.put("body", row.get("body"));
            out.add(copy);
        }
        return out;
    }

    private long blockOfTurn(long season, int turn) {
        return jdbc.queryForObject("SELECT month_block_id FROM season_path WHERE season_id = ? AND turn_no = ?", Long.class, season,
                turn);
    }

    @Test
    void the_response_feed_is_the_stored_rows_in_display_order_with_no_gaps() throws Exception {
        long season = seasonWithTeam();
        int total = 0;
        for (int turn = 0; turn < 3; turn++) {
            MockHttpServletResponse r = advance(mvc, season, turn);
            assertThat(r.getStatus()).isEqualTo(201);
            List<Map<String, Object>> rows = stored(season, turn + 1);
            List<Map<String, Object>> shown = items(json(r).get("feed"));
            assertThat(shown).as("turn " + (turn + 1)).isEqualTo(normalised(rows));
            assertThat(rows).hasSizeLessThanOrEqualTo(15);
            for (int i = 0; i < rows.size(); i++) {
                assertThat(((Number) rows.get(i).get("seq")).intValue()).isEqualTo(i + 1);
            }
            total += rows.size();
        }
        assertThat(total).as("a season of four fake assets produces some feed").isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_feed WHERE season_id = ? AND turn_no > 3", Long.class, season))
                .isZero();
    }

    @Test
    void every_stored_column_equals_what_the_generator_makes_of_the_same_input() throws Exception {
        long season = seasonWithTeam();
        for (int turn = 0; turn < 4; turn++) {
            assertThat(advance(mvc, season, turn).getStatus()).isEqualTo(201);
        }
        long seed = jdbc.queryForObject("SELECT seed FROM season WHERE id = ?", Long.class, season);
        int compared = 0;
        for (int turn = 1; turn <= 4; turn++) {
            java.util.Map<Long, java.math.BigDecimal> prices = new java.util.LinkedHashMap<>();
            final int t = turn;
            jdbc.query("SELECT asset_id, price FROM turn_price WHERE season_id = ? AND turn_no = ?", rs -> {
                prices.put(rs.getLong("asset_id"), rs.getBigDecimal("price"));
            }, season, t);
            List<antlab.feed.GeneratedFeedRow> expected = generator.generate(reader.read(season, seed, turn, prices));
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT seq, kind, direction, strength, repeat_count, template_id,"
                    + " template_vars::text AS vars, category_hidden, body FROM turn_feed WHERE season_id = ? AND turn_no = ?"
                    + " ORDER BY seq", season, turn);
            assertThat(rows).as("turn " + turn).hasSameSizeAs(expected);
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                antlab.feed.GeneratedFeedRow want = expected.get(i);
                assertThat(row.get("kind")).isEqualTo(want.kind().name());
                assertThat(row.get("direction")).isEqualTo(want.direction() == null ? null : want.direction().name());
                assertThat(row.get("strength") == null ? null : ((Number) row.get("strength")).intValue()).isEqualTo(want.strength());
                assertThat(((Number) row.get("repeat_count")).intValue()).as("repeat of " + want.kind()).isEqualTo(want.repeatCount());
                assertThat(row.get("template_id")).isEqualTo(want.templateId());
                assertThat(row.get("vars")).isEqualTo(want.templateVarsJson());
                assertThat(row.get("category_hidden")).isEqualTo(want.categoryHidden());
                assertThat(row.get("body")).isEqualTo(want.body());
                compared++;
            }
        }
        assertThat(compared).isPositive();
    }

    @Test
    void an_event_of_the_block_becomes_a_card_with_no_chip_and_a_rare_type_hides_its_category() throws Exception {
        long season = seasonWithTeam();
        jdbc.update("INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, direction, strength,"
                + " reaction_change, title, source) VALUES ('ZT12_RARE_01', ?, 'POLICY', 'T12_RARE', 'START', 'FAKE_OUTCOME', 'DOWN',"
                + " 2, -0.04000000, 'FAKE title', 'FAKE source')", blockOfTurn(season, 1));
        try {
            MockHttpServletResponse r = advance(mvc, season, 0);
            assertThat(r.getStatus()).isEqualTo(201);
            Map<String, Object> card = jdbc.queryForMap("SELECT kind, asset_id, asset_group_id, category_hidden, template_id,"
                    + " body FROM turn_feed WHERE season_id = ? AND turn_no = 1 AND kind = 'EVENT'", season);
            assertThat(card.get("asset_id")).isNull();
            assertThat(card.get("asset_group_id")).isNull();
            assertThat(card.get("category_hidden")).isEqualTo(true);
            assertThat((String) card.get("template_id")).startsWith("event.hidden");
            assertThat((String) card.get("body")).endsWith("-4%");
            assertThat(items(json(r).get("feed"))).extracting(row -> row.get("body")).contains(card.get("body"));
        } finally {
            jdbc.update("DELETE FROM turn_feed WHERE season_id = ?", season);
            jdbc.update("DELETE FROM month_event WHERE event_key = 'ZT12_RARE_01'");
        }
    }

    @Test
    void a_block_that_is_not_reviewed_is_a_500_and_nothing_of_the_turn_is_kept(CapturedOutput output) throws Exception {
        long season = seasonWithTeam();
        Map<String, Object> before = state(jdbc, season);
        long block = blockOfTurn(season, 1);
        jdbc.update("UPDATE month_block SET events_reviewed = FALSE WHERE id = ?", block);
        try {
            MockHttpServletResponse r = advance(mvc, season, 0);
            assertThat(r.getStatus()).isEqualTo(500);
            assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
            assertThat(r.getContentAsString()).doesNotContain("FEED_").doesNotContain("reviewed");
            assertThat(state(jdbc, season)).as("a failed feed leaves turn, prices, feed, log and season as they were").isEqualTo(before);
            assertThat(output.getAll()).contains("피드 생성 실패").contains("FEED_EVENTS_UNREVIEWED");
        } finally {
            jdbc.update("UPDATE month_block SET events_reviewed = TRUE WHERE id = ?", block);
        }
        assertThat(advance(mvc, season, 0).getStatus()).as("the same turn advances once the block is reviewed").isEqualTo(201);
    }

    @Test
    void an_event_without_a_reaction_is_a_500_and_names_only_the_failure_code(CapturedOutput output) throws Exception {
        long season = seasonWithTeam();
        Map<String, Object> before = state(jdbc, season);
        jdbc.update("INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, outcome, direction, strength,"
                + " title, source) VALUES ('ZT12_NOREACT', ?, 'TRADE', 'T12_NOREACT', 'START', 'FAKE_OUTCOME', 'DOWN', 2,"
                + " 'FAKE title', 'FAKE source')", blockOfTurn(season, 1));
        try {
            MockHttpServletResponse r = advance(mvc, season, 0);
            assertThat(r.getStatus()).isEqualTo(500);
            assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
            assertThat(state(jdbc, season)).isEqualTo(before);
            assertThat(output.getAll()).contains("FEED_REACTION_MISSING").doesNotContain("ZT12_NOREACT");
        } finally {
            jdbc.update("DELETE FROM month_event WHERE event_key = 'ZT12_NOREACT'");
        }
    }

    @Test
    void a_stored_row_that_breaks_a_constraint_is_a_500_with_a_store_failure_line(CapturedOutput output) throws Exception {
        long season = seasonWithTeam();
        jdbc.update("INSERT INTO turn_feed (season_id, turn_no, seq, kind, template_id, body) VALUES (?, 1, 1, 'NUDGE', 'fake.v1',"
                + " 'FAKE body')", season);
        Map<String, Object> before = state(jdbc, season);

        MockHttpServletResponse r = advance(mvc, season, 0);

        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        assertThat(state(jdbc, season)).as("the turn is rolled back as a whole").isEqualTo(before);
        assertThat(output.getAll()).contains("피드 저장 실패").contains("FEED_STORE").doesNotContain("FAKE body");
    }

    @Test
    void a_reconciliation_mismatch_leaves_no_feed_but_the_failure_record_stays() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = advance(mvc, season, 0);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("RECONCILIATION_FAILED");
        Map<String, Object> after = state(jdbc, season);
        assertThat(after.get("turn_feed")).as("the feed is part of the rolled-back turn").isEqualTo(before.get("turn_feed")).isEqualTo(0L);
        assertThat(after.get("turn")).isEqualTo(before.get("turn"));
        assertThat((Long) after.get("reconciliation_log")).isEqualTo((Long) before.get("reconciliation_log") + 17);
    }

    @Test
    void the_feed_rows_are_written_after_the_snapshot_and_before_reconciliation() throws Exception {
        long season = seasonWithTeam();
        SqlRecorder.start();
        MockHttpServletResponse r = advance(mvc, season, 0);
        List<String> sqls = SqlRecorder.stop().stream().map(q -> q.toLowerCase(Locale.ROOT)).toList();
        assertThat(r.getStatus()).isEqualTo(201);

        int snapshot = indexOf(sqls, q -> q.startsWith("insert into turn "));
        int firstFeed = indexOf(sqls, q -> q.startsWith("insert into turn_feed"));
        int lastFeed = -1;
        for (int i = 0; i < sqls.size(); i++) {
            if (sqls.get(i).startsWith("insert into turn_feed")) {
                lastFeed = i;
            }
        }
        int firstRule = indexOf(sqls, q -> q.contains("recon_") || q.contains("jsonb_build_object"));
        assertThat(snapshot).isGreaterThanOrEqualTo(0);
        assertThat(firstFeed).as("feed rows were written").isGreaterThan(snapshot);
        assertThat(firstRule).as("reconciliation rules ran").isGreaterThan(lastFeed);
        assertThat(sqls.stream().filter(q -> q.startsWith("insert into turn_feed"))).hasSize(stored(season, 1).size());
    }

    private static int indexOf(List<String> sqls, java.util.function.Predicate<String> match) {
        for (int i = 0; i < sqls.size(); i++) {
            if (match.test(sqls.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
