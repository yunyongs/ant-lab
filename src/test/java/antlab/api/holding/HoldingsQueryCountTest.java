package antlab.api.holding;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buy;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getHoldings;
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

/** 현재 팀 조회의 SQL 문 수 — 보유 1종목과 3종목이 같고 쓰기가 없다(종목별 조회를 목록에 돌리면 N+1 이라는 것을 숫자로 막는다). */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class HoldingsQueryCountTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private List<String> measure(long season) {
        SqlRecorder.start();
        MockHttpServletResponse r = getHoldings(mvc, season);
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
        // 턴 1에서도 같다(턴 전환 뒤 가격 행이 늘어나도 문장 수는 그대로)
        assertThat(advance(mvc, one, 0).getStatus()).isEqualTo(201);
        assertThat(advance(mvc, three, 0).getStatus()).isEqualTo(201);

        List<String> a = measure(one);
        List<String> b = measure(three);
        System.out.println("[T11] 보유 조회 문장 수 (1종목) = " + a.size() + ", (3종목) = " + b.size());
        a.forEach(q -> System.out.println("[T11] 1종목: " + q.replaceAll("\\s+", " ")));
        assertThat(a).hasSameSizeAs(b);
        // 절댓값: 현재 회원 1 + 시즌 1 + 계좌 1 + 보유 조인 1 (양쪽이 같이 늘어도 잡힌다)
        assertThat(a).hasSize(4);
        assertThat(b).hasSize(4);
        for (List<String> sqls : List.of(a, b)) {
            sqls.forEach(q -> assertThat(q.toLowerCase(Locale.ROOT)).doesNotStartWith("insert").doesNotStartWith("update")
                    .doesNotStartWith("delete"));
        }
    }
}
