package antlab.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import antlab.member.CurrentMember;
import antlab.scoring.SeasonReportQuery;
import antlab.scoring.SeasonReportService;
import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

/** 성적표 조회는 읽기 전용 + REPEATABLE_READ 이고 쓰기·잠금 문장이 0건이며, 문장 수가 보유 종목 수·턴 수와 무관하다. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonReportTransactionTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    CurrentMember currentMember;

    @Test
    void 서비스_메서드는_읽기_전용이고_반복_가능한_읽기_격리다() throws Exception {
        Method get = SeasonReportService.class.getMethod("get", Long.class);
        Transactional tx = get.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    /** 애너테이션 모양만이 아니라 서비스 트랜잭션 안에서 실제로 반복 가능한 읽기 + 읽기 전용인지 DB 가 알려 주는 값으로 확인한다. */
    @Test
    void 서비스_트랜잭션_안에서_격리_수준과_읽기_전용이_실제로_적용된다() throws Exception {
        long season = ReportApiSupport.finishedSeason(mvc, jdbc);
        List<String> seen = new ArrayList<>();
        doAnswer(invocation -> {
            seen.add(jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class));
            seen.add(jdbc.queryForObject("SELECT current_setting('transaction_read_only')", String.class));
            return invocation.callRealMethod();
        }).when(currentMember).existingId();
        assertThat(ReportApiSupport.getReport(mvc, season).getStatus()).isEqualTo(200);
        assertThat(seen).as("서비스 안에서 읽은 격리 수준과 읽기 전용").containsExactly("repeatable read", "on");
    }

    private List<String> measure(long season) {
        SqlRecorder.start();
        MockHttpServletResponse r = ReportApiSupport.getReport(mvc, season);
        List<String> sqls = SqlRecorder.stop();
        assertThat(r.getStatus()).isEqualTo(200);
        return sqls;
    }

    /** SqlRecorder 는 Hibernate 문장만 본다. 원천 조회(JdbcClient) 문장은 상수 텍스트 블록이라 읽기 전용인지 원문으로 확인한다. */
    @Test
    void 원천_조회_문장은_읽기_전용이다() throws Exception {
        int statements = 0;
        for (var field : SeasonReportQuery.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                String sql = ((String) field.get(null)).toLowerCase(Locale.ROOT).strip();
                if (sql.startsWith("with") || sql.startsWith("select")) {
                    statements++;
                    assertThat(sql).doesNotContain("insert ").doesNotContain("update ").doesNotContain("delete ")
                            .doesNotContain("for update").doesNotContain("for share").doesNotContain("for no key update");
                }
            }
        }
        assertThat(statements).as("원천 조회 문장 수").isEqualTo(5);
    }

    @Test
    void 쓰기와_잠금이_없고_JPA_문장_수가_보유_종목_수와_무관하다() throws Exception {
        long full = ReportApiSupport.finishedSeason(mvc, jdbc);
        long small = antlab.api.turn.TurnApiSupport.startSeason(mvc);
        antlab.api.turn.TurnApiSupport.buy(mvc, jdbc, small, 0, "000660", 10, "FORWARD_BREAKOUT");
        for (int t = 0; t < 12; t++) {
            assertThat(antlab.api.turn.TurnApiSupport.advance(mvc, small, t).getStatus()).isEqualTo(201);
        }
        List<String> a = measure(full);
        List<String> b = measure(small);
        // 총 문장 수 = Hibernate 문장(회원·시즌·턴·전술 변경·라인업·경로 수익률·종목) + 네이티브 6(정합·주문·보유·기준선 가격·대사 + 변경 비용)
        System.out.println("[M6] 성적표 Hibernate 문장 수 (보유 4종목) = " + a.size() + ", (보유 1종목) = " + b.size());
        assertThat(a).hasSameSizeAs(b).hasSize(7);
        for (List<String> sqls : List.of(a, b)) {
            sqls.forEach(q -> {
                String s = q.toLowerCase(Locale.ROOT);
                assertThat(s).doesNotStartWith("insert").doesNotStartWith("update").doesNotStartWith("delete");
                assertThat(s).doesNotContain("for update").doesNotContain("for share").doesNotContain("for no key update");
            });
        }
    }
}
