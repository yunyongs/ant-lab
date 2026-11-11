package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** 턴 결과 조회의 SQL 문 수 — 보유 종목 수와 무관하고 쓰기가 없다. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnViewQueryCountTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private List<String> measure(long season, int turn) {
        SqlRecorder.start();
        MockHttpServletResponse r = getTurn(mvc, season, turn);
        List<String> sqls = SqlRecorder.stop();
        assertThat(r.getStatus()).isEqualTo(200);
        return sqls;
    }

    @Test
    void 문장_수는_보유_종목_수와_무관하고_쓰기가_없다() throws Exception {
        long one = startSeason(mvc);
        buy(mvc, jdbc, one, 0, "000660", 10, "FORWARD_BREAKOUT");
        long three = startSeason(mvc);
        buy(mvc, jdbc, three, 0, "000660", 10, "FORWARD_BREAKOUT");
        buy(mvc, jdbc, three, 0, "069500", 100, "MIDFIELDER");
        buy(mvc, jdbc, three, 0, "SPY", 5, "DEFENDER");
        for (long season : new long[] {one, three}) {
            assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);
            assertThat(advance(mvc, season, 1).getStatus()).isEqualTo(201);
        }
        for (int turn = 1; turn <= 2; turn++) {
            List<String> a = measure(one, turn);
            List<String> b = measure(three, turn);
            System.out.println("[T11] 턴 조회 turns/" + turn + " 문장 수 (1종목) = " + a.size() + ", (3종목) = " + b.size());
            assertThat(a).hasSameSizeAs(b);
            // 절댓값: 현재 회원 1 + 시즌 1 + 턴 범위 1 + 전술 변경 1 (양쪽이 같이 늘어도 잡힌다)
            assertThat(a).hasSize(4);
            assertThat(b).hasSize(4);
            for (List<String> sqls : List.of(a, b)) {
                sqls.forEach(q -> assertThat(q.toLowerCase(Locale.ROOT)).doesNotStartWith("insert")
                        .doesNotStartWith("update").doesNotStartWith("delete"));
            }
        }
    }

    @Test
    void 문장_수는_전술_변경_건수와_무관하다() throws Exception {
        long plain = startSeason(mvc);
        buyTeam(mvc, jdbc, plain);
        long changed = startSeason(mvc);
        buyTeam(mvc, jdbc, changed);
        for (long season : new long[] {plain, changed}) {
            for (int t = 0; t < 3; t++) {
                assertThat(advance(mvc, season, t).getStatus()).isEqualTo(201);
            }
        }
        // 턴 1·2 에 변경 3건(턴 2 에 2건) — 변경이 없는 시즌과 같은 문장 수여야 한다(변경 건수만큼 도는 조회는 N+1)
        TurnViewSupport.changeFormation(jdbc, changed, 1, 20, 30, 30, 20);
        TurnViewSupport.changeFormation(jdbc, changed, 2, 20, 20, 40, 20);
        TurnViewSupport.changeFormation(jdbc, changed, 2, 30, 20, 30, 20);
        List<String> a = measure(plain, 3);
        List<String> b = measure(changed, 3);
        System.out.println("[T11] 턴 조회 turns/3 문장 수 (변경 0건) = " + a.size() + ", (변경 3건) = " + b.size());
        assertThat(a).hasSize(4);
        assertThat(b).hasSameSizeAs(a);
    }
}
