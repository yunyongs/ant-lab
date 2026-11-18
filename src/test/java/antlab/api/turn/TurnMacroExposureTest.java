package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.season.MacroAxesDto;
import antlab.season.MacroAxisDto;
import antlab.season.MacroDto;
import antlab.season.RegimeProbabilitiesDto;
import antlab.support.IntegrationTest;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * The macro part of the turn result shows the whitelisted keys and nothing that could carry a level or a date: no label word, no
 * indicator value, no month. The stored label and the real month of the played turns are absent from the bodies and the log.
 */
@ExtendWith(OutputCaptureExtension.class)
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql", "/fixtures/replay_macro.sql"})
@Sql(scripts = {"/fixtures/cleanup_macro.sql", "/fixtures/cleanup_season_start.sql"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnMacroExposureTest {

    private static final Set<String> FORBIDDEN = Set.of("label", "regimeLabel", "phase", "indicatorZ", "indicators", "quality",
            "level", "value", "monthBlockId", "month", "monthStart", "date", "coverage", "stale");

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private static Set<String> components(Class<?> record) {
        Set<String> out = new HashSet<>();
        Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).forEach(out::add);
        return out;
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> out = new HashSet<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static void allKeys(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                out.add(e.getKey());
                allKeys(e.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(n -> allKeys(n, out));
        }
    }

    private static void strings(JsonNode node, List<String> out) {
        if (node.isString()) {
            out.add(node.asString());
        } else if (node.isObject()) {
            node.properties().forEach(e -> strings(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(n -> strings(n, out));
        }
    }

    @Test
    void the_macro_keys_equal_the_record_components_and_no_label_level_or_month_appears(CapturedOutput output) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            MockHttpServletResponse r = advance(mvc, season, t);
            assertThat(r.getStatus()).isEqualTo(201);
            responses.add(r);
        }
        for (int turn = 1; turn <= 3; turn++) {
            responses.add(getTurn(mvc, season, turn));
        }
        MockHttpServletResponse error = getTurn(mvc, season, 4);
        assertThat(error.getStatus()).isEqualTo(409);
        responses.add(error);

        List<String> labels = jdbc.queryForList("SELECT mb.regime_label FROM season_path sp JOIN month_block mb"
                + " ON mb.id = sp.month_block_id WHERE sp.season_id = ? AND sp.turn_no <= 3", String.class, season);
        List<String> months = jdbc.queryForList("SELECT to_char(mb.month_start, 'YYYY-MM') FROM season_path sp JOIN month_block mb"
                + " ON mb.id = sp.month_block_id WHERE sp.season_id = ? ORDER BY sp.turn_no", String.class, season);
        assertThat(labels).hasSize(3).doesNotContainNull();
        assertThat(months).isNotEmpty();

        for (MockHttpServletResponse r : responses.subList(0, 6)) {
            JsonNode macro = json(r).get("macro");
            assertThat(keys(macro)).isEqualTo(components(MacroDto.class));
            assertThat(keys(macro.get("axes"))).isEqualTo(components(MacroAxesDto.class));
            for (String axis : MacroSupport.AXES) {
                assertThat(keys(macro.get("axes").get(axis))).isEqualTo(components(MacroAxisDto.class));
            }
            assertThat(keys(macro.get("probabilities"))).isEqualTo(components(RegimeProbabilitiesDto.class));
            Set<String> all = new HashSet<>();
            allKeys(macro, all);
            assertThat(all).doesNotContainAnyElementsOf(FORBIDDEN);
            List<String> values = new ArrayList<>();
            strings(macro, values);
            assertThat(values).as("no regime label word").noneMatch(v -> v.matches("^P[1-4]$"));
        }
        for (MockHttpServletResponse r : responses) {
            String body = r.getContentAsString();
            assertThat(body).doesNotContainPattern("\\d{4}-\\d{2}").doesNotContain("\"P1\"").doesNotContain("\"P2\"")
                    .doesNotContain("\"P3\"").doesNotContain("\"P4\"").doesNotContain("indicator");
            months.forEach(m -> assertThat(body).doesNotContain(m));
        }
        months.forEach(m -> assertThat(output.getAll()).as("log: month").doesNotContain(m));
        assertThat(output.getAll()).doesNotContain("indicator_z");
    }
}
