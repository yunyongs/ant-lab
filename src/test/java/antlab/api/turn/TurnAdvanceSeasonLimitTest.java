package antlab.api.turn;

import static antlab.api.turn.TurnApiSupport.advance;
import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** 진행 중 시즌 상한(3)과 실제 전환: 12번 전환해 끝낸 시즌은 상한에서 빠져 넷째 시즌을 시작할 수 있다(JDBC 로 흉내 내지 않는다). */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class TurnAdvanceSeasonLimitTest {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;

    private MockHttpServletResponse start() {
        return mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"riskLimit\": \"-0.20\", \"formation\": {\"goalkeeper\": 10, \"defender\": 30,"
                        + " \"midfielder\": 30, \"forward\": 30}, \"pathLength\": 12}").exchange().getResponse();
    }

    @Test
    void 실제_전환으로_끝낸_시즌은_진행_중_상한에서_빠진다() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse r = start();
            assertThat(r.getStatus()).isEqualTo(201);
            ids.add(JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong());
        }
        assertThat(start().getStatus()).isEqualTo(409);
        for (int t = 0; t < 12; t++) {
            assertThat(advance(mvc, ids.get(0), t).getStatus()).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM season WHERE id = ?", String.class, ids.get(0))).isEqualTo("FINISHED");
        assertThat(start().getStatus()).isEqualTo(201);
    }
}
