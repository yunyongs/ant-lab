package antlab.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import antlab.member.CurrentMember;
import antlab.season.SeasonReviewService;
import antlab.support.IntegrationTest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

/** The review service runs in a read-only transaction; the DB itself reports it from inside the service. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReviewTransactionTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    CurrentMember currentMember;

    @Test
    void 서비스_메서드는_읽기_전용이다() throws Exception {
        Method get = SeasonReviewService.class.getMethod("get", Long.class);
        Transactional tx = get.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
    }

    @Test
    void 서비스_트랜잭션_안에서_읽기_전용이_실제로_적용된다() throws Exception {
        long season = ReviewApiSupport.finishedSeason(mvc, jdbc, 12);
        List<String> seen = new ArrayList<>();
        doAnswer(invocation -> {
            seen.add(jdbc.queryForObject("SELECT current_setting('transaction_read_only')", String.class));
            return invocation.callRealMethod();
        }).when(currentMember).existingId();
        assertThat(ReviewApiSupport.getReview(mvc, season).getStatus()).isEqualTo(200);
        assertThat(seen).containsExactly("on");
    }
}
