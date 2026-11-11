package antlab.api.order;

import static antlab.api.order.OrderApiSupport.assetId;
import static antlab.api.order.OrderApiSupport.body;
import static antlab.api.order.OrderApiSupport.place;
import static antlab.api.order.OrderApiSupport.startSeason;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.account.OrderService;
import antlab.support.IntegrationTest;
import antlab.support.SqlRecorder;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 잠금 순서(season → cash_account, member 는 잠그지 않음)와 트랜잭션 격리 수준. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class BuyOrderLockTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void 첫_시즌_조회가_잠금_조회이고_그_다음이_계좌_잠금이며_회원은_잠그지_않는다() throws Exception {
        long season = startSeason(mvc);
        long asset = assetId(jdbc, "000660");

        SqlRecorder.start();
        MockHttpServletResponse r = place(mvc, season, body(0, "BUY", asset, 1, "FORWARD_BREAKOUT"));
        List<String> sql = SqlRecorder.stop().stream().map(String::toLowerCase).toList();

        assertThat(r.getStatus()).isEqualTo(201);
        List<String> seasonReads = sql.stream().filter(q -> q.startsWith("select") && q.contains(" from season ")).toList();
        assertThat(seasonReads).as("시즌 조회는 잠금 조회 한 번뿐이다").hasSize(1);
        assertThat(seasonReads.get(0)).contains("for no key update");
        int seasonLock = indexOf(sql, " from season ", "for no key update");
        int accountLock = indexOf(sql, " from cash_account ", "for no key update");
        assertThat(seasonLock).isGreaterThanOrEqualTo(0);
        assertThat(accountLock).as("계좌 잠금은 시즌 잠금 뒤").isGreaterThan(seasonLock);
        assertThat(sql).as("회원 행 잠금 없음").noneMatch(q -> q.contains(" from member ") && q.contains("for "));
        // 시즌 잠금 앞에는 회원 읽기(잠금 없음)만 올 수 있다
        assertThat(sql.subList(0, seasonLock)).allMatch(q -> q.contains(" from member ") && !q.contains("for "));
        // 보유·구간은 잠그지 않는다(시즌 락이 이미 직렬화)
        assertThat(sql).noneMatch(q -> (q.contains(" from holding ") || q.contains(" from lineup ")) && q.contains("for "));
    }

    private static int indexOf(List<String> sql, String from, String lock) {
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).contains(from) && sql.get(i).contains(lock)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void 서비스_트랜잭션은_격리_수준을_기본으로_두고_기본은_READ_COMMITTED다() throws Exception {
        Method place = OrderService.class.getMethod("place", long.class, antlab.account.PlaceOrderRequest.class);
        Transactional annotation = place.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.DEFAULT);
        // 같은 트랜잭션 관리자가 기본 정의로 여는 트랜잭션의 실제 격리 수준
        String level = new TransactionTemplate(txManager)
                .execute(s -> jdbc.queryForObject("SHOW transaction_isolation", String.class));
        assertThat(level).isEqualTo("read committed");
    }
}
