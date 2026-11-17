package antlab.api.season;

import static antlab.api.turn.TurnApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * MVP-14 added the member kind to the season read (it decides the derived notStarted flag). It is one statement per
 * request, never one per listed season: the list costs the same with one season as with three.
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReadQueryCountTest {

    @Autowired
    MockMvcTester mvc;

    private int statements(String uri) {
        SqlRecorder.start();
        mvc.get().uri(uri).exchange();
        List<String> sql = SqlRecorder.stop();
        System.out.println("[MVP-14] " + uri + " -> " + sql.size() + " statements");
        return sql.size();
    }

    @Test
    void theMemberKindIsReadOncePerRequest() throws Exception {
        long first = startSeason(mvc);
        int one = statements("/api/v1/seasons");
        int detail = statements("/api/v1/seasons/" + first);
        startSeason(mvc);
        startSeason(mvc);
        assertThat(statements("/api/v1/seasons")).as("the list does not grow with the number of seasons").isEqualTo(one);
        assertThat(statements("/api/v1/seasons/" + first)).isEqualTo(detail);
        assertThat(one).as("list: member check, member kind, seasons with balance").isEqualTo(3);
        assertThat(detail).as("detail: member check, season, cash account, member kind").isEqualTo(4);
    }
}
