package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static antlab.api.turn.TurnApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import antlab.scoring.ReconciliationFailedException;
import antlab.season.ReconciliationFailureRecorder;
import antlab.season.ReconciliationFailureRecorder.Outcome;
import antlab.season.ReconciliationProperties;
import antlab.season.TurnService;
import antlab.support.IntegrationTest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The recorder writes the log and, in the same transaction, decides the halt. A failure that lands after another request
 * already advanced the season is history and never halts; a failure inside the recorder leaves neither log rows nor a halt.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class ReconciliationFailureRecorderTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TurnService turnService;
    @Autowired
    ReconciliationFailureRecorder recorder;
    @MockitoSpyBean
    ReconciliationProperties properties;

    private ReconciliationFailedException captureFailure(long season, int turn) {
        AtomicReference<ReconciliationFailedException> captured = new AtomicReference<>();
        assertThatThrownBy(() -> turnService.advance(season, turn)).isInstanceOfSatisfying(ReconciliationFailedException.class,
                captured::set);
        return captured.get();
    }

    private String status(long season) {
        return jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, season);
    }

    @Test
    void thresholdReachedOnThePendingTurnHaltsInTheRecordTransaction() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        ReconciliationFailedException failed = captureFailure(season, 0);
        assertThat(recorder.record(failed)).isEqualTo(new Outcome(1, 1, false));
        assertThat(recorder.record(failed)).isEqualTo(new Outcome(2, 2, false));
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
        assertThat(recorder.record(failed)).isEqualTo(new Outcome(3, 3, true));
        assertThat(status(season)).isEqualTo("HALTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ?", Long.class, season))
                .isEqualTo(51L);
    }

    @Test
    void aFailureRecordedAfterTheSeasonAlreadyAdvancedWritesRowsButNeverHalts() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        ReconciliationFailedException stale = captureFailure(season, 0); // a failure for pending turn 1
        recorder.record(stale);
        recorder.record(stale);
        jdbc.update("UPDATE lineup SET end_turn = NULL WHERE season_id = ? AND asset_id = ?", season, assetId(jdbc, "069500"));
        MockHttpServletResponse ok = advance(mvc, season, 0);
        assertThat(ok.getStatus()).isEqualTo(201); // the season is at turn 1 now

        Outcome late = recorder.record(stale); // third failed attempt of turn 1 — but turn 1 is no longer pending
        assertThat(late.haltedNow()).isFalse();
        assertThat(late.attemptNo()).isEqualTo(4); // attempts 1-2 failed, 3 was the success, this one is 4
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT current_turn_no FROM season WHERE id = ?", Integer.class, season)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND attempt_no = 4",
                Long.class, season)).isEqualTo(17L);
    }

    @Test
    void aFailureInsideTheRecorderRollsBackTheLogRowsAndDoesNotHalt() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        ReconciliationFailedException failed = captureFailure(season, 0);
        recorder.record(failed);
        recorder.record(failed);
        Map<String, Object> before = state(jdbc, season);

        doThrow(new IllegalStateException("threshold lookup down")).when(properties).haltAfterFailures();
        assertThatThrownBy(() -> recorder.record(failed)).isInstanceOf(IllegalStateException.class)
                .hasMessage("threshold lookup down");

        assertThat(state(jdbc, season)).as("neither log rows nor status changed").isEqualTo(before);
        assertThat(status(season)).isEqualTo("IN_PROGRESS");
    }
}
