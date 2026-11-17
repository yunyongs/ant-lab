package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.json;
import static antlab.api.turn.TurnApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.api.dev.DevReconciliationController;
import antlab.observability.GameMetrics;
import antlab.support.DefaultBearerConfig;
import antlab.support.PostgresTestcontainersConfig;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * MVP-15 stop state: three failed advances of one pending turn halt the season, the halting request keeps
 * RECONCILIATION_FAILED, later ones get SEASON_HALTED, and a resume gives exactly one more try.
 * Runs with the local profile because the resume endpoint exists only there.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import({PostgresTestcontainersConfig.class, TestTokens.class, DefaultBearerConfig.class, TestMembers.class})
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ReconciliationHaltTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestTokens tokens;
    @Autowired
    TestMembers testMembers;
    @Autowired
    MeterRegistry registry;

    private String status(long season) {
        return jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season);
    }

    private int currentTurn(long season) {
        return jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season);
    }

    private long logRows(long season) {
        return jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season);
    }

    private double counter(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private void assertFailed(long season, int expectedTurn) throws Exception {
        MockHttpServletResponse r = advance(mvc, season, expectedTurn);
        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("RECONCILIATION_FAILED");
    }

    private MockHttpServletResponse resume(long season) {
        return mvc.post().uri("/api/v1/dev/seasons/" + season + "/reconciliation/resume")
                .header("Authorization", "Bearer " + tokens.admin(testMembers.defaultId()))
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE).exchange()
                .getResponse();
    }

    private void fix(long season) {
        assertThat(jdbc.update("UPDATE lineup SET end_turn = NULL WHERE season_id = ? AND asset_id = ?", season,
                assetId(jdbc, "069500"))).isEqualTo(1);
    }

    @Test
    void thirdFailureHaltsWithTheSameAnswerAndTheFourthIsSeasonHalted() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        double haltedBefore = counter("antlab.season.halted");
        assertFailed(season, 0);
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
        assertFailed(season, 0);
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
        assertThat(counter("antlab.season.halted")).isEqualTo(haltedBefore);

        MockHttpServletResponse third = advance(mvc, season, 0);
        assertThat(third.getStatus()).isEqualTo(409);
        assertThat(code(third)).isEqualTo("RECONCILIATION_FAILED");
        assertThat(json(third).get("detail").asString()).isEqualTo("대사가 맞지 않아 턴을 넘기지 않았다");
        assertThat(status(season)).isEqualTo("HALTED");
        assertThat(logRows(season)).isEqualTo(3 * 17);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT attempt_no) FROM reconciliation_log WHERE season_id = ?",
                Long.class, season)).isEqualTo(3L);
        assertThat(counter("antlab.season.halted")).isEqualTo(haltedBefore + 1);

        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse fourth = advance(mvc, season, 0);
        assertThat(fourth.getStatus()).isEqualTo(409);
        assertThat(code(fourth)).isEqualTo("SEASON_HALTED");
        assertThat(json(fourth).get("detail").asString()).isEqualTo("대사가 거듭 맞지 않아 시즌을 멈췄다 — 관리자 확인이 필요하다");
        assertThat(fourth.getContentAsString()).doesNotContain("LINEUP").doesNotContain("POSITION");
        Map<String, Object> after = state(jdbc, season);
        assertThat(after).as("a refused advance writes no turn, turn_price or reconciliation_log row").isEqualTo(before);
        assertThat(currentTurn(season)).isZero();
        assertThat(counter("antlab.season.halted")).as("a refused request is not a second halt").isEqualTo(haltedBefore + 1);
    }

    @Test
    void haltedCheckComesBeforeTurnMismatch() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        for (int i = 0; i < 3; i++) {
            assertFailed(season, 0);
        }
        MockHttpServletResponse wrongTurn = advance(mvc, season, 5);
        assertThat(wrongTurn.getStatus()).isEqualTo(409);
        assertThat(code(wrongTurn)).isEqualTo("SEASON_HALTED");
        assertThat(logRows(season)).isEqualTo(3 * 17);
    }

    @Test
    void aSuccessEndsTheStreakAndFailuresAreCountedPerPendingTurn() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        assertFailed(season, 0);
        assertFailed(season, 0);
        fix(season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(currentTurn(season)).isEqualTo(1);

        // break the same lineup again at turn 1: two failures at turn 2 stay below the threshold
        jdbc.update("UPDATE lineup SET end_turn = 1 WHERE season_id = ? AND asset_id = ?", season, assetId(jdbc, "069500"));
        assertFailed(season, 1);
        assertFailed(season, 1);
        assertThat(status(season)).as("2 old failures at turn 1 + 2 new at turn 2 are not 3 in a row").isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT attempt_no) FROM reconciliation_log WHERE season_id = ? AND turn_no = 1"
                + " AND status = 'MISMATCH'", Long.class, season)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT attempt_no) FROM reconciliation_log WHERE season_id = ? AND turn_no = 2"
                + " AND status = 'MISMATCH'", Long.class, season)).isEqualTo(2L);
    }

    @Test
    void manualCheckRowsAtTheCurrentTurnNeverCountTowardTheHalt() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        jdbc.update("UPDATE cash_account SET reserved = 1 WHERE season_id = ?", season);
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse manual = mvc.post().uri("/api/v1/dev/seasons/" + season + "/reconciliation")
                    .header("Authorization", "Bearer " + tokens.admin(testMembers.defaultId()))
                    .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE).exchange()
                    .getResponse();
            assertThat(manual.getStatus()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT attempt_no) FROM reconciliation_log WHERE season_id = ? AND turn_no = 0"
                + " AND status = 'MISMATCH'", Long.class, season)).isEqualTo(3L);
        assertFailed(season, 0);
        assertFailed(season, 0);
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void resumeGivesExactlyOneMoreTryAndASuccessMovesOn() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        for (int i = 0; i < 3; i++) {
            assertFailed(season, 0);
        }
        assertThat(status(season)).isEqualTo("HALTED");
        double resumedBefore = counter("antlab.season.resumed");
        double haltedBefore = counter("antlab.season.halted");

        MockHttpServletResponse first = resume(season);
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(json(first).get("resumed").asBoolean()).isTrue();
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
        assertThat(counter("antlab.season.resumed")).isEqualTo(resumedBefore + 1);

        // one more try, unfixed: the 4th failed attempt halts again at once
        assertFailed(season, 0);
        assertThat(status(season)).isEqualTo("HALTED");
        assertThat(counter("antlab.season.halted")).isEqualTo(haltedBefore + 1);
        assertThat(logRows(season)).isEqualTo(4 * 17);

        // resume, fix, advance: succeeds
        assertThat(resume(season).getStatus()).isEqualTo(200);
        fix(season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
        assertThat(currentTurn(season)).isEqualTo(1);
    }

    @Test
    void resumeOnARunningSeasonChangesNothingAndDoesNotCount() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        double resumedBefore = counter("antlab.season.resumed");
        Map<String, Object> before = state(jdbc, season);
        MockHttpServletResponse r = resume(season);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(json(r).get("resumed").asBoolean()).isFalse();
        assertThat(json(r).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(state(jdbc, season)).isEqualTo(before);
        assertThat(counter("antlab.season.resumed")).isEqualTo(resumedBefore);
    }

    @Test
    void haltedMetricNamesAreRegisteredAtZeroBeforeAnyEvent() {
        assertThat(registry.find("antlab.season.halted").counter()).isNotNull();
        assertThat(registry.find("antlab.season.resumed").counter()).isNotNull();
        assertThat(registry.find("antlab.turn.advance").tags("outcome", "rejected", "code", "SEASON_HALTED").counter())
                .isNotNull();
        assertThat(GameMetrics.class).isNotNull();
    }
}
