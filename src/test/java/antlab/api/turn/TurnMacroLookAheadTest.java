package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Metamorphic look-ahead check of the macro environment: change the regime values of every later month and nothing a played
 * turn shows may move; change the month of the turn itself and exactly its macro part must move.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql", "/fixtures/replay_macro.sql"})
@Sql(scripts = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnMacroLookAheadTest {

    private static final int CUT = 3;
    private static final String OVERWRITE = """
            UPDATE month_block SET rate_dir = 'NEUTRAL', rate_strength = 9.99999999, growth_dir = 'NEUTRAL', growth_strength = 8.88888888,
                fx_dir = 'NEUTRAL', fx_strength = 7.77777777, sentiment_dir = 'NEUTRAL', sentiment_strength = 6.66666666,
                prob_p1 = 0.4000, prob_p2 = 0.3000, prob_p3 = 0.2000, prob_p4 = 0.1000, regime_label = 'P1'
            WHERE id IN (%s)""";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    /** A season advanced to the cut; a path never repeats a month, so no later month is also an earlier one. */
    private long seasonWithDistinctSides() throws Exception {
        long season = startSeason(mvc);
        List<Long> blocks = MacroSupport.pathBlocks(jdbc, season);
        assertThat(new HashSet<>(blocks)).as("a path never repeats a month").hasSameSizeAs(blocks);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < CUT; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        return season;
    }

    private void overwrite(List<Long> blocks) {
        String ids = String.join(",", blocks.stream().map(String::valueOf).toList());
        assertThat(jdbc.update(OVERWRITE.formatted(ids))).isPositive();
    }

    private List<String> bodies(long season) {
        List<String> out = new ArrayList<>();
        for (int turn = 1; turn <= CUT; turn++) {
            MockHttpServletResponse r = getTurn(mvc, season, turn);
            assertThat(r.getStatus()).isEqualTo(200);
            try {
                out.add(r.getContentAsString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    @Test
    void changing_the_regime_values_of_every_later_month_leaves_the_played_turns_byte_identical() throws Exception {
        long season = seasonWithDistinctSides();
        List<String> before = bodies(season);
        List<Long> blocks = MacroSupport.pathBlocks(jdbc, season);

        overwrite(blocks.subList(CUT, blocks.size()));

        assertThat(bodies(season)).as("turns 1.." + CUT + " ignore the later months").isEqualTo(before);
        MockHttpServletResponse ahead = getTurn(mvc, season, CUT + 1);
        assertThat(ahead.getStatus()).isEqualTo(409);
        assertThat(json(ahead).get("code").asString()).isEqualTo("TURN_NOT_REACHED");
        assertThat(ahead.getContentAsString()).doesNotContain("macro").doesNotContain("9.99999999");
    }

    @Test
    void changing_the_month_of_the_turn_itself_moves_only_its_macro_and_the_next_advance_shows_the_overwritten_month()
            throws Exception {
        long season = seasonWithDistinctSides();
        List<String> before = bodies(season);
        List<Long> blocks = MacroSupport.pathBlocks(jdbc, season);

        overwrite(List.of(blocks.get(CUT - 1)));
        List<String> after = bodies(season);

        for (int i = 0; i < CUT - 1; i++) {
            assertThat(after.get(i)).as("turn " + (i + 1) + " does not use the month of turn " + CUT).isEqualTo(before.get(i));
        }
        ObjectNode changed = (ObjectNode) tree(after.get(CUT - 1));
        ObjectNode original = (ObjectNode) tree(before.get(CUT - 1));
        assertThat(changed.get("macro")).isNotEqualTo(original.get("macro"));
        assertThat(changed.get("macro")).isEqualTo(MacroSupport.expected(jdbc, season, CUT));
        changed.remove("macro");
        original.remove("macro");
        assertThat(changed).as("everything but macro is unchanged").isEqualTo(original);

        overwrite(List.of(blocks.get(CUT)));
        MockHttpServletResponse next = advance(mvc, season, CUT);
        assertThat(next.getStatus()).isEqualTo(201);
        assertThat(json(next).get("macro")).isEqualTo(MacroSupport.expected(jdbc, season, CUT + 1));
        assertThat(json(next).get("macro").get("axes").get("rate").get("strength").asString()).isEqualTo("9.99999999");
    }

    private static void assertOneTurnRow(List<String> sqls, String where) {
        List<String> macro = sqls.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("prob_p1")).toList();
        assertThat(macro).as(where).hasSize(1);
        String sql = macro.get(0).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertThat(sql).contains("turn_no=?").doesNotContain("turn_no>").doesNotContain("turn_no<").doesNotContain("between")
                .doesNotContain("turn_no+").doesNotContain("turn_no-").doesNotContain("order by");
    }

    @Test
    void the_advance_macro_statement_asks_for_one_turn_row_and_never_a_range() throws Exception {
        long season = seasonWithDistinctSides();
        antlab.support.SqlRecorder.start();
        MockHttpServletResponse r = advance(mvc, season, CUT);
        List<String> sqls = antlab.support.SqlRecorder.stop();
        assertThat(r.getStatus()).isEqualTo(201);
        assertOneTurnRow(sqls, "advance");
    }

    @Test
    void the_macro_statements_ask_for_one_turn_row_and_never_a_range() throws Exception {
        long season = seasonWithDistinctSides();
        for (int turn = 1; turn <= CUT; turn++) {
            antlab.support.SqlRecorder.start();
            MockHttpServletResponse r = getTurn(mvc, season, turn);
            List<String> sqls = antlab.support.SqlRecorder.stop();
            assertThat(r.getStatus()).isEqualTo(200);
            assertOneTurnRow(sqls, "turn " + turn);
        }
    }

    private static JsonNode tree(String text) {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(text);
    }
}
