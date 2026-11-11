package antlab.api.holding;

import static antlab.api.turn.TurnApiSupport.advance;
import static antlab.api.turn.TurnApiSupport.buyTeam;
import static antlab.api.turn.TurnApiSupport.startSeason;
import static antlab.api.turn.TurnViewSupport.getHoldings;
import static antlab.api.turn.TurnViewSupport.getTurn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import antlab.member.CurrentMember;
import antlab.support.IntegrationTest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 조회 서비스 트랜잭션의 실제 연결 상태 — 애너테이션이 아니라 서비스 안에서 DB 연결이 보고하는 격리 수준과 읽기 전용 여부를 본다.
 * 보유 조회는 {@code repeatable read}, 턴 조회는 {@code read committed}, 둘 다 읽기 전용이다.
 * ({@code JdbcTemplate} 은 JPA 트랜잭션의 연결을 공유하므로 트랜잭션 밖이면 {@code read committed/off} 로 나와 시험이 스스로 실패한다.)
 */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class QueryIsolationTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    CurrentMember member;

    @Test
    void 보유_조회는_반복_읽기_읽기_전용이고_턴_조회는_커밋된_읽기_읽기_전용이다() throws Exception {
        long season = startSeason(mvc);
        buyTeam(mvc, jdbc, season);
        assertThat(advance(mvc, season, 0).getStatus()).isEqualTo(201);

        List<String> seen = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            seen.add(jdbc.queryForObject("select current_setting('transaction_isolation') || '/'"
                    + " || current_setting('transaction_read_only')", String.class));
            return inv.callRealMethod();
        }).when(member).existingId();

        assertThat(getHoldings(mvc, season).getStatus()).isEqualTo(200);
        assertThat(seen).as("보유 조회").isNotEmpty().containsOnly("repeatable read/on");

        seen.clear();
        assertThat(getTurn(mvc, season, 1).getStatus()).isEqualTo(200);
        assertThat(seen).as("턴 조회").isNotEmpty().containsOnly("read committed/on");
    }
}
