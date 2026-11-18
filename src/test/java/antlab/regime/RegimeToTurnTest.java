package antlab.regime;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static antlab.regime.RegimeFixtures.PATH;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.FakeEngineServer;
import antlab.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * From the engine's answer to the turn screen: the label job (against a fake engine) fills the empty regime columns of the
 * months, a season is played over them, and every turn shows the strings the engine sent, character by character. Probabilities
 * that add up to 0.9999 or 1.0001 stay as sent (no renormalising). A month the engine answered as CARRIED shows all null even
 * though the answer carried an fx axis for it.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class RegimeToTurnTest {

    private static final String TOKEN = "FAKE-ENGINE-TOKEN-0123456789-abcdefghijklmnop";
    private static final FakeEngineServer ENGINE = FakeEngineServer.start();
    private static final String[] DIRECTIONS = {"UP", "DOWN", "NEUTRAL"};

    @DynamicPropertySource
    static void engine(DynamicPropertyRegistry registry) {
        registry.add("antlab.engine.url", ENGINE::url);
        registry.add("antlab.engine.token", () -> TOKEN);
        registry.add("antlab.engine.regime-timeout", () -> "5s");
    }

    @AfterAll
    static void stop() {
        ENGINE.close();
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RegimeLabelJob job;

    @BeforeEach
    void reset() {
        ENGINE.reset();
    }

    private static String eight(int index, int axis) {
        return new BigDecimal(index).multiply(new BigDecimal("0.0137")).add(new BigDecimal("0.1").add(new BigDecimal(axis)))
                .setScale(8).toPlainString();
    }

    /** One answer for every month of the table: odd positions CARRIED, even ones EVALUATED with distinct values. */
    private ObjectNode answer(List<String> months) {
        ObjectNode answer = RegimeFixtures.tree();
        ObjectNode evaluatedTemplate = RegimeFixtures.month(answer, 0).deepCopy();
        ObjectNode carriedTemplate = RegimeFixtures.month(answer, 1).deepCopy();
        ArrayNode out = answer.putArray("months");
        int evaluated = 0;
        int carried = 0;
        for (int i = 0; i < months.size(); i++) {
            boolean isEvaluated = i % 2 == 0;
            ObjectNode month = (isEvaluated ? evaluatedTemplate : carriedTemplate).deepCopy();
            month.put("month", months.get(i));
            if (isEvaluated) {
                evaluated++;
                ObjectNode axes = (ObjectNode) month.get("axes");
                for (int a = 0; a < MacroNames.AXES.length; a++) {
                    if (MacroNames.AXES[a].equals("sentiment") && i % 8 == 0) {
                        axes.putNull("sentiment");
                    } else {
                        axes.set(MacroNames.AXES[a], tree("{\"direction\":\"" + DIRECTIONS[(i + a) % 3] + "\",\"strength\":\""
                                + eight(i, a) + "\"}"));
                    }
                }
                String p4 = i % 4 == 0 ? "0.2499" : "0.2501";
                month.set("probabilities", tree("{\"p1\":\"0.2500\",\"p2\":\"0.2500\",\"p3\":\"0.2500\",\"p4\":\"" + p4 + "\"}"));
                month.put("label", "P" + (1 + i % 4));
            } else {
                carried++;
                month.put("status", "CARRIED");
            }
            out.add(month);
        }
        ObjectNode coverage = (ObjectNode) answer.get("coverage");
        coverage.put("evaluated", evaluated).put("carried", carried).put("noData", 0);
        return answer;
    }

    private static JsonNode tree(String text) {
        return tools.jackson.databind.json.JsonMapper.shared().readTree(text);
    }

    @Test
    void the_turn_shows_the_strings_the_engine_sent_and_a_carried_month_shows_nothing() throws Exception {
        jdbc.update("""
                UPDATE month_block SET rate_dir = NULL, growth_dir = NULL, fx_dir = NULL, sentiment_dir = NULL,
                    rate_strength = NULL, growth_strength = NULL, fx_strength = NULL, sentiment_strength = NULL,
                    prob_p1 = NULL, prob_p2 = NULL, prob_p3 = NULL, prob_p4 = NULL, regime_label = NULL WHERE month_start = DATE '2001-02-01'""");
        List<String> months = jdbc.queryForList("SELECT to_char(month_start, 'YYYY-MM') FROM month_block ORDER BY month_start",
                String.class);
        ObjectNode answer = answer(months);
        ENGINE.respond(PATH, 200, "application/json", answer.toString());

        RegimeLabelJob.Outcome outcome = job.run();
        assertThat(outcome.rows()).isEqualTo(months.size());
        assertThat(outcome.filled()).isPositive();

        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        List<String> pathMonths = jdbc.queryForList("SELECT to_char(mb.month_start, 'YYYY-MM') FROM season_path sp JOIN month_block mb"
                + " ON mb.id = sp.month_block_id WHERE sp.season_id = ? ORDER BY sp.turn_no", String.class, season);
        int sawEvaluated = 0;
        int sawCarried = 0;
        for (int turn = 1; turn <= 12; turn++) {
            MockHttpServletResponse posted = advance(mvc, season, turn - 1);
            assertThat(posted.getStatus()).isEqualTo(201);
            JsonNode macro = json(posted).get("macro");
            assertThat(json(getTurn(mvc, season, turn)).get("macro")).isEqualTo(macro);
            JsonNode sent = month(answer, pathMonths.get(turn - 1));
            if ("EVALUATED".equals(sent.get("status").asString())) {
                sawEvaluated++;
                for (String axis : MacroNames.AXES) {
                    JsonNode expected = sent.get("axes").get(axis);
                    if (expected.isNull()) {
                        assertThat(macro.get("axes").get(axis).isNull()).as("turn " + turn + " " + axis).isTrue();
                    } else {
                        assertThat(macro.get("axes").get(axis).get("direction").asString()).isEqualTo(expected.get("direction").asString());
                        assertThat(macro.get("axes").get(axis).get("strength").asString()).isEqualTo(expected.get("strength").asString());
                    }
                }
                for (String p : new String[] {"p1", "p2", "p3", "p4"}) {
                    assertThat(macro.get("probabilities").get(p).asString()).as("turn " + turn + " " + p)
                            .isEqualTo(sent.get("probabilities").get(p).asString());
                }
                BigDecimal sum = BigDecimal.ZERO;
                for (String p : new String[] {"p1", "p2", "p3", "p4"}) {
                    sum = sum.add(new BigDecimal(macro.get("probabilities").get(p).asString()));
                }
                assertThat(sum).as("not renormalised").isNotEqualByComparingTo("1");
            } else {
                sawCarried++;
                assertThat(macro.toString()).isEqualTo(
                        "{\"axes\":{\"rate\":null,\"growth\":null,\"fx\":null,\"sentiment\":null},\"probabilities\":null}");
            }
        }
        assertThat(sawEvaluated).as("the path met an evaluated month").isPositive();
        assertThat(sawCarried).as("the path met a carried month").isPositive();
    }

    private static JsonNode month(ObjectNode answer, String yearMonth) {
        for (JsonNode m : answer.get("months")) {
            if (m.get("month").asString().equals(yearMonth)) {
                return m;
            }
        }
        throw new IllegalStateException("month not in the answer");
    }

    /** Axis names in the engine's answer. */
    private static final class MacroNames {
        static final String[] AXES = {"rate", "growth", "fx", "sentiment"};
    }
}
