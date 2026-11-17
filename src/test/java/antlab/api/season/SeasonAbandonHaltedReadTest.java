package antlab.api.season;

import static antlab.api.season.AbandonSupport.abandon;
import static antlab.api.season.AbandonSupport.status;
import static antlab.api.turn.TurnApiSupport.code;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import antlab.member.MemberKind;
import antlab.season.SeasonAbandonMarker;
import antlab.support.IntegrationTest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Audit C1: another request halts the season between this request's last auto turn and its re-read. The re-read then
 * shows HALTED, and the answer must be 409 SEASON_HALTED, never a 200 that carries a halted season.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAbandonHaltedReadTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    javax.sql.DataSource dataSource;
    @MockitoSpyBean
    SeasonAbandonMarker marker;

    @Test
    void aSeasonHaltedByAnotherRequestBeforeTheReReadIs409SeasonHaltedNot200() throws Exception {
        long season = startSeason(mvc);
        AtomicBoolean halted = new AtomicBoolean();
        doAnswer(invocation -> {
            if (halted.compareAndSet(false, true)) {
                // Its own connection: the spy runs inside the read-only transaction of the re-read.
                try (java.sql.Connection other = dataSource.getConnection(); java.sql.Statement st = other.createStatement()) {
                    st.executeUpdate("UPDATE season SET status = 'HALTED' WHERE id = " + season);
                }
            }
            return invocation.callRealMethod();
        }).when((SeasonAbandonMarker) AopTestUtils.getUltimateTargetObject(marker)).read(anyLong(), any(MemberKind.class));

        MockHttpServletResponse r = abandon(mvc, season, 0);

        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("SEASON_HALTED");
        assertThat(status(jdbc, season)).isEqualTo("HALTED");
    }
}
