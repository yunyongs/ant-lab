package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnApiSupport.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import antlab.feed.AlertGenerator;
import antlab.feed.FeedFailure;
import antlab.feed.FeedGenerationException;
import antlab.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * An alert that cannot be made is a data-preparation defect, not an empty list: the turn advance answers 500 and the whole turn is
 * rolled back with it (no turn, price, feed, alert or log row of that turn, current turn unchanged), the same as for a feed failure.
 */
@ExtendWith(OutputCaptureExtension.class)
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceAlertRollbackTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    AlertGenerator alerts;

    @Test
    void an_alert_failure_is_a_500_and_nothing_of_the_turn_is_kept(CapturedOutput output) throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        Map<String, Object> before = state(jdbc, season);
        doThrow(new FeedGenerationException(FeedFailure.FEED_RENDER, "forced")).when(alerts).generate(any(), anyInt());

        MockHttpServletResponse r = advance(mvc, season, 0);

        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(code(r)).isEqualTo("INTERNAL_ERROR");
        assertThat(r.getContentAsString()).doesNotContain("FEED_").doesNotContain("forced");
        assertThat(state(jdbc, season)).as("turn, prices, feed, alerts, log and season stay as they were").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_feed WHERE season_id = ?", Long.class, season)).isZero();
        assertThat(output.getAll()).contains("피드 생성 실패").contains("FEED_RENDER");
    }

    @Test
    void the_same_turn_advances_once_alerts_can_be_made_again() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        doThrow(new FeedGenerationException(FeedFailure.FEED_RENDER, "forced")).when(alerts).generate(any(), anyInt());
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(500);
        org.mockito.Mockito.reset(alerts);

        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_feed WHERE season_id = ? AND turn_no = 1 AND kind = 'NUDGE'",
                Long.class, season)).isPositive();
    }
}
