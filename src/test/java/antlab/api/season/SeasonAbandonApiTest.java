package antlab.api.season;

import static antlab.api.season.AbandonSupport.abandon;
import static antlab.api.season.AbandonSupport.abandonRaw;
import static antlab.api.season.AbandonSupport.abandonedTurn;
import static antlab.api.season.AbandonSupport.count;
import static antlab.api.season.AbandonSupport.currentTurn;
import static antlab.api.season.AbandonSupport.json;
import static antlab.api.season.AbandonSupport.noWriteCounts;
import static antlab.api.season.AbandonSupport.position;
import static antlab.api.season.AbandonSupport.status;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.season.SeedGenerator;
import org.springframework.boot.test.system.CapturedOutput;
import antlab.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * MVP-14 abandon endpoint on a real PostgreSQL: one request marks the season and runs every remaining turn with no
 * trades, the same request is idempotent, and the refusals leave nothing behind. The seed is fixed so that two seasons
 * of one test walk the same path (golden comparison with a manual walk).
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SeasonAbandonApiTest {

    @TestConfiguration
    static class FixedSeed {
        @Bean
        @Primary
        SeedGenerator fixedSeed() {
            return () -> 20_261_117L;
        }
    }

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry registry;

    private double abandonedCounter(boolean notStarted) {
        var c = registry.find("antlab.season.abandoned").tag("not_started", Boolean.toString(notStarted)).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void abandonAtTurnZeroWithNoHoldingsFinishesTheSeasonAndIsNotStarted() throws Exception {
        long season = startSeason(mvc);
        double notStartedBefore = abandonedCounter(true);

        MockHttpServletResponse r = abandon(mvc, season, 0);

        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("status").asString()).isEqualTo("FINISHED");
        assertThat(body.get("currentTurnNo").asInt()).isEqualTo(12);
        assertThat(body.get("pathLength").asInt()).isEqualTo(12);
        assertThat(body.get("abandonedTurnNo").asInt()).isZero();
        assertThat(body.get("notStarted").asBoolean()).isTrue();
        assertThat(body.get("advancedTurns").asInt()).isEqualTo(12);
        assertThat(body.get("remainingTurns").asInt()).isZero();
        assertThat(status(jdbc, season)).isEqualTo("FINISHED");
        assertThat(count(jdbc, "turn", season)).isEqualTo(12);
        assertThat(count(jdbc, "reconciliation_log", season)).isEqualTo(12 * 17);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT total_value) FROM turn WHERE season_id = ?", Long.class, season))
                .as("no holdings: every turn is worth the initial cash").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT total_value = (SELECT initial_cash FROM season WHERE id = ?) FROM turn "
                + "WHERE season_id = ? AND turn_no = 12", Boolean.class, season, season)).isTrue();
        assertThat(abandonedCounter(true)).isEqualTo(notStartedBefore + 1);
    }

    @Test
    void abandonAfterTradingHoldsEverythingAndTheReportAnswers() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        for (int t = 0; t < 4; t++) {
            assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
        }
        String before = position(jdbc, season);
        Map<String, Long> writesBefore = noWriteCounts(jdbc, season);
        double startedBefore = abandonedCounter(false);

        MockHttpServletResponse r = abandon(mvc, season, 4);

        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode body = json(r);
        assertThat(body.get("status").asString()).isEqualTo("FINISHED");
        assertThat(body.get("abandonedTurnNo").asInt()).isEqualTo(4);
        assertThat(body.get("notStarted").asBoolean()).isFalse();
        assertThat(body.get("advancedTurns").asInt()).isEqualTo(8);
        assertThat(abandonedTurn(jdbc, season)).isEqualTo(4);
        assertThat(currentTurn(jdbc, season)).isEqualTo(12);
        assertThat(position(jdbc, season)).as("holdings, cash and open lineup rows are those of the mark").isEqualTo(before);
        assertThat(noWriteCounts(jdbc, season)).as("no order, execution, ledger, formation or lineup row after the mark")
                .isEqualTo(writesBefore);
        for (int t = 5; t <= 12; t++) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = ?"
                    + " AND status = 'MATCH'", Long.class, season, t)).as("turn " + t).isEqualTo(17L);
        }
        assertThat(mvc.get().uri("/api/v1/seasons/" + season + "/report").exchange().getResponse().getStatus()).isEqualTo(200);
        assertThat(abandonedCounter(false)).isEqualTo(startedBefore + 1);
    }

    @Test
    void autoTurnsEqualTheManualWalkWithNoTrades() throws Exception {
        long abandoned = startSeason(mvc);
        long manual = startSeason(mvc);
        for (long season : new long[] {abandoned, manual}) {
            buyTeam(mvc, jdbc, season);
            for (int t = 0; t < 3; t++) {
                assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
            }
        }
        assertThat(abandon(mvc, abandoned, 3).getStatus()).isEqualTo(200);
        for (int t = 3; t < 12; t++) {
            assertThat(advance(mvc, manual, t).getStatus()).isEqualTo(201);
        }

        String turnSql = "SELECT string_agg(turn_no || ':' || cash_balance || ':' || holdings_value || ':' || total_value"
                + " || ':' || drawdown || ':' || goalkeeper_value || ':' || defender_value || ':' || midfielder_value"
                + " || ':' || forward_value, ',' ORDER BY turn_no) FROM turn WHERE season_id = ?";
        assertThat(jdbc.queryForObject(turnSql, String.class, abandoned)).isEqualTo(jdbc.queryForObject(turnSql, String.class, manual));
        String priceSql = "SELECT string_agg(turn_no || ':' || asset_id || ':' || price, ',' ORDER BY turn_no, asset_id)"
                + " FROM turn_price WHERE season_id = ?";
        assertThat(jdbc.queryForObject(priceSql, String.class, abandoned)).isEqualTo(jdbc.queryForObject(priceSql, String.class, manual));
        assertThat(count(jdbc, "turn_feed", abandoned)).isEqualTo(count(jdbc, "turn_feed", manual));
        // The feed content too (ids and times left out): kind, links, direction, strength, template, variables, body.
        String feedSql = "SELECT string_agg(turn_no || ':' || seq || ':' || kind || ':' || coalesce(asset_id::text, '-')"
                + " || ':' || coalesce(asset_group_id::text, '-') || ':' || coalesce(direction, '-') || ':'"
                + " || coalesce(strength::text, '-') || ':' || repeat_count || ':' || template_id || ':'"
                + " || template_vars::text || ':' || body, '|' ORDER BY turn_no, seq) FROM turn_feed WHERE season_id = ?";
        assertThat(jdbc.queryForObject(feedSql, String.class, abandoned)).isEqualTo(jdbc.queryForObject(feedSql, String.class, manual));
    }

    @Test
    void theAbandonAnswerHasExactlyTheRecordKeysAndNoSeedDateOrMonthBlock(CapturedOutput output) throws Exception {
        long season = startSeason(mvc);
        String seed = jdbc.queryForObject("SELECT seed::text FROM season WHERE id = ?", String.class, season);
        String engine = jdbc.queryForObject("SELECT engine_version FROM season WHERE id = ?", String.class, season);

        MockHttpServletResponse r = abandon(mvc, season, 0);

        assertThat(r.getStatus()).isEqualTo(200);
        String body = r.getContentAsString();
        JsonNode json = json(r);
        java.util.Set<String> expected = new java.util.HashSet<>();
        for (java.lang.reflect.RecordComponent rc : antlab.season.AbandonResultDto.class.getRecordComponents()) {
            expected.add(rc.getName());
        }
        assertThat(new java.util.HashSet<>(json.propertyNames())).isEqualTo(expected);
        assertThat(body).doesNotContain(seed).doesNotContain(engine).doesNotContain("seed").doesNotContain("baseDate")
                .doesNotContain("monthBlock");
        assertThat(body).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
        assertThat(output.getAll()).doesNotContain(seed).doesNotContain(engine);
        jdbc.queryForList("SELECT mb.month_start::text FROM season_path sp JOIN month_block mb ON mb.id = sp.month_block_id"
                + " WHERE sp.season_id = ?", String.class, season)
                .forEach(month -> assertThat(output.getAll()).as("month in log").doesNotContain(month));
    }

    @Test
    void aRepeatOnAFinishedAbandonedSeasonAnswers200AndChangesNothing() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(abandon(mvc, season, 0).getStatus()).isEqualTo(200);
        Map<String, Object> before = stateOf(season);
        double startedBefore = abandonedCounter(false);
        double notStartedBefore = abandonedCounter(true);

        MockHttpServletResponse repeat = abandon(mvc, season, 7); // the turn is ignored on a repeat

        assertThat(repeat.getStatus()).isEqualTo(200);
        JsonNode body = json(repeat);
        assertThat(body.get("status").asString()).isEqualTo("FINISHED");
        assertThat(body.get("advancedTurns").asInt()).isZero();
        assertThat(body.get("abandonedTurnNo").asInt()).isZero();
        assertThat(stateOf(season)).isEqualTo(before);
        assertThat(abandonedCounter(false)).as("a repeat is not a second abandon").isEqualTo(startedBefore);
        assertThat(abandonedCounter(true)).as("a repeat is not a second abandon").isEqualTo(notStartedBefore);
    }

    @Test
    void aStaleTurnWhileInProgressIs409TurnMismatchAndMarksNothing() throws Exception {
        long season = startSeason(mvc);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        Map<String, Object> before = stateOf(season);
        double notStartedBefore = abandonedCounter(true);

        MockHttpServletResponse r = abandon(mvc, season, 0);

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("TURN_MISMATCH");
        assertThat(status(jdbc, season)).isEqualTo("IN_PROGRESS");
        assertThat(abandonedTurn(jdbc, season)).isNull();
        assertThat(stateOf(season)).isEqualTo(before);
        assertThat(abandonedCounter(true)).isEqualTo(notStartedBefore);
    }

    @Test
    void aTurnAheadOfTheSeasonIs409TurnMismatchAndMarksNothing() throws Exception {
        long season = startSeason(mvc);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);

        MockHttpServletResponse r = abandon(mvc, season, 5);

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("TURN_MISMATCH");
        assertThat(status(jdbc, season)).isEqualTo("IN_PROGRESS");
        assertThat(abandonedTurn(jdbc, season)).isNull();
    }

    @Test
    void aSeasonFinishedWithoutAbandonUnknownIdsAndBadBodiesAreRefused() throws Exception {
        long finished = startSeason(mvc);
        for (int t = 0; t < 12; t++) {
            assertThat(advance(mvc, finished, t).getStatus()).isEqualTo(201);
        }
        MockHttpServletResponse notActive = abandon(mvc, finished, 12);
        assertThat(notActive.getStatus()).isEqualTo(409);
        assertThat(code(notActive)).isEqualTo("SEASON_NOT_ACTIVE");
        assertThat(abandonedTurn(jdbc, finished)).isNull();

        MockHttpServletResponse unknown = abandon(mvc, 987_654_321L, 0);
        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("NOT_FOUND");

        long running = startSeason(mvc);
        for (String body : List.of("{}", "{\"expectedTurnNo\": -1}", "{\"expectedTurnNo\": \"x\"}", "")) {
            MockHttpServletResponse bad = abandonRaw(mvc, running, body);
            assertThat(bad.getStatus()).as("body: " + body).isEqualTo(400);
        }
        assertThat(mvc.post().uri("/api/v1/seasons/abc/abandon").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTurnNo\": 0}").exchange().getResponse().getStatus()).isEqualTo(400);
        assertThat(status(jdbc, running)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void anotherMembersSeasonIs404AndStaysUntouched() throws Exception {
        long season = startSeason(mvc);
        Long other = jdbc.queryForObject("INSERT INTO member (email, name, password) VALUES "
                + "('abandon-other@example.invalid', '다른 회원', '!') RETURNING id", Long.class);
        try {
            jdbc.update("UPDATE season SET member_id = ? WHERE id = ?", other, season);
            MockHttpServletResponse r = abandon(mvc, season, 0);
            assertThat(r.getStatus()).isEqualTo(404);
            assertThat(code(r)).isEqualTo("NOT_FOUND");
            assertThat(status(jdbc, season)).isEqualTo("IN_PROGRESS");
        } finally {
            antlab.support.SeasonAccountCleaner.delete(jdbc, List.of(season), List.of(other), List.of(), List.of(),
                    List.of());
        }
    }

    @Test
    void theSeasonResponsesCarryTheAbandonFacts() throws Exception {
        long season = startSeason(mvc);
        JsonNode before = json(mvc.get().uri("/api/v1/seasons/" + season).exchange().getResponse());
        assertThat(before.get("abandonedTurnNo").isNull()).isTrue();
        assertThat(before.get("notStarted").asBoolean()).isFalse();

        abandon(mvc, season, 0);

        JsonNode after = json(mvc.get().uri("/api/v1/seasons/" + season).exchange().getResponse());
        assertThat(after.get("abandonedTurnNo").asInt()).isZero();
        assertThat(after.get("notStarted").asBoolean()).isTrue();
        JsonNode listed = json(mvc.get().uri("/api/v1/seasons").exchange().getResponse()).get("items");
        assertThat(listed).anySatisfy(item -> {
            assertThat(item.get("id").asLong()).isEqualTo(season);
            assertThat(item.get("abandonedTurnNo").asInt()).isZero();
            assertThat(item.get("notStarted").asBoolean()).isTrue();
        });
    }

    @Test
    void anAbandonedSeasonFreesItsSlotOnlyWhenFinishedAndAnAbandoningOneKeepsIt() throws Exception {
        long season = startSeason(mvc);
        startSeason(mvc);
        startSeason(mvc);
        assertThat(mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse().getStatus())
                .as("three running seasons fill the limit").isEqualTo(409);
        assertThat(abandon(mvc, season, 0).getStatus()).isEqualTo(200);
        assertThat(status(jdbc, season)).isEqualTo("FINISHED");
        assertThat(mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse().getStatus())
                .as("a finished abandoned season frees the slot").isEqualTo(201);
    }

    private Map<String, Object> stateOf(long season) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String table : new String[] {"turn", "turn_price", "turn_feed", "reconciliation_log", "holding", "cash_ledger",
                "lineup", "orders"}) {
            out.put(table, count(jdbc, table, season));
        }
        out.put("season", jdbc.queryForObject(
                "SELECT status || ':' || current_turn_no || ':' || coalesce(abandoned_turn_no::text, '-') || ':' || version"
                        + " FROM season WHERE id = ?", String.class, season));
        return out;
    }
}
