package antlab.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import antlab.member.CurrentMember;
import antlab.scoring.BehaviourQuery;
import antlab.scoring.DefenderHoldingQuery;
import antlab.scoring.DisciplineQuery;
import antlab.scoring.SeasonAnalysisQuery;
import antlab.scoring.SeasonAnalysisService;
import antlab.scoring.SeasonReportQuery;
import antlab.scoring.SeasonSignalReader;
import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The analysis read is read-only and repeatable-read, writes and locks nothing, and its statement count does not depend on holdings. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonAnalysisTransactionTest {

    private static final int TURNS = 12;
    private static final int FIXED_NATIVE = 8;
    private static final int BOARD_STATEMENTS_PER_TURN = 4;

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    CurrentMember currentMember;

    @Test
    void 서비스_메서드는_읽기_전용이고_반복_가능한_읽기_격리다() throws Exception {
        Method get = SeasonAnalysisService.class.getMethod("get", Long.class);
        Transactional tx = get.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void 서비스_트랜잭션_안에서_격리_수준과_읽기_전용이_실제로_적용된다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        List<String> seen = new ArrayList<>();
        doAnswer(invocation -> {
            seen.add(jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class));
            seen.add(jdbc.queryForObject("SELECT current_setting('transaction_read_only')", String.class));
            return invocation.callRealMethod();
        }).when(currentMember).existingId();
        assertThat(SeasonAnalysisApiSupport.getAnalysis(mvc, season).getStatus()).isEqualTo(200);
        assertThat(seen).containsExactly("repeatable read", "on");
    }

    private SqlRecorder.Recorded measure(long season) {
        SqlRecorder.start();
        MockHttpServletResponse r = SeasonAnalysisApiSupport.getAnalysis(mvc, season);
        SqlRecorder.Recorded recorded = SqlRecorder.stopAll();
        assertThat(r.getStatus()).isEqualTo(200);
        return recorded;
    }

    private static String statement(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static void assertReadOnly(String text) {
        String sql = text.toLowerCase(Locale.ROOT);
        assertThat(sql).doesNotContain("insert ").doesNotContain("update ").doesNotContain("delete ")
                .doesNotContain("for update").doesNotContain("for share").doesNotContain("for no key update");
    }

    @Test
    void 쓰기와_잠금이_없고_문장_수가_보유_종목_수와_무관하다() throws Exception {
        long full = ReportApiSupport.finishedSeason(mvc, jdbc);
        long small = antlab.api.turn.TurnApiSupport.startSeason(mvc);
        antlab.api.turn.TurnApiSupport.buy(mvc, jdbc, small, 0, "000660", 10, "FORWARD_BREAKOUT");
        for (int t = 0; t < 12; t++) {
            assertThat(antlab.api.turn.TurnApiSupport.advance(mvc, small, t).getStatus()).isEqualTo(201);
        }
        SqlRecorder.Recorded a = measure(full);
        SqlRecorder.Recorded b = measure(small);
        long manyOrders = ReportApiSupport.finishedSeason(mvc, jdbc);
        for (int t = 1; t < 10; t++) {
            ReportApiSupport.fill(jdbc, manyOrders, antlab.api.turn.TurnApiSupport.assetId(jdbc, "BRK.B"), t, "BUY", 1);
        }
        SqlRecorder.Recorded c = measure(manyOrders);
        assertThat(c.total()).as("more orders, same statements").isEqualTo(a.total());
        System.out.println("[M3] analysis statements (4 held assets) = Hibernate " + a.hibernate().size() + " + JdbcClient "
                + a.jdbcClient().size() + ", (1 held asset) = " + b.hibernate().size() + " + " + b.jdbcClient().size());
        // Hibernate: member, season, turn snapshots, formation changes. JdbcClient: eight fixed statements (consistency, prices, late
        // orders, discipline orders, behaviour orders, feed signals, market pair prices, defender holdings) and the board input of
        // each judged turn 1..T-1.
        int judgedTurns = TURNS - 1;
        assertThat(a.hibernate()).hasSameSizeAs(b.hibernate()).hasSize(4);
        assertThat(a.jdbcClient()).hasSameSizeAs(b.jdbcClient()).hasSize(FIXED_NATIVE + BOARD_STATEMENTS_PER_TURN * judgedTurns);
        assertThat(a.total()).isEqualTo(b.total());
        List<String> fixed = List.of(statement(SeasonReportQuery.class, "CONSISTENCY"),
                statement(SeasonAnalysisQuery.class, "PRICES"), statement(SeasonAnalysisQuery.class, "LATE_ORDERS"),
                statement(DisciplineQuery.class, "ORDERS"), statement(BehaviourQuery.class, "ORDERS"),
                statement(SeasonSignalReader.class, "FEED"), statement(SeasonReportQuery.class, "BENCHMARK"),
                statement(DefenderHoldingQuery.class, "HELD"));
        for (SqlRecorder.Recorded recorded : List.of(a, b)) {
            for (String text : fixed) {
                assertThat(recorded.jdbcClient()).as("fixed statement once").filteredOn(text::equals).hasSize(1);
            }
            List<String> perTurn = new ArrayList<>(recorded.jdbcClient());
            fixed.forEach(perTurn::remove);
            assertThat(perTurn).as("board statements of the judged turns").hasSize(BOARD_STATEMENTS_PER_TURN * judgedTurns);
        }
        for (SqlRecorder.Recorded recorded : List.of(a, b)) {
            recorded.all().forEach(q -> {
                String s = q.toLowerCase(Locale.ROOT).strip();
                assertThat(s).doesNotStartWith("insert").doesNotStartWith("update").doesNotStartWith("delete");
                assertReadOnly(q);
            });
        }
    }
}
