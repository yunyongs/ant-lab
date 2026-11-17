package antlab.api.season;

import static antlab.api.season.AbandonSupport.abandon;
import static antlab.api.season.AbandonSupport.abandonedTurn;
import static antlab.api.season.AbandonSupport.count;
import static antlab.api.season.AbandonSupport.currentTurn;
import static antlab.api.season.AbandonSupport.json;
import static antlab.api.season.AbandonSupport.status;
import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.assetId;
import static antlab.api.turn.TurnApiSupport.brokenSeason;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import antlab.api.dev.DevReconciliationController;
import antlab.scoring.ReconciliationClassification;
import antlab.scoring.ReconciliationService;
import antlab.scoring.ReconciliationStatus;
import antlab.scoring.RuleResult;
import antlab.support.DefaultBearerConfig;
import antlab.support.PostgresTestcontainersConfig;
import antlab.support.TestMembers;
import antlab.support.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * MVP-14 abandon when an auto turn fails reconciliation: the request stops at the first failure (one attempt, not
 * three), the mark and the earlier auto turns stay, a repeat continues, three failed requests halt the season, a
 * halted season refuses abandon, and after the dev resume a repeat finishes the season. Local profile: the resume
 * endpoint exists only there.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Import({PostgresTestcontainersConfig.class, TestTokens.class, DefaultBearerConfig.class, TestMembers.class})
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
@SuppressWarnings("unchecked")
class SeasonAbandonStopTest {

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
    @MockitoSpyBean
    ReconciliationService reconciliation;

    /** The turn whose check the spy turns into a mismatch; -1 = none. */
    private final AtomicInteger failAtTurn = new AtomicInteger(-1);

    @BeforeEach
    void injectOneMismatch() {
        failAtTurn.set(-1);
        doAnswer(invocation -> {
            List<RuleResult> real = (List<RuleResult>) invocation.callRealMethod();
            if ((int) invocation.getArgument(1) != failAtTurn.get()) {
                return real;
            }
            List<RuleResult> out = new ArrayList<>(real);
            RuleResult first = real.get(0);
            out.set(0, new RuleResult(first.ruleNo(), first.checkCode(), ReconciliationStatus.MISMATCH, first.expectedValue(),
                    first.actualValue(), first.diffValue(), 1, ReconciliationClassification.INVESTIGATE, "{}"));
            return out;
        }).when((ReconciliationService) AopTestUtils.getUltimateTargetObject(reconciliation)).evaluateTurn(anyLong(), anyInt());
    }

    private double counter(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private MockHttpServletResponse resume(long season) {
        return mvc.post().uri("/api/v1/dev/seasons/" + season + "/reconciliation/resume")
                .header("Authorization", "Bearer " + tokens.admin(testMembers.defaultId()))
                .header(DevReconciliationController.DEV_HEADER, DevReconciliationController.DEV_HEADER_VALUE).exchange()
                .getResponse();
    }

    private void fixLineup(long season) {
        assertThat(jdbc.update("UPDATE lineup SET end_turn = NULL WHERE season_id = ? AND asset_id = ?", season,
                assetId(jdbc, "069500"))).isEqualTo(1);
    }

    private void assertReconciliationFailed(MockHttpServletResponse r) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("RECONCILIATION_FAILED");
    }

    @Test
    void aFailureOnTheFirstAutoTurnKeepsTheMarkLogsOneAttemptAndARepeatContinues() throws Exception {
        long season = brokenSeason(mvc, jdbc);

        assertReconciliationFailed(abandon(mvc, season, 0));

        assertThat(status(jdbc, season)).isEqualTo("ABANDONING");
        assertThat(abandonedTurn(jdbc, season)).isZero();
        assertThat(currentTurn(jdbc, season)).isZero();
        assertThat(count(jdbc, "reconciliation_log", season)).as("one failed attempt, not three").isEqualTo(17L);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT attempt_no) FROM reconciliation_log WHERE season_id = ?",
                Long.class, season)).isEqualTo(1L);

        fixLineup(season);
        MockHttpServletResponse repeat = abandon(mvc, season, 0);

        assertThat(repeat.getStatus()).as(repeat.getContentAsString()).isEqualTo(200);
        assertThat(json(repeat).get("status").asString()).isEqualTo("FINISHED");
        assertThat(json(repeat).get("advancedTurns").asInt()).isEqualTo(12);
        assertThat(count(jdbc, "turn", season)).isEqualTo(12L);
    }

    @Test
    void aFailureInTheMiddleLeavesAConsistentTurnAndTheEarlierAutoTurnsStay() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        failAtTurn.set(4);

        assertReconciliationFailed(abandon(mvc, season, 0));

        assertThat(status(jdbc, season)).isEqualTo("ABANDONING");
        assertThat(currentTurn(jdbc, season)).as("the turns before the failing one are committed").isEqualTo(3);
        assertThat(count(jdbc, "turn", season)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_price WHERE season_id = ? AND turn_no = 4", Long.class, season))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_log WHERE season_id = ? AND turn_no = 4"
                + " AND status = 'MISMATCH'", Long.class, season)).isEqualTo(1L);

        failAtTurn.set(-1);
        MockHttpServletResponse repeat = abandon(mvc, season, 99); // ignored on a repeat
        assertThat(repeat.getStatus()).as(repeat.getContentAsString()).isEqualTo(200);
        assertThat(json(repeat).get("status").asString()).isEqualTo("FINISHED");
        assertThat(json(repeat).get("advancedTurns").asInt()).isEqualTo(9);
        assertThat(count(jdbc, "turn", season)).isEqualTo(12L);
    }

    @Test
    void threeFailedRequestsHaltTheSeasonAndAHaltedSeasonRefusesUntilResumed() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        double haltedBefore = counter("antlab.season.halted");

        assertReconciliationFailed(abandon(mvc, season, 0));
        assertReconciliationFailed(abandon(mvc, season, 0));
        assertThat(status(jdbc, season)).isEqualTo("ABANDONING");
        assertReconciliationFailed(abandon(mvc, season, 0));
        assertThat(status(jdbc, season)).isEqualTo("HALTED");
        assertThat(counter("antlab.season.halted")).isEqualTo(haltedBefore + 1);

        MockHttpServletResponse fourth = abandon(mvc, season, 0);
        assertThat(fourth.getStatus()).isEqualTo(409);
        assertThat(code(fourth)).isEqualTo("SEASON_HALTED");
        assertThat(count(jdbc, "reconciliation_log", season)).isEqualTo(3 * 17L);

        assertThat(resume(season).getStatus()).isEqualTo(200);
        assertThat(status(jdbc, season)).as("a halted abandon resumes as abandoning").isEqualTo("ABANDONING");
        fixLineup(season);
        MockHttpServletResponse repeat = abandon(mvc, season, 0);
        assertThat(repeat.getStatus()).as(repeat.getContentAsString()).isEqualTo(200);
        assertThat(json(repeat).get("status").asString()).isEqualTo("FINISHED");
        assertThat(count(jdbc, "turn", season)).isEqualTo(12L);
    }

    @Test
    void aSeasonHaltedBeforeAbandonRefusesAndIsNotMarked() throws Exception {
        long season = brokenSeason(mvc, jdbc);
        for (int i = 0; i < 3; i++) {
            assertReconciliationFailed(advance(mvc, season, 0));
        }
        assertThat(status(jdbc, season)).isEqualTo("HALTED");

        MockHttpServletResponse r = abandon(mvc, season, 0);

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("SEASON_HALTED");
        assertThat(abandonedTurn(jdbc, season)).isNull();
        assertThat(status(jdbc, season)).isEqualTo("HALTED");
    }
}
