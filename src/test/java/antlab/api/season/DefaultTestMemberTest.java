package antlab.api.season;

import static org.assertj.core.api.Assertions.assertThat;

import antlab.support.DefaultBearerConfig;
import antlab.support.IntegrationTest;
import antlab.support.TestMembers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The default test token names a real, committed member row that cannot log in. */
@IntegrationTest
@Sql(scripts = {"/fixtures/cleanup_season_start.sql", "/fixtures/history_pool.sql", "/fixtures/replay_months.sql",
        "/fixtures/turn_returns.sql"})
@Sql(scripts = "/fixtures/cleanup_season_start.sql", executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class DefaultTestMemberTest {

    private static final String START_BODY = """
            {"riskLimit": "-0.20", "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}}""";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TestMembers members;

    @Test
    void 기본_토큰으로_시작한_시즌의_주인은_기본_시험_회원이다() throws Exception {
        MockHttpServletResponse r = mvc.post().uri("/api/v1/seasons").contentType(MediaType.APPLICATION_JSON)
                .content(START_BODY).exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(201);
        long season = tools.jackson.databind.json.JsonMapper.shared().readTree(r.getContentAsString()).get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT member_id FROM season WHERE id = ?", Long.class, season))
                .isEqualTo(members.defaultId());
    }

    @Test
    void 기본_시험_회원은_로그인할_수_없다() throws Exception {
        assertThat(jdbc.queryForObject("SELECT password FROM member WHERE id = ?", String.class, members.defaultId()))
                .isEqualTo("!");
        MockHttpServletResponse r = mvc.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .requestAttr(DefaultBearerConfig.ANONYMOUS, true)
                .content("{\"email\": \"" + members.defaultEmail() + "\", \"password\": \"any-password-123\"}")
                .exchange().getResponse();
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentAsString()).contains("INVALID_CREDENTIALS");
    }
}
