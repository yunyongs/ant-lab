package antlab.api.season;

import static antlab.api.season.SeasonApiSupport.VALID_BODY;
import static antlab.api.season.SeasonApiSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** 진행 중 시즌 3개 상한과 같은 회원의 동시 시작. 커밋 모드(가짜 행을 커밋하고 시험 뒤 지운다). */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class SeasonLimitTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long active() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM season s JOIN member m ON m.id = s.member_id
                WHERE lower(m.email) = 'player@example.invalid' AND s.status <> 'FINISHED'""", Long.class);
    }

    private static String code(MockHttpServletResponse r) throws Exception {
        return JsonMapper.shared().readTree(r.getContentAsString()).get("code").asString();
    }

    @Test
    void 진행_중_3개_뒤_넷째는_409_SEASON_LIMIT이고_끝난_시즌이_생기면_다시_시작할_수_있다() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse r = post(mvc, VALID_BODY);
            assertThat(r.getStatus()).isEqualTo(201);
            ids.add(JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong());
        }
        MockHttpServletResponse fourth = post(mvc, VALID_BODY);
        assertThat(fourth.getStatus()).isEqualTo(409);
        assertThat(code(fourth)).isEqualTo("SEASON_LIMIT");
        assertThat(active()).isEqualTo(3);

        // 하나를 끝난 시즌으로 바꾸면(ck_season_finished_turn: 현재 턴 = 경로 길이) 다시 시작할 수 있다
        jdbc.update("UPDATE season SET status = 'FINISHED', current_turn_no = path_length WHERE id = ?", ids.get(0));
        assertThat(post(mvc, VALID_BODY).getStatus()).isEqualTo(201);
        assertThat(active()).isEqualTo(3);

        // ABANDONING 도 진행 중으로 센다
        jdbc.update("UPDATE season SET status = 'ABANDONING', abandoned_turn_no = 0 WHERE id = ?", ids.get(1));
        assertThat(active()).isEqualTo(3);
        assertThat(post(mvc, VALID_BODY).getStatus()).isEqualTo(409);
    }

    @Test
    void 진행_중_2개에서_동시에_둘이_시작하면_정확히_하나만_성공한다() throws Exception {
        assertThat(post(mvc, VALID_BODY).getStatus()).isEqualTo(201);
        assertThat(post(mvc, VALID_BODY).getStatus()).isEqualTo(201);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return post(mvc, VALID_BODY);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : futures) {
                MockHttpServletResponse r = f.get(30, TimeUnit.SECONDS);
                statuses.add(r.getStatus());
                if (r.getStatus() == 409) {
                    codes.add(code(r));
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(codes).containsExactly("SEASON_LIMIT");
            assertThat(active()).as("비완료 시즌은 3개").isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }
}
